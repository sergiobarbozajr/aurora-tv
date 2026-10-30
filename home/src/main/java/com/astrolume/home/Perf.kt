package com.astrolume.home

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/** Um retrato do desempenho. Valores nulos = a TV não liberou (nunca são inventados). */
data class PerfSnap(
    val connected: Boolean = false,
    val status: String = "",
    val cpu: Float? = null,
    val cores: List<Float> = emptyList(),
    val freqMhz: List<Int> = emptyList(),
    val tempC: Float? = null,
    val tempName: String = "",
    val thermal: Int = 0,
    val gpuMemMb: Float? = null,
    val gpuDriver: String? = null,
    val gpuErrors: Int? = null,
    val top: List<Pair<String, Float>> = emptyList(),
    val ramUsed: Float = 0f,
    val ramTotal: Float = 0f,
    val stoUsed: Float = 0f,
    val stoTotal: Float = 0f,
    val hist: List<Float> = emptyList(),
)

object Perf {
    var snap by mutableStateOf(PerfSnap())
        private set
    @Volatile private var running = false
    @Volatile private var gen = 0

    private val NAMES = mapOf(
        "system_server" to "Sistema Android",
        "surfaceflinger" to "Gráficos do sistema",
        "audioserver" to "Áudio",
        "android.hardware.audio.service" to "Áudio",
        "tvservice" to "Serviço da TV",
        "com.google.android.katniss" to "Google Assistente",
        "com.google.android.apps.tv.launcherx" to "Tela inicial do Google",
        "com.google.android.gms" to "Serviços do Google",
        "com.astrolume.home" to "AstroLume Home",
        "media.codec" to "Decodificador de vídeo",
        "media.swcodec" to "Decodificador de vídeo",
        "android.hardware.media.c2@1.2-service" to "Decodificador de vídeo",
        "vendor.rtk.thal-service" to "Sensor térmico",
    )

    fun start(c: Context) {
        if (running) return
        running = true
        val my = ++gen
        thread(name = "perf", isDaemon = true) { loop(c.applicationContext, my) }
    }

    fun stop() {
        running = false
        gen++
    }

    private fun label(c: Context, proc: String): String? {
        val base = proc.substringBefore(':').substringAfterLast('/')
        if (base in setOf("top", "sh", "toybox", "dumpsys", "cat", "adbd", "grep", "head")) return null
        NAMES[proc]?.let { return it }
        NAMES[base]?.let { return it }
        return try {
            val ai = c.packageManager.getApplicationInfo(base, 0)
            c.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Throwable) {
            if (base.startsWith("[")) null else base
        }
    }

    private fun loop(c: Context, my: Int) {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        var prev: List<LongArray>? = null
        val hist = ArrayList<Float>()
        var t = 0
        var s = PerfSnap()
        var tries = 0
        while (running && my == gen) {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gb = 1024f * 1024f * 1024f
            s = s.copy(ramTotal = mi.totalMem / gb, ramUsed = (mi.totalMem - mi.availMem) / gb, thermal = try { pm.currentThermalStatus } catch (e: Throwable) { 0 })
            if (t % 10 == 0) {
                val st = StatFs(Environment.getDataDirectory().path)
                s = s.copy(stoTotal = st.totalBytes / 1e9f, stoUsed = (st.totalBytes - st.availableBytes) / 1e9f)
            }
            var conn = Adb.connected(c)
            if (!conn && (tries == 0 || t % 15 == 0)) {
                tries++
                s = s.copy(status = "conectando")
                snap = s
                conn = Adb.connect(c)
            }
            if (!conn) {
                s = s.copy(connected = false, status = Adb.lastError.ifBlank { "off" }, cpu = null, cores = emptyList(), tempC = null, top = emptyList())
            } else {
                s = s.copy(connected = true, status = "ok")
                // CPU: contadores do /proc/stat (diferença entre duas leituras) e frequência de cada núcleo
                Adb.sh(c, "head -9 /proc/stat; echo @; cat /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq")?.let { out ->
                    val parts = out.split("@")
                    val rows = parts[0].lines().filter { it.startsWith("cpu") }.map { l ->
                        l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.toLongArray()
                    }
                    val p = prev
                    if (p != null && p.size == rows.size) {
                        val use = rows.indices.map { i ->
                            val a = p[i]; val b = rows[i]
                            if (a.size < 5 || b.size < 5) 0f else {
                                val idleA = a[3] + a[4]; val idleB = b[3] + b[4]
                                val totA = a.sum(); val totB = b.sum()
                                val dt = (totB - totA).toFloat()
                                if (dt <= 0f) 0f else ((dt - (idleB - idleA)) / dt * 100f).coerceIn(0f, 100f)
                            }
                        }
                        val total = use.firstOrNull() ?: 0f
                        hist.add(total)
                        if (hist.size > 60) hist.removeAt(0)
                        s = s.copy(cpu = total, cores = use.drop(1), hist = hist.toList())
                    }
                    prev = rows
                    if (parts.size > 1) s = s.copy(freqMhz = parts[1].lines().mapNotNull { it.trim().toIntOrNull()?.div(1000) })
                }
                // temperatura: serviço térmico do Android (sensor único da TV)
                if (t % 3 == 0) Adb.sh(c, "dumpsys thermalservice | grep -A3 'Current temperatures from HAL'")?.let { out ->
                    val m = Regex("mValue=([0-9.]+)[^}]*mName=([^,}]+)").find(out)
                    if (m != null) s = s.copy(tempC = m.groupValues[1].toFloatOrNull(), tempName = m.groupValues[2].trim())
                }
                // GPU: memória de vídeo e situação do driver PowerVR (a % de uso a TV não libera)
                if (t % 5 == 0) Adb.sh(c, "dumpsys gpu | grep -m1 'Global total'; echo @; cat /proc/pvr/status 2>/dev/null")?.let { out ->
                    val g = Regex("Global total:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
                    val drv = Regex("Driver Status:\\s*(\\S+)").find(out)?.groupValues?.get(1)
                    val err = listOf("Server Errors", "HWR Event Count").sumOf { k -> Regex("$k:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                    s = s.copy(gpuMemMb = g?.let { it / 1048576f }, gpuDriver = drv, gpuErrors = if (drv != null) err else null)
                }
                // quem mais usa a CPU (a cada 10 s, para o próprio teste não pesar)
                if (t % 10 == 1) Adb.sh(c, "top -b -n 1 -m 8 -q -o %CPU,ARGS", 5000)?.let { out ->
                    val n = s.cores.size.coerceAtLeast(1)
                    val list = LinkedHashMap<String, Float>()
                    for (l in out.lines()) {
                        val m = Regex("^\\s*([0-9.]+)\\s+(\\S+)").find(l) ?: continue
                        val v = m.groupValues[1].toFloatOrNull() ?: continue
                        val name = label(c, m.groupValues[2]) ?: continue
                        list[name] = (list[name] ?: 0f) + v / n
                    }
                    s = s.copy(top = list.entries.sortedByDescending { it.value }.take(4).map { it.key to it.value })
                }
            }
            snap = s
            t++
            try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
        }
    }
}
