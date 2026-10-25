package com.auroratv.tv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.DisplayMetrics
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// ---------- paleta ----------
private val CInk = Color(0xFFF2F5FA)
private val CLight = Color(0xFFC3CDDE)
private val CDim = Color(0xFF7E8AA2)
private val CAccent = Color(0xFF6CC6FF)
private val CWarm = Color(0xFFFFD27A)
private val CCard = Color(0xFF1A1F2C)
private val CCardHi = Color(0xFF232A3B)
private val CLive = Color(0xFFFF6B81)
private val CPanel = Color(0xFF161A24)

private enum class Z { INTRO, NAV, CONTENT, PLAYER, TEST, IPTV, EXIT }

private val LocalK = compositionLocalOf { 1.5f }

@Composable
private fun u(n: Number): Dp = (n.toFloat() * LocalK.current).dp

@Composable
private fun ts(n: Number): TextUnit = (n.toFloat() * LocalK.current).sp

var videoPlayingFlag = false

class MainActivity : ComponentActivity() {
    lateinit var speech: ActivityResultLauncher<Intent>
    var onSpeech: ((String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val json = assets.open("catalog.json").bufferedReader().use { it.readText() }
        val catalog = parseCatalog(json)
        speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            var txt: String? = null
            if (r.resultCode == RESULT_OK) {
                txt = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            }
            val cb = onSpeech
            onSpeech = null
            cb?.invoke(txt)
        }
        setContent { AuroraApp(catalog, this) }
    }

    override fun onPause() {
        super.onPause()
        Sound.setPaused(true)
    }

    override fun onResume() {
        super.onResume()
        Sound.setPaused(videoPlayingFlag)
    }

    override fun onDestroy() {
        super.onDestroy()
        Sound.release()
    }
}

@Composable
fun AuroraApp(catalog: Catalog, activity: MainActivity) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val k = maxWidth.value / 1280f
        CompositionLocalProvider(LocalK provides k) { AppBody(catalog, activity) }
    }
}

private class FreeData(
    val antenna: List<Card>, val internet: List<Card>, val intl: List<Card>,
    val films: List<Card>, val anime: List<Card>, val news: List<Card>, val docs: List<Card>
)

private fun buildFree(catalog: Catalog, extra: List<Item>): FreeData {
    val ant = ArrayList<Card>()
    val net = ArrayList<Card>()
    val intl = ArrayList<Card>()
    val films = ArrayList<Card>()
    val anime = ArrayList<Card>()
    val news = ArrayList<Card>()
    val docs = ArrayList<Card>()
    fun add(list: ArrayList<Card>, c: Card) {
        if (list.none { it.name.equals(c.name, true) }) list.add(c)
    }
    for (t in catalog.tabs) {
        val tn = Iptv.norm(t.name)
        for ((i, item) in t.items.withIndex()) {
            val url = item.url
            when {
                tn.startsWith("tv ao vivo") -> {
                    if (item.kind == "antenna") add(ant, mkCard(item.title, "Antena", "antenna", i, null, item.app ?: "", emptyList(), item.desc))
                    else if (url != null && item.source == "Internet") add(net, mkCard(item.title, "Internet", "stream", i, url, "", emptyList(), item.desc))
                    else if (url != null && item.source == "Internacional") add(intl, mkCard(item.title, "Internacional", "stream", i, url, "", emptyList(), item.desc))
                }
                tn.startsWith("filmes") -> if (url != null) add(films, mkCard(item.title, "Licença livre", "vod", i, url, "", emptyList(), item.desc))
                tn.startsWith("animes") -> if (url != null) add(anime, mkCard(item.title, "Ao vivo", "stream", i, url, "", emptyList(), item.desc))
                tn.startsWith("noticias") -> if (url != null) add(news, mkCard(item.title, item.source, "stream", i, url, "", emptyList(), item.desc))
                tn.startsWith("documentarios") -> if (url != null) add(docs, mkCard(item.title, "Canal aberto", "stream", i, url, "", emptyList(), item.desc))
            }
        }
    }
    for ((i, item) in extra.withIndex()) {
        if (item.url != null) add(net, mkCard(item.title, "Internet", "stream", i + 3, item.url, "", emptyList(), item.desc))
    }
    return FreeData(ant, net, intl, films, anime, news, docs)
}

private val KEYS = listOf(
    listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J"),
    listOf("K", "L", "M", "N", "O", "P", "Q", "R", "S", "T"),
    listOf("U", "V", "W", "X", "Y", "Z", "ESPAÇO", "APAGAR", "MIC", "PRONTO")
)

private val TEST_COLORS = listOf(
    Triple(Color(0xFFFFFFFF), Color(0xFF555555), "Branco: manchas escuras, faixas, pontos claros."),
    Triple(Color(0xFF000000), Color(0xFF888888), "Preto: pontos acesos, luz nas bordas."),
    Triple(Color(0xFFFF0000), Color(0xFF330000), "Vermelho: linhas e pixels apagados."),
    Triple(Color(0xFF00FF00), Color(0xFF003300), "Verde: linhas e pixels apagados."),
    Triple(Color(0xFF0000FF), Color(0xFFCCCCFF), "Azul: linhas e pixels apagados."),
    Triple(Color(0xFF808080), Color(0xFF222222), "Cinza: manchas de poeira e brilho irregular.")
)

private class OkHolder {
    var key: String? = null
    var run: Runnable? = null
}

private class SearchCache {
    var key = ""
    var rows: List<RowData> = emptyList()
}

private fun keyToAct(k: Key): String? = when (k) {
    Key.DirectionUp -> "up"
    Key.DirectionDown -> "down"
    Key.DirectionLeft -> "left"
    Key.DirectionRight -> "right"
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> "ok"
    Key.F -> "fav"
    else -> null
}

