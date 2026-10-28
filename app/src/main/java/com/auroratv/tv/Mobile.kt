package com.auroratv.tv

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Versão para celular: mesmo motor da TV (conta IPTV, player, Assistente IA, Som IA, notícias, música,
 * atualização), com telas feitas para toque. Toda seção da TV existe aqui (abas + "Mais").
 */

private val MBg = Color(0xFF05060D)
private val MCard = Color(0xFF141826)
private val MDim = Color(0xFF8C93AD)
private val MGrad = listOf(Color(0xFF7FE9FF), Color(0xFF9B84FF), Color(0xFFFF7FD8))

/** Seções iguais às da TV (a mesma lista); as 4 primeiras viram abas, o resto fica em "Mais". */
private val M_TABS = listOf("Início", "TV ao vivo", "Filmes", "Séries", "Mais")
private val M_MORE = listOf("Pesquisar", "Minhas Listas", "Streams", "Novelas", "Documentários", "Esporte", "Notícias", "Música")

private sealed class MScreen {
    object Tabs : MScreen()
    class Section(val name: String) : MScreen()
    class Episodes(val title: String, val seasons: List<Iptv.Season>) : MScreen()
    class Reader(val card: Card) : MScreen()
    class Setting(val name: String) : MScreen()
}

@Composable
fun MobileApp(catalog: Catalog, activity: MainActivity) {
    CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = AuroraFont, color = Color.White)
    ) { MobileBody(catalog, activity) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileBody(catalog: Catalog, activity: MainActivity) {
    val context = LocalContext.current
    val pb = remember { Playback(context) }
    var tab by remember { mutableStateOf(0) }
    var stack by remember { mutableStateOf(listOf<MScreen>(MScreen.Tabs)) }
    var playing by remember { mutableStateOf<Card?>(null) }
    var playList by remember { mutableStateOf<List<Card>>(emptyList()) }
    var iptvRows by remember { mutableStateOf<Map<String, List<RowData>>>(emptyMap()) }
    var iptvTotal by remember { mutableStateOf(0) }
    var iptvBusy by remember { mutableStateOf(false) }
    var newsRows by remember { mutableStateOf<List<RowData>>(emptyList()) }
    var m3uExtra by remember { mutableStateOf<List<Item>>(emptyList()) }
    var logosVer by remember { mutableStateOf(0) }
    var favs by remember { mutableStateOf(Store.favs().toList()) }
    var weather by remember { mutableStateOf("") }
    val cityIdx = Store.city.coerceIn(0, catalog.cities.size - 1)

    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()
    fun push(s: MScreen) {
        stack = stack + s
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    fun reloadIptv() {
        if (!Store.anyAcc()) return
        iptvBusy = true
        Iptv.loadAll { r ->
            iptvBusy = false
            if (r.total > 0) {
                iptvRows = r.rows
                iptvTotal = r.total
            }
        }
    }

    LaunchedEffect(Unit) {
        AudioFx.apply(Store.eq())
        reloadIptv()
        Logos.load { logosVer++ }
        for (src in catalog.lists) Net.loadM3u(src) { loaded -> m3uExtra = m3uExtra + loaded }
    }
    LaunchedEffect(cityIdx) {
        Net.fetchWeather(catalog.cities[cityIdx]) { r -> weather = if (r != null) catalog.cities[cityIdx].name + " " + r.substringBefore(" · ") else "" }
        while (true) {
            News.load(catalog.cities[cityIdx].name) { r -> if (r.isNotEmpty()) newsRows = r }
            delay(15 * 60 * 1000L)
        }
    }
    DisposableEffect(Unit) { onDispose { pb.release() } }

    val free = remember(m3uExtra, logosVer) { buildFree(catalog, m3uExtra) }
    val openRows = remember(logosVer) {
        Logos.open.groupBy { it.cat }.map { (cat, l) ->
            RowData("Canais abertos · $cat", l.distinctBy { it.name.lowercase() }.mapIndexed { i, o ->
                mkCard(o.name, cat, "stream", i + 7, o.url, "", emptyList(), "Canal aberto pela internet").copy(img = o.logo)
            })
        }
    }

    fun iptv(sec: String) = iptvRows[sec] ?: emptyList()
    fun loginRow(): List<RowData> = if (Store.anyAcc()) emptyList() else listOf(
        RowData("Sua conta IPTV", listOf(mkCard("Conectar conta IPTV", "Toque para entrar", "login", 0, null, "", emptyList(), "")))
    )

    fun rowsFor(name: String): List<RowData> = when (name) {
        "Início" -> {
            val r = ArrayList<RowData>()
            val h = Store.history().mapNotNull { n -> allCards(iptvRows, free, openRows).firstOrNull { it.name == n } }
            if (h.isNotEmpty()) r.add(RowData("Continuar assistindo", h))
            r.addAll(newsRows)
            iptv("Filmes").flatMap { it.cards }.filter { it.img.isNotEmpty() }.take(20).let { if (it.isNotEmpty()) r.add(RowData("Filmes da sua lista", it)) }
            iptv("Séries").flatMap { it.cards }.filter { it.img.isNotEmpty() }.take(20).let { if (it.isNotEmpty()) r.add(RowData("Séries da sua lista", it)) }
            r.add(RowData("Abertos pela internet", free.internet))
            r.add(RowData("Seus streams", streamRows().flatMap { it.cards }.take(8)))
            r
        }
        "TV ao vivo" -> loginRow() + iptv("TV ao vivo") + RowData("Abertos pela internet", free.internet) + openRows + RowData("Internacionais", free.intl)
        "Filmes" -> loginRow() + iptv("Filmes") + RowData("Filmes de licença livre", free.films)
        "Séries" -> loginRow() + iptv("Séries") + RowData("Animes ao vivo", free.anime)
        "Novelas" -> loginRow() + iptv("Novelas")
        "Documentários" -> loginRow() + iptv("Documentários") + RowData("Canais abertos", free.docs)
        "Esporte" -> loginRow() + iptv("Esporte")
        "Notícias" -> loginRow() + iptv("Notícias") + RowData("Ao vivo", free.news) + newsRows
        "Streams" -> streamRows()
        "Música" -> musicRows(openRows.filter { it.title.endsWith("Música") }.flatMap { it.cards }, iptv("Música").map { RowData("Da sua lista · " + it.title, it.cards) })
        "Minhas Listas" -> {
            val all = allCards(iptvRows, free, openRows)
            val f = favs.mapNotNull { n -> all.firstOrNull { it.name == n } }
            listOf(RowData("Favoritos (${f.size})", f.ifEmpty { listOf(mkCard("Nenhum favorito ainda", "Toque e segure num título", "empty", 0)) }))
        }
        else -> emptyList()
    }.filter { it.cards.isNotEmpty() }

    fun launchApp(c: Card) {
        val pm = context.packageManager
        for (p in c.pkgs) {
            val i = pm.getLaunchIntentForPackage(p) ?: pm.getLeanbackLaunchIntentForPackage(p)
            if (i != null) {
                try {
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (e: Exception) {
                }
            }
        }
        val uri = if (c.pkgs.isNotEmpty() && c.extra != "busca") "market://details?id=${c.pkgs[0]}" else "market://search?q=${Uri.encode(c.name)}&c=apps"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Instale o ${c.name} pela Play Store.")
        }
    }

    fun play(c: Card, list: List<Card>) {
        if (c.url == null) return
        applyQuality(pb.player, Store.quality)
        pb.start(c)
        playing = c
        playList = list.filter { it.kind == "stream" && it.url != null }
        Store.pushHistory(c.name)
    }

    fun open(c: Card, list: List<Card>) {
        when (c.kind) {
            "login" -> push(MScreen.Setting("Conta IPTV"))
            "news" -> push(MScreen.Reader(c))
            "quote" -> toast(c.name + "  " + c.label)
            "app" -> launchApp(c)
            "stream", "vod" -> play(c, list)
            "series" -> {
                toast("Carregando temporadas e episódios…")
                Iptv.seriesInfo(c.extra) { s -> if (s.isEmpty()) toast("Sem sinal dessa série agora.") else push(MScreen.Episodes(c.name, s)) }
            }
            "empty" -> {}
            else -> toast("Disponível na versão de TV")
        }
    }

    fun toggleFav(c: Card) {
        if (c.kind == "empty" || c.kind == "login" || c.kind == "news" || c.kind == "quote") return
        val l = favs.toMutableList()
        if (l.contains(c.name)) {
            l.remove(c.name)
            toast("Removido de Minhas Listas")
        } else {
            l.add(0, c.name)
            toast("★ ${c.name} em Minhas Listas")
        }
        favs = l
        Store.saveFavs(l)
    }

    // ---------- player em tela cheia (deitado) ----------
    val pc = playing
    if (pc != null) {
        BackHandler {
            pb.stop()
            playing = null
        }
        MobilePlayer(pb, pc, onClose = {
            pb.stop()
            playing = null
        }, onZap = { d ->
            if (playList.isNotEmpty()) {
                val i = (playList.indexOfFirst { it.name == pc.name } + d + playList.size) % playList.size
                play(playList[i], playList)
            }
        })
        return
    }

    BackHandler(enabled = stack.size > 1) { pop() }
    Box(Modifier.fillMaxSize().background(MBg)) {
        MobileSky()
        when (val s = stack.last()) {
            is MScreen.Tabs -> {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    MobileTop(weather) { push(MScreen.Section("Pesquisar")) }
                    if (M_TABS[tab] == "Mais") MoreScreen(onSection = { push(MScreen.Section(it)) }, onSetting = { push(MScreen.Setting(it)) }, version = Updater.currentVersion(context))
                    else MobileRows(rowsFor(M_TABS[tab]), favs, iptvBusy && M_TABS[tab] != "Início", { c, l -> open(c, l) }, { toggleFav(it) }, hero = M_TABS[tab] == "Início")
                }
                MobileNav(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
            }
            is MScreen.Section -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                MobileHeader(s.name) { pop() }
                if (s.name == "Pesquisar") SearchScreen(allCards(iptvRows, free, openRows) + streamRows().flatMap { it.cards }) { c, l -> open(c, l) }
                else MobileRows(rowsFor(s.name), favs, iptvBusy, { c, l -> open(c, l) }, { toggleFav(it) }, hero = false)
            }
            is MScreen.Episodes -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                MobileHeader(s.title) { pop() }
                EpisodesScreen(s.seasons) { c, l -> play(c, l) }
            }
            is MScreen.Reader -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                MobileHeader(s.card.label) { pop() }
                ReaderScreen(s.card)
            }
            is MScreen.Setting -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                MobileHeader(s.name) { pop() }
                SettingScreen(s.name, catalog, iptvTotal, onIptvChanged = { reloadIptv() })
            }
        }
    }
}

