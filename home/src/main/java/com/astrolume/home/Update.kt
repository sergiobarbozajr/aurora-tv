package com.astrolume.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Atualização do AstroLume Home pelas versões "home-v…" do GitHub (separadas do AstroLume TV). */
object Updater {
    private const val REPO = "sergiobarbozajr/aurora-tv"
    private val main = Handler(Looper.getMainLooper())

    class Check(val status: String, val remote: String, val apk: String?)

    fun current(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    private fun parts(v: String) = v.trim().removePrefix("v").split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    private fun newer(r: String, l: String): Boolean {
        val a = parts(r); val b = parts(l)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** status: new | same | error */
    fun check(c: Context, cb: (Check) -> Unit) {
        val local = current(c)
        thread {
            var res = Check("error", "", null)
            try {
                val body = Info.get("https://api.github.com/repos/$REPO/releases?per_page=30")
                if (body != null) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val tag = o.optString("tag_name")
                        if (!tag.startsWith("home-v")) continue
                        val assets = o.optJSONArray("assets")
                        var apk: String? = null
                        if (assets != null) for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            if (a.optString("name").endsWith(".apk", true)) { apk = a.optString("browser_download_url"); break }
                        }
                        val v = tag.removePrefix("home-v")
                        res = Check(if (apk != null && newer(v, local)) "new" else "same", v, apk)
                        break
                    }
                    if (res.status == "error") res = Check("same", local, null)
                }
            } catch (e: Exception) {
            }
            main.post { cb(res) }
        }
    }

    fun downloadAndInstall(c: Context, url: String, progress: (Int) -> Unit) {
        thread {
            var ok = false
            val dir = File(c.cacheDir, "updates")
            val file = File(dir, "AstroLumeHome.apk")
            try {
                dir.mkdirs()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AstroLume Home")
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
                                if (p != last) { last = p; main.post { progress(p) } }
                            }
                        }
                    }
                }
                ok = got > 100_000
            } catch (e: Exception) {
            }
            main.post {
                if (!ok) progress(-1) else { progress(100); install(c, file) }
            }
        }
    }

    private fun install(c: Context, file: File) {
        try {
            if (!c.packageManager.canRequestPackageInstalls()) {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", file)
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}