@Composable
private fun AppBody(catalog: Catalog, activity: MainActivity) {
    val context: Context = activity
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    val engine = remember { MeteorEngine() }
    val okh = remember { OkHolder() }
    val searchCache = remember { SearchCache() }

    var zone by remember { mutableStateOf(Z.INTRO) }
    var nav by remember { mutableStateOf(0) }
    var row by remember { mutableStateOf(0) }
    var col by remember { mutableStateOf(0) }
    var sidx by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var testIdx by remember { mutableStateOf(0) }
    var loginField by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var kbd by remember { mutableStateOf(false) }
    var kr by remember { mutableStateOf(0) }
    var kc by remember { mutableStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var exitSel by remember { mutableStateOf(0) }
    var iptvFirst by remember { mutableStateOf(false) }

    var toastMsg by remember { mutableStateOf<String?>(null) }
    var toastCtx by remember { mutableStateOf("") }
    var toastJob by remember { mutableStateOf<Job?>(null) }

    var quality by remember { mutableStateOf(Store.quality) }
    var enhance by remember { mutableStateOf(Store.enhance) }
    var cityIdx by remember { mutableStateOf(Store.city.coerceIn(0, catalog.cities.size - 1)) }
    var soundLvl by remember { mutableStateOf(Store.sound) }
    var meteorOpt by remember { mutableStateOf(Store.meteor) }

    var speedText by remember { mutableStateOf("aperte OK para medir") }
    var netTick by remember { mutableStateOf(0) }
    var weatherText by remember { mutableStateOf("") }
    var clock by remember { mutableStateOf(nowLabel()) }

    val favs = remember { mutableStateListOf<String>().apply { addAll(Store.favs()) } }
    var histVersion by remember { mutableStateOf(0) }

    var fUrl by remember { mutableStateOf(Store.iptvUrl) }
    var fUser by remember { mutableStateOf(Store.iptvUser) }
    var fPass by remember { mutableStateOf(Store.iptvPass) }
    var iptvSaved by remember { mutableStateOf(Store.iptvUrl.isNotBlank()) }
    var iptvRows by remember { mutableStateOf<Map<String, List<RowData>>>(emptyMap()) }
    var iptvTotal by remember { mutableStateOf(0) }
    var iptvBusy by remember { mutableStateOf(false) }
    var iptvVersion by remember { mutableStateOf(0) }
    var m3uExtra by remember { mutableStateOf<List<Item>>(emptyList()) }

    var updMsg by remember { mutableStateOf("Aperte OK para procurar atualização.") }
    var updUrl by remember { mutableStateOf<String?>(null) }
    var updBusy by remember { mutableStateOf(false) }

    var playCard by remember { mutableStateOf<Card?>(null) }
    var playList by remember { mutableStateOf<List<Card>>(emptyList()) }
    var playIdx by remember { mutableStateOf(0) }
    var noSignal by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                noSignal = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) noSignal = false
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(zone) {
        try { focus.requestFocus() } catch (e: Exception) {}
    }
    LaunchedEffect(zone) {
        videoPlayingFlag = zone == Z.PLAYER
        Sound.setPaused(zone == Z.PLAYER)
    }
    LaunchedEffect(Unit) { Sound.apply(Store.sound) }
    LaunchedEffect(nav, zone) {
        if (toastCtx != "$nav|$zone") toastMsg = null
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = nowLabel()
            delay(15000)
        }
    }
    LaunchedEffect(cityIdx) {
        val c = catalog.cities[cityIdx]
        weatherText = ""
        Net.fetchWeather(c) { r -> weatherText = if (r != null) c.name + " " + r.substringBefore(" · ") else c.name }
    }
    LaunchedEffect(Unit) {
        for (src in catalog.lists) {
            Net.loadM3u(src) { loaded -> m3uExtra = m3uExtra + loaded }
        }
    }
    LaunchedEffect(Unit) {
        if (iptvSaved) {
            iptvBusy = true
            Iptv.load(Store.iptvUrl, Store.iptvUser, Store.iptvPass) { r ->
                iptvBusy = false
                if (r.total > 0) {
                    iptvRows = r.rows
                    iptvTotal = r.total
                    iptvVersion++
                }
            }
        }
    }
    // sem sinal: tempo demais carregando também conta como sem sinal; tenta reconectar sozinho
    LaunchedEffect(zone, playCard) {
        if (zone == Z.PLAYER) {
            var stuck = 0
            while (true) {
                delay(1000)
                if (player.playbackState == Player.STATE_BUFFERING) {
                    stuck++
                    if (stuck >= 12) noSignal = true
                } else stuck = 0
            }
        }
    }
    LaunchedEffect(noSignal, zone) {
        if (noSignal && zone == Z.PLAYER) {
            while (true) {
                delay(8000)
                try {
                    player.prepare()
                    player.play()
                } catch (e: Exception) {}
            }
        }
    }

    // ---------- dados ----------
    val free = remember(catalog, m3uExtra) { buildFree(catalog, m3uExtra) }
    val allCards = remember(free, iptvVersion) {
        val l = ArrayList<Card>()
        l.addAll(free.antenna); l.addAll(free.internet); l.addAll(free.intl); l.addAll(free.films)
        l.addAll(free.anime); l.addAll(free.news); l.addAll(free.docs)
        streamRows().forEach { l.addAll(it.cards) }
        iptvRows.values.forEach { rs -> rs.forEach { l.addAll(it.cards) } }
        l
    }
    val byName = remember(allCards) {
        val m = LinkedHashMap<String, Card>()
        for (c in allCards) if (!m.containsKey(c.name)) m[c.name] = c
        m
    }

    fun loginCard(): Card = mkCard(
        if (iptvSaved) "Conta IPTV conectada" else "Entrar com IPTV",
        if (iptvSaved) "$iptvTotal itens · OK para trocar ou remover" else "conta licenciada",
        "login", 0
    )

    fun histCards(): List<Card> = Store.history().mapNotNull { byName[it] }

    fun rowsFor(i: Int): List<RowData> {
        val name = SECTIONS[i].name
        fun iptv(sec: String): List<RowData> = iptvRows[sec] ?: emptyList()
        fun withLogin(r: List<RowData>): List<RowData> =
            if (!iptvSaved) listOf(RowData("Sua conta IPTV", listOf(loginCard()))) + r else r
        val out: List<RowData> = when (name) {
            "Início" -> {
                val r = ArrayList<RowData>()
                val h = histCards()
                if (h.isNotEmpty()) r.add(RowData("Continuar assistindo", h))
                r.add(RowData("Abertos pela internet", free.internet))
                r.add(RowData("Internacionais", free.intl))
                r.add(RowData("Seus streams", streamRows().flatMap { it.cards }.take(8)))
                r
            }
            "Pesquisar" -> {
                val q = Iptv.norm(query.trim())
                val key = "$q#$iptvVersion#${allCards.size}"
                if (searchCache.key != key) {
                    searchCache.key = key
                    searchCache.rows = if (q.isEmpty()) {
                        listOf(RowData("Sugestões", (free.intl + free.internet + free.anime).take(10)))
                    } else {
                        val res = allCards.filter { Iptv.norm(it.name).contains(q) }.take(120)
                        if (res.isEmpty()) listOf(RowData("Nada encontrado para “$query”", listOf(mkCard("Tente outra palavra", "", "empty", 0))))
                        else listOf(RowData("Resultados para “$query” (${res.size})", res))
                    }
                }
                searchCache.rows
            }
            "Minhas Listas" -> {
                val f = favs.mapNotNull { byName[it] }
                val r = ArrayList<RowData>()
                r.add(RowData("Favoritos (${f.size})", if (f.isNotEmpty()) f else listOf(mkCard("Nenhum favorito ainda", "OK duas vezes num título", "empty", 0))))
                val h = histCards()
                if (h.isNotEmpty()) r.add(RowData("Vistos recentemente", h))
                r
            }
            "Streams" -> streamRows()
            "Filmes" -> withLogin(iptv("Filmes") + RowData("Filmes de licença livre", free.films))
            "Séries" -> withLogin(iptv("Séries") + RowData("Animes ao vivo", free.anime))
            "Novelas" -> withLogin(iptv("Novelas"))
            "Documentários" -> withLogin(iptv("Documentários") + RowData("Canais abertos", free.docs))
            "Esporte" -> withLogin(iptv("Esporte"))
            "Notícias" -> withLogin(iptv("Notícias") + RowData("Ao vivo", free.news))
            "TV ao vivo" -> listOf(RowData("Sua lista IPTV", listOf(loginCard()))) +
                RowData("Antena digital da TV", free.antenna) + iptv("TV ao vivo") +
                RowData("Abertos pela internet", free.internet) + RowData("Internacionais", free.intl)
            else -> emptyList()
        }
        return out.filter { it.cards.isNotEmpty() }
    }

    val rows = remember(nav, query, iptvVersion, favs.size, histVersion, m3uExtra, iptvSaved, iptvTotal, allCards) { rowsFor(nav) }
    val sec = SECTIONS[nav]
    val base = if (sec.kind == "search") 1 else 0

    fun curCard(): Card? {
        val r = rows.getOrNull(row - base) ?: return null
        return r.cards.getOrNull(col) ?: r.cards.firstOrNull()
    }

    // ---------- ações ----------
    fun showToast(m: String) {
        toastMsg = m
        toastCtx = "$nav|$zone"
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(5000)
            toastMsg = null
        }
    }

    fun askText(title: String, initial: String, secret: Boolean, onOk: (String) -> Unit) {
        try {
            val et = EditText(context)
            et.setText(initial)
            et.setSingleLine(true)
            et.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            et.setSelection(et.text.length)
            AlertDialog.Builder(context)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("OK") { _, _ -> onOk(et.text.toString()) }
                .setNegativeButton("Cancelar", null)
                .show()
        } catch (e: Exception) {
        }
    }

    fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            activity.onSpeech = { txt ->
                listening = false
                if (txt != null && txt.isNotBlank()) {
                    query = txt.lowercase()
                    showToast("Entendi: “$txt”")
                }
            }
            listening = true
            activity.speech.launch(i)
        } catch (e: Exception) {
            listening = false
            showToast("Voz indisponível neste aparelho.")
        }
    }

    fun toggleFav(c: Card) {
        if (c.kind == "login" || c.kind == "empty") return
        if (favs.contains(c.name)) {
            favs.remove(c.name)
            showToast("${c.name} saiu dos Favoritos")
        } else {
            favs.add(0, c.name)
            showToast("★ ${c.name} adicionado a Minhas Listas")
        }
        Store.saveFavs(favs.toList())
        val r = rows.getOrNull(row - base)
        if (r != null && col > r.cards.size - 1) col = max(0, r.cards.size - 1)
    }

    fun play(c: Card, list: List<Card>) {
        val url = c.url ?: return
        try {
            player.setMediaItem(MediaItem.fromUri(url))
            applyQuality(player, quality)
            applyEnhance(player, enhance)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            noSignal = true
        }
        playCard = c
        playList = list.filter { it.kind == "stream" && it.url != null }
        playIdx = playList.indexOfFirst { it.name == c.name }.coerceAtLeast(0)
        noSignal = false
        Store.pushHistory(c.name)
        histVersion++
        zone = Z.PLAYER
    }

    fun zap(d: Int) {
        val c = playCard ?: return
        if (c.kind != "stream" || playList.size < 2) return
        val n = playList.size
        val ni = ((playIdx + d) % n + n) % n
        play(playList[ni], playList)
    }

    fun openAntenna(c: Card) {
        var ok = false
        try {
            val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val tuner = tim?.tvInputList?.firstOrNull { it.type == TvInputInfo.TYPE_TUNER }
            if (tuner != null) {
                val i = Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner.id))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                ok = true
            }
        } catch (e: Exception) {
        }
        if (!ok) {
            try {
                val i = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                ok = true
            } catch (e: Exception) {
            }
        }
        if (!ok) showToast("Para ver ${c.name}, aperte ENTRADAS no controle e escolha Antena.")
    }

    fun launchApp(c: Card) {
        val pm = context.packageManager
        for (p in c.pkgs) {
            val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p)
            if (i != null) {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    return
                } catch (e: Exception) {
                }
            }
        }
        val uri = if (c.pkgs.isNotEmpty()) "market://details?id=${c.pkgs[0]}" else "market://search?q=${Uri.encode(c.name)}"
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            showToast("${c.name} não está instalado. Abri a Play Store.")
        } catch (e: Exception) {
            showToast("Instale o ${c.name} pela Play Store.")
        }
    }

    fun activateCard(c: Card, list: List<Card>) {
        when (c.kind) {
            "login" -> {
                zone = Z.IPTV
                loginField = 0
                iptvFirst = false
            }
            "app" -> launchApp(c)
            "antenna" -> openAntenna(c)
            "stream", "vod" -> play(c, list)
            "series" -> {
                showToast("Procurando o primeiro episódio…")
                Iptv.firstEpisode(c.extra) { url, ep ->
                    if (url != null) {
                        play(c.copy(url = url, kind = "vod"), emptyList())
                        showToast("${c.name} · $ep (a escolha de episódios vem na próxima versão)")
                    } else showToast("Sem sinal dessa série agora.")
                }
            }
        }
    }

    fun doLogin() {
        if (fUrl.isBlank()) {
            showToast("Digite o endereço (URL) da sua assinatura.")
            return
        }
        if (iptvBusy) return
        iptvBusy = true
        showToast("Entrando e carregando a sua lista…")
        Iptv.load(fUrl, fUser, fPass) { r ->
            iptvBusy = false
            if (r.total > 0) {
                Store.iptvUrl = fUrl
                Store.iptvUser = fUser
                Store.iptvPass = fPass
                iptvRows = r.rows
                iptvTotal = r.total
                iptvSaved = true
                iptvVersion++
                zone = Z.NAV
                nav = 0
                row = 0
                col = 0
                iptvFirst = false
                showToast("Conta conectada: ${r.total} itens separados em Filmes, Séries, Novelas e outros.")
            } else {
                showToast(
                    when (r.error) {
                        "login" -> "Usuário ou senha não aceitos."
                        "vazio" -> "A lista veio vazia."
                        else -> "Sem sinal do servidor. Confira o endereço."
                    }
                )
            }
        }
    }

    fun disconnect() {
        Store.iptvUrl = ""
        Store.iptvUser = ""
        Store.iptvPass = ""
        fUrl = ""
        fUser = ""
        fPass = ""
        iptvSaved = false
        iptvRows = emptyMap()
        iptvTotal = 0
        iptvVersion++
        loginField = 0
        showToast("Conta removida deste aparelho.")
    }

    fun updAction() {
        if (updBusy) return
        val url = updUrl
        if (url != null) {
            updBusy = true
            updMsg = "Baixando… 0%"
            Updater.downloadAndInstall(context, url) { p ->
                if (p < 0) {
                    updBusy = false
                    updMsg = "Sem sinal para baixar. Tente de novo."
                } else if (p < 100) {
                    updMsg = "Baixando… $p%"
                } else {
                    updBusy = false
                    updMsg = if (Updater.canInstall(context)) "Pronto. Confirme a instalação na tela do Android."
                    else "Libere “Instalar apps desconhecidos” para o AuroraTV (abri a tela) e aperte OK de novo."
                }
            }
            return
        }
        updBusy = true
        updMsg = "Procurando atualização…"
        Updater.check(context) { r ->
            updBusy = false
            when (r.status) {
                "new" -> {
                    updUrl = r.apkUrl
                    updMsg = "Nova versão ${r.remote} disponível. Aperte OK para baixar e instalar."
                }
                "same" -> updMsg = "Você já está na versão mais recente (${Updater.currentVersion(context)})."
                "none" -> updMsg = "Nenhuma atualização publicada ainda. Se o repositório no GitHub for privado, o app não consegue conferir: torne-o público."
                else -> updMsg = "Sem conexão para conferir. Tente de novo."
            }
        }
    }

    fun valueName(k: String): String = when (k) {
        "quality" -> QUALITY_NAMES[quality]
        "enh" -> ENHANCE_NAMES[enhance]
        "city" -> catalog.cities[cityIdx].name
        "sound" -> SOUND_NAMES[soundLvl]
        else -> METEOR_NAMES[meteorOpt]
    }

    fun changeVal(k: String, d: Int) {
        when (k) {
            "quality" -> {
                quality = (quality + d + QUALITY_NAMES.size) % QUALITY_NAMES.size
                Store.quality = quality
                applyQuality(player, quality)
            }
            "enh" -> {
                enhance = (enhance + d + ENHANCE_NAMES.size) % ENHANCE_NAMES.size
                Store.enhance = enhance
                applyEnhance(player, enhance)
            }
            "city" -> {
                cityIdx = (cityIdx + d + catalog.cities.size) % catalog.cities.size
                Store.city = cityIdx
            }
            "sound" -> {
                soundLvl = (soundLvl + d + SOUND_NAMES.size) % SOUND_NAMES.size
                Store.sound = soundLvl
                Sound.apply(soundLvl)
            }
            else -> {
                meteorOpt = (meteorOpt + d + METEOR_NAMES.size) % METEOR_NAMES.size
                Store.meteor = meteorOpt
            }
        }
    }

    fun finishIntro() {
        if (iptvSaved) {
            zone = Z.NAV
        } else {
            zone = Z.IPTV
            iptvFirst = true
            loginField = 0
        }
    }

    fun clampCol() {
        val r = rows.getOrNull(row - base)
        if (r != null) col = min(col, max(0, r.cards.size - 1)) else col = 0
    }

    fun typeKey(k: String) {
        when (k) {
            "ESPAÇO" -> query += " "
            "APAGAR" -> query = query.dropLast(1)
            "PRONTO" -> {
                kbd = false
                if (rows.isNotEmpty()) {
                    row = 1
                    col = 0
                }
            }
            "MIC" -> startVoice()
            else -> query += k.lowercase()
        }
    }

    fun cardOk() {
        val c = curCard() ?: return
        val list = rows.getOrNull(row - base)?.cards ?: emptyList()
        if (c.kind == "login" || c.kind == "empty") {
            activateCard(c, list)
            return
        }
        val key = "$nav:$row:$col"
        val pending = okh.run
        if (okh.key == key && pending != null) {
            handler.removeCallbacks(pending)
            okh.run = null
            okh.key = null
            toggleFav(c)
        } else {
            okh.key = key
            val r = Runnable {
                okh.key = null
                okh.run = null
                activateCard(c, list)
            }
            okh.run = r
            handler.postDelayed(r, 380)
        }
    }

    fun settingsAct(a: String) {
        val item = SETTING_ITEMS[sidx]
        val ek = item.edit
        if (editing && ek != null) {
            when (a) {
                "left" -> changeVal(ek, -1)
                "right" -> changeVal(ek, 1)
                "back", "ok" -> {
                    editing = false
                    showToast("Salvo: " + valueName(ek))
                }
            }
            return
        }
        when (a) {
            "back", "left" -> zone = Z.NAV
            "up" -> sidx = max(0, sidx - 1)
            "down" -> sidx = min(SETTING_ITEMS.size - 1, sidx + 1)
            "ok" -> {
                if (ek != null) editing = true
                else when (item.name) {
                    "Rede e internet" -> {
                        speedText = "medindo…"
                        Net.speedTest { speedText = it; netTick++ }
                    }
                    "Teste de tela" -> {
                        testIdx = 0
                        zone = Z.TEST
                    }
                    "Abrir ajustes do Google TV" -> openDisplaySettings(context)
                    "Conta IPTV" -> {
                        zone = Z.IPTV
                        loginField = 0
                        iptvFirst = false
                    }
                    "Atualização do app" -> updAction()
                }
            }
        }
    }

    fun contentAct(a: String) {
        if (a == "home") {
            zone = Z.NAV
            nav = 0
            editing = false
            kbd = false
            return
        }
        if (a == "fav") {
            val c = curCard()
            if (c != null) toggleFav(c)
            return
        }
        if (sec.kind == "settings") {
            settingsAct(a)
            return
        }
        if (kbd) {
            when (a) {
                "back" -> kbd = false
                "up" -> kr = max(0, kr - 1)
                "down" -> kr = min(KEYS.size - 1, kr + 1)
                "left" -> kc = max(0, kc - 1)
                "right" -> kc = min(KEYS[kr].size - 1, kc + 1)
                "ok" -> typeKey(KEYS[kr][kc])
            }
            return
        }
        if (a == "back") {
            zone = Z.NAV
            return
        }
        val maxRow = rows.size - 1 + base
        if (sec.kind == "search" && row == 0) {
            when (a) {
                "ok" -> {
                    kbd = true
                    kr = 0
                    kc = 0
                }
                "down" -> if (rows.isNotEmpty()) {
                    row = 1
                    col = 0
                }
                "left", "up" -> zone = Z.NAV
            }
            return
        }
        when (a) {
            "up" -> if (row > 0) {
                row--
                if (row >= base) clampCol() else col = 0
            } else zone = Z.NAV
            "down" -> if (row < maxRow) {
                row++
                clampCol()
            }
            "left" -> if (col > 0) col-- else zone = Z.NAV
            "right" -> {
                val r = rows.getOrNull(row - base)
                if (r != null && col < r.cards.size - 1) col++
            }
            "ok" -> cardOk()
        }
    }

    fun act(a: String) {
        when (zone) {
            Z.INTRO -> finishIntro()
            Z.PLAYER -> when (a) {
                "back" -> {
                    player.stop()
                    zone = Z.CONTENT
                }
                "up" -> zap(-1)
                "down" -> zap(1)
                "left" -> if (playCard?.kind != "stream") player.seekTo(max(0L, player.currentPosition - 10000L))
                "right" -> if (playCard?.kind != "stream") player.seekTo(player.currentPosition + 10000L)
                "ok" -> if (playCard?.kind != "stream") {
                    if (player.isPlaying) player.pause() else player.play()
                }
            }
            Z.TEST -> when (a) {
                "back" -> zone = Z.CONTENT
                "ok", "right" -> testIdx = (testIdx + 1) % TEST_COLORS.size
                "left" -> testIdx = (testIdx + TEST_COLORS.size - 1) % TEST_COLORS.size
            }
            Z.IPTV -> {
                val n = if (iptvSaved) 5 else 4
                when (a) {
                    "back", "home" -> {
                        zone = if (iptvFirst) Z.NAV else Z.CONTENT
                        iptvFirst = false
                    }
                    "up" -> loginField = (loginField + n - 1) % n
                    "down" -> loginField = (loginField + 1) % n
                    "ok" -> when (loginField) {
                        0 -> askText("Endereço (URL)", fUrl, false) { fUrl = it.trim() }
                        1 -> askText("Usuário", fUser, false) { fUser = it.trim() }
                        2 -> askText("Senha", fPass, true) { fPass = it.trim() }
                        3 -> doLogin()
                        else -> disconnect()
                    }
                }
            }
            Z.EXIT -> when (a) {
                "left", "right" -> exitSel = 1 - exitSel
                "back", "home" -> zone = Z.NAV
                "ok" -> if (exitSel == 0) zone = Z.NAV else activity.finish()
            }
            Z.NAV -> when (a) {
                "up" -> {
                    nav = max(0, nav - 1)
                    row = 0
                    col = 0
                }
                "down" -> {
                    nav = min(SECTIONS.size - 1, nav + 1)
                    row = 0
                    col = 0
                }
                "right", "ok" -> {
                    if (SECTIONS[nav].kind == "exit") {
                        zone = Z.EXIT
                        exitSel = 0
                    } else {
                        if (meteorOpt == 0) engine.spawn("enter")
                        zone = Z.CONTENT
                        row = 0
                        col = 0
                        sidx = 0
                        editing = false
                        kbd = false
                    }
                }
                "home" -> {
                    nav = 0
                    row = 0
                    col = 0
                }
                "back" -> {
                    zone = Z.EXIT
                    exitSel = 0
                }
            }
            Z.CONTENT -> contentAct(a)
        }
    }

    BackHandler(enabled = true) { act("back") }

    val tick = frameClock(zone != Z.PLAYER && zone != Z.TEST)
    val showMain = zone != Z.PLAYER && zone != Z.TEST

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    val a = keyToAct(e.key)
                    if (a != null) {
                        act(a)
                        true
                    } else false
                } else false
            }
            .focusable()
    ) {
        when (zone) {
            Z.PLAYER -> PlayerScreen(player, playCard, noSignal)
            Z.TEST -> {
                val tc = TEST_COLORS[testIdx]
                Box(Modifier.fillMaxSize().background(tc.first)) {
                    Text(
                        "${testIdx + 1}/${TEST_COLORS.size}  ${tc.third}   OK: próxima   VOLTAR: sair",
                        color = tc.second, fontSize = ts(16),
                        modifier = Modifier.align(Alignment.BottomStart).padding(u(30))
                    )
                }
            }
            else -> {
                BackgroundFx(tick)
                if (zone != Z.INTRO) {
                    if (zone == Z.IPTV) {
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF122034), Color(0xFF080A0F)))))
                    }
                    if (sec.kind == "settings" && zone != Z.IPTV) {
                        val cur = SETTING_ITEMS[sidx]
                        SettingsScreen(
                            sidx, editing && zone == Z.CONTENT, zone == Z.CONTENT,
                            cur.edit?.let { valueName(it) },
                            settingBody(
                                cur, context, speedText, netTick, catalog.cities[cityIdx].name, weatherText, editing,
                                updMsg, iptvSaved, iptvTotal
                            )
                        )
                    } else if (zone != Z.IPTV) {
                        ContentScreen(
                            sec, rows, base, zone == Z.CONTENT, row, col, favs, query, kbd, kr, kc, listening, curCard()
                        )
                    }
                    Sidebar(nav, zone == Z.NAV)
                    if (zone == Z.IPTV) {
                        IptvBox(fUrl, fUser, fPass, loginField, iptvFirst, iptvSaved, iptvBusy)
                    }
                    if (zone == Z.EXIT) ExitDialog(exitSel)
                    Hint(zone, sec, editing, kbd, Modifier.align(Alignment.BottomStart))
                    Pill(clock, weatherText, Modifier.align(Alignment.BottomEnd))
                }
            }
        }
        val tm = toastMsg
        if (tm != null && zone != Z.INTRO) {
            Box(Modifier.align(Alignment.BottomCenter).padding(start = u(280), bottom = u(56), end = u(40))) {
                Box(
                    Modifier.clip(RoundedCornerShape(u(12))).background(Color(0xDD000000))
                        .border(BorderStroke(u(2), CAccent), RoundedCornerShape(u(12)))
                        .padding(horizontal = u(22), vertical = u(12))
                ) {
                    Text(tm, color = CInk, fontSize = ts(17), textAlign = TextAlign.Center)
                }
            }
        }
        if (showMain && zone != Z.INTRO) MeteorLayer(tick, engine, meteorOpt == 0)
        if (zone == Z.INTRO) IntroAnim { finishIntro() }
    }
}

