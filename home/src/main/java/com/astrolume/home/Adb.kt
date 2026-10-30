package com.astrolume.home

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Conexão do launcher com a depuração sem fio da própria TV (o mesmo acesso de leitura do teste feito pelo computador).
 * A chave fica só no armazenamento privado do app. Pode vir do instalador (arquivo "adbkey", já pareado pelo computador)
 * ou ser criada aqui e pareada com o código que a TV mostra.
 */
class HomeAdb private constructor(c: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(8, TimeUnit.SECONDS)
        val dir = c.filesDir
        key = loadKey(dir) ?: newKey(dir)
        cert = makeCert(key)
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "AstroLume Home"

    companion object {
        @Volatile private var inst: HomeAdb? = null
        fun get(c: Context): HomeAdb = inst ?: synchronized(this) { inst ?: HomeAdb(c.applicationContext).also { inst = it } }
        fun reset() { synchronized(this) { try { inst?.close() } catch (e: Throwable) {}; inst = null } }

        private fun pkcs8(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))

        /** "adbkey" do instalador (PEM) tem prioridade; senão a chave própria do app */
        private fun loadKey(dir: File): PrivateKey? {
            val pem = File(dir, "adbkey")
            if (pem.exists()) try {
                val b64 = pem.readText().lines().filter { !it.startsWith("-----") }.joinToString("")
                return pkcs8(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Throwable) {
            }
            val own = File(dir, "home_key.der")
            if (own.exists()) try { return pkcs8(own.readBytes()) } catch (e: Throwable) {}
            return null
        }

        private fun newKey(dir: File): PrivateKey {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val k = g.generateKeyPair().private
            File(dir, "home_key.der").writeBytes(k.encoded)
            return k
        }

        private fun makeCert(k: PrivateKey): Certificate {
            val rsa = k as RSAPrivateCrtKey
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(rsa.modulus, rsa.publicExponent))
            val name = X500Name("CN=AstroLume Home")
            val now = System.currentTimeMillis()
            val b = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, pub)
            val der = b.build(JcaContentSignerBuilder("SHA256withRSA").build(k)).encoded
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        }
    }
}

object Adb {
    @Volatile var lastError: String = ""
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("adb", Context.MODE_PRIVATE)

    /** a depuração sem fio está ligada? */
    fun wifiOn(c: Context): Boolean = try { Settings.Global.getInt(c.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (e: Throwable) { false }

    /** se o instalador liberou a permissão, religa a depuração sem fio quando ela desliga (ex.: depois de reiniciar) */
    fun ensureWifi(c: Context): Boolean {
        if (wifiOn(c)) return true
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false
        return try {
            Settings.Global.putInt(c.contentResolver, "adb_wifi_enabled", 1)
            Thread.sleep(2500)
            wifiOn(c)
        } catch (e: Throwable) {
            false
        }
    }

    fun connected(c: Context): Boolean = try { HomeAdb.get(c).isConnected } catch (e: Throwable) { false }

    /** conecta (descobre a porta sozinho pela rede local; se falhar, tenta a última porta usada) */
    @Synchronized
    fun connect(c: Context): Boolean {
        if (connected(c)) return true
        if (!ensureWifi(c)) {
            lastError = "wifi_off"
            return false
        }
        val m = try { HomeAdb.get(c) } catch (e: Throwable) {
            lastError = "chave: ${e.javaClass.simpleName}"
            return false
        }
        try {
            if (m.connectTls(c, 6000)) {
                prefs(c).edit().putBoolean("paired", true).apply()
                lastError = ""
                return true
            }
        } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
            lastError = "pair"
            return false
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
        val port = prefs(c).getInt("port", 0)
        if (port > 0) try {
            m.setHostAddress("127.0.0.1")
            if (m.connect(port)) {
                lastError = ""
                return true
            }
        } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
            lastError = "pair"
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
        if (!prefs(c).getBoolean("paired", false) && !File(c.filesDir, "adbkey").exists()) lastError = "pair"
        HomeAdb.reset()
        return false
    }

    /** pareamento com o código de 6 dígitos que a TV mostra em "Parear o dispositivo com um código de pareamento" */
    fun pair(c: Context, host: String, port: Int, code: String): Boolean = try {
        val ok = HomeAdb.get(c).pair(host.ifBlank { "127.0.0.1" }, port, code.trim())
        if (ok) prefs(c).edit().putBoolean("paired", true).apply()
        lastError = if (ok) "" else "codigo"
        ok
    } catch (e: Throwable) {
        lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        false
    }

    /** roda um comando de leitura e devolve a saída (com limite de tempo) */
    fun sh(c: Context, cmd: String, timeoutMs: Long = 4000): String? {
        val m = try { HomeAdb.get(c) } catch (e: Throwable) { return null }
        if (!m.isConnected) return null
        return try {
            val st = m.openStream("shell:$cmd")
            val ins = st.openInputStream()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val end = System.currentTimeMillis() + timeoutMs
            val reader = Thread {
                try {
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.write(buf, 0, n) }
                    }
                } catch (e: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join((end - System.currentTimeMillis()).coerceAtLeast(1))
            try { st.close() } catch (e: Throwable) {}
            synchronized(out) { out.toString("UTF-8") }
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            if (!m.isConnected) HomeAdb.reset()
            null
        }
    }
}
