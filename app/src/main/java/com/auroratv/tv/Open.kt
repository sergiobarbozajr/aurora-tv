package com.auroratv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Canais abertos do iptv-org: uma lista pública de transmissões que já são abertas na internet.
 *
 * A lista é grande, então ela é baixada uma vez, guardada no próprio aparelho e reaproveitada
 * por um dia. Se a internet falhar, o app usa a cópia guardada em vez de ficar sem nada —
 * é justamente para isso que ela serve, ter uma alternativa quando a outra fonte falha.
 */
object Aberto {
    const val SRC = "https://iptv-org.github.io/iptv/index.m3u"

    class Ch(val name: String, val url: String, val logo: String, val group: String, val country: String)

    @Volatile
    var channels: List<Ch> = emptyList()
        private set

    @Volatile
    var loading = false
        private set

    @Volatile
    var error = ""
        private set

    /** Quando a lista guardada foi baixada (0 = nunca). */
    @Volatile
    var at = 0L
        private set

    private val main = Handler(Looper.getMainLooper())
    private fun file(c: Context) = File(c.cacheDir, "aberto.m3u")

    private fun download(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 40000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AstroLumeTV/1.0")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        if (c.responseCode !in 200..299) null else {
            val raw = if (c.contentEncoding?.contains("gzip", true) == true) java.util.zip.GZIPInputStream(c.inputStream) else c.inputStream
            raw.bufferedReader().use { it.readText() }
        }
    } catch (e: Throwable) {
        null
    }

    private fun attr(line: String, key: String): String {
        val i = line.indexOf("$key=\"")
        if (i < 0) return ""
        val j = line.indexOf('"', i + key.length + 2)
        return if (j < 0) "" else line.substring(i + key.length + 2, j)
    }

    private fun parse(body: String): List<Ch> {
        val out = ArrayList<Ch>(12000)
        var name = ""
        var logo = ""
        var group = ""
        var country = ""
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val comma = line.lastIndexOf(',')
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title").ifBlank { "Outros" }
                    country = attr(line, "tvg-country")
                }
                line.startsWith("#") || line.isEmpty() -> {}
                else -> {
                    if (name.isNotEmpty() && (line.startsWith("http") || line.startsWith("rtsp") || line.startsWith("rtmp"))) {
                        out.add(Ch(name, line, logo, group, country))
                    }
                    name = ""
                }
            }
        }
        return out
    }

    /**
     * Carrega a lista. Usa a cópia guardada se ela tiver menos de um dia,
     * e, se a internet falhar, usa a cópia guardada de qualquer idade.
     */
    fun load(c: Context, force: Boolean = false, onDone: (Int) -> Unit = {}) {
        if (loading) return
        if (!force && channels.isNotEmpty()) {
            onDone(channels.size)
            return
        }
        loading = true
        val app = c.applicationContext
        thread(isDaemon = true) {
            val f = file(app)
            val novo = f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L
            var body = if (novo && !force) try { f.readText() } catch (e: Throwable) { null } else null
            var baixou = false
            if (body == null) {
                body = download(SRC)
                baixou = body != null
                if (baixou) try { f.writeText(body!!) } catch (e: Throwable) {}
                // sem internet: vale a cópia velha, melhor do que nada
                if (body == null && f.exists()) body = try { f.readText() } catch (e: Throwable) { null }
            }
            val list = if (body == null) emptyList() else parse(body)
            channels = list
            liveCache = emptyList()
            synchronized(tagCache) { tagCache.clear() }
            at = if (f.exists()) f.lastModified() else 0L
            error = if (list.isEmpty()) "não deu para baixar a lista de canais abertos" else ""
            loading = false
            main.post { onDone(list.size) }
        }
    }

    private val PT = setOf("BR", "PT", "AO", "MZ")

    private fun cardOf(ch: Ch, i: Int): Card =
        Card(ch.name, ch.group, "stream", Palette.pick(i).first, Palette.pick(i).second, ch.url, "", emptyList(), "Canal aberto · " + ch.group, ch.logo)

    // as fileiras são montadas uma vez, quando a lista chega, e não a cada vez que a tela pede
    @Volatile
    private var liveCache: List<RowData> = emptyList()
    private val tagCache = HashMap<String, List<RowData>>()

    /** As fileiras para a TV ao vivo: primeiro o que é do Brasil, depois por categoria. */
    fun liveRows(): List<RowData> {
        liveCache.let { if (it.isNotEmpty()) return it }
        val all = channels
        if (all.isEmpty()) return emptyList()
        val out = ArrayList<RowData>()
        val br = all.filter { it.country.contains("BR", true) }
        if (br.isNotEmpty()) out.add(RowData("Canais abertos do Brasil", br.take(80).mapIndexed { i, c -> cardOf(c, i) }))
        val pt = all.filter { !it.country.contains("BR", true) && it.country.split(";").any { k -> k.trim().uppercase() in PT } }
        if (pt.isNotEmpty()) out.add(RowData("Em português", pt.take(60).mapIndexed { i, c -> cardOf(c, i + 7) }))
        val porGrupo = all.groupBy { it.group }.entries.sortedByDescending { it.value.size }
        for ((g, list) in porGrupo.take(14)) {
            if (list.size < 6) continue
            out.add(RowData(g, list.take(60).mapIndexed { i, c -> cardOf(c, i + g.hashCode() and 7) }))
        }
        liveCache = out
        return out
    }

    /** Canais de um assunto, para encaixar nas seções de Filmes, Séries e companhia. */
    fun rowsFor(vararg termos: String): List<RowData> {
        val chave = termos.joinToString("|")
        synchronized(tagCache) { tagCache[chave]?.let { return it } }
        val all = channels
        if (all.isEmpty()) return emptyList()
        val hit = all.filter { ch -> termos.any { ch.group.contains(it, true) } }
        if (hit.size < 4) return emptyList()
        val br = hit.filter { it.country.contains("BR", true) }
        val out = ArrayList<RowData>()
        if (br.isNotEmpty()) out.add(RowData("Canais abertos do Brasil", br.take(40).mapIndexed { i, c -> cardOf(c, i) }))
        out.add(RowData("Canais abertos pelo mundo", hit.filter { !it.country.contains("BR", true) }.take(60).mapIndexed { i, c -> cardOf(c, i + 5) }))
        val r = out.filter { it.cards.isNotEmpty() }
        synchronized(tagCache) { tagCache[chave] = r }
        return r
    }

    fun quando(): String {
        if (at == 0L) return "ainda não baixada"
        val h = (System.currentTimeMillis() - at) / 3600_000L
        return if (h < 1) "atualizada agora" else if (h < 24) "atualizada há ${h}h" else "atualizada há ${h / 24} dia(s)"
    }
}

