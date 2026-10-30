package com.astrolume.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PT = Locale("pt", "BR")

@Composable
fun T(
    text: String, color: Color, size: Float, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start, shadow: Boolean = false, spacing: Float = 0f, lines: Int = 1,
) {
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = size.sp, fontWeight = weight, fontFamily = Inter, textAlign = align,
            letterSpacing = spacing.sp, shadow = if (shadow) Shadow(Color(0x99000000), Offset(0f, 2f), 12f) else null,
        ),
        maxLines = lines, overflow = TextOverflow.Ellipsis,
    )
}

/** Toda a tela é desenhada em 960×540 e escalada para a resolução da TV. */
@Composable
fun HomeRoot(st: HomeState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val d = LocalDensity.current
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        CompositionLocalProvider(LocalDensity provides Density(d.density * scale, 1f)) {
            Box(Modifier.size(960.dp, 540.dp).align(Alignment.Center)) { Home(st) }
        }
    }
}

@Composable
private fun Home(st: HomeState) {
    val pal = palette(st.theme)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(st.modal) { if (st.modal != "pair") try { fr.requestFocus() } catch (e: Throwable) {} }
    Box(
        Modifier.fillMaxSize().focusRequester(fr).focusable()
            .onPreviewKeyEvent { e ->
                if (st.modal == "pair") return@onPreviewKeyEvent false
                val k = when (e.key) {
                    Key.DirectionLeft -> "left"; Key.DirectionRight -> "right"; Key.DirectionUp -> "up"; Key.DirectionDown -> "down"
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> "ok"
                    else -> null
                } ?: return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) st.key(k)
                true
            }
    ) {
        Backdrop(st.theme)
        val p by animateFloatAsState(if (st.expanded) 1f else 0f, tween(520, easing = FastOutSlowInEasing), label = "exp")
        // marca e canto superior
        BasicText(
            buildBrand(), Modifier.offset(34.dp, 22.dp),
            style = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 17.sp, color = pal.ink),
        )
        Row(Modifier.align(Alignment.TopEnd).padding(top = 25.dp, end = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            st.weather?.let { T("${it.city} ${it.temp}° ${it.icon}", pal.dim, 11.5f, shadow = pal.image) }
        }
        // repouso: relógio grande, data e mini cotações
        Rest(st, pal, Modifier.offset(52.dp, 112.dp).graphicsLayer { alpha = 1f - p; translationY = -248.dp.toPx() * p })
        // faixa de apps
        Dock(st, pal, Modifier.offset(30.dp, 0.dp).graphicsLayer { translationY = (390f - 338f * p) * density })
        // painéis (entradas, atalhos, clima, cotações)
        if (p > 0.01f) Panels(st, pal, Modifier.offset(48.dp, 0.dp).graphicsLayer { alpha = p; translationY = (230f - 46f * p) * density })
        st.modal?.let { Modal(st, pal, it) }
        ToastBox(st)
    }
}

private fun buildBrand() = androidx.compose.ui.text.buildAnnotatedString {
    pushStyle(androidx.compose.ui.text.SpanStyle(brush = Brush.horizontalGradient(listOf(Color(0xFF4FC8E8), Color(0xFF8B6CFF), Color(0xFFFF6FC8)))))
    append("AstroLume")
    pop()
    append(" Home")
}

@Composable
private fun Rest(st: HomeState, pal: Pal, m: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5000) } }
    val d = Date(now)
    Column(m) {
        T(SimpleDateFormat("EEEE, d 'de' MMMM", PT).format(d).replaceFirstChar { it.uppercase() }, pal.dim, 18f, FontWeight.Medium, shadow = pal.image)
        T(SimpleDateFormat("HH:mm", PT).format(d), pal.ink, 112f, FontWeight.Light, shadow = pal.image, spacing = -3f)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            for (q in st.quotes) Row {
                T("${q.symbol} ", pal.dim, 13f, shadow = pal.image)
                T(q.value, pal.ink, 13f, FontWeight.SemiBold, shadow = pal.image)
                T(" " + (if (q.change >= 0) "▲" else "▼") + String.format(PT, "%.2f%%", kotlin.math.abs(q.change)), if (q.change >= 0) Color(0xFF1F9D55).let { if (pal.dark) Color(0xFF5BE38A) else it } else Color(0xFFD6455D).let { if (pal.dark) Color(0xFFFF8A9C) else it }, 13f, shadow = pal.image)
            }
        }
    }
}

