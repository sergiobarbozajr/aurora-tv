package com.auroratv.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/** Informativos da tela inicial: notícias por RSS aberto e cotações. Se uma fonte falhar, ela some sem mensagem de erro. */
object News {
    private val main = Handler(Looper.getMainLooper())

    private class Feed(val group: String, val source: String, val url: String)

    private val FEEDS = listOf(
        Feed("Notícias do Brasil", "G1", "https://g1.globo.com/rss/g1/"),
        Feed("Economia e bolsa", "InfoMoney", "https://www.infomoney.com.br/feed/"),
        Feed("Economia e bolsa", "G1 Economia", "https://g1.globo.com/rss/g1/economia/"),
        Feed("Economia e bolsa", "Investidor10", "https://investidor10.com.br/noticias/feed/"),
        Feed("Economia e bolsa", "Money Times", "https://www.moneytimes.com.br/feed/"),
        Feed("Economia e bolsa", "InfoMoney Mercados", "https://www.infomoney.com.br/mercados/feed/"),
        Feed("Futebol", "ge", "https://ge.globo.com/rss/ge/futebol/"),
        Feed("Tecnologia", "TecMundo", "https://rss.tecmundo.com.br/feed"),
        Feed("Tecnologia", "G1 Tecnologia", "https://g1.globo.com/rss/g1/tecnologia/"),
        Feed("Tecnologia", "Olhar Digital", "https://olhardigital.com.br/feed/"),
        Feed("Futebol", "Lance!", "https://www.lance.com.br/feed/")
    )

    /** Jornais da cidade e do estado escolhidos em Configurações → Clima e cidade. Nada de outros estados. */
    private fun regional(city: String): Pair<String, List<Feed>> {
        val c = Iptv.norm(city)
        fun f(src: String, url: String) = Feed("REGIAO", src, url)
        val rj = listOf(
            f("g1 RJ", "https://g1.globo.com/rss/g1/rj/rio-de-janeiro/"),
            f("O Dia", "https://odia.ig.com.br/rss.xml"),
            f("Extra", "https://extra.globo.com/rss.xml")
        )
        return when {
            c.startsWith("macae") || c.startsWith("rio das ostras") || c.startsWith("campos") -> Pair(
                "$city e região",
                listOf(
                    f("g1 Norte Fluminense", "https://g1.globo.com/rss/g1/rj/norte-fluminense/"),
                    f("g1 Região dos Lagos", "https://g1.globo.com/rss/g1/rj/regiao-dos-lagos/"),
                    f("Clique Diário", "https://www.cliquediario.com.br/feed/"),
                    f("O Debate", "https://odebateon.com.br/feed/")
                ) + rj
            )
            c.startsWith("niteroi") || c.startsWith("rio de janeiro") -> Pair("$city e RJ", rj)
            c.startsWith("vitoria") -> Pair("$city e ES", listOf(f("g1 ES", "https://g1.globo.com/rss/g1/espirito-santo/"), f("A Gazeta", "https://www.agazeta.com.br/rss")))
            c.startsWith("belo horizonte") -> Pair("$city e MG", listOf(f("g1 MG", "https://g1.globo.com/rss/g1/minas-gerais/"), f("O Tempo", "https://www.otempo.com.br/rss")))
            c.startsWith("sao paulo") -> Pair("$city e SP", listOf(f("g1 SP", "https://g1.globo.com/rss/g1/sao-paulo/")))
            else -> Pair(city, emptyList())
        }
    }

    /** Investidor10 não publica feed RSS: lê a página de notícias e tira manchete, link e foto. */
    private fun investidor10(): List<Card> {
        val out = ArrayList<Card>()
        val html = get("https://investidor10.com.br/noticias/") ?: return out
        val linkRe = Regex("<a[^>]+href=\"(https://investidor10\\.com\\.br/noticias/[a-z0-9-]{12,}[^\"]*)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val imgRe2 = Regex("(?:data-src|src)=\"(https://[^\"]+\\.(?:jpg|jpeg|png|webp)[^\"]*)\"", RegexOption.IGNORE_CASE)
        val seen = HashSet<String>()
        for (m in linkRe.findAll(html)) {
            if (out.size >= 10) break
            val url = m.groupValues[1]
            if (!seen.add(url.substringBefore("?"))) continue
            val inner = m.groupValues[2]
            var title = clean(inner.replace(Regex("<[^>]+>"), " "))
            if (title.length < 25) {
                val t = Regex("title=\"([^\"]{25,})\"").find(m.value)?.groupValues?.get(1)
                if (t != null) title = clean(t)
            }
            if (title.length < 25) continue
            // foto: dentro do link ou logo antes dele
            val from = maxOf(0, m.range.first - 1500)
            val img = imgRe2.find(inner)?.groupValues?.get(1) ?: imgRe2.findAll(html.substring(from, m.range.first)).lastOrNull()?.groupValues?.get(1) ?: ""
            // a foto vem da própria matéria (og:image), que é a imagem certa da notícia
            out.add(mkCard(title, "Investidor10", "news", title.hashCode() and 0x7fffffff, null, url, emptyList(), title))
        }
        return out
    }