/**
 * Transmissão de rede, no espírito do "abrir fluxo de rede" do VLC: você digita um endereço
 * e o app toca. Se o endereço for uma lista (.m3u), ele mostra o que tem dentro.
 */
object Rede {
    class Res(val items: List<Aberto.Ch>, val direct: Boolean, val erro: String)

    private val main = Handler(Looper.getMainLooper())

    fun abrir(url: String, onDone: (Res) -> Unit) {
        thread(isDaemon = true) {
            val u = url.trim()
            val ehLista = u.endsWith(".m3u", true) || u.endsWith(".m3u_plus", true) || u.contains("type=m3u", true)
            if (!ehLista) {
                // .m3u8 e qualquer outro endereço de vídeo vão direto para o player
                main.post { onDone(Res(emptyList(), true, "")) }
                return@thread
            }
            val body = try {
                val c = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AstroLumeTV/1.0")
                }
                if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Throwable) {
                null
            }
            if (body == null) {
                main.post { onDone(Res(emptyList(), false, "não deu para abrir esse endereço")) }
                return@thread
            }
            if (!body.contains("#EXTM3U") && !body.contains("#EXTINF")) {
                main.post { onDone(Res(emptyList(), true, "")) }
                return@thread
            }
            val list = ArrayList<Aberto.Ch>()
            var name = ""
            var logo = ""
            var group = ""
            for (raw in body.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF") -> {
                        val comma = line.lastIndexOf(',')
                        name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        logo = attrOf(line, "tvg-logo")
                        group = attrOf(line, "group-title").ifBlank { "Transmissão de rede" }
                    }
                    line.startsWith("#") || line.isEmpty() -> {}
                    else -> {
                        if (name.isNotEmpty()) list.add(Aberto.Ch(name, line, logo, group, ""))
                        name = ""
                    }
                }
            }
            main.post { onDone(Res(list, false, if (list.isEmpty()) "a lista veio vazia" else "")) }
        }
    }

    private fun attrOf(line: String, key: String): String {
        val i = line.indexOf("$key=\"")
        if (i < 0) return ""
        val j = line.indexOf('"', i + key.length + 2)
        return if (j < 0) "" else line.substring(i + key.length + 2, j)
    }

    fun cards(items: List<Aberto.Ch>): List<Card> = items.mapIndexed { i, c ->
        Card(c.name, c.group, "stream", Palette.pick(i).first, Palette.pick(i).second, c.url, "", emptyList(), "Transmissão de rede", c.logo)
    }
}

