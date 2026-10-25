package com.auroratv.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Resultado do carregamento da assinatura: fileiras já separadas por seção. */
class IptvResult(val rows: Map<String, List<RowData>>, val total: Int, val error: String?)

/**
 * Lê a conta IPTV licenciada do usuário (Xtream: URL + usuário + senha, ou uma lista M3U)
 * e separa o conteúdo em Filmes, Séries, Novelas, Documentários, Esporte, Notícias e TV ao vivo
 * usando as categorias que a própria assinatura entrega, mais regras simples por nome.
 */
object Iptv {
    private val main = Handler(Looper.getMainLooper())

    fun norm(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase()

    private fun has(s: String, vararg k: String): Boolean = k.any { s.contains(it) }

    /** base = "Filmes" | "Séries" | "TV ao vivo". Devolve a seção final. */
    fun sectionFor(base: String, group: String, name: String): String {
        val g = norm(group)
        val n = if (base == "TV ao vivo") norm(name) else ""
        return when {
            has(g, "novela") || has(n, "novela") -> "Novelas"
            has(g, "document", "discovery", "nat geo", "national geographic", "history", "animal planet") -> "Documentários"
            has(g, "esport", "sport", "futebol", "nba", "ufc", "premiere", "espn", "combate") ||
                has(n, "sportv", "espn", "premiere", "combate", "dazn") -> "Esporte"
            has(g, "notic", "news", "jornal") ||
                has(n, "cnn", "globonews", "band news", "jovem pan news", "record news") -> "Notícias"
            else -> base
        }
    }

    private fun http(urlStr: String, timeout: Int = 30000): String? {
        return try {
            val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AuroraTV/0.7")
            }
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun load(url: String, user: String, pass: String, cb: (IptvResult) -> Unit) {
        thread {
            val res = try {
                val u = url.trim()
                val isM3u = u.contains(".m3u", true) || u.contains("get.php", true) || u.contains("type=m3u", true)
                if (isM3u) loadM3u(u) else loadXtream(u, user.trim(), pass.trim())
            } catch (e: Throwable) {
                IptvResult(emptyMap(), 0, "erro")
            }
            main.post { cb(res) }
        }
    }

    private class Acc {
        val map = LinkedHashMap<String, LinkedHashMap<String, MutableList<Card>>>()
        var total = 0
        fun add(section: String, group: String, c: Card) {
            val g = if (group.isBlank()) "Outros" else group
            val rows = map.getOrPut(section) { LinkedHashMap() }
            val list = rows.getOrPut(g) { ArrayList() }
            if (list.size < 300) list.add(c)
            total++
        }

        fun build(): Map<String, List<RowData>> {
            val out = LinkedHashMap<String, List<RowData>>()
            for ((sec, rows) in map) out[sec] = rows.map { RowData(it.key, it.value) }
            return out
        }
    }

    private fun idxOf(s: String) = s.hashCode() and 0x7fffffff

    private fun loadXtream(rawUrl: String, user: String, pass: String): IptvResult {
        var base = rawUrl.substringBefore("/player_api.php").trimEnd('/')
        if (!base.startsWith("http", true)) base = "http://$base"
        fun api(action: String?): String =
            "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (if (action != null) "&action=$action" else "")

        val auth = http(api(null), 20000) ?: return IptvResult(emptyMap(), 0, "rede")
        try {
            val ui = JSONObject(auth).optJSONObject("user_info")
            if (ui == null || ui.optInt("auth", 0) != 1) return IptvResult(emptyMap(), 0, "login")
        } catch (e: Exception) {
            return IptvResult(emptyMap(), 0, "login")
        }

        fun cats(action: String): Map<String, String> {
            val m = HashMap<String, String>()
            val body = http(api(action)) ?: return m
            try {
                val a = JSONArray(body)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    m[o.optString("category_id")] = o.optString("category_name")
                }
            } catch (e: Exception) {
            }
            return m
        }

        val acc = Acc()

        val liveCats = cats("get_live_categories")
        http(api("get_live_streams"))?.let { body ->
            try {
                val a = JSONArray(body)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val name = o.optString("name")
                    if (name.isBlank()) continue
                    val cat = liveCats[o.optString("category_id")] ?: ""
                    val id = o.optString("stream_id")
                    val sec = sectionFor("TV ao vivo", cat, name)
                    acc.add(sec, cat, mkCard(name, cat.ifBlank { "Ao vivo" }, "stream", idxOf(name), "$base/live/${enc(user)}/${enc(pass)}/$id.m3u8"))
                }
            } catch (e: Exception) {
            }
        }

        val vodCats = cats("get_vod_categories")
        http(api("get_vod_streams"))?.let { body ->
            try {
                val a = JSONArray(body)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val name = o.optString("name")
                    if (name.isBlank()) continue
                    val cat = vodCats[o.optString("category_id")] ?: ""
                    val id = o.optString("stream_id")
                    val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                    val sec = sectionFor("Filmes", cat, name)
                    acc.add(sec, cat, mkCard(name, "Filme", "vod", idxOf(name), "$base/movie/${enc(user)}/${enc(pass)}/$id.$ext"))
                }
            } catch (e: Exception) {
            }
        }