// ---------------------------------------------------------------- telas

@Composable
private fun Sidebar(nav: Int, focused: Boolean) {
    val inf = rememberInfiniteTransition(label = "fl")
    val fy by inf.animateFloat(
        initialValue = -2.5f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(3500), RepeatMode.Reverse),
        label = "fy"
    )
    val shape = RoundedCornerShape(u(44))
    val k = LocalK.current
    val fontPx = with(LocalDensity.current) { (26f * k).dp.toPx() }
    Box(
        Modifier.padding(start = u(22), top = u(22), bottom = u(22)).width(u(258)).fillMaxHeight()
            .graphicsLayer { translationY = fy * density }
            .clip(shape).background(Color(0x6B0E121C)).border(BorderStroke(u(1), Color(0x2EFFFFFF)), shape)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = u(14), vertical = u(24))) {
            Box(Modifier.padding(start = u(10), bottom = u(8))) { LogoSmall(fontPx) }
            SECTIONS.forEachIndexed { i, s ->
                if (i == SECTIONS.size - 2) Spacer(Modifier.weight(1f))
                NavItem(s, focused && i == nav, !focused && i == nav)
            }
        }
    }
}

@Composable
private fun NavItem(s: Section, foc: Boolean, sel: Boolean) {
    val gk = LocalK.current * LocalDensity.current.density
    val shape = RoundedCornerShape(u(14))
    var m = Modifier.fillMaxWidth()
    if (foc) {
        m = m.drawBehind {
            drawRoundRect(Color(0x33FFFFFF), Offset(-10f * gk, -6f * gk), Size(size.width + 20f * gk, size.height + 12f * gk), CornerRadius(30f * gk))
            drawRoundRect(Color(0x22FFFFFF), Offset(-20f * gk, -12f * gk), Size(size.width + 40f * gk, size.height + 24f * gk), CornerRadius(40f * gk))
        }.clip(shape).background(Brush.horizontalGradient(listOf(Color.White, Color(0xFFEAF4FF))), shape)
    }
    Row(m.padding(horizontal = u(16), vertical = u(8)), verticalAlignment = Alignment.CenterVertically) {
        val c = if (foc) Color.Black else if (sel) Color.White else Color(0xFFAEBBD2)
        NavIcon(s.icon, c, u(24))
        Spacer(Modifier.width(u(14)))
        Text(s.name, color = c, fontSize = ts(19), fontWeight = if (foc) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CardView(c: Card, focused: Boolean, fav: Boolean) {
    val shape = RoundedCornerShape(u(12))
    val gk = LocalK.current * LocalDensity.current.density
    val w = if (c.kind == "login") u(430) else u(206)
    var m = Modifier.width(w).height(u(122))
    if (focused) {
        m = m.drawBehind {
            drawRoundRect(Color(0x33CFE9FF), Offset(-8f * gk, -6f * gk), Size(size.width + 16f * gk, size.height + 14f * gk), CornerRadius(20f * gk))
        }
    }
    m = if (c.kind == "empty") m.background(CCard, shape) else m.background(Brush.linearGradient(listOf(c.hueA, c.hueB)), shape)
    if (focused) m = m.border(BorderStroke(u(2), Color(0xFFCFE9FF)), shape)
    else if (c.kind == "empty") m = m.border(BorderStroke(u(1), Color(0xFF262C3B)), shape)
    Box(m) {
        if (c.kind == "login") {
            Column(Modifier.align(Alignment.CenterStart).padding(horizontal = u(18))) {
                Text("＋ " + c.name, color = CInk, fontSize = ts(20), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.height(u(6)))
                Text(c.label, color = CLight, fontSize = ts(13), maxLines = 2)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(u(12)), verticalArrangement = Arrangement.SpaceBetween) {
                if (c.label.isNotEmpty()) {
                    val live = c.kind == "stream" || c.kind == "antenna"
                    Box(
                        Modifier.clip(RoundedCornerShape(u(5)))
                            .background(if (live) CLive else Color(0xD1FFFFFF))
                            .padding(horizontal = u(7), vertical = u(2))
                    ) {
                        Text(c.label, color = Color(0xFF0A1018), fontSize = ts(11), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else Spacer(Modifier.height(u(1)))
                Text(
                    c.name, color = if (c.kind == "empty") CDim else CInk,
                    fontSize = ts(if (c.kind == "empty") 15 else 17), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (fav) Text("★", color = CWarm, fontSize = ts(20), modifier = Modifier.align(Alignment.TopEnd).padding(top = u(8), end = u(10)))
    }
}

@Composable
private fun HeroView(c: Card?, scene: Boolean, fav: Boolean, secName: String) {
    val shape = RoundedCornerShape(u(18))
    Box(Modifier.fillMaxWidth().height(u(254)).clip(shape)) {
        if (scene) {
            FamilyScene(Modifier.fillMaxSize())
        } else {
            val a = c?.hueA ?: Color(0xFF141A26)
            val b = c?.hueB ?: Color(0xFF080A0F)
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b))))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color(0x47FFFFFF), Color.Transparent), center = Offset(1500f, 120f), radius = 900f)
                )
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0.08f to Color(0xFF0A0C10), 0.6f to Color(0x330A0C10), 1f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFF0A0C10))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = u(34), bottom = u(30), end = u(34))) {
            val tag = when {
                c == null -> secName.uppercase()
                c.kind == "login" -> "TV AO VIVO"
                else -> c.label.uppercase() + if (fav) " · ★ FAVORITO" else ""
            }
            Text(tag, color = CAccent, fontSize = ts(14), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(u(8)))
            Text(
                if (c == null) secName else if (c.kind == "login") "Entrar com IPTV" else c.name,
                color = CInk, fontSize = ts(44), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(u(8)))
            Text(
                when {
                    c == null -> ""
                    c.kind == "login" -> "Conecte a sua conta licenciada e os canais entram aqui."
                    scene -> "OK abre o app · a conta é a sua, dentro do próprio serviço"
                    c.kind == "antenna" -> "OK abre a TV da TCL na entrada de antena"
                    else -> "OK abre · OK duas vezes favorita"
                },
                color = CLight, fontSize = ts(18), maxLines = 1
            )
        }
    }
}