/**
 * Serviço aberto de IPTV: o usuário dá um endereço e o app tenta trazer o conteúdo para dentro.
 *
 * Ele tenta, em ordem, os formatos que um serviço costuma publicar: a lista M3U e a API Xtream.
 * Se nenhum responder, não inventa nada — devolve vazio e quem chamou abre o site no navegador.
 */
object Servico {
    class Res(val rows: Map<String, List<RowData>>, val nome: String, val total: Int, val erro: String)

    private val main = Handler(Looper.getMainLooper())

    private fun baixa(u: String, ms: Int = 25000): String? = try {
        val c = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = ms
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AstroLumeTV/1.0")
        }
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText().take(8_000_000) }
    } catch (e: Throwable) {
        null
    }

    /** Endereços que um serviço costuma usar para publicar a lista. */
    private fun candidatos(base: String): List<String> {
        val b = base.trimEnd('/')
        return listOf(
            b,
            "$b/playlist.m3u",
            "$b/playlist.m3u8",
            "$b/lista.m3u",
            "$b/tv.m3u",
            "$b/channels.m3u",
            "$b/api/playlist.m3u",
            "$b/get.php?type=m3u_plus",
        )
    }

    private fun nomeDe(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.").substringBefore('.')
            .replaceFirstChar { it.uppercase() }

    fun abrir(url: String, onDone: (Res) -> Unit) {
        thread(isDaemon = true) {
            val nome = nomeDe(url)
            var corpo: String? = null
            for (c in candidatos(url)) {
                val b = baixa(c) ?: continue
                if (b.contains("#EXTINF")) {
                    corpo = b
                    break
                }
            }
            if (corpo == null) {
                main.post { onDone(Res(emptyMap(), nome, 0, "")) }
                return@thread
            }
            // reaproveita o leitor de M3U: nome, endereço, logo e grupo
            val itens = ArrayList<Aberto.Ch>()
            var n = ""
            var logo = ""
            var grupo = ""
            for (raw in corpo.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF") -> {
                        val v = line.lastIndexOf(',')
                        n = if (v >= 0) line.substring(v + 1).trim() else ""
                        logo = at(line, "tvg-logo")
                        grupo = at(line, "group-title").ifBlank { "Canais" }
                    }
                    line.startsWith("#") || line.isEmpty() -> {}
                    else -> {
                        if (n.isNotEmpty()) itens.add(Aberto.Ch(n, line, logo, grupo, ""))
                        n = ""
                    }
                }
            }
            // separa por tipo, olhando o grupo e o formato do endereço
            fun ehFilme(c: Aberto.Ch) = c.group.contains("film", true) || c.group.contains("movie", true) || c.group.contains("vod", true) || c.url.contains("/movie/")
            fun ehSerie(c: Aberto.Ch) = c.group.contains("serie", true) || c.group.contains("series", true) || c.url.contains("/series/")
            val filmes = itens.filter { ehFilme(it) }
            val series = itens.filter { ehSerie(it) }
            val canais = itens.filter { !ehFilme(it) && !ehSerie(it) }
            fun fileiras(lista: List<Aberto.Ch>, tipo: String): List<RowData> =
                lista.groupBy { it.group }.entries.sortedByDescending { it.value.size }.take(12).map { (g, v) ->
                    RowData(
                        "$g · $nome",
                        v.take(60).mapIndexed { i, c ->
                            Card(c.name, nome, tipo, Palette.pick(i).first, Palette.pick(i).second, c.url, "", emptyList(), "Fonte: $nome", c.logo)
                        },
                    )
                }
            val mapa = HashMap<String, List<RowData>>()
            if (canais.isNotEmpty()) mapa["TV ao vivo"] = fileiras(canais, "stream")
            if (filmes.isNotEmpty()) mapa["Filmes"] = fileiras(filmes, "vod")
            if (series.isNotEmpty()) mapa["Séries"] = fileiras(series, "vod")
            main.post { onDone(Res(mapa, nome, itens.size, "")) }
        }
    }

    private fun at(line: String, key: String): String {
        val i = line.indexOf("$key=\"")
        if (i < 0) return ""
        val j = line.indexOf('"', i + key.length + 2)
        return if (j < 0) "" else line.substring(i + key.length + 2, j)
    }
}
