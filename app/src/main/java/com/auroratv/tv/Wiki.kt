package com.auroratv.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Capa de filme ou série pela Wikipédia (aberta, sem chave), quando a lista não traz imagem. */
object Wiki {
    private val cache = HashMap<String, String>()

    /** Tira ano, qualidade e marcas da lista do nome ("Duna (2021) [4K] FHD" → "Duna"). */
    fun clean(name: String): String = name
        .replace(Regex("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}"), " ")
        .replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|1080p|720p|dublado|legendado|dual|leg|dub|l)\\b"), " ")
        .replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
        .replace(Regex("[|:_\\-–]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Endereço da imagem, ou vazio. Roda fora da tela (chamar de uma corrotina de IO). */
    fun cover(name: String, series: Boolean): String {
        val q = clean(name)
        if (q.length < 2) return ""
        val ck = (if (series) "s:" else "f:") + q.lowercase()
        synchronized(cache) { cache[ck]?.let { return it } }
        var out = ""
        try {
            val term = q + if (series) " série" else " filme"
            val u = "https://pt.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=1&prop=pageimages&piprop=thumbnail&pithumbsize=500&gsrsearch=" +
                URLEncoder.encode(term, "UTF-8")
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "AuroraTV/1.0 (app de TV pessoal)")
            }
            if (c.responseCode in 200..299) {
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val pages = o.optJSONObject("query")?.optJSONObject("pages")
                if (pages != null) {
                    val it = pages.keys()
                    while (it.hasNext()) {
                        val p = pages.getJSONObject(it.next())
                        val src = p.optJSONObject("thumbnail")?.optString("source", "") ?: ""
                        if (src.isNotBlank()) {
                            out = src
                            break
                        }
                    }
                }
            }
        } catch (e: Throwable) {
        }
        synchronized(cache) { cache[ck] = out }
        return out
    }
}