@Composable
private fun Dock(st: HomeState, pal: Pal, m: Modifier) {
    val list = st.visible
    val ls = rememberLazyListState()
    LaunchedEffect(st.col, st.row, list.size) { if (st.row == 0 && list.isNotEmpty()) ls.animateScrollToItem((st.col - 3).coerceAtLeast(0)) }
    Box(
        m.size(900.dp, 112.dp).shadow(10.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x33141E3C), spotColor = Color(0x33141E3C))
            .clip(RoundedCornerShape(26.dp)).background(pal.card).border(1.dp, pal.line, RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!st.loaded) T("Carregando aplicativos…", pal.dim, 14f)
        LazyRow(
            state = ls, contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(112.dp),
        ) {
            itemsIndexed(list, key = { _, a -> a.pkg }) { i, a -> AppTile(a, st.modal == null && st.row == 0 && st.col == i, pal) }
            item(key = "+") { PlusTile(st.modal == null && st.row == 0 && st.col == list.size, pal) }
        }
    }
}

private val TileShape = RoundedCornerShape(14.dp)

@Composable
fun AppTile(a: AppEntry, focused: Boolean, pal: Pal, w: Dp = 120.dp, h: Dp = 68.dp) {
    val sc by animateFloatAsState(if (focused) 1.09f else 1f, tween(170), label = "s")
    Box(
        Modifier.size(w, h).graphicsLayer { scaleX = sc; scaleY = sc }
            .shadow(if (focused) 14.dp else 4.dp, TileShape)
            .clip(TileShape)
            .background(Brush.linearGradient(listOf(a.c1, a.c2), Offset(0f, 0f), Offset(w.value * 2f, h.value * 3f)))
            .border(if (focused) 3.dp else 1.dp, if (focused) pal.focus else Color(0x59FFFFFF), TileShape),
        contentAlignment = Alignment.Center,
    ) {
        if (a.banner != null) {
            Image(a.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                a.icon?.let { Image(it, null, Modifier.size(h * 0.42f)) }
                T(a.label, Color.White, 11f, FontWeight.ExtraBold, align = TextAlign.Center, shadow = true, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
        Gloss(focused, if (a.banner != null) 0.26f else 0.42f)
    }
}

/** brilho de vidro + reflexo que passa no app em foco */
@Composable
private fun Gloss(focused: Boolean, top: Float) {
    val sweep = if (focused) {
        val tr = rememberInfiniteTransition(label = "sweep")
        tr.animateFloat(-0.5f, 1.6f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "x").value
    } else -2f
    Box(
        Modifier.fillMaxSize().drawWithContent {
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = top), .44f to Color.White.copy(alpha = top * .28f), .46f to Color.Transparent, 1f to Color(0x2E000000)))
            if (sweep > -1f) {
                val x = size.width * sweep
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x73FFFFFF), Color.Transparent), Offset(x - size.width * .25f, 0f), Offset(x + size.width * .1f, size.height)))
            }
        }
    )
}