    private fun get(urlStr: String): String? {
        return try {
            val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            }
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            null
        }
    }

    private fun clean(s: String): String {
        var t = s.trim()
        if (t.startsWith("<![CDATA[")) t = t.removePrefix("<![CDATA[").removeSuffix("]]>")
        return try {
            android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('￼', ' ').replace(Regex("\\s+"), " ").trim()
        } catch (e: Throwable) {
            t
        }
    }

    private val itemRe = Regex("<item[ >].*?</item>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val titleRe = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val descRe = Regex("<description[^>]*>(.*?)</description>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val mediaRe = Regex("<(?:media:content|media:thumbnail|enclosure)[^>]*?url=\"([^\"]+)\"", setOf(RegexOption.IGNORE_CASE))
    private val linkRe = Regex("<link[^>]*>\\s*(?:<!\\[CDATA\\[)?\\s*(https?://[^<\\s\\]]+)", setOf(RegexOption.IGNORE_CASE))
    private val imgRe = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE))

    /** Foto da matéria: mídia do feed, ou a primeira imagem do texto. */
    private fun photo(block: String): String {
        mediaRe.find(block)?.groupValues?.get(1)?.let { if (it.startsWith("http") && !it.endsWith(".mp4")) return it.replace("&amp;", "&") }
        val raw = block.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        imgRe.find(raw)?.groupValues?.get(1)?.let { if (it.startsWith("http")) return it.replace("&amp;", "&") }
        return ""
    }

    private fun parse(xml: String, source: String, group: String, limit: Int): List<Card> {
        val out = ArrayList<Card>()
        for (m in itemRe.findAll(xml)) {
            if (out.size >= limit) break
            val block = m.value
            val title = titleRe.find(block)?.groupValues?.get(1)?.let { clean(it) } ?: continue
            if (title.length < 8) continue
            val desc = descRe.find(block)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            val link = linkRe.find(block)?.groupValues?.get(1)?.trim() ?: ""
            out.add(mkCard(title, source, "news", title.hashCode() and 0x7fffffff, null, link, emptyList(), if (desc.isBlank()) title else desc).copy(img = photo(block)))
        }
        return out
    }

    private fun fmt(v: Double, dec: Int): String = String.format(Locale("pt", "BR"), "%,." + dec + "f", v)

    private fun pct(p: Double): String {
        val arrow = if (p >= 0) "▲" else "▼"
        return arrow + " " + String.format(Locale("pt", "BR"), "%.2f", Math.abs(p)) + "%"
    }

    private fun quotes(): List<Card> {
        val out = ArrayList<Card>()
        try {
            val body = get("https://query1.finance.yahoo.com/v8/finance/chart/%5EBVSP?interval=1d&range=5d")
            if (body != null) {
                val meta = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                val px = meta.getDouble("regularMarketPrice")
                val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", px))
                val p = if (prev > 0) (px - prev) / prev * 100.0 else 0.0
                out.add(mkCard("Ibovespa  " + fmt(px, 0), pct(p), "quote", 1, null, "", emptyList(), "Índice da bolsa brasileira (B3)"))
            }
        } catch (e: Throwable) {
        }
        try {
            val body = get("https://economia.awesomeapi.com.br/last/USD-BRL,EUR-BRL,BTC-BRL")
            if (body != null) {
                val o = JSONObject(body)
                val defs = listOf(
                    Triple("USDBRL", "Dólar", 2),
                    Triple("EURBRL", "Euro", 2),
                    Triple("BTCBRL", "Bitcoin", 0)
                )
                for ((i, d) in defs.withIndex()) {
                    val q = o.optJSONObject(d.first) ?: continue
                    val bid = q.optString("bid", "").toDoubleOrNull() ?: continue
                    val p = q.optString("pctChange", "0").toDoubleOrNull() ?: 0.0
                    out.add(mkCard(d.second + "  R$ " + fmt(bid, d.third), pct(p), "quote", i + 2, null, "", emptyList(), "Cotação em reais"))
                }
            }
        } catch (e: Throwable) {
        }
        return out
    }

    /** Devolve fileiras prontas: "Mercado agora" e uma por assunto. */
    fun load(city: String, cb: (List<RowData>) -> Unit) {
        thread {
            val rows = ArrayList<RowData>()
            try {
                val q = quotes()
                if (q.isNotEmpty()) rows.add(RowData("Mercado agora", q))
            } catch (e: Throwable) {
            }
            val groups = LinkedHashMap<String, MutableList<Card>>()
            // primeiro as notícias da região (cidade e estado), depois o resto
            val (regName, regFeeds) = regional(city)
            val regKey = "Notícias de $regName"
            for (f in regFeeds) {
                try {
                    val xml = get(f.url) ?: continue
                    val l = groups.getOrPut(regKey) { ArrayList() }
                    for (c in parse(xml, f.source, regKey, 8)) if (l.none { it.name == c.name }) l.add(c)
                } catch (e: Throwable) {
                }
            }
            for (f in FEEDS) {
                try {
                    val xml = get(f.url) ?: continue
                    val items = parse(xml, f.source, f.group, 8)
                    val l = groups.getOrPut(f.group) { ArrayList() }
                    for (c in items) if (l.none { it.name == c.name }) l.add(c)
                } catch (e: Throwable) {
                }
            }
            try {
                val inv = investidor10()
                if (inv.isNotEmpty()) {
                    val l = groups.getOrPut("Economia e bolsa") { ArrayList() }
                    // mistura as fontes, para não ficar só uma no começo da fileira
                    val merged = ArrayList<Card>()
                    val a = l.toList()
                    for (i in 0 until maxOf(a.size, inv.size)) {
                        if (i < inv.size) merged.add(inv[i])
                        if (i < a.size) merged.add(a[i])
                    }
                    l.clear()
                    l.addAll(merged.distinctBy { it.name })
                }
            } catch (e: Throwable) {
            }
            for ((g, l) in groups) if (l.isNotEmpty()) rows.add(RowData(g, l.take(if (g == "Economia e bolsa") 20 else 14)))
            main.post { cb(rows) }
        }
    }
}
