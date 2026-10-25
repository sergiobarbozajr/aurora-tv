package com.auroratv.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.min

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

    var lastCode = 0
    var lastError = ""
    private var relaxed = false

    /** Aceita certificado HTTPS vencido ou próprio do servidor, como fazem os players de IPTV. Só é ligado se o servidor precisar. */
    private fun relax() {
        if (relaxed) return
        try {
            val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, tm, java.security.SecureRandom())
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            relaxed = true
        } catch (e: Throwable) {
        }
    }

    private val agents = listOf(
        "okhttp/4.12.0",
        "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "VLC/3.0.20 LibVLC/3.0.20"
    )

    private fun once(urlStr: String, timeout: Int, agent: String): String? {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = min(timeout, 15000)
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", agent)
            setRequestProperty("Accept", "*/*")
        }
        lastCode = c.responseCode
        // redirecionamento entre http e https (o Android não segue sozinho)
        if (lastCode in 301..308) {
            val loc = c.getHeaderField("Location")
            if (loc != null && loc.startsWith("http")) return once(loc, timeout, agent)
        }
        return if (lastCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun http(urlStr: String, timeout: Int = 60000): String? {
        lastError = ""
        for (agent in agents) {
            try {
                val r = once(urlStr, timeout, agent)
                if (r != null) return r
                if (lastCode != 403 && lastCode != 401 && lastCode != 444 && lastCode != 406) return null
                lastError = "o servidor recusou o acesso (código $lastCode)"
            } catch (e: javax.net.ssl.SSLException) {
                if (!relaxed) {
                    relax()
                    try {
                        val r = once(urlStr, timeout, agent)
                        if (r != null) return r
                    } catch (e2: Exception) {
                    }
                }
                lastCode = -1
                lastError = "certificado de segurança do servidor"
                return null
            } catch (e: java.net.UnknownHostException) {
                lastCode = -1
                lastError = "não encontrei o servidor pelo nome (confira o endereço; se estiver certo, o provedor de internet pode estar bloqueando)"
                return null
            } catch (e: java.net.SocketTimeoutException) {
                lastCode = -1
                lastError = "o servidor demorou demais para responder"
                return null
            } catch (e: java.net.ConnectException) {
                lastCode = -1
                lastError = "o servidor não aceitou a conexão nessa porta"
                return null
            } catch (e: Exception) {
                lastCode = -1
                lastError = e.javaClass.simpleName
                return null
            }
        }
        return null
    }

    /** Arruma o endereço digitado: tira espaços, barra final, caminhos do player e põe http:// se faltar. */
    fun cleanBase(raw: String): String {
        var b = raw.trim().replace(" ", "")
        for (cut in listOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php", "/c/", "/live/")) {
            val i = b.indexOf(cut, ignoreCase = true)
            if (i > 0) b = b.substring(0, i)
        }
        b = b.substringBefore("?").trimEnd('/')
        if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) b = "http://$b"
        return b
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun load(url: String, user: String, pass: String, cb: (IptvResult) -> Unit) {
        thread {
            val res = try {
                val u = url.trim()
                var us = user.trim()
                var pw = pass.trim()
                var base = u
                val looksM3u = u.contains(".m3u", true) || u.contains("get.php", true) || u.contains("type=m3u", true)
                if (u.contains("username=", true) && u.contains("password=", true)) {
                    val uri = android.net.Uri.parse(u)
                    us = uri.getQueryParameter("username") ?: us
                    pw = uri.getQueryParameter("password") ?: pw
                    val port = if (uri.port > 0) ":" + uri.port else ""
                    base = (uri.scheme ?: "http") + "://" + (uri.host ?: "") + port
                }
                var r = if (us.isNotEmpty() && pw.isNotEmpty()) loadXtream(base, us, pw) else IptvResult(emptyMap(), 0, "login")
                if (r.total == 0 && looksM3u) {
                    val m = loadM3u(u)
                    if (m.total > 0) r = m
                }
                r
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
        var liveExt = "m3u8"
        var base = cleanBase(rawUrl)
        fun api(action: String?): String =
            "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (if (action != null) "&action=$action" else "")

        var auth = http(api(null), 20000)
        if (auth == null && lastCode == -1) {
            // tenta o outro protocolo (http <-> https)
            val firstErr = lastError
            base = if (base.startsWith("https://", true)) "http://" + base.substring(8) else "https://" + base.substring(7)
            auth = http(api(null), 20000)
            if (auth == null) lastError = firstErr
        }
        if (auth == null) return IptvResult(emptyMap(), 0, "rede")
        try {
            val ui = JSONObject(auth).optJSONObject("user_info")
            if (ui == null || ui.optInt("auth", 0) != 1) return IptvResult(emptyMap(), 0, "login")
            val fm = ui.optJSONArray("allowed_output_formats")
            if (fm != null) {
                val l = ArrayList<String>()
                for (i in 0 until fm.length()) l.add(fm.optString(i).lowercase())
                if (l.isNotEmpty() && !l.contains("m3u8")) liveExt = if (l.contains("ts")) "ts" else l[0]
            }
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
                    acc.add(sec, cat, mkCard(name, cat.ifBlank { "Ao vivo" }, "stream", idxOf(name), "$base/live/${enc(user)}/${enc(pass)}/$id.$liveExt").copy(img = o.optString("stream_icon", ""), extra = "$base|$user|$pass|$id"))
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
                    acc.add(sec, cat, mkCard(name, "Filme", "vod", idxOf(name), "$base/movie/${enc(user)}/${enc(pass)}/$id.$ext").copy(img = o.optString("stream_icon", "")))
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
                    acc.add(sec, cat, mkCard(name, "Série", "series", idxOf(name), null, "$base|$user|$pass|$id").copy(img = o.optString("cover", "")))
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
        var logo = ""
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val comma = line.lastIndexOf(',')
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                group = ""
                for (m in attr.findAll(line)) {
                    if (m.groupValues[1].equals("group-title", true)) group = m.groupValues[2]
                    if (m.groupValues[1].equals("tvg-logo", true)) logo = m.groupValues[2]
                    if (name.isEmpty() && m.groupValues[1].equals("tvg-name", true)) name = m.groupValues[2]
                }
            } else if (line.isNotEmpty() && !line.startsWith("#") && name.isNotEmpty()) {
                val low = line.lowercase()
                val lg = logo
                fun card(n: String, l: String, k: String) = mkCard(n, l, k, idxOf(n), line).copy(img = lg)
                when {
                    low.contains("/movie/") -> acc.add(sectionFor("Filmes", group, name), group, card(name, "Filme", "vod"))
                    low.contains("/series/") -> acc.add(sectionFor("Séries", group, name), group, card(name, "Série", "vod"))
                    else -> acc.add(sectionFor("TV ao vivo", group, name), group, card(name, group.ifBlank { "Ao vivo" }, "stream"))
                }
                name = ""
                group = ""
                logo = ""
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

    /** Guia de programação: o que passa agora e o próximo programa de um canal ao vivo. */
    fun shortEpg(extra: String, cb: (String) -> Unit) {
        thread {
            var txt = ""
            try {
                val p = extra.split("|")
                if (p.size >= 4) {
                    val body = http("${p[0]}/player_api.php?username=${enc(p[1])}&password=${enc(p[2])}&action=get_short_epg&stream_id=${p[3]}&limit=2", 15000)
                    if (body != null) {
                        val arr = JSONObject(body).optJSONArray("epg_listings")
                        if (arr != null) {
                            fun dec(s: String): String = try {
                                String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
                            } catch (e: Exception) {
                                s
                            }
                            val names = ArrayList<String>()
                            for (i in 0 until minOf(2, arr.length())) {
                                val t = dec(arr.getJSONObject(i).optString("title", ""))
                                if (t.isNotEmpty()) names.add(t)
                            }
                            if (names.isNotEmpty()) txt = "Agora: " + names[0] + if (names.size > 1) "   ·   Depois: " + names[1] else ""
                        }
                    }
                }
            } catch (e: Throwable) {
            }
            main.post { cb(txt) }
        }
    }

    class Season(val num: String, val eps: List<Card>)

    /** Todas as temporadas e episódios de uma série (título, sinopse, duração e endereço de cada um). */
    fun seriesInfo(extra: String, cb: (List<Season>) -> Unit) {
        thread {
            val out = ArrayList<Season>()
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
                        for (k in keys) {
                            val arr = eps.optJSONArray(k) ?: continue
                            val list = ArrayList<Card>()
                            for (i in 0 until arr.length()) {
                                val e = arr.getJSONObject(i)
                                val num = e.optString("episode_num", (i + 1).toString())
                                val ext = e.optString("container_extension", "mp4").ifBlank { "mp4" }
                                val info = e.optJSONObject("info")
                                val plot = info?.optString("plot", "") ?: ""
                                val dur = info?.optString("duration", "") ?: ""
                                val img = info?.optString("movie_image", "") ?: ""
                                var title = e.optString("title", "").ifBlank { "Episódio $num" }
                                if (title.length > 60) title = title.take(60) + "…"
                                val url = "$base/series/${enc(user)}/${enc(pass)}/${e.optString("id")}.$ext"
                                list.add(
                                    mkCard(title, "T$k · E$num" + if (dur.isNotBlank()) " · $dur" else "", "vod", idxOf(title + k + num), url, "", emptyList(), plot)
                                        .copy(img = img)
                                )
                            }
                            if (list.isNotEmpty()) out.add(Season(k, list))
                        }
                    }
                }
            } catch (e: Throwable) {
            }
            main.post { cb(out) }
        }
    }
}
