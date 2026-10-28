package com.auroratv.tv

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.math.pow

/**
 * Resumo de notícias feito no próprio aparelho (sem servidor, sem custo):
 * baixa a matéria, separa o texto principal e escolhe as frases que mais carregam o assunto
 * (frequência das palavras-chave, posição e tamanho), na ordem original, e fecha com uma conclusão.
 */
object Summary {
    class Result(val points: List<String>, val conclusion: String, val image: String, val ok: Boolean)

    private val cache = HashMap<String, Result>()

    private val STOP = (
        "a o e é de da do das dos em no na nos nas um uma uns umas para por com sem sob sobre que se ao aos à às ou mas " +
            "como mais menos muito muita muitos muitas já não sim ele ela eles elas isso isto esse essa este esta aquele aquela " +
            "foi ser são era eram está estão estava tem têm ter há seu sua seus suas lhe lhes nós vocês você pelo pela pelos pelas " +
            "também até quando onde qual quais quem porque pois entre após antes depois ainda segundo diz disse afirmou além " +
            "num numa dele dela deles delas cada todo toda todos todas outro outra outros outras mesmo mesma vai vão pode podem"
        ).split(" ").toHashSet()

    private fun get(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
        }
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText().take(1_500_000) }
    } catch (e: Throwable) {
        null
    }

    private fun clean(s: String): String = try {
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace('￼', ' ').replace(Regex("\\s+"), " ").trim()
    } catch (e: Throwable) {
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** Parágrafos do corpo da matéria (ignora menus, legendas, "leia também", assinatura). */
    private fun paragraphs(html: String): List<String> {
        var body = html.replace(Regex("<(script|style|noscript|svg|figure|aside|nav|footer|header|form)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        val art = Regex("<article[^>]*>(.*?)</article>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(body).maxByOrNull { it.value.length }
        if (art != null && art.value.length > 1500) body = art.groupValues[1]
        val out = ArrayList<String>()
        for (m in Regex("<p[^>]*>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(body)) {
            // tira créditos de foto e legendas ("(Foto: Reprodução/Caixa)")
            val t = clean(m.groupValues[1]).replace(Regex("\\((?:Foto|Imagem|Crédito|Créditos)[^)]*\\)", RegexOption.IGNORE_CASE), "").trim()
            if (t.length < 60) continue
            if (Regex("^(?:Foto|Imagem|Crédito|Legenda)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(t) || t.contains("Reprodução/")) continue
            val low = t.lowercase()
            if (low.startsWith("leia também") || low.startsWith("veja também") || low.contains("assine") && low.length < 160 || low.contains("cookies") || low.contains("todos os direitos")) continue
            if (out.none { it == t }) out.add(t)
        }
        return out
    }

    private fun sentences(ps: List<String>): List<String> {
        val out = ArrayList<String>()
        for (p in ps) for (s in p.split(Regex("(?<=[.!?…])\\s+(?=[\"“A-ZÁÉÍÓÚÂÊÔÃÕÇ0-9])"))) {
            val t = s.trim()
            if (t.length in 40..420) out.add(t)
        }
        return out
    }

    private fun words(s: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toList()

    fun summarize(url: String, title: String, fallback: String): Result {
        synchronized(cache) { cache[url]?.let { return it } }
        val html = if (url.startsWith("http")) get(url) else null
        val image = html?.let {
            Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.replace("&amp;", "&")
        } ?: ""
        val ps = html?.let { paragraphs(it) } ?: emptyList()
        var ss = sentences(ps)
        if (ss.size < 3) ss = sentences(listOf(fallback))
        if (ss.isEmpty()) return Result(emptyList(), "", image, false)
        // peso das palavras: frequência no texto, com bônus para as que estão no título
        val freq = HashMap<String, Double>()
        for (s in ss) for (w in words(s)) freq[w] = (freq[w] ?: 0.0) + 1.0
        val tw = words(title).toHashSet()
        for (w in tw) freq[w] = (freq[w] ?: 0.0) + 3.0
        val maxF = freq.values.maxOrNull() ?: 1.0
        val n = ss.size
        val score = DoubleArray(n) { i ->
            val ws = words(ss[i])
            if (ws.isEmpty()) 0.0 else {
                val base = ws.sumOf { (freq[it] ?: 0.0) / maxF } / ws.size.toDouble().pow(0.55)
                val pos = if (i < 3) 1.35 else if (i < n / 3) 1.1 else 1.0
                base * pos * (1.0 + 0.15 * ln(1.0 + ws.count { it in tw }))
            }
        }
        // conclusão: a frase mais forte do último terço (onde a matéria costuma fechar o assunto)
        val tailStart = (n * 2) / 3
        val conclIdx = if (n >= 4) (tailStart until n).maxByOrNull { score[it] } ?: (n - 1) else n - 1
        val take = when {
            n > 40 -> 5
            n > 15 -> 4
            else -> 3
        }
        val picked = (0 until n).filter { it != conclIdx }.sortedByDescending { score[it] }.take(take).sorted().map { ss[it] }
        val r = Result(picked, ss[conclIdx], image, true)
        synchronized(cache) { cache[url] = r }
        return r
    }
}
