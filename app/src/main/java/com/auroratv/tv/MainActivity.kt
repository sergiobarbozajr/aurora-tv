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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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

private enum class Z { INTRO, NAV, CONTENT, PLAYER, TEST, IPTV, EXIT, KEYMAP, READER, EPISODES }

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
        customKeys = parseKeys(Store.keymap)
        val json = assets.open("catalog.json").bufferedReader().use { it.readText() }
        val catalog = parseCatalog(json)
        try {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
        } catch (e: Throwable) {
        }
        try {
            @Suppress("DEPRECATION")
            val modes = windowManager.defaultDisplay.supportedModes
            var best: android.view.Display.Mode? = null
            for (m in modes) {
                val b = best
                if (b == null ||
                    m.physicalWidth * m.physicalHeight > b.physicalWidth * b.physicalHeight ||
                    (m.physicalWidth * m.physicalHeight == b.physicalWidth * b.physicalHeight && m.refreshRate > b.refreshRate)
                ) best = m
            }
            if (best != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (e: Throwable) {
        }
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
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J"),
    listOf("K", "L", "M", "N", "O", "P", "Q", "R", "S", "T"),
    listOf("U", "V", "W", "X", "Y", "Z", "ESPAÇO", "APAGAR", "MIC"),
    listOf("CANCELAR", "PRONTO")
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

private var customKeys: Map<Int, String> = emptyMap()

private fun parseKeys(raw: String): Map<Int, String> {
    val m = HashMap<Int, String>()
    for (part in raw.split(",")) {
        val kv = part.split(":")
        if (kv.size == 2) {
            val c = kv[0].toIntOrNull()
            if (c != null) m[c] = kv[1]
        }
    }
    return m
}

private fun keyToAct(e: androidx.compose.ui.input.key.KeyEvent): String? {
    val code = e.nativeKeyEvent.keyCode
    val custom = customKeys[code]
    if (custom != null) return custom
    return when (e.key) {
        Key.DirectionUp -> "up"
        Key.DirectionDown -> "down"
        Key.DirectionLeft -> "left"
        Key.DirectionRight -> "right"
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> "ok"
        Key.Back, Key.Escape, Key.ButtonB, Key.Backspace -> "back"
        Key.F -> "fav"
        else -> null
    }
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
    var keymapStep by remember { mutableStateOf(0) }
    var newsRows by remember { mutableStateOf<List<RowData>>(emptyList()) }
    var reader by remember { mutableStateOf<Card?>(null) }
    var keymapLast by remember { mutableStateOf("") }

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
    var epgText by remember { mutableStateOf("") }

    val pb = remember { Playback(context) }
    val player = pb.player
    var lastRelogin by remember { mutableStateOf(0L) }

    // menu do player, ajuste de tela, episódios
    var pmenu by remember { mutableStateOf<String?>(null) }
    var pmSel by remember { mutableStateOf(0) }
    var menuTick by remember { mutableStateOf(0) }
    var osdTick by remember { mutableStateOf(0) }
    var screenMode by remember { mutableStateOf(Store.screenMode.coerceIn(0, SCREEN_NAMES.size - 1)) }
    var adj by remember { mutableStateOf(intArrayOf(100, 100, 0, 0)) }
    var adjusting by remember { mutableStateOf(false) }
    var adjPart by remember { mutableStateOf(0) }
    var seasons by remember { mutableStateOf<List<Iptv.Season>>(emptyList()) }
    var seasonIdx by remember { mutableStateOf(0) }
    var epIdx by remember { mutableStateOf(0) }
    var seriesName by remember { mutableStateOf("") }
    var seriesActive by remember { mutableStateOf(false) }
    var epFromPlayer by remember { mutableStateOf(false) }
    var epTick by remember { mutableStateOf(0) }
    var hideOff by remember { mutableStateOf(Store.hideOffline) }
    var searchRows by remember { mutableStateOf<List<RowData>>(emptyList()) }
    var searchIndex by remember { mutableStateOf<List<Pair<String, Card>>>(emptyList()) }
    var nextEpisode by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(pb) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                applyEnhance(player, enhance, isHdrFormat(player))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) nextEpisode?.invoke()
            }
        }
        player.addListener(l)
        pb.onAuthExpired = {
            val now = System.currentTimeMillis()
            if (Store.iptvUrl.isNotBlank() && now - lastRelogin > 60000) {
                lastRelogin = now
                // login silencioso: refaz a sessão e atualiza a lista sem pedir nada
                Iptv.load(Store.iptvUrl, Store.iptvUser, Store.iptvPass) { r ->
                    if (r.total > 0) {
                        iptvRows = r.rows
                        iptvTotal = r.total
                        iptvVersion++
                    }
                }
            }
        }
        onDispose {
            player.removeListener(l)
            pb.release()
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(zone) {
        try { focus.requestFocus() } catch (e: Exception) {}
    }
    LaunchedEffect(zone, epFromPlayer) {
        val v = zone == Z.PLAYER || (zone == Z.EPISODES && epFromPlayer)
        videoPlayingFlag = v
        Sound.setPaused(v)
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
        while (true) {
            News.load { r -> if (r.isNotEmpty()) newsRows = r }
            delay(15 * 60 * 1000L)
        }
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

    LaunchedEffect(allCards) {
        searchIndex = withContext(Dispatchers.Default) { allCards.map { Iptv.norm(it.name) to it } }
    }
    LaunchedEffect(query, searchIndex) {
        val q = Iptv.norm(query.trim())
        if (q.isEmpty()) {
            searchRows = listOf(RowData("Sugestões", (free.intl + free.internet + free.anime).take(10)))
            return@LaunchedEffect
        }
        delay(120) // espera a próxima letra; a busca roda fora da tela
        val idx = searchIndex
        val res = withContext(Dispatchers.Default) {
            val starts = ArrayList<Card>()
            val other = ArrayList<Card>()
            for ((n, c) in idx) {
                if (n.startsWith(q)) starts.add(c) else if (n.contains(q)) other.add(c)
                if (starts.size + other.size >= 150) break
            }
            starts + other
        }
        searchRows = if (res.isEmpty()) listOf(RowData("Nada encontrado para “$query”", listOf(mkCard("Tente outra palavra", "", "empty", 0))))
        else listOf(RowData("Resultados para “$query” (${res.size})", res))
    }
    LaunchedEffect(Unit) {
        delay(30000)
        while (true) {
            val mine = (favs.mapNotNull { byName[it] } + Store.history().mapNotNull { byName[it] }).distinctBy { it.name }
            pb.checkChannels(mine)
            delay(30 * 60 * 1000L)
        }
    }

    fun loginCard(): Card = mkCard(
        if (iptvSaved) "Conta IPTV conectada" else "Entrar com IPTV",
        if (iptvSaved) "$iptvTotal itens · OK para trocar ou remover" else "conta licenciada",
        "login", 0
    )

    fun histCards(): List<Card> = Store.history().mapNotNull { byName[it] }

    fun rowsFor(i: Int): List<RowData> {
        val name = SECTIONS[i].name
        fun iptv(sec: String): List<RowData> {
            val r = iptvRows[sec] ?: emptyList()
            if (hideOff == 0) return r
            return r.map { RowData(it.title, it.cards.filter { c -> pb.health[c.name] != 3 }) }
        }
        fun withLogin(r: List<RowData>): List<RowData> =
            if (!iptvSaved) listOf(RowData("Sua conta IPTV", listOf(loginCard()))) + r else r
        val out: List<RowData> = when (name) {
            "Início" -> {
                val r = ArrayList<RowData>()
                r.addAll(newsRows)
                val h = histCards()
                if (h.isNotEmpty()) r.add(RowData("Continuar assistindo", h))
                r.add(RowData("Abertos pela internet", free.internet))
                r.add(RowData("Internacionais", free.intl))
                r.add(RowData("Seus streams", streamRows().flatMap { it.cards }.take(8)))
                r
            }
            "Pesquisar" -> searchRows
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

    val rows = remember(nav, iptvVersion, favs.size, histVersion, m3uExtra, iptvSaved, iptvTotal, allCards, newsRows, searchRows, hideOff) { rowsFor(nav) }
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
        if (c.kind == "login" || c.kind == "empty" || c.kind == "news" || c.kind == "quote") return
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
        if (c.url == null) return
        applyQuality(player, quality)
        applyEnhance(player, enhance, true)
        pb.start(c)
        playCard = c
        playList = list.filter { it.kind == "stream" && it.url != null }
        playIdx = playList.indexOfFirst { it.name == c.name }.coerceAtLeast(0)
        epgText = ""
        pmenu = null
        adjusting = false
        adj = Store.screenAdj(c.name) ?: Store.screenAdj("global") ?: intArrayOf(100, 100, 0, 0)
        osdTick++
        if (c.kind == "stream") seriesActive = false
        if (c.kind == "stream" && c.extra.count { it == '|' } >= 3) Iptv.shortEpg(c.extra) { t -> if (playCard?.name == c.name) epgText = t }
        Store.pushHistory(c.name)
        histVersion++
        zone = Z.PLAYER
    }

    fun playEpisode(si: Int, ei: Int) {
        val s = seasons.getOrNull(si) ?: return
        val ep = s.eps.getOrNull(ei) ?: return
        seasonIdx = si
        epIdx = ei
        seriesActive = true
        play(ep, emptyList())
        seriesActive = true
        nextEpisode = {
            val ns = if (ei + 1 < s.eps.size) si else si + 1
            val ne = if (ei + 1 < s.eps.size) ei + 1 else 0
            if (seasons.getOrNull(ns)?.eps?.getOrNull(ne) != null && zone == Z.PLAYER) {
                showToast("Próximo episódio")
                playEpisode(ns, ne)
            }
        }
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
        var tuned = false
        try {
            val nn = Iptv.norm(c.name)
            val cur = context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME), null, null, null
            )
            if (cur != null) {
                cur.use {
                    while (it.moveToNext()) {
                        val dn = Iptv.norm(it.getString(1) ?: "")
                        if (dn == nn || dn.startsWith(nn + " ") || dn.startsWith(nn + "-")) {
                            val i = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(it.getLong(0)))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                            tuned = true
                            break
                        }
                    }
                }
            }
        } catch (e: Throwable) {
        }
        if (tuned) return
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
        if (ok) showToast("Abri a antena. O Android não deixa o app trocar de canal sozinho: escolha ${c.name} no controle.")
        else showToast("Para ver ${c.name}, aperte ENTRADAS no controle e escolha Antena.")
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
        try {
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val token = Iptv.norm(c.name.substringBefore(" "))
            val hit = pm.queryIntentActivities(q, 0).firstOrNull { Iptv.norm(it.loadLabel(pm).toString()).contains(token) }
            if (hit != null) {
                val i = pm.getLeanbackLaunchIntentForPackage(hit.activityInfo.packageName)
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    return
                }
            }
        } catch (e: Exception) {
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
            "news" -> {
                reader = c
                zone = Z.READER
            }
            "quote" -> showToast(c.name + "  " + c.label)
            "app" -> launchApp(c)
            "antenna" -> openAntenna(c)
            "stream", "vod" -> play(c, list)
            "series" -> {
                showToast("Carregando temporadas e episódios…")
                Iptv.seriesInfo(c.extra) { list ->
                    if (list.isEmpty()) showToast("Sem sinal dessa série agora.")
                    else {
                        seasons = list
                        seriesName = c.name
                        seasonIdx = 0
                        epIdx = 0
                        epFromPlayer = false
                        epTick++
                        zone = Z.EPISODES
                    }
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
                        else -> "Não consegui falar com o servidor: " + Iptv.lastError.ifBlank { "código ${Iptv.lastCode}" } + ". Endereço tentado: " + Iptv.cleanBase(fUrl)
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
        "hideoff" -> HIDEOFF_NAMES[hideOff]
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
                applyEnhance(player, enhance, isHdrFormat(player))
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
            "hideoff" -> {
                hideOff = (hideOff + d + HIDEOFF_NAMES.size) % HIDEOFF_NAMES.size
                Store.hideOffline = hideOff
            }
            else -> {
                meteorOpt = (meteorOpt + d + METEOR_NAMES.size) % METEOR_NAMES.size
                Store.meteor = meteorOpt
            }
        }
    }

    fun keymapPress(code: Int) {
        keymapLast = android.view.KeyEvent.keyCodeToString(code)
        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER) {
            customKeys = emptyMap()
            Store.keymap = ""
            zone = Z.CONTENT
            showToast("Mapeamento padrão restaurado.")
            return
        }
        val action = if (keymapStep == 0) "back" else "home"
        customKeys = customKeys + (code to action)
        Store.keymap = customKeys.entries.joinToString(",") { it.key.toString() + ":" + it.value }
        keymapStep++
        if (keymapStep > 1) {
            zone = Z.CONTENT
            showToast("Controle mapeado e salvo.")
        }
    }

    fun finishIntro() {
        zone = Z.NAV
    }

    fun clampCol() {
        val r = rows.getOrNull(row - base)
        if (r != null) col = min(col, max(0, r.cards.size - 1)) else col = 0
    }

    fun typeKey(k: String) {
        when (k) {
            "ESPAÇO" -> query += " "
            "APAGAR" -> query = query.dropLast(1)
            "CANCELAR" -> {
                kbd = false
                kr = 0
                kc = 0
            }
            "PRONTO" -> {
                kbd = false
                kr = 0
                kc = 0
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
        if (c.kind == "login" || c.kind == "empty" || c.kind == "news" || c.kind == "quote") {
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
                    "Mapear controle remoto" -> {
                        customKeys = emptyMap()
                        keymapStep = 0
                        keymapLast = ""
                        zone = Z.KEYMAP
                    }
                    "Bloquear anúncios (DNS)" -> openNetworkSettings(context)
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
                "up" -> {
                    kr = max(0, kr - 1)
                    kc = min(kc, KEYS[kr].size - 1)
                }
                "down" -> {
                    if (kr == KEYS.size - 1) {
                        kbd = false
                        kr = 0
                        kc = 0
                        if (rows.isNotEmpty()) {
                            row = 1
                            col = 0
                        }
                    } else {
                        kr = min(KEYS.size - 1, kr + 1)
                        kc = min(kc, KEYS[kr].size - 1)
                    }
                }
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

    fun menuItems(): List<String> = when (pmenu) {
        "main" -> listOfNotNull("Áudio", "Legendas", "Ajuste de tela", if (seriesActive) "Temporadas e episódios" else null, "Informações")
        "audio" -> {
            val l = ArrayList<String>()
            var n = 1
            for (g in player.currentTracks.groups) if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) for (i in 0 until g.length) {
                l.add((if (g.isTrackSelected(i)) "● " else "") + Playback.trackLabel(g.getTrackFormat(i), n++))
            }
            if (l.isEmpty()) listOf("Só existe uma faixa de áudio") else l
        }
        "subs" -> {
            val l = ArrayList<String>()
            val off = player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)
            l.add((if (off) "● " else "") + "Desligado")
            var n = 1
            for (g in player.currentTracks.groups) if (g.type == androidx.media3.common.C.TRACK_TYPE_TEXT) for (i in 0 until g.length) {
                l.add((if (!off && g.isTrackSelected(i)) "● " else "") + Playback.trackLabel(g.getTrackFormat(i), n++))
            }
            l
        }
        "screen" -> SCREEN_NAMES.mapIndexed { i, n -> (if (i == screenMode) "● " else "") + n } + listOf("Usar este ajuste em tudo", "Restaurar padrão")
        "info" -> {
            val f = player.videoFormat
            listOf(
                "Resolução: " + (if (f != null && f.width > 0) "${f.width} x ${f.height}" else "—"),
                "Faixa dinâmica: " + if (isHdrFormat(player)) "HDR" else "SDR",
                "Vídeo: " + (f?.sampleMimeType?.substringAfter("/") ?: "—"),
                "Taxa: " + (if (f != null && f.bitrate > 0) "${f.bitrate / 1000} kbps" else "—")
            )
        }
        else -> emptyList()
    }

    fun selectTrack(type: Int, index: Int) {
        var n = 0
        for (g in player.currentTracks.groups) if (g.type == type) for (i in 0 until g.length) {
            if (n == index) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
                return
            }
            n++
        }
    }

    fun menuOk() {
        val items = menuItems()
        val sel = items.getOrNull(pmSel) ?: return
        when (pmenu) {
            "main" -> {
                pmSel = 0
                pmenu = when {
                    sel.startsWith("Áudio") -> "audio"
                    sel.startsWith("Legendas") -> "subs"
                    sel.startsWith("Ajuste") -> "screen"
                    sel.startsWith("Temporadas") -> {
                        epFromPlayer = true
                        epTick++
                        zone = Z.EPISODES
                        null
                    }
                    else -> "info"
                }
            }
            "audio" -> {
                if (!sel.startsWith("Só")) selectTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, pmSel)
                pmenu = null
                showToast("Áudio: " + sel.removePrefix("● "))
            }
            "subs" -> {
                if (pmSel == 0) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
                } else selectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, pmSel - 1)
                pmenu = null
                showToast("Legendas: " + sel.removePrefix("● "))
            }
            "screen" -> {
                if (pmSel < SCREEN_NAMES.size) {
                    screenMode = pmSel
                    Store.screenMode = pmSel
                    pmenu = null
                    if (pmSel == SCREEN_NAMES.size - 1) {
                        adjusting = true
                        adjPart = 0
                    } else showToast("Tela: " + SCREEN_NAMES[pmSel])
                } else if (sel.startsWith("Usar")) {
                    Store.saveScreenAdj("global", adj)
                    pmenu = null
                    showToast("Ajuste salvo para todos os vídeos")
                } else {
                    adj = intArrayOf(100, 100, 0, 0)
                    playCard?.let { Store.saveScreenAdj(it.name, null) }
                    Store.saveScreenAdj("global", null)
                    screenMode = 0
                    Store.screenMode = 0
                    pmenu = null
                    showToast("Tela restaurada ao padrão")
                }
            }
            else -> pmenu = null
        }
    }

    fun playerAct(a: String) {
        osdTick++
        if (adjusting) {
            val v = adj.copyOf()
            when (a) {
                "ok" -> adjPart = 1 - adjPart
                "back" -> {
                    adjusting = false
                    playCard?.let { Store.saveScreenAdj(it.name, adj) }
                    showToast("Ajuste salvo para este conteúdo")
                }
                "left" -> if (adjPart == 0) v[0] = max(50, v[0] - 2) else v[2] = max(-100, v[2] - 2)
                "right" -> if (adjPart == 0) v[0] = min(200, v[0] + 2) else v[2] = min(100, v[2] + 2)
                "up" -> if (adjPart == 0) v[1] = min(200, v[1] + 2) else v[3] = max(-100, v[3] - 2)
                "down" -> if (adjPart == 0) v[1] = max(50, v[1] - 2) else v[3] = min(100, v[3] + 2)
            }
            adj = v
            return
        }
        if (pmenu != null) {
            menuTick++
            val n = menuItems().size
            when (a) {
                "up" -> pmSel = max(0, pmSel - 1)
                "down" -> pmSel = min(n - 1, pmSel + 1)
                "ok", "right" -> menuOk()
                "back", "left" -> {
                    if (pmenu == "main") pmenu = null else {
                        pmenu = "main"
                        pmSel = 0
                    }
                }
            }
            return
        }
        if (pb.diag != null && a == "ok") {
            pb.retryNow()
            return
        }
        val live = playCard?.kind == "stream"
        when (a) {
            "back" -> {
                pb.stop()
                nextEpisode = null
                zone = Z.CONTENT
            }
            "up" -> if (live) zap(-1) else {
                pmenu = "main"
                pmSel = 0
                menuTick++
            }
            "down" -> if (live) zap(1)
            "left" -> if (!live) player.seekTo(max(0L, player.currentPosition - 10000L))
            "right" -> if (!live) player.seekTo(player.currentPosition + 10000L)
            "ok" -> if (!live) {
                if (player.isPlaying) player.pause() else player.play()
            }
        }
    }

    fun act(a: String) {
        when (zone) {
            Z.INTRO -> finishIntro()
            Z.PLAYER -> playerAct(a)
            Z.EPISODES -> {
                epTick++
                val s = seasons.getOrNull(seasonIdx)
                when (a) {
                    "up" -> epIdx = max(0, epIdx - 1)
                    "down" -> if (s != null) epIdx = min(s.eps.size - 1, epIdx + 1)
                    "left" -> if (seasonIdx > 0) {
                        seasonIdx--
                        epIdx = 0
                    }
                    "right" -> if (seasonIdx < seasons.size - 1) {
                        seasonIdx++
                        epIdx = 0
                    }
                    "ok" -> playEpisode(seasonIdx, epIdx)
                    "back", "home" -> zone = if (epFromPlayer) Z.PLAYER else Z.CONTENT
                }
            }
            Z.KEYMAP -> {}
            Z.READER -> if (a == "back" || a == "ok" || a == "left") zone = Z.CONTENT
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

    val inVideo = zone == Z.PLAYER || (zone == Z.EPISODES && epFromPlayer)
    val tick = frameClock(!inVideo && zone != Z.TEST)
    val showMain = !inVideo && zone != Z.TEST

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (zone == Z.KEYMAP) {
                    if (e.type == KeyEventType.KeyDown) keymapPress(e.nativeKeyEvent.keyCode)
                    true
                } else {
                    val a = keyToAct(e)
                    if (a == null) false
                    else {
                        if (e.type == KeyEventType.KeyDown) act(a)
                        true
                    }
                }
            }
            .focusable()
    ) {
        when {
            inVideo -> {
                PlayerScreen(pb, playCard, epgText, osdTick, screenMode, adj, adjusting, adjPart, pmenu != null || adjusting)
                if (pmenu != null && zone == Z.PLAYER) PlayerMenu(pmenu ?: "", menuItems(), pmSel, menuTick) { pmenu = null }
            }
            zone == Z.TEST -> {
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
                            sec, rows, base, zone == Z.CONTENT, row, col, favs, query, kbd, kr, kc, listening, curCard(), pb.health
                        )
                    }
                    Sidebar(nav, zone == Z.NAV)
                    if (zone == Z.IPTV) {
                        IptvBox(fUrl, fUser, fPass, loginField, iptvFirst, iptvSaved, iptvBusy)
                    }
                    if (zone == Z.EXIT) ExitDialog(exitSel)
                    if (zone == Z.KEYMAP) KeymapBox(keymapStep, keymapLast)
                    if (zone == Z.READER) ReaderBox(reader)
                    Hint(zone, sec, editing, kbd, Modifier.align(Alignment.BottomStart))
                    Pill(clock, weatherText, Modifier.align(Alignment.BottomEnd))
                }
            }
        }
        if (zone == Z.EPISODES) {
            EpisodesPanel(seriesName, seasons, seasonIdx, epIdx, playCard?.name, epTick, epFromPlayer) { zone = Z.PLAYER }
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
    val fontPx = with(LocalDensity.current) { (20f * k).dp.toPx() }
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
private fun CardView(c: Card, focused: Boolean, fav: Boolean, hl: Int) {
    val shape = RoundedCornerShape(u(12))
    val gk = LocalK.current * LocalDensity.current.density
    val w = if (c.kind == "login") u(430) else u(206)
    val art = if (c.kind == "app" || c.img.isNotEmpty()) rememberCardImage(c) else null
    val glossy = c.kind == "app"
    // cartões de stream: efeito 3D (sombra, relevo e brilho); com foco crescem um pouco
    var m = Modifier.width(w).height(u(122))
    if (glossy) m = m.graphicsLayer {
        val sc = if (focused) 1.06f else 1f
        scaleX = sc
        scaleY = sc
        shadowElevation = (if (focused) 22f else 10f) * gk
        this.shape = shape
        clip = false
    }
    if (focused) {
        m = m.drawBehind {
            drawRoundRect(Color(0x33CFE9FF), Offset(-8f * gk, -6f * gk), Size(size.width + 16f * gk, size.height + 14f * gk), CornerRadius(20f * gk))
        }
    }
    m = if (c.kind == "empty") m.background(CCard, shape) else m.background(Brush.linearGradient(listOf(c.hueA, c.hueB)), shape)
    if (focused) m = m.border(BorderStroke(u(2), Color(0xFFCFE9FF)), shape)
    else if (c.kind == "empty") m = m.border(BorderStroke(u(1), Color(0xFF262C3B)), shape)
    else if (glossy) m = m.border(BorderStroke(u(1), Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x14FFFFFF)))), shape)
    val cover = art != null && (c.kind == "vod" || c.kind == "series")
    val banner = art != null && c.kind == "app" && !Img.isIcon(c)
    Box(m.clip(shape)) {
        if (c.kind == "login") {
            Column(Modifier.align(Alignment.CenterStart).padding(horizontal = u(18))) {
                Text("＋ " + c.name, color = CInk, fontSize = ts(20), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.height(u(6)))
                Text(c.label, color = CLight, fontSize = ts(13), maxLines = 2)
            }
        } else if (banner && art != null) {
            // banner oficial do app instalado, preenchendo o cartão
            androidx.compose.foundation.Image(art, c.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else if (cover && art != null) {
            // capa do filme/série enquadrada no cartão inteiro, nome sobre um degradê
            androidx.compose.foundation.Image(art, c.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
            Text(
                c.name, color = CInk, fontSize = ts(15), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(u(12))
            )
        } else {
            if (art != null) {
                // logo do canal (ou ícone do app) ocupando o espaço do cartão, sem cortar
                androidx.compose.foundation.Image(
                    art, c.name,
                    Modifier.align(Alignment.Center).padding(start = u(16), end = u(16), top = u(26), bottom = u(30)).fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
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
                    fontSize = ts(if (c.kind == "empty") 15 else if (art != null) 14 else if (glossy) 20 else 17),
                    fontWeight = if (glossy) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = if (art != null) 1 else 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (glossy) {
            // brilho de vidro: reflexo na metade de cima e um fio de luz na borda
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color(0x59FFFFFF), 0.42f to Color(0x14FFFFFF), 0.43f to Color.Transparent, 1f to Color(0x33000000))
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color(0x33FFFFFF), Color.Transparent), center = Offset(60f * gk, 10f * gk), radius = 140f * gk)
                )
            )
        }
        if (fav) Text("★", color = CWarm, fontSize = ts(20), modifier = Modifier.align(Alignment.TopEnd).padding(top = u(8), end = u(10)))
        if (hl > 0 && c.kind == "stream") {
            // saúde do canal: verde online, amarelo instável, vermelho fora do ar
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = u(10), bottom = u(10)).size(u(10))
                    .clip(RoundedCornerShape(u(5)))
                    .background(if (hl == 1) Color(0xFF5BE38A) else if (hl == 2) Color(0xFFFFD25A) else Color(0xFFFF5A6E))
            )
        }
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
                    c.kind == "news" || c.kind == "quote" -> c.desc.take(150)
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
private fun RowsView(rows: List<RowData>, base: Int, active: Boolean, row: Int, col: Int, favs: List<String>, health: Map<String, Int>, modifier: Modifier) {
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
                    itemsIndexed(r.cards) { ci, c -> CardView(c, isActive && col == ci, favs.contains(c.name), health[c.name] ?: 0) }
                }
            }
        }
    }
}

