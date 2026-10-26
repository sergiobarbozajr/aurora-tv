package com.auroratv.tv

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Logos de canais tirados de listas públicas de canais abertos (sem chave, sem cadastro).
 * Cada lista M3U traz o logo do canal no campo tvg-logo; o app guarda nome → logo.
 */
object Logos {
    private val main = Handler(Looper.getMainLooper())
    private val map = HashMap<String, String>()

    /** Canais abertos da lista pública do Brasil (iptv-org): nome, endereço, logo e categoria. */
    class Open(val name: String, val url: String, val logo: String, val cat: String)

    @Volatile
    var open: List<Open> = emptyList()
        private set

    private fun catOf(group: String): String {
        val g = group.lowercase()
        return when {
            "news" in g -> "Notícias"
            "relig" in g -> "Religiosos"
            "kids" in g || "animation" in g -> "Infantil"
            "educ" in g || "science" in g -> "Educativos"
            "sport" in g -> "Esporte"
            "music" in g -> "Música"
            "legislative" in g || "public" in g -> "Públicos e legislativos"
            "documentary" in g -> "Documentários"
            "movies" in g || "series" in g || "entertainment" in g || "general" in g || "culture" in g || "lifestyle" in g || "comedy" in g -> "Variedades"
            else -> "Outros"
        }
    }

    private val SOURCES = listOf(
        "https://iptv-org.github.io/iptv/countries/br.m3u",
        "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_brazil.m3u8"
    )

    // nomes da antena → como aparecem nas listas
    private val ALIAS = mapOf(
        "globo" to listOf("tv globo", "globo"),
        "sbt" to listOf("sbt"),
        "band" to listOf("band", "tv bandeirantes", "bandeirantes"),
        "record" to listOf("record tv", "record"),
        "redetv" to listOf("redetv", "rede tv"),
        "tv cultura" to listOf("tv cultura", "cultura")
    )

    private fun key(s: String) = Iptv.norm(s).replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").replace(Regex("\\b(hd|fhd|sd|4k|uhd)\\b"), "")
        .replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    fun load(done: () -> Unit) {
        thread {
            val attr = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
            for (src in SOURCES) {
                try {
                    val c = (URL(src).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", "okhttp/4.12.0")
                    }
                    if (c.responseCode !in 200..299) continue
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    val chans = ArrayList<Open>()
                    var pName = ""
                    var pLogo = ""
                    var pGroup = ""
                    for (raw in body.lineSequence()) {
                        val line = raw.trim()
                        if (line.startsWith("#EXTINF")) {
                            var logo = ""
                            var group = ""
                            for (m in attr.findAll(line)) {
                                if (m.groupValues[1].equals("tvg-logo", true)) logo = m.groupValues[2]
                                if (m.groupValues[1].equals("group-title", true)) group = m.groupValues[2]
                            }
                            val name = line.substringAfterLast(",").trim()
                            pName = name.replace(Regex("\\[.*?\\]"), "").replace(Regex("\\((\\d{3,4}p)\\)"), "").replace(Regex("\\s+"), " ").trim()
                            pLogo = logo
                            pGroup = group
                            val k = key(name)
                            if (k.isNotEmpty() && logo.startsWith("http")) synchronized(map) { map.putIfAbsent(k, logo) }
                        } else if (line.isNotEmpty() && !line.startsWith("#") && pName.isNotEmpty()) {
                            if (src.contains("iptv-org") && line.startsWith("http")) chans.add(Open(pName, line, pLogo, catOf(pGroup)))
                            pName = ""
                        }
                    }
                    if (chans.isNotEmpty()) open = chans.distinctBy { it.name.lowercase() }
                } catch (e: Throwable) {
                }
            }
            main.post(done)
        }
    }

    /** Logo do canal pelo nome; vazio se não achar. */
    fun find(name: String): String {
        val k = key(name)
        if (k.isEmpty()) return ""
        synchronized(map) {
            map[k]?.let { return it }
            val aliases = ALIAS[k] ?: listOf(k)
            for (a in aliases) {
                map[a]?.let { return it }
                // o nome mais curto que começa com o apelido ("tv globo sp" para "tv globo")
                val hit = map.keys.filter { it.startsWith("$a ") || it == a }.minByOrNull { it.length }
                if (hit != null) return map[hit] ?: ""
            }
        }
        return ""
    }
}