private fun allCards(iptvRows: Map<String, List<RowData>>, free: FreeData, openRows: List<RowData>): List<Card> {
    val l = ArrayList<Card>()
    l.addAll(free.internet); l.addAll(free.intl); l.addAll(free.films); l.addAll(free.anime); l.addAll(free.news); l.addAll(free.docs)
    openRows.forEach { l.addAll(it.cards) }
    iptvRows.values.forEach { rs -> rs.forEach { l.addAll(it.cards) } }
    return l.distinctBy { it.name + "|" + it.url }
}

@Composable
private fun MobileSky() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(listOf(Color(0x552A1F5A), Color.Transparent), center = Offset(size.width * 0.75f, -40f), radius = size.width * 0.9f))
        val r = java.util.Random(4)
        for (i in 0 until 140) drawCircle(Color.White.copy(alpha = 0.2f + r.nextFloat() * 0.5f), 0.6f + r.nextFloat() * 1.3f, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height))
    }
}

@Composable
private fun LogoText(size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            LogoPainter.WORD, fontSize = size.sp, fontWeight = FontWeight.Black,
            style = androidx.compose.ui.text.TextStyle(brush = Brush.horizontalGradient(MGrad), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontFamily = AuroraFont)
        )
        Spacer(Modifier.width(5.dp))
        Text("TV", fontSize = size.sp, fontWeight = FontWeight.Black, color = Color(0xFFE7E8EE), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    }
}