@Composable
private fun ContentScreen(
    sec: Section, rows: List<RowData>, base: Int, active: Boolean, row: Int, col: Int, favs: List<String>,
    query: String, kbd: Boolean, kr: Int, kc: Int, listening: Boolean, cur: Card?, health: Map<String, Int>
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
                                Modifier.weight(1f).height(u(40))
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
        RowsView(rows, base, active && !kbd, row, col, favs, health, Modifier.weight(1f))
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
            tip + nl + nl + "Máxima (recomendado) usa sempre a maior resolução que o canal oferece e pede à TV o maior modo de tela. As outras opções limitam o teto." + nl + nl +
                "Saída atual da tela: ${dm.widthPixels} x ${dm.heightPixels}" + nl + nl +
                "Fora do AuroraTV (Netflix, YouTube etc.) o Android é quem decide. Para travar no máximo: Ajustes do Google TV > Tela e som > Resolução 4K 60 Hz, HDR ligado e “Ajustar à taxa do conteúdo” desligado. O item “Abrir ajustes do Google TV” leva até lá."
        }
        "Realce de imagem" -> tip + nl + nl + "Médio, Alto e Ultra aumentam cor, contraste e brilho em vídeo comum (SDR). Em vídeo HDR10 o realce fica desligado sozinho, assim o HDR10 passa intacto para a TV." + nl + nl + "Nitidez: o app não consegue mexer na nitidez da TV. Ajuste “Nitidez” no menu de imagem da TCL (veja o Guia de imagem)."
        "Clima e cidade" -> tip + nl + nl + "Agora em $city: " + weather
        "Som do aplicativo" -> tip + nl + nl + "Música de fundo do app: tema espacial de piano e violino, suave e minimalista, em ciclo. Volume próprio, independente da TV. Pausa sozinha quando um vídeo começa e volta quando você sai do player."
        "Canais fora do ar" -> tip + nl + nl + "O app marca cada canal: verde = online, amarelo = instável, vermelho = fora do ar. A marcação vem de quando você assiste e de um teste leve, a cada 30 minutos, só dos seus favoritos e recentes (para não sobrecarregar o servidor)." + nl + nl + "Ocultar esconde das abas os canais marcados como fora do ar."
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
                    "Mapear controle remoto" ->
            "Ensina ao app qual botão do seu controle é o VOLTAR e qual é o INÍCIO." + nl + nl +
                (if (customKeys.isEmpty()) "Agora: padrão do Android." else "Agora: " + customKeys.entries.joinToString(" · ") { android.view.KeyEvent.keyCodeToString(it.key) + " = " + it.value }) +
                nl + nl + "OK para começar."
        "Bloquear anúncios (DNS)" ->
            "O AuroraTV não tem anúncios. Para reduzir anúncios nos outros apps e sites, use um DNS que bloqueia anúncios:" + nl + nl +
                "1. OK abre as configurações de rede." + nl +
                "2. Procure “DNS privado” (Rede > Avançado) e escolha nome do provedor." + nl +
                "3. Digite: dns.adguard-dns.com" + nl + nl +
                "Se o seu Google TV não mostrar DNS privado, troque o DNS no roteador. Anúncios dentro de Netflix, Prime e similares não são bloqueados por isso."
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
        zone == Z.KEYMAP -> "aperte o botão pedido · OK cancela"
        zone == Z.READER -> "Voltar ou OK fecha"
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
private fun PlayerScreen(
    pb: Playback, card: Card?, epg: String, osdTick: Int, mode: Int, adj: IntArray, adjusting: Boolean, adjPart: Int, hideOsd: Boolean
) {
    val player = pb.player
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
    // painel de informações: aparece por 12 s e some; volta a cada tecla
    var osd by remember { mutableStateOf(true) }
    LaunchedEffect(osdTick, card?.name) {
        osd = true
        delay(12000)
        osd = false
    }
    val k = LocalK.current
    val rm = when (mode) {
        0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val bw = maxWidth
        val bh = maxHeight
        // o tamanho da superfície de vídeo muda o enquadramento na hora, sem recarregar o stream
        val vm: Modifier = when (mode) {
            3 -> Modifier.align(Alignment.Center).width(minOf(bw, bh * 16f / 9f)).height(minOf(bh, bw * 9f / 16f))
            4 -> Modifier.align(Alignment.Center).width(bh * 4f / 3f).height(bh)
            5 -> Modifier.align(Alignment.Center)
                .offset(x = (adj[2] * 2f * k).dp, y = (adj[3] * 2f * k).dp)
                .requiredSize(bw * (adj[0] / 100f), bh * (adj[1] / 100f))
            else -> Modifier.fillMaxSize()
        }
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
            update = { it.resizeMode = rm },
            modifier = vm
        )
        if (adjusting) {
            Box(vm) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val l = 46f * k * density
                    val sw = 4f * k * density
                    val c = Color(0xB3FFFFFF)
                    val w = size.width
                    val h = size.height
                    drawLine(c, Offset(0f, 0f), Offset(l, 0f), sw); drawLine(c, Offset(0f, 0f), Offset(0f, l), sw)
                    drawLine(c, Offset(w, 0f), Offset(w - l, 0f), sw); drawLine(c, Offset(w, 0f), Offset(w, l), sw)
                    drawLine(c, Offset(0f, h), Offset(l, h), sw); drawLine(c, Offset(0f, h), Offset(0f, h - l), sw)
                    drawLine(c, Offset(w, h), Offset(w - l, h), sw); drawLine(c, Offset(w, h), Offset(w, h - l), sw)
                }
            }
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = u(30))
                    .background(Color(0xCC05070C), RoundedCornerShape(u(14))).padding(horizontal = u(24), vertical = u(14)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                fun sg(v: Int) = if (v > 0) "+$v" else "$v"
                Text(
                    "Largura: ${adj[0]}%     Altura: ${adj[1]}%     Posição X: ${sg(adj[2])}     Posição Y: ${sg(-adj[3])}",
                    color = CInk, fontSize = ts(19), fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(u(6)))
                Text(
                    if (adjPart == 0) "TAMANHO · ◀ ▶ largura · ▲ ▼ altura · OK muda para posição · Voltar salva"
                    else "POSIÇÃO · setas movem a imagem · OK muda para tamanho · Voltar salva",
                    color = CWarm, fontSize = ts(15)
                )
            }
        }
        val d = pb.diag
        if (d != null) {
            Box(Modifier.fillMaxSize().background(Color(0xFF05070C)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(u(820))) {
                    Text("SEM SINAL", color = CLight, fontSize = ts(56), fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(u(8)))
                    Text(d.title, color = CInk, fontSize = ts(22), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(u(14)))
                    Text("Camada: " + d.layer, color = CDim, fontSize = ts(16))
                    Text("O que houve: " + d.detail, color = CDim, fontSize = ts(16), textAlign = TextAlign.Center)
                    Text("Correção: " + d.fix, color = CAccent, fontSize = ts(16), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(u(14)))
                    Text("OK tenta agora  ·  Voltar sai", color = CDim, fontSize = ts(15))
                }
            }
        } else if (pb.connecting) {
            Text(
                "Conectando" + (card?.let { " a " + it.name } ?: "") + "…",
                color = CLight, fontSize = ts(20),
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(u(12)))
                    .padding(horizontal = u(20), vertical = u(10))
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = osd && !hideOsd && d == null && card != null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 3 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 3 }
        ) {
            val cc = card
            if (cc != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = u(40), vertical = u(36))
                        .background(Color(0xBB000000), RoundedCornerShape(u(14))).padding(horizontal = u(22), vertical = u(16))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (cc.kind == "stream") "● AO VIVO · " else "") + cc.label.uppercase(), color = CLive, fontSize = ts(15), fontWeight = FontWeight.Bold)
                        if (dyn.isNotEmpty()) {
                            Spacer(Modifier.width(u(12)))
                            Box(
                                Modifier.clip(RoundedCornerShape(u(6))).background(if (dyn == "SDR") Color(0xFF333333) else CAccent)
                                    .padding(horizontal = u(9), vertical = u(1))
                            ) { Text(dyn, color = if (dyn == "SDR") CLight else Color(0xFF06121F), fontSize = ts(13), fontWeight = FontWeight.Bold) }
                        }
                    }
                    Text(cc.name, color = CInk, fontSize = ts(30), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (epg.isNotEmpty()) Text(epg, color = CAccent, fontSize = ts(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (cc.kind == "stream") "▲▼ trocam de canal · Voltar sai" else "▲ menu (áudio, legendas, tela) · ◀ ▶ pulam 10 s · OK pausa · Voltar sai",
                        color = CLight, fontSize = ts(16)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerMenu(kind: String, items: List<String>, sel: Int, tick: Int, onTimeout: () -> Unit) {
    LaunchedEffect(tick) {
        delay(15000)
        onTimeout()
    }
    val title = when (kind) {
        "main" -> "Opções"
        "audio" -> "Áudio"
        "subs" -> "Legendas"
        "screen" -> "Ajuste de tela"
        else -> "Informações"
    }
    Box(Modifier.fillMaxSize()) {
        val shape = RoundedCornerShape(u(18))
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = u(40)).width(u(440))
                .background(Color(0xE60E121B), shape).border(BorderStroke(u(1), Color(0x33FFFFFF)), shape).padding(u(24))
        ) {
            Text(title, color = CInk, fontSize = ts(26), fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(u(12)))
            items.forEachIndexed { i, t ->
                val on = i == sel && kind != "info"
                Box(
                    Modifier.fillMaxWidth().padding(vertical = u(2))
                        .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(u(10)))
                        .padding(horizontal = u(16), vertical = u(9))
                ) {
                    Text(
                        t, color = if (on) Color.Black else CLight, fontSize = ts(18),
                        fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(u(12)))
            Text(if (kind == "info") "Voltar volta" else "▲▼ escolhe · OK confirma · Voltar volta", color = CDim, fontSize = ts(13))
        }
    }
}

@Composable
private fun EpisodesPanel(
    series: String, seasons: List<Iptv.Season>, si: Int, ei: Int, playing: String?, tick: Int, fromPlayer: Boolean, onTimeout: () -> Unit
) {
    LaunchedEffect(tick) {
        if (fromPlayer) {
            delay(15000)
            onTimeout()
        }
    }
    val eps = seasons.getOrNull(si)?.eps ?: emptyList()
    val playIdx = eps.indexOfFirst { it.name == playing }
    Box(Modifier.fillMaxSize()) {
        val shape = RoundedCornerShape(u(18))
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(u(24)).width(u(620))
                .background(Color(0xF20E121B), shape).border(BorderStroke(u(1), Color(0x33FFFFFF)), shape).padding(u(26))
        ) {
            Text(series, color = CInk, fontSize = ts(28), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(u(12)))
            val ts0 = rememberLazyListState()
            LaunchedEffect(si) { ts0.animateScrollToItem(max(0, si - 2)) }
            LazyRow(state = ts0, horizontalArrangement = Arrangement.spacedBy(u(10))) {
                itemsIndexed(seasons) { i, s ->
                    val on = i == si
                    Box(
                        Modifier.background(if (on) Color.White else CCard, RoundedCornerShape(u(20)))
                            .padding(horizontal = u(16), vertical = u(7))
                    ) { Text("Temporada " + s.num, color = if (on) Color.Black else CLight, fontSize = ts(16), fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(u(14)))
            val st = rememberLazyListState()
            LaunchedEffect(ei, si) { st.animateScrollToItem(max(0, ei - 3)) }
            LazyColumn(Modifier.weight(1f), state = st, verticalArrangement = Arrangement.spacedBy(u(6))) {
                itemsIndexed(eps) { i, e ->
                    val on = i == ei
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (on) CCardHi else Color.Transparent, RoundedCornerShape(u(10)))
                            .then(if (on) Modifier.border(BorderStroke(u(2), Color(0xFFCFE9FF)), RoundedCornerShape(u(10))) else Modifier)
                            .padding(horizontal = u(14), vertical = u(9))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.label, color = CAccent, fontSize = ts(13), fontWeight = FontWeight.Bold)
                            if (i == playIdx) Text("   ▶ assistindo", color = CLive, fontSize = ts(13), fontWeight = FontWeight.Bold)
                            if (playIdx >= 0 && i == playIdx + 1) Text("   próximo", color = CWarm, fontSize = ts(13), fontWeight = FontWeight.Bold)
                        }
                        Text(e.name, color = CInk, fontSize = ts(18), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(u(10)))
            val cur = eps.getOrNull(ei)
            if (cur != null && cur.desc.isNotBlank()) {
                Text(cur.desc, color = CLight, fontSize = ts(15), lineHeight = ts(21), maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(u(8)))
            }
            Text("◀ ▶ temporada · ▲▼ episódio · OK assiste · Voltar fecha", color = CDim, fontSize = ts(13))
        }
    }
}

@Composable
private fun KeymapBox(step: Int, last: String) {
    Box(Modifier.fillMaxSize().background(Color(0xEE02030A)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(u(18))
        Column(
            Modifier.width(u(640)).background(CPanel, shape).border(BorderStroke(u(1), Color(0xFF262C3B)), shape).padding(u(34)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Mapear o controle", color = CInk, fontSize = ts(30), fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(u(16)))
            Text(
                if (step == 0) "Aperte agora o botão VOLTAR do seu controle." else "Agora aperte o botão INÍCIO (ou o que deve voltar ao menu).",
                color = CLight, fontSize = ts(20), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(u(16)))
            Text(if (last.isEmpty()) "Esperando um botão…" else "Último botão: $last", color = CAccent, fontSize = ts(18))
            Spacer(Modifier.height(u(12)))
            Text("Passo ${step + 1} de 2 · OK cancela e usa o padrão", color = CDim, fontSize = ts(14))
        }
    }
}

@Composable
private fun ReaderBox(c: Card?) {
    Box(Modifier.fillMaxSize().background(Color(0xE602030A)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(u(18))
        Column(Modifier.width(u(860)).background(CPanel, shape).border(BorderStroke(u(1), Color(0xFF262C3B)), shape).padding(u(36))) {
            Text((c?.label ?: "").uppercase(), color = CAccent, fontSize = ts(15), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(u(10)))
            Text(c?.name ?: "", color = CInk, fontSize = ts(30), fontWeight = FontWeight.ExtraBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(u(16)))
            Text(c?.desc ?: "", color = CLight, fontSize = ts(20), lineHeight = ts(30), maxLines = 9, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(u(18)))
            Text("Voltar ou OK fecha", color = CDim, fontSize = ts(14))
        }
    }
}
