package com.auroratv.tv

import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Paleta
private val Black = Color(0xFF000000)
private val Card = Color(0xFF141414)
private val CardOn = Color(0xFF2A2A2A)
private val Focus = Color(0xFFFFB547)
private val White = Color(0xFFFFFFFF)
private val Light = Color(0xFFE6EAF0)
private val Dim = Color(0xFFA9B1BD)
private val NetBlue = Color(0xFF9CC8FF)
private val IntlGreen = Color(0xFF8FE3B0)
private val LiveRed = Color(0xFFFF4D5E)

private enum class Zone { TABS, ROW, GUIDE, CONFIG, SETTINGS, PLAYER, TEST }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = assets.open("catalog.json").bufferedReader().use { it.readText() }
        val catalog = parseCatalog(json)
        setContent { AuroraApp(catalog) }
    }
}

@Composable
fun AuroraApp(catalog: Catalog) {
    val context = LocalContext.current
    val tabs = catalog.tabs
    val liveTabIndex = tabs.indexOfFirst { it.guide }.let { if (it < 0) 0 else it }

    var tabIndex by remember { mutableStateOf(0) }
    var itemIndex by remember { mutableStateOf(0) }
    var zone by remember { mutableStateOf(Zone.TABS) }

    var settingsIndex by remember { mutableStateOf(0) }
    var cityIndex by remember { mutableStateOf(0) }
    var speedText by remember { mutableStateOf("Ainda não medida.") }
    var testIndex by remember { mutableStateOf(0) }

    var weatherText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var clock by remember { mutableStateOf(nowLabel()) }

    // canais carregados por lista; força recomposição
    var liveVersion by remember { mutableStateOf(0) }

    val settingsItems = listOf(
        "Rede e internet", "Velocidade da internet", "Imagem (TCL A400M)",
        "Teste de tela", "Clima e cidade", "Sobre o aparelho"
    )
    val testColors = listOf(
        Triple(Color(0xFFFFFFFF), Color(0xFF555555), "Branco: procure manchas escuras, faixas e pontos mais claros."),
        Triple(Color(0xFF000000), Color(0xFF888888), "Preto: procure pontos acesos e luz vazando nas bordas."),
        Triple(Color(0xFFFF0000), Color(0xFF330000), "Vermelho: procure linhas e pixels apagados."),
        Triple(Color(0xFF00FF00), Color(0xFF003300), "Verde: procure linhas e pixels apagados."),
        Triple(Color(0xFF0000FF), Color(0xFFCCCCFF), "Azul: procure linhas e pixels apagados."),
        Triple(Color(0xFF808080), Color(0xFF222222), "Cinza: procure manchas de poeira e brilho irregular.")
    )

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // relógio
    LaunchedEffect(Unit) {
        while (true) { clock = nowLabel(); kotlinx_delay(15000) }
    }
    // clima
    LaunchedEffect(cityIndex) {
        weatherText = catalog.cities[cityIndex].name + "  buscando…"
        Net.fetchWeather(catalog.cities[cityIndex]) { r ->
            weatherText = catalog.cities[cityIndex].name + "  " + (r ?: "sem dados")
        }
    }
    // listas IPTV legais -> aba TV AO VIVO
    LaunchedEffect(Unit) {
        for (src in catalog.lists) {
            Net.loadM3u(src) { loaded ->
                val live = tabs[liveTabIndex].items
                val have = live.map { it.title.lowercase() }.toMutableSet()
                for (ch in loaded) {
                    if (!have.contains(ch.title.lowercase())) {
                        live.add(ch); have.add(ch.title.lowercase())
                    }
                }
                liveVersion++
            }
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Garante que o controle volte a comandar a tela após sair do player.
    LaunchedEffect(zone) { try { focus.requestFocus() } catch (e: Exception) {} }

    fun items(): MutableList<Item> = tabs[tabIndex].items
    fun isGuide(): Boolean = tabs[tabIndex].guide

    fun play(ti: Int, ii: Int) {
        val it = tabs[ti].items[ii]
        if (it.kind == "antenna") {
            message = "${it.title} chega grátis pela antena digital da sua TV. No controle, aperte INÍCIO e abra Entradas/TV. Pela internet, use o app ${it.app ?: "da emissora"}."
            return
        }
        if (it.url == null) { message = "Este item ainda não tem vídeo cadastrado."; return }
        player.setMediaItem(MediaItem.fromUri(it.url))
        player.prepare()
        itemIndex = ii; tabIndex = ti
        zone = Zone.PLAYER
    }

    fun zap(dir: Int) {
        val list = tabs[tabIndex].items
        val n = list.size
        var i = itemIndex
        for (k in 1..n) {
            i = ((i + dir) % n + n) % n
            if (list[i].url != null && list[i].live) {
                play(tabIndex, i); return
            }
        }
    }

    fun handle(key: Key): Boolean {
        // mensagem aberta
        if (message != null) {
            if (key == Key.Enter || key == Key.DirectionCenter || key == Key.Back) message = null
            return true
        }
        // teste de tela
        if (zone == Zone.TEST) {
            when (key) {
                Key.Enter, Key.DirectionCenter, Key.DirectionRight -> testIndex = (testIndex + 1) % testColors.size
                Key.DirectionLeft -> testIndex = (testIndex - 1 + testColors.size) % testColors.size
                Key.Back -> zone = Zone.SETTINGS
                else -> return false
            }
            return true
        }
        // player
        if (zone == Zone.PLAYER) {
            when (key) {
                Key.Back -> { player.stop(); zone = if (isGuide()) Zone.GUIDE else Zone.ROW }
                Key.DirectionUp -> zap(-1)
                Key.DirectionDown -> zap(1)
            }
            return true
        }
        // configurações
        if (zone == Zone.SETTINGS) {
            val sid = settingsIndex
            when (key) {
                Key.Back -> zone = Zone.CONFIG
                Key.DirectionUp -> if (sid > 0) settingsIndex = sid - 1
                Key.DirectionDown -> if (sid < settingsItems.size - 1) settingsIndex = sid + 1
                Key.DirectionLeft -> if (sid == 4) cityIndex = (cityIndex - 1 + catalog.cities.size) % catalog.cities.size
                Key.DirectionRight -> if (sid == 4) cityIndex = (cityIndex + 1) % catalog.cities.size
                Key.Enter, Key.DirectionCenter -> {
                    when (sid) {
                        1 -> { speedText = "medindo…"; Net.speedTest { speedText = it } }
                        3 -> { testIndex = 0; zone = Zone.TEST }
                    }
                }
                else -> return false
            }
            return true
        }
        // abas
        if (zone == Zone.TABS || zone == Zone.CONFIG) {
            when (key) {
                Key.DirectionRight -> {
                    if (zone == Zone.TABS) {
                        if (tabIndex < tabs.size - 1) { tabIndex += 1; itemIndex = 0 }
                        else zone = Zone.CONFIG
                    }
                }
                Key.DirectionLeft -> {
                    if (zone == Zone.CONFIG) zone = Zone.TABS
                    else if (tabIndex > 0) { tabIndex -= 1; itemIndex = 0 }
                }
                Key.DirectionDown, Key.Enter, Key.DirectionCenter -> {
                    if (zone == Zone.CONFIG) { settingsIndex = 0; zone = Zone.SETTINGS }
                    else if (items().isNotEmpty()) { itemIndex = 0; zone = if (isGuide()) Zone.GUIDE else Zone.ROW }
                }
                else -> return false
            }
            return true
        }
        // linha de cards
        if (zone == Zone.ROW) {
            val n = items().size
            when (key) {
                Key.DirectionRight -> if (itemIndex < n - 1) itemIndex += 1
                Key.DirectionLeft -> if (itemIndex > 0) itemIndex -= 1
                Key.DirectionUp -> zone = Zone.TABS
                Key.Enter, Key.DirectionCenter -> play(tabIndex, itemIndex)
                else -> return false
            }
            return true
        }
        // guia de canais
        if (zone == Zone.GUIDE) {
            val n = items().size
            when (key) {
                Key.DirectionDown -> if (itemIndex < n - 1) itemIndex += 1
                Key.DirectionUp -> if (itemIndex > 0) itemIndex -= 1 else zone = Zone.TABS
                Key.DirectionLeft -> zone = Zone.TABS
                Key.Enter, Key.DirectionCenter -> play(tabIndex, itemIndex)
                else -> return false
            }
            return true
        }
        return false
    }

    BackHandler(enabled = true) {
        when (zone) {
            Zone.TEST -> zone = Zone.SETTINGS
            Zone.PLAYER -> { player.stop(); zone = if (isGuide()) Zone.GUIDE else Zone.ROW }
            Zone.SETTINGS -> zone = Zone.CONFIG
            Zone.ROW, Zone.GUIDE -> zone = Zone.TABS
            else -> {}
        }
        if (message != null) message = null
    }

    // ---------- UI ----------
    Box(
        Modifier
            .fillMaxSize()
            .background(Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) handle(e.key) else false
            }
            .focusable()
    ) {
        when (zone) {
            Zone.PLAYER -> PlayerScreen(player, tabs[tabIndex].items[itemIndex])
            Zone.TEST -> {
                val tc = testColors[testIndex]
                Box(Modifier.fillMaxSize().background(tc.first)) {
                    Text(
                        "${testIndex + 1}/${testColors.size}  ${tc.third}   OK: próxima   VOLTAR: sair",
                        color = tc.second, fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)
                    )
                }
            }
            Zone.SETTINGS -> SettingsScreen(
                settingsItems, settingsIndex, settingsText(settingsItems[settingsIndex], context, speedText, catalog.cities[cityIndex], weatherText)
            )
            else -> HomeScreen(
                tabs, tabIndex, itemIndex, zone, weatherText, clock, liveTabIndex, liveVersion
            )
        }

        message?.let {
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth(0.72f)
                    .border(3.dp, Focus, RoundedCornerShape(4.dp))
                    .background(Color(0xFF1A1A1A)).padding(28.dp)
            ) {
                Text(it, color = White, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    tabs: List<Tab>, tabIndex: Int, itemIndex: Int, zone: Zone,
    weatherText: String, clock: String, liveTabIndex: Int, liveVersion: Int
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        // topo
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Aurora", color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("TV", color = Focus, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$clock     $weatherText", color = Light, fontSize = 18.sp)
        }
        Spacer(Modifier.height(14.dp))
        // abas
        val tabFocused = zone == Zone.TABS
        val configFocused = zone == Zone.CONFIG
        LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(tabs) { i, t ->
                TabChip(t.name, i == tabIndex && tabFocused, i == tabIndex && !tabFocused && zone != Zone.CONFIG)
            }
            item { TabChip("CONFIGURAÇÕES", configFocused, false) }
        }
        Spacer(Modifier.height(18.dp))

        val tab = tabs[tabIndex]
        @Suppress("UNUSED_EXPRESSION") liveVersion // mantém recomposição quando a lista carrega
        if (tab.guide) {
            GuideView(tab, itemIndex, zone == Zone.GUIDE)
        } else {
            RowView(tab, itemIndex, zone == Zone.ROW)
        }
    }
}