@Composable
private fun MobileTop(weather: String, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LogoText(24)
            if (weather.isNotEmpty()) Text(weather, color = MDim, fontSize = 12.sp)
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).clickable { onSearch() },
            contentAlignment = Alignment.Center
        ) { NavIcon("search", Color.White, 20.dp) }
    }
}

@Composable
private fun MobileHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MobileNav(sel: Int, modifier: Modifier, onSel: (Int) -> Unit) {
    val icons = listOf("home", "tv", "film", "series", "power")
    Row(
        modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().height(68.dp).clip(RoundedCornerShape(24.dp))
            .background(Color(0xF0141826)).border(BorderStroke(1.dp, Color(0x14FFFFFF)), RoundedCornerShape(24.dp)),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically
    ) {
        M_TABS.forEachIndexed { i, n ->
            val on = i == sel
            Column(Modifier.weight(1f).clickable { onSel(i) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (n == "Mais") Text("•••", color = if (on) Color.White else MDim, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                else NavIcon(icons[i], if (on) Color(0xFF9B84FF) else MDim, 22.dp)
                Spacer(Modifier.height(3.dp))
                Text(if (n == "TV ao vivo") "Ao vivo" else n, color = if (on) Color.White else MDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileRows(rows: List<RowData>, favs: List<String>, loading: Boolean, onOpen: (Card, List<Card>) -> Unit, onFav: (Card) -> Unit, hero: Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        if (loading) item { Text("Carregando a sua lista…", color = MDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
        if (hero) {
            val h = rows.firstOrNull { it.title == "Continuar assistindo" }?.cards?.firstOrNull()
            if (h != null) item { HeroCard(h) { onOpen(h, emptyList()) } }
        }
        items(rows, key = { it.title }) { r ->
            Column(Modifier.padding(bottom = 18.dp)) {
                Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                val wide = r.cards.firstOrNull()?.let { it.kind != "vod" && it.kind != "series" } ?: true
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(r.cards, key = { it.name + "|" + it.url + "|" + it.label }) { c ->
                        MobileCard(c, wide, favs.contains(c.name), Modifier.combinedClickable(onClick = { onOpen(c, r.cards) }, onLongClick = { onFav(c) }))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(c: Card, onClick: () -> Unit) {
    val art = rememberCardImage(c)
    Box(
        Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3B2A7A), Color(0xFF152A55), Color(0xFF0A0F1F)))).clickable { onClick() }
    ) {
        if (art != null) Image(art, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text("CONTINUE ASSISTINDO", color = MGrad[0], fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(c.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 9.dp)) {
                Text("▶ Continuar", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun MobileCard(c: Card, wide: Boolean, fav: Boolean, modifier: Modifier) {
    val art = if (c.kind == "app" || c.img.isNotEmpty() || c.kind == "vod" || c.kind == "series" || (c.kind == "news" && c.extra.startsWith("http"))) rememberCardImage(c) else null
    val shape = RoundedCornerShape(16.dp)
    val m = if (wide) Modifier.width(200.dp).height(112.dp) else Modifier.width(118.dp).height(172.dp)
    Box(modifier.then(m).clip(shape).background(Brush.linearGradient(listOf(c.hueA, c.hueB)))) {
        val photo = art != null && (c.kind == "vod" || c.kind == "series" || c.kind == "news" || (c.kind == "app" && !Img.isIcon(c)))
        if (art != null) {
            if (photo) Image(art, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Image(art, c.name, Modifier.align(Alignment.Center).padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 26.dp).fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (photo) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        if (c.label.isNotEmpty()) {
            val live = c.kind == "stream"
            Box(Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(if (live) Color(0xFFFF5A6E) else Color(0xD1FFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(c.label, color = Color(0xFF0A1018), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (fav) Text("★", color = Color(0xFFFFD27A), fontSize = 16.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
        Text(
            c.name, fontSize = if (c.kind == "news") 12.sp else 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
        )
    }
}

@Composable
private fun MoreScreen(onSection: (String) -> Unit, onSetting: (String) -> Unit, version: String) {
    val icons = mapOf(
        "Pesquisar" to "search", "Minhas Listas" to "star", "Streams" to "popcorn", "Novelas" to "heart", "Documentários" to "doc",
        "Esporte" to "ball", "Notícias" to "news", "Música" to "music"
    )
    val settings = listOf(
        "Conta IPTV", "Qualidade de vídeo", "Assistente IA de imagem", "Som IA", "Clima e cidade", "Canais fora do ar", "Rede e internet", "Atualização do app", "Sobre o aparelho"
    )
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { Text("Todas as seções", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 6.dp)) }
        items(M_MORE) { n ->
            val h = Palette.pickFor(n)
            Column(
                Modifier.height(84.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(h.first, h.second))).clickable { onSection(n) }.padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                NavIcon(icons[n] ?: "home", Color.White, 22.dp)
                Text(n, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { Text("Configurações", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
        items(settings, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { n ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x0FFFFFFF)).clickable { onSetting(n) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(n, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("›", color = MDim, fontSize = 18.sp)
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Text("AstroLume TV · versão $version · celular\nFeito por Sérgio Barboza Júnior", color = MDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SearchScreen(all: List<Card>, onOpen: (Card, List<Card>) -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Canal, filme, série, notícia…", color = MDim) }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        val nq = Iptv.norm(q)
        val res = if (nq.length < 2) emptyList() else all.filter { Iptv.norm(it.name).contains(nq) }.take(80)
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            items(res, key = { it.name + "|" + it.url + "|" + it.label }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x0FFFFFFF)).clickable { onOpen(c, res) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MobileCard(c, true, false, Modifier.width(96.dp).height(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.label, color = MDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodesScreen(seasons: List<Iptv.Season>, onPlay: (Card, List<Card>) -> Unit) {
    var si by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons.size) { i ->
                val on = i == si
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Brush.horizontalGradient(MGrad.take(2)) else Brush.horizontalGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF))))
                        .clickable { si = i }.padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Temporada " + seasons[i].num, color = if (on) MBg else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        val eps = seasons.getOrNull(si)?.eps ?: emptyList()
        LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
            items(eps, key = { it.name + "|" + it.url }) { e ->
                val saved = e.url?.let { Store.resumeGet(it) } ?: 0L
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x0FFFFFFF)).clickable { onPlay(e, emptyList()) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MobileCard(e, true, false, Modifier.width(96.dp).height(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(e.name, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (saved > 0) Text("parou em " + hmsM(saved), color = MDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(c: Card) {
    var sum by remember(c) { mutableStateOf<Summary.Result?>(null) }
    LaunchedEffect(c) { sum = withContext(Dispatchers.IO) { Summary.summarize(c.extra, c.name, c.desc) } }
    val art = rememberCardImage(c)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
        item {
            if (art != null) Image(art, c.name, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(12.dp))
            Text(c.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            val s = sum
            when {
                s == null -> Text("Resumindo a notícia…", color = MDim)
                !s.ok || s.points.isEmpty() -> Text(c.desc, color = Color(0xFFC9CDE0), fontSize = 16.sp, lineHeight = 23.sp)
                else -> {
                    Text("RESUMO FEITO PELA IA DO APP", color = MDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    for (p in s.points) Row(Modifier.padding(bottom = 8.dp)) {
                        Text("•  ", color = MGrad[0], fontWeight = FontWeight.Bold)
                        Text(p, color = Color(0xFFC9CDE0), fontSize = 15.sp, lineHeight = 21.sp)
                    }
                    if (s.conclusion.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1F6CC6FF)).padding(12.dp)) {
                        Text("CONCLUSÃO", color = MGrad[0], fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(s.conclusion, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Fonte: " + c.label, color = MDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingScreen(name: String, catalog: Catalog, iptvTotal: Int, onIptvChanged: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    @Composable
    fun choice(options: List<String>, sel: Int, onPick: (Int) -> Unit) {
        Column {
            options.forEachIndexed { i, o ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (i == sel) Color(0x339B84FF) else Color(0x0FFFFFFF)).clickable { onPick(i); tick++ }.padding(14.dp)
                ) {
                    Text((if (i == sel) "●  " else "○  ") + o, fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
    val t = tick
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
        item {
            when (name) {
                "Conta IPTV" -> IptvForm(iptvTotal, onIptvChanged)
                "Qualidade de vídeo" -> choice(QUALITY_NAMES, Store.quality) { Store.quality = it }
                "Assistente IA de imagem" -> {
                    Text(ImageAi.summary(), color = MDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    choice(ENHANCE_NAMES, Store.enhance.coerceIn(0, ENHANCE_NAMES.size - 1)) { Store.enhance = it }
                }
                "Som IA" -> {
                    Text("Reconhece explosões, diálogo e ambiente e ajusta o som na hora.", color = MDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    choice(listOf("Desligado", "Automático"), Store.somIa) {
                        Store.somIa = it
                        SomIa.enabled = it == 1
                    }
                }
                "Clima e cidade" -> choice(catalog.cities.map { it.name }, Store.city.coerceIn(0, catalog.cities.size - 1)) { Store.city = it }
                "Canais fora do ar" -> choice(HIDEOFF_NAMES, Store.hideOffline.coerceIn(0, 1)) { Store.hideOffline = it }
                "Rede e internet" -> Text(networkInfo(context), color = Color(0xFFC9CDE0), fontSize = 14.sp, lineHeight = 21.sp)
                "Atualização do app" -> UpdateBox()
                else -> Text(
                    "AstroLume TV · versão " + Updater.currentVersion(context) + " · celular\n" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                        " · Android " + android.os.Build.VERSION.RELEASE + "\n\n" + ImageAi.summary() + "\n\nFeito por Sérgio Barboza Júnior",
                    color = Color(0xFFC9CDE0), fontSize = 14.sp, lineHeight = 21.sp
                )
            }
            if (t < 0) Text("")
        }
    }
}

@Composable
private fun IptvForm(total: Int, onChanged: () -> Unit) {
    var slot by remember { mutableStateOf(0) }
    val a = remember(slot) { Store.acc(slot) }
    var url by remember(slot) { mutableStateOf(a.first) }
    var user by remember(slot) { mutableStateOf(a.second) }
    var pass by remember(slot) { mutableStateOf(a.third) }
    var msg by remember { mutableStateOf(if (total > 0) "Conectado: $total itens carregados." else "") }
    Column {
        Text("Use a sua conta licenciada. Os dados ficam só neste aparelho.", color = MDim, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(5) { i ->
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (i == slot) Color(0x669B84FF) else Color(0x1FFFFFFF)).clickable { slot = i }.padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text("Conta ${i + 1}" + if (Store.acc(i).first.isNotBlank()) " ✓" else "", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Servidor (http://…) ou link da lista") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Usuário") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Senha") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable {
                Store.setAcc(slot, url.trim(), user.trim(), pass)
                msg = "Conectando…"
                Iptv.loadAll { r -> msg = if (r.total > 0) "Conectado: ${r.total} itens." else "Não conectou: ${r.error ?: "confira os dados"}" }
                onChanged()
            }.padding(horizontal = 18.dp, vertical = 11.dp)) { Text("Salvar e conectar", color = Color.Black, fontWeight = FontWeight.Bold) }
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).clickable {
                Store.setAcc(slot, "", "", "")
                url = ""; user = ""; pass = ""
                msg = "Conta ${slot + 1} removida."
                onChanged()
            }.padding(horizontal = 18.dp, vertical = 11.dp)) { Text("Remover", fontWeight = FontWeight.Bold) }
        }
        if (msg.isNotEmpty()) Text(msg, color = MDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun UpdateBox() {
    val context = LocalContext.current
    var msg by remember { mutableStateOf("Versão instalada: " + Updater.currentVersion(context)) }
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Updater.check(context) { r ->
            when (r.status) {
                "new" -> {
                    msg = "Nova versão ${r.remote} disponível."
                    url = r.apkUrl
                }
                "same" -> msg = "Você já está na versão mais recente (${Updater.currentVersion(context)})."
                else -> msg = "Sem conexão para conferir. Tente de novo."
            }
        }
    }
    Column {
        Text(msg, color = Color(0xFFC9CDE0), fontSize = 14.sp)
        val u = url
        if (u != null) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable {
                msg = "Baixando…"
                Updater.downloadAndInstall(context, u) { p -> msg = if (p >= 100) "Pronto. Confirme a instalação." else "Baixando… $p%" }
            }.padding(horizontal = 18.dp, vertical = 11.dp)) { Text("Atualizar agora", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}

private fun hmsM(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

/** Player do celular: deitado, toque mostra os controles, toque duplo nas laterais pula 10 s, barra arrastável. */
@Composable
private fun MobilePlayer(pb: Playback, card: Card, onClose: () -> Unit, onZap: (Int) -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val player = pb.player
    val live = card.kind == "stream"
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                pb.setAi(Store.enhance)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    var osd by remember { mutableStateOf(true) }
    var osdTick by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var sia by remember { mutableStateOf("") }
    LaunchedEffect(osdTick) {
        osd = true
        delay(4500)
        osd = false
    }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.let { if (it == androidx.media3.common.C.TIME_UNSET) 0L else it }
            isPlaying = player.playWhenReady
            sia = if (SomIa.enabled) SomIa.scene else ""
            delay(300)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(card.url) {
        detectTapGestures(
            onTap = { if (osd) osd = false else osdTick++ },
            onDoubleTap = { o ->
                if (!live) {
                    val d = if (o.x < size.width / 2f) -10000L else 10000L
                    player.seekTo((player.currentPosition + d).coerceAtLeast(0L))
                    osdTick++
                }
            }
        )
    }) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                this.player = player
            }
        }, update = { if (it.player !== player) it.player = player }, modifier = Modifier.fillMaxSize())
        val d = pb.diag
        if (d != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SEM SINAL", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(d.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(d.fix, color = Color(0xFF6CC6FF), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { pb.retryNow() }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Text("Tentar agora", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        } else if (pb.connecting) Text("Conectando…", modifier = Modifier.align(Alignment.Center))
        if (osd) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xCC000000))))
            Row(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (live) "● AO VIVO · " else "") + card.label.uppercase(), color = Color(0xFFFF7FD8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(card.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pb.aiLabel.isNotEmpty()) Chip(if (pb.aiLabel.startsWith("IA")) pb.aiLabel else "IA · " + pb.aiLabel, Brush.horizontalGradient(listOf(Color(0xFF7B5CFF), Color(0xFF3FA9FF))), Color.White)
                if (sia.isNotEmpty()) Chip("Som IA · $sia", Brush.horizontalGradient(listOf(Color(0xFFFF6A3D), Color(0xFFFFB13D))), Color(0xFF1A0B02))
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(if (live) "◀" else "−10", 54) { if (live) onZap(-1) else player.seekTo((player.currentPosition - 10000L).coerceAtLeast(0L)); osdTick++ }
                RoundBtn(if (isPlaying) "❚❚" else "▶", 72, primary = true) { if (player.playWhenReady) player.pause() else player.play(); osdTick++ }
                RoundBtn(if (live) "▶" else "+10", 54) { if (live) onZap(1) else player.seekTo(player.currentPosition + 10000L); osdTick++ }
            }
            if (!live && dur > 0) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
                    val shown = dragging?.let { (it * dur).toLong() } ?: pos
                    Row {
                        Text("▶ " + hmsM(shown), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("−" + hmsM(dur - shown) + "  ·  " + hmsM(dur), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { dragging = it; osdTick++ },
                        onValueChangeFinished = {
                            dragging?.let { player.seekTo((it * dur).toLong()) }
                            dragging = null
                        },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF9B84FF), inactiveTrackColor = Color(0x44FFFFFF))
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(t: String, b: Brush, c: Color) {
    Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(b).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(t, color = c, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun RoundBtn(t: String, sizeDp: Int, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(sizeDp.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (primary) Color.White else Color(0x66000000)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(t, color = if (primary) Color.Black else Color.White, fontSize = if (primary) 24.sp else 15.sp, fontWeight = FontWeight.ExtraBold) }
}