@Composable
private fun PlusTile(focused: Boolean, pal: Pal) {
    val sc by animateFloatAsState(if (focused) 1.09f else 1f, tween(170), label = "p")
    Box(
        Modifier.size(72.dp, 68.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(TileShape).background(if (focused) pal.cardHi else pal.card)
            .border(if (focused) 3.dp else 1.dp, if (focused) pal.focus else pal.dim, TileShape),
        contentAlignment = Alignment.Center,
    ) { T("+", pal.ink, 32f, FontWeight.Light) }
}

@Composable
private fun SmallTile(label: String, icon: String, focused: Boolean, pal: Pal) {
    val sc by animateFloatAsState(if (focused) 1.05f else 1f, tween(170), label = "t")
    val sh = RoundedCornerShape(12.dp)
    Column(
        Modifier.size(112.dp, 63.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(sh).background(if (focused) pal.cardHi else pal.card)
            .border(if (focused) 3.dp else 1.dp, if (focused) pal.focus else pal.line, sh),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        LineIcon(icon, pal.ink, 18.dp)
        Spacer(Modifier.height(6.dp))
        T(label, if (focused) pal.ink else pal.dim, 11f, FontWeight.Medium)
    }
}

@Composable
private fun Panels(st: HomeState, pal: Pal, m: Modifier) {
    val f = { r: Int, c: Int -> st.modal == null && st.row == r && st.col == c }
    Column(m.width(864.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                T("Entradas", pal.dim, 12f, FontWeight.SemiBold, Modifier.padding(start = 4.dp, bottom = 8.dp), shadow = pal.image)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { st.INPUTS.forEachIndexed { i, (l, ic) -> SmallTile(l, ic, f(1, i), pal) } }
            }
            Column {
                T("Atalhos", pal.dim, 12f, FontWeight.SemiBold, Modifier.padding(start = 4.dp, bottom = 8.dp), shadow = pal.image)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { st.SHORT.forEachIndexed { i, (l, ic) -> SmallTile(l, ic, f(1, i + 2), pal) } }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            WeatherBox(st, pal, f(2, 0), Modifier.weight(1.45f))
            QuotesBox(st, pal, f(2, 1), Modifier.weight(1f))
        }
    }
}

@Composable
private fun PanelBox(focused: Boolean, pal: Pal, m: Modifier, content: @Composable () -> Unit) {
    val sc by animateFloatAsState(if (focused) 1.02f else 1f, tween(170), label = "b")
    val sh = RoundedCornerShape(17.dp)
    Box(
        m.height(128.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(sh).background(if (focused) pal.cardHi else pal.card)
            .border(if (focused) 3.dp else 1.dp, if (focused) pal.focus else pal.line, sh).padding(horizontal = 18.dp, vertical = 14.dp)
    ) { content() }
}

@Composable
private fun WeatherBox(st: HomeState, pal: Pal, focused: Boolean, m: Modifier) {
    PanelBox(focused, pal, m) {
        val w = st.weather
        if (w == null) { T("Carregando o clima…", pal.dim, 13f); return@PanelBox }
        Row {
            Column(Modifier.width(150.dp)) {
                T(w.city, pal.dim, 12f)
                T("${w.icon} ${w.temp}°", pal.ink, 44f, FontWeight.Light)
                T(w.text, pal.ink, 13f)
                T("Máx. ${w.max}° Mín. ${w.min}°", pal.dim, 11f)
            }
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(96.dp).background(pal.line))
            Row(
                Modifier.padding(start = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                for (h in w.hours) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T(h.label, pal.dim, 10.5f)
                    T(h.icon, pal.ink, 13f, modifier = Modifier.padding(top = 4.dp))
                    T("${h.temp}°", pal.ink, 12.5f, FontWeight.Medium, Modifier.padding(top = 3.dp))
                }
                Column {
                    T("🌅 ${w.sunrise}", pal.dim, 11f)
                    Spacer(Modifier.height(6.dp))
                    T("🌇 ${w.sunset}", pal.dim, 11f)
                }
            }
        }
    }
}

@Composable
private fun QuotesBox(st: HomeState, pal: Pal, focused: Boolean, m: Modifier) {
    PanelBox(focused, pal, m) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                T("Cotações", pal.dim, 11f, modifier = Modifier.weight(1f))
                T("variação em 24 h", pal.dim, 11f)
            }
            if (st.quotes.isEmpty()) T("Carregando…", pal.dim, 12f, modifier = Modifier.padding(top = 8.dp))
            for (q in st.quotes) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                T(q.symbol, pal.dim, 12.5f, modifier = Modifier.width(26.dp))
                T(q.name, pal.ink, 12.5f, modifier = Modifier.weight(1f))
                T(q.value, pal.ink, 12.5f)
                val up = q.change >= 0
                Box(
                    Modifier.padding(start = 7.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (up) (if (pal.dark) Color(0x331F9D55) else Color(0xFFD9F5E3)) else (if (pal.dark) Color(0x33D6455D) else Color(0xFFFDE1E6)))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    T((if (up) "+" else "−") + String.format(PT, "%.1f%%", kotlin.math.abs(q.change)),
                        if (up) (if (pal.dark) Color(0xFF5BE38A) else Color(0xFF1F9D55)) else (if (pal.dark) Color(0xFFFF8A9C) else Color(0xFFD6455D)), 10f, FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ToastBox(st: HomeState) {
    var show by remember { mutableLongStateOf(0L) }
    LaunchedEffect(st.toastAt) { show = st.toastAt; delay(2200); if (show == st.toastAt) show = 0L }
    val a by animateFloatAsState(if (show != 0L) 1f else 0f, tween(250), label = "toast")
    if (a > 0.01f) Box(Modifier.fillMaxSize().padding(bottom = 22.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.graphicsLayer { alpha = a }.clip(RoundedCornerShape(11.dp)).background(Color(0xE0141620)).padding(horizontal = 17.dp, vertical = 9.dp)) {
            T(st.toast, Color.White, 13f)
        }
    }
}