@Composable
private fun TabChip(name: String, focused: Boolean, selected: Boolean) {
    val bg = when { focused -> Focus; selected -> Color(0xFF333333); else -> Color.Transparent }
    val fg = when { focused -> Black; selected -> White; else -> Dim }
    Box(
        Modifier.clip(RoundedCornerShape(28.dp)).background(bg)
            .border(if (focused) 3.dp else 0.dp, if (focused) Focus else Color.Transparent, RoundedCornerShape(28.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(name, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuideView(tab: Tab, itemIndex: Int, active: Boolean) {
    Row(Modifier.fillMaxSize()) {
        val state = rememberLazyListState()
        LaunchedEffect(itemIndex) { if (active) state.animateScrollToItem(itemIndex) }
        LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(tab.items) { i, it ->
                val on = active && i == itemIndex
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .border(if (on) 3.dp else 0.dp, if (on) Focus else Color.Transparent)
                        .background(if (on) CardOn else Card)
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it.title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(it.source.uppercase(), color = sourceColor(it.source), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        val sel = tab.items.getOrNull(itemIndex)
        Column(Modifier.width(380.dp).fillMaxHeight().background(Card).padding(24.dp)) {
            if (sel != null) {
                Text(sel.source.uppercase(), color = Focus, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(sel.title, color = White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text(sel.desc, color = Light, fontSize = 18.sp)
                Spacer(Modifier.height(20.dp))
                Text(guideAction(sel), color = White, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun RowView(tab: Tab, itemIndex: Int, active: Boolean) {
    Column(Modifier.fillMaxSize()) {
        val hero = if (active && tab.items.isNotEmpty()) null else tab.hero
        val sel = tab.items.getOrNull(itemIndex)
        Box(
            Modifier.fillMaxWidth().height(200.dp)
                .background(if (hero != null) hero.color else Color(0xFF1A1A1A)).padding(26.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(if (hero != null) hero.tag else sel?.source?.uppercase() ?: "", color = Focus, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(if (hero != null) hero.title else sel?.title ?: "", color = White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(if (hero != null) hero.desc else sel?.desc ?: "", color = Light, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(tab.rowTitle, color = Light, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        val state = rememberLazyListState()
        LaunchedEffect(itemIndex) { if (active) state.animateScrollToItem(itemIndex) }
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(tab.items) { i, it ->
                val on = active && i == itemIndex
                Column(
                    Modifier.width(200.dp).height(150.dp)
                        .border(if (on) 3.dp else 0.dp, if (on) Focus else Color.Transparent)
                        .background(it.color).padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text((if (it.live) "AO VIVO · " else "") + it.source, color = Focus, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Column {
                        Text(it.title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(it.desc, color = Light, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerScreen(player: ExoPlayer, item: Item) {
    // Detecta o tipo de faixa dinâmica do vídeo atual (HDR10, HLG ou SDR).
    var dynamicRange by remember { mutableStateOf("") }
    LaunchedEffect(item.url) {
        dynamicRange = ""
        while (true) {
            dynamicRange = when (player.videoFormat?.colorInfo?.colorTransfer) {
                androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
                androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
                null -> ""
                else -> "SDR"
            }
            kotlinx_delay(1500)
        }
    }
    Box(Modifier.fillMaxSize().background(Black)) {
        AndroidView(
            factory = { ctx ->
                // surfaceType padrão = SurfaceView, que preserva o HDR nativo.
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
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color(0xCC000000)).padding(horizontal = 28.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (item.live) "AO VIVO · " else "") + item.source.uppercase(), color = LiveRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (dynamicRange.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(if (dynamicRange == "SDR") Color(0xFF333333) else Focus)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(dynamicRange, color = if (dynamicRange == "SDR") Light else Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(item.title, color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(item.desc + "   ·   setas cima/baixo trocam de canal   ·   VOLTAR sai", color = Light, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SettingsScreen(items: List<String>, index: Int, body: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Text("Configurações", color = White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEachIndexed { i, t ->
                    val on = i == index
                    Box(
                        Modifier.fillMaxWidth().height(64.dp)
                            .border(if (on) 3.dp else 0.dp, if (on) Focus else Color.Transparent)
                            .background(if (on) CardOn else Card).padding(horizontal = 24.dp),
                        contentAlignment = Alignment.CenterStart
                    ) { Text(t, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.width(28.dp))
            Box(Modifier.fillMaxSize().background(Card).padding(28.dp)) {
                Text(body, color = Light, fontSize = 20.sp)
            }
        }
    }
}

// ---------- helpers ----------

private fun sourceColor(s: String): Color = when (s) {
    "Antena" -> Focus
    "Internacional" -> IntlGreen
    else -> NetBlue
}

private fun guideAction(it: Item): String = when {
    it.kind == "antenna" -> "Este canal chega grátis pela antena digital da sua TV, com a melhor imagem. No controle, aperte INÍCIO e abra Entradas/TV. Pela internet, ele fica no app ${it.app ?: "da emissora"}."
    it.url != null -> "OK para assistir. No player, as setas para cima e para baixo trocam de canal."
    else -> "Canal sem endereço cadastrado."
}

private fun settingsText(id: String, context: android.content.Context, speed: String, city: City, weather: String): String {
    val nl = "\n"
    return when (id) {
        "Rede e internet" -> "Para trocar de Wi-Fi ou ver os detalhes da rede, abra Configurações do Google TV › Rede e Internet." + nl + nl + "A AuroraTV usa a mesma conexão do aparelho."
        "Velocidade da internet" -> "Mede a velocidade de download baixando cerca de 10 MB." + nl + nl + "Resultado: $speed" + nl + nl + "OK para medir agora."
        "Imagem (TCL A400M)" -> {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") (context as? MainActivity)?.windowManager?.defaultDisplay?.getRealMetrics(dm)
            "Resolução da tela: ${dm.widthPixels} x ${dm.heightPixels}" + nl + nl +
            "Ajustes recomendados (menu da TV):" + nl +
            "• Modo de imagem: Cinema ou Filme (mais fiel)" + nl +
            "• Local dimming / Mini-LED: Alto, para preto mais profundo" + nl +
            "• Nitidez: baixa (perto de 0-15); exagerar cria contornos falsos" + nl +
            "• Contraste dinâmico: Médio" + nl +
            "• Movimento (DNR/MEMC): Baixo para filmes, Médio para esporte" + nl + nl +
            hdrLine(context) + nl + nl +
            "A melhora de imagem é feita pela própria TCL. O teto é 4K (a tela é 4K)." + nl +
            "A AuroraTV entrega o vídeo HDR10 como está (nativo), sem converter para SDR. O HDR só aparece em conteúdo que já é HDR."
        }
        "Teste de tela" -> "Mostra cores puras em tela cheia para procurar linhas, manchas e pontos." + nl + nl +
            "Linha fina e reta de ponta a ponta: painel ou cabo flat." + nl +
            "Faixa escura alinhada: barra de LED." + nl +
            "Pontos claros redondos: lente de LED solta." + nl +
            "Mancha cinza borrada: poeira." + nl + nl + "OK para começar."
        "Clima e cidade" -> "Cidade:   <  ${city.name}  >" + nl + "Setas esquerda/direita trocam a cidade." + nl + nl + "Agora: $weather"
        "Sobre o aparelho" -> "Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}" + nl +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" + nl +
            "AuroraTV: versão 0.4 (Android TV)"
        else -> ""
    }
}

@Suppress("DEPRECATION")
private fun hdrLine(context: android.content.Context): String {
    return try {
        val display = (context as? MainActivity)?.windowManager?.defaultDisplay
        val caps = display?.hdrCapabilities
        val types = caps?.supportedHdrTypes ?: IntArray(0)
        val names = ArrayList<String>()
        for (t in types) {
            when (t) {
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> names.add("HDR10")
                android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> names.add("HLG")
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> names.add("HDR10+")
                android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> names.add("Dolby Vision")
            }
        }
        if (names.isEmpty()) "Faixa dinâmica da tela: SDR (HDR não detectado nesta saída)"
        else "Faixa dinâmica suportada pela TV: " + names.joinToString(", ")
    } catch (e: Exception) {
        "Faixa dinâmica da tela: não foi possível ler."
    }
}

private fun nowLabel(): String {
    val fmt = SimpleDateFormat("EEE, d MMM    HH:mm", Locale("pt", "BR"))
    return fmt.format(Date())
}

// pequena espera sem depender de kotlinx-coroutines extra
private suspend fun kotlinx_delay(ms: Long) = kotlinx.coroutines.delay(ms)
