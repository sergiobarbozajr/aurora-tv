package com.auroratv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Atualização dentro do app: confere a última versão publicada no GitHub, baixa e abre o instalador. */
object Updater {
    private const val REPO = "sergiobarbozajr/aurora-tv"
    private val main = Handler(Looper.getMainLooper())

    class Check(val status: String, val remote: String, val apkUrl: String?)

    fun currentVersion(c: Context): String =
        try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }

    private fun newer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** status: new | same | none | error */
    fun check(c: Context, cb: (Check) -> Unit) {
        val local = currentVersion(c)
        thread {
            var res = Check("error", "", null)
            try {
                // TV: versão "latest". Celular: pré-lançamentos "celular-vX" (separados, sem conflito com a TV)
                val cel = BuildConfig.FLAVOR == "celular"
                val api = if (cel) "https://api.github.com/repos/$REPO/releases?per_page=15" else "https://api.github.com/repos/$REPO/releases/latest"
                val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "AstroLume TV")
                }
                val code = conn.responseCode
                if (code == 404) {
                    res = Check("none", "", null)
                } else if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val o = if (!cel) JSONObject(body) else {
                        val arr = org.json.JSONArray(body)
                        var f: JSONObject? = null
                        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("tag_name").startsWith("celular-")) { f = arr.getJSONObject(i); break }
                        f ?: JSONObject("{}")
                    }
                    val tag = o.optString("tag_name", "").removePrefix("celular-")
                    var apk: String? = null
                    val assets = o.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk", true)) {
                                apk = a.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    res = if (apk != null && newer(tag, local)) Check("new", tag.removePrefix("v"), apk) else Check("same", tag.removePrefix("v"), apk)
                }
            } catch (e: Exception) {
            }
            main.post { cb(res) }
        }
    }

    /** Baixa o APK e abre o instalador do Android. progress recebe 0..100 (ou -1 se falhar). */
    fun downloadAndInstall(c: Context, url: String, progress: (Int) -> Unit) {
        thread {
            var ok = false
            val dir = File(c.cacheDir, "updates")
            val file = File(dir, "AstroLume TV.apk")
            try {
                dir.mkdirs()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AstroLume TV")
                }
                val len = conn.contentLength.toLong()
                var got = 0L
                var last = -1
                conn.inputStream.use { ins ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (len > 0) {
                                val p = (got * 100 / len).toInt()
                                if (p != last) {
                                    last = p
                                    main.post { progress(p) }
                                }
                            }
                        }
                    }
                }
                ok = got > 100_000
            } catch (e: Exception) {
            }
            main.post {
                if (!ok) {
                    progress(-1)
                } else {
                    progress(100)
                    install(c, file)
                }
            }
        }
    }

    private fun install(c: Context, file: File) {
        try {
            if (!c.packageManager.canRequestPackageInstalls()) {
                val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(i)
                return
            }
            val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
        }
    }

    fun canInstall(c: Context): Boolean =
        try { c.packageManager.canRequestPackageInstalls() } catch (e: Exception) { true }
}
