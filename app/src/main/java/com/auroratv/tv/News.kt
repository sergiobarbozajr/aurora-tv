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
        Feed("Notícias do dia", "G1", "https://g1.globo.com/rss/g1/"),
        Feed("Economia e bolsa", "InfoMoney", "https://www.infomoney.com.br/feed/"),
        Feed("Economia e bolsa", "G1 Economia", "https://g1.globo.com/rss/g1/economia/"),
        Feed("Futebol", "ge", "https://ge.globo.com/rss/ge/futebol/"),
        Feed("Tecnologia", "TecMundo", "https://rss.tecmundo.com.br/feed"),
        Feed("Tecnologia", "G1 Tecnologia", "https://g1.globo.com/rss/g1/tecnologia/")
    )

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

    private fun parse(xml: String, source: String, group: String, limit: Int): List<Card> {
        val out = ArrayList<Card>()
        for (m in itemRe.findAll(xml)) {
            if (out.size >= limit) break
            val block = m.value
            val title = titleRe.find(block)?.groupValues?.get(1)?.let { clean(it) } ?: continue
            if (title.length < 8) continue
            val desc = descRe.find(block)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            out.add(mkCard(title, source, "news", title.hashCode() and 0x7fffffff, null, "", emptyList(), if (desc.isBlank()) title else desc))
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
    fun load(cb: (List<RowData>) -> Unit) {
        thread {
            val rows = ArrayList<RowData>()
            try {
                val q = quotes()
                if (q.isNotEmpty()) rows.add(RowData("Mercado agora", q))
            } catch (e: Throwable) {
            }
            val groups = LinkedHashMap<String, MutableList<Card>>()
            for (f in FEEDS) {
                try {
                    val xml = get(f.url) ?: continue
                    val items = parse(xml, f.source, f.group, 8)
                    val l = groups.getOrPut(f.group) { ArrayList() }
                    for (c in items) if (l.none { it.name == c.name }) l.add(c)
                } catch (e: Throwable) {
                }
            }
            for ((g, l) in groups) if (l.isNotEmpty()) rows.add(RowData(g, l.take(14)))
            main.post { cb(rows) }
        }
    }
}