@Composable
private fun RowsView(rows: List<RowData>, base: Int, active: Boolean, row: Int, col: Int, favs: List<String>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(row, active) {
        if (active) state.animateScrollToItem(max(0, row - base - 1))
    }
    LazyColumn(modifier, state = state) {
        itemsIndexed(rows) { ri, r ->
            val isActive = active && row == ri + base
            Column(Modifier.padding(bottom = u(26))) {
                Text(
                    r.title, color = CLight, fontSize = ts(19), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = u(14))
                )
                val rs = rememberLazyListState()
                LaunchedEffect(col, isActive) {
                    if (isActive) rs.animateScrollToItem(max(0, col - 3))
                }
                LazyRow(state = rs, horizontalArrangement = Arrangement.spacedBy(u(16)), modifier = Modifier.padding(vertical = u(6))) {
                    itemsIndexed(r.cards) { ci, c -> CardView(c, isActive && col == ci, favs.contains(c.name)) }
                }
            }
        }
    }
}

@Composable
private fun ContentScreen(
    sec: Section, rows: List<RowData>, base: Int, active: Boolean, row: Int, col: Int, favs: List<String>,
    query: String, kbd: Boolean, kr: Int, kc: Int, listening: Boolean, cur: Card?
) {
    val withHero = sec.kind != "search" && sec.kind != "lists"
    Column(Modifier.fillMaxSize().padding(start = u(336), end = u(36), top = u(30), bottom = u(44))) {
        if (withHero) {
            HeroView(cur, sec.name == "Streams", cur != null && favs.contains(cur.name), sec.name)
            Spacer(Modifier.height(u(18)))
        }
        if (sec.kind == "search") {
            val barFoc = active && row == 0
            val shape = RoundedCornerShape(u(14))
            Row(
                Modifier.fillMaxWidth().height(u(62)).background(CCard, shape)
                    .then(if (barFoc || kbd) Modifier.border(BorderStroke(u(2), Color(0xFFCFE9FF)), shape) else Modifier)
                    .padding(horizontal = u(22)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIcon("search", CLight, u(26))
                Spacer(Modifier.width(u(14)))
                if (query.isEmpty() && !kbd) Text("Buscar canais, filmes, séries, novelas, documentários…", color = CDim, fontSize = ts(20), maxLines = 1)
                else Text(query + if (kbd) "|" else "", color = Color.White, fontSize = ts(22), fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.height(u(14)))
            if (kbd) {
                KEYS.forEachIndexed { ri, r ->
                    Row(Modifier.fillMaxWidth().padding(bottom = u(8)), horizontalArrangement = Arrangement.spacedBy(u(8))) {
                        r.forEachIndexed { ci, key ->
                            val f = kr == ri && kc == ci
                            val ks = RoundedCornerShape(u(9))
                            val lab = if (key == "MIC") (if (listening) "Ouvindo…" else "🎙 Falar") else key
                            Box(
                                Modifier.weight(1f).height(u(44))
                                    .background(if (key == "MIC" && listening) CLive else if (f) CCardHi else CCard, ks)
                                    .then(if (f) Modifier.border(BorderStroke(u(2), Color(0xFFCFE9FF)), ks) else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(lab, color = if (key == "MIC" && !listening) CAccent else if (f) Color.White else CLight, fontSize = ts(16), fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }
            } else {
                Text("OK na barra abre o teclado, com microfone. Uma busca só, em todo o conteúdo.", color = CDim, fontSize = ts(15))
                Spacer(Modifier.height(u(12)))
            }
        }
        RowsView(rows, base, active && !kbd, row, col, favs, Modifier.weight(1f))
    }
}

@Composable
private fun SettingsScreen(sidx: Int, editing: Boolean, focused: Boolean, value: String?, body: String) {
    Column(Modifier.fillMaxSize().padding(start = u(336), end = u(36), top = u(30), bottom = u(44))) {
        Text("Configurações", color = CInk, fontSize = ts(34), fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(u(18)))
        Row(Modifier.fillMaxSize()) {
            val st = rememberLazyListState()
            LaunchedEffect(sidx) { st.animateScrollToItem(max(0, sidx - 4)) }
            LazyColumn(Modifier.width(u(430)).fillMaxHeight(), state = st, verticalArrangement = Arrangement.spacedBy(u(8))) {
                itemsIndexed(SETTING_ITEMS) { i, it ->
                    val on = focused && i == sidx
                    val shape = RoundedCornerShape(u(12))
                    Box(
                        Modifier.fillMaxWidth().height(u(58))
                            .background(if (on) CCardHi else CCard, shape)
                            .then(
                                if (on) Modifier.border(BorderStroke(u(2), if (editing) CWarm else Color(0xFFCFE9FF)), shape) else Modifier
                            )
                            .padding(horizontal = u(20)),
                        contentAlignment = Alignment.CenterStart
                    ) { Text(it.name, color = CInk, fontSize = ts(19), fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
            Spacer(Modifier.width(u(24)))
            Column(Modifier.fillMaxSize().background(CCard, RoundedCornerShape(u(14))).padding(u(24))) {
                if (value != null) {
                    val shape = RoundedCornerShape(u(10))
                    Row(
                        Modifier.background(Color(0xFF0E121B), shape)
                            .then(if (editing) Modifier.border(BorderStroke(u(2), CWarm), shape) else Modifier)
                            .padding(horizontal = u(16), vertical = u(8)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("◀", color = CWarm, fontSize = ts(22))
                        Text("  $value  ", color = Color.White, fontSize = ts(22), fontWeight = FontWeight.Bold)
                        Text("▶", color = CWarm, fontSize = ts(22))
                    }
                    Spacer(Modifier.height(u(10)))
                }
                Text(body, color = CLight, fontSize = ts(18), lineHeight = ts(26))
            }
        }
    }
}

private fun settingBody(
    item: SettingItem, context: Context, speed: String, netTick: Int, city: String, weather: String, editing: Boolean,
    updMsg: String, iptvSaved: Boolean, iptvTotal: Int
): String {
    val nl = "\n"
    val tip = if (editing) "Editando: ◀ ▶ mudam · Voltar salva e sai." else "OK para entrar e mudar."
    return when (item.name) {
        "Qualidade de vídeo" -> {
            val dm = context.resources.displayMetrics
            tip + nl + nl + "Limita a resolução pedida nos canais ao vivo. Automática usa a melhor possível; Economia poupa dados." + nl + nl +
                "Saída atual da tela: ${dm.widthPixels} x ${dm.heightPixels}"
        }
        "Realce de imagem" -> tip + nl + nl + "Cor, contraste e luz no vídeo dos canais que tocam aqui. Ligado, desativa o HDR10 nativo naquele vídeo. Para filmes HDR, deixe Desligado."
        "Clima e cidade" -> tip + nl + nl + "Agora em $city: " + weather
        "Som do aplicativo" -> tip + nl + nl + "Música de fundo do app: um tema estelar de piano, calmo, em ciclo. Pausa sozinha quando um vídeo começa."
        "Meteoro ao apertar OK" -> tip + nl + nl + "Mostra um meteoro azul passando no alto da tela quando você aperta OK para entrar numa seção. A animação de abertura não muda."
        "Rede e internet" -> {
            val unused = netTick + 0
            networkInfo(context) + nl + nl + "Velocidade de download: $speed" + nl + "OK mede agora (baixa cerca de 10 MB)."
        }
        "Guia de imagem (TCL A400M)" ->
            "Ajustes recomendados no menu da própria TCL:" + nl +
                "• Modo de imagem: Cinema ou Filme (mais fiel)" + nl +
                "• Local dimming / Mini-LED: Alto" + nl +
                "• Nitidez: baixa (0 a 15)" + nl +
                "• Contraste dinâmico: Médio" + nl +
                "• Movimento (DNR/MEMC): Baixo para filmes, Médio para esporte" + nl + nl +
                hdrLine(context) + nl + "O teto é 4K. O HDR só aparece em conteúdo que já é HDR."
        "Teste de tela" -> "Cores puras em tela cheia para achar linhas, manchas e LEDs com defeito." + nl + nl + "OK para começar."
        "Abrir ajustes do Google TV" -> "Abre as configurações de tela do Google TV: resolução de saída, taxa de atualização, HDR e Dolby Vision." + nl + nl + "OK para abrir."
        "Conta IPTV" ->
            (if (iptvSaved) "Conta conectada: $iptvTotal itens carregados." else "Nenhuma conta conectada.") + nl + nl +
                "OK abre a tela para entrar, trocar ou remover a sua conta licenciada. Os dados ficam só neste aparelho."
        "Atualização do app" ->
            "Versão instalada: " + Updater.currentVersion(context) + nl + nl + updMsg + nl + nl +
                "O app confere a última versão publicada no GitHub, baixa e abre o instalador do Android."
        else ->
            "AuroraTV · versão " + Updater.currentVersion(context) + nl + "Criado em 2026" + nl +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" + nl + nl +
                "Feito por Sérgio Barboza Júnior."
    }
}

@Composable
private fun IptvBox(url: String, user: String, pass: String, field: Int, first: Boolean, saved: Boolean, busy: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(u(18))
        Column(Modifier.width(u(620)).background(CPanel, shape).border(BorderStroke(u(1), Color(0xFF262C3B)), shape).padding(u(34))) {
            Text("Entrar com IPTV", color = CInk, fontSize = ts(30), fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(u(6)))
            Text("Use a sua conta licenciada. Os canais da sua assinatura entram na TV ao vivo, e filmes e séries nas suas abas.", color = CDim, fontSize = ts(15))
            Spacer(Modifier.height(u(20)))
            val fields = listOf(
                Triple("Endereço (URL)", if (url.isBlank()) "http://seu-servidor:porta" else url, url.isBlank()),
                Triple("Usuário", if (user.isBlank()) "seu-usuario" else user, user.isBlank()),
                Triple("Senha", if (pass.isBlank()) "••••••••" else "•".repeat(min(pass.length, 14)), pass.isBlank())
            )
            fields.forEachIndexed { i, f ->
                Text(f.first, color = CDim, fontSize = ts(13))
                Spacer(Modifier.height(u(5)))
                val s = RoundedCornerShape(u(10))
                Box(
                    Modifier.fillMaxWidth().height(u(50)).background(Color(0xFF0E121B), s)
                        .border(BorderStroke(u(2), if (field == i) Color(0xFFCFE9FF) else Color(0xFF262C3B)), s)
                        .padding(horizontal = u(16)),
                    contentAlignment = Alignment.CenterStart
                ) { Text(f.second, color = if (f.third) CDim else CLight, fontSize = ts(18), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(u(12)))
            }
            val bs = RoundedCornerShape(u(10))
            Box(
                Modifier.fillMaxWidth().height(u(52))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFBFE4FF), CAccent)), bs)
                    .then(if (field == 3) Modifier.border(BorderStroke(u(2), Color.White), bs) else Modifier),
                contentAlignment = Alignment.Center
            ) { Text(if (busy) "Entrando…" else "Entrar", color = Color(0xFF06121F), fontSize = ts(19), fontWeight = FontWeight.ExtraBold) }
            if (saved) {
                Spacer(Modifier.height(u(10)))
                Box(
                    Modifier.fillMaxWidth().height(u(46)).background(CCard, bs)
                        .then(if (field == 4) Modifier.border(BorderStroke(u(2), CLive), bs) else Modifier),
                    contentAlignment = Alignment.Center
                ) { Text("Remover a conta deste aparelho", color = CLight, fontSize = ts(17), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(u(14)))
            Text(
                "Os dados ficam só no aparelho. " + if (first) "Sem conta agora? Aperte Voltar para entrar no app." else "Voltar sai deste quadro.",
                color = CDim, fontSize = ts(12.5f)
            )
        }
    }
}

@Composable
private fun ExitDialog(sel: Int) {
    Box(Modifier.fillMaxSize().background(Color(0xB802030A)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(u(18))
        Column(
            Modifier.width(u(520)).background(CPanel, shape).border(BorderStroke(u(1), Color(0xFF262C3B)), shape).padding(u(34)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Sair do AuroraTV?", color = CInk, fontSize = ts(30), fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(u(6)))
            Text("Você volta para a tela inicial do Google TV.", color = CDim, fontSize = ts(15))
            Spacer(Modifier.height(u(22)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u(14))) {
                val bs = RoundedCornerShape(u(10))
                Box(
                    Modifier.weight(1f).height(u(52))
                        .background(if (sel == 0) Brush.horizontalGradient(listOf(Color(0xFFBFE4FF), CAccent)) else Brush.horizontalGradient(listOf(CCardHi, CCardHi)), bs),
                    contentAlignment = Alignment.Center
                ) { Text("Cancelar", color = if (sel == 0) Color(0xFF06121F) else CLight, fontSize = ts(19), fontWeight = FontWeight.ExtraBold) }
                Box(
                    Modifier.weight(1f).height(u(52))
                        .background(if (sel == 1) Brush.horizontalGradient(listOf(Color(0xFFFFD0D6), CLive)) else Brush.horizontalGradient(listOf(CCardHi, CCardHi)), bs),
                    contentAlignment = Alignment.Center
                ) { Text("Sair", color = if (sel == 1) Color(0xFF2A050C) else CLight, fontSize = ts(19), fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

@Composable
private fun Hint(zone: Z, sec: Section, editing: Boolean, kbd: Boolean, modifier: Modifier) {
    val t = when {
        zone == Z.IPTV -> "▲▼ campos · OK edita · Voltar sai"
        zone == Z.EXIT -> "◀ ▶ escolhe · OK confirma"
        zone == Z.CONTENT && sec.kind == "settings" -> if (editing) "◀ ▶ mudam o valor · Voltar salva e sai" else "▲▼ escolhe · OK entra no item · Voltar ao menu"
        zone == Z.CONTENT && kbd -> "setas no teclado · OK digita · Voltar fecha"
        zone == Z.CONTENT -> "setas movem · OK abre · OK 2x favorita · Voltar sai"
        else -> "▲▼ menu · ▶ ou OK entra"
    }
    Text(t, color = Color(0x99C8D4E8), fontSize = ts(13), maxLines = 1, modifier = modifier.padding(start = u(316), bottom = u(14), end = u(380)))
}

@Composable
private fun Pill(clock: String, weather: String, modifier: Modifier) {
    val shape = RoundedCornerShape(u(30))
    Box(
        modifier.padding(end = u(30), bottom = u(10))
            .background(Color(0x14FFFFFF), shape).border(BorderStroke(u(1), Color(0x1AFFFFFF)), shape)
            .padding(horizontal = u(14), vertical = u(5))
    ) {
        Text(clock + if (weather.isNotEmpty()) "  ·  $weather" else "", color = Color(0xC7E6EEFA), fontSize = ts(14), maxLines = 1)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerScreen(player: ExoPlayer, card: Card?, noSignal: Boolean) {
    var dyn by remember { mutableStateOf("") }
    LaunchedEffect(card?.url) {
        dyn = ""
        while (true) {
            dyn = when (player.videoFormat?.colorInfo?.colorTransfer) {
                androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
                androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
                null -> ""
                else -> "SDR"
            }
            delay(1500)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                // SurfaceView (padrão) preserva o HDR nativo.
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (noSignal) {
            Box(Modifier.fillMaxSize().background(Color(0xFF05070C)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SEM SINAL", color = CLight, fontSize = ts(56), fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(u(10)))
                    Text("Tentando reconectar…  ·  Voltar sai", color = CDim, fontSize = ts(18))
                }
            }
        } else if (card != null) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = u(40), vertical = u(36))
                    .background(Color(0xBB000000), RoundedCornerShape(u(14))).padding(horizontal = u(22), vertical = u(16))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (card.kind == "stream") "● AO VIVO · " else "") + card.label.uppercase(), color = CLive, fontSize = ts(15), fontWeight = FontWeight.Bold)
                    if (dyn.isNotEmpty()) {
                        Spacer(Modifier.width(u(12)))
                        Box(
                            Modifier.clip(RoundedCornerShape(u(6))).background(if (dyn == "SDR") Color(0xFF333333) else CAccent)
                                .padding(horizontal = u(9), vertical = u(1))
                        ) { Text(dyn, color = if (dyn == "SDR") CLight else Color(0xFF06121F), fontSize = ts(13), fontWeight = FontWeight.Bold) }
                    }
                }
                Text(card.name, color = CInk, fontSize = ts(30), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (card.kind == "stream") "▲▼ trocam de canal · Voltar sai" else "◀ ▶ pulam 10 s · OK pausa · Voltar sai",
                    color = CLight, fontSize = ts(16)
                )
            }
        }
    }
}