        val serCats = cats("get_series_categories")
        http(api("get_series"))?.let { body ->
            try {
                val a = JSONArray(body)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val name = o.optString("name")
                    if (name.isBlank()) continue
                    val cat = serCats[o.optString("category_id")] ?: ""
                    val id = o.optString("series_id")
                    val sec = sectionFor("Séries", cat, name)
                    acc.add(sec, cat, mkCard(name, "Série", "series", idxOf(name), null, "$base|$user|$pass|$id"))
                }
            } catch (e: Exception) {
            }
        }

        return IptvResult(acc.build(), acc.total, if (acc.total == 0) "vazio" else null)
    }

    private fun loadM3u(url: String): IptvResult {
        val body = http(url, 40000) ?: return IptvResult(emptyMap(), 0, "rede")
        val acc = Acc()
        val attr = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
        var name = ""
        var group = ""
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val comma = line.lastIndexOf(',')
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                group = ""
                for (m in attr.findAll(line)) {
                    if (m.groupValues[1].equals("group-title", true)) group = m.groupValues[2]
                    if (name.isEmpty() && m.groupValues[1].equals("tvg-name", true)) name = m.groupValues[2]
                }
            } else if (line.isNotEmpty() && !line.startsWith("#") && name.isNotEmpty()) {
                val low = line.lowercase()
                when {
                    low.contains("/movie/") -> acc.add(sectionFor("Filmes", group, name), group, mkCard(name, "Filme", "vod", idxOf(name), line))
                    low.contains("/series/") -> acc.add(sectionFor("Séries", group, name), group, mkCard(name, "Série", "vod", idxOf(name), line))
                    else -> acc.add(sectionFor("TV ao vivo", group, name), group, mkCard(name, group.ifBlank { "Ao vivo" }, "stream", idxOf(name), line))
                }
                name = ""
                group = ""
            }
        }
        return IptvResult(acc.build(), acc.total, if (acc.total == 0) "vazio" else null)
    }

    /** Busca os episódios de uma série e devolve a URL do primeiro, ou null. */
    fun firstEpisode(extra: String, cb: (String?, String) -> Unit) {
        thread {
            var url: String? = null
            var title = ""
            try {
                val p = extra.split("|")
                val base = p[0]
                val user = p[1]
                val pass = p[2]
                val id = p[3]
                val body = http("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_series_info&series_id=$id")
                if (body != null) {
                    val eps = JSONObject(body).optJSONObject("episodes")
                    if (eps != null) {
                        val keys = ArrayList<String>()
                        val it = eps.keys()
                        while (it.hasNext()) keys.add(it.next())
                        keys.sortBy { k -> k.toIntOrNull() ?: 9999 }
                        val first = keys.firstOrNull()
                        if (first != null) {
                            val arr = eps.getJSONArray(first)
                            if (arr.length() > 0) {
                                val e = arr.getJSONObject(0)
                                val ext = e.optString("container_extension", "mp4").ifBlank { "mp4" }
                                url = "$base/series/${enc(user)}/${enc(pass)}/${e.optString("id")}.$ext"
                                title = "T$first · E1"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
            main.post { cb(url, title) }
        }
    }
}
