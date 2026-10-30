package com.astrolume.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.concurrent.thread

private val PTB = Locale("pt", "BR")
private fun f1(v: Float) = String.format(PTB, "%.1f", v)
private fun f2(v: Float) = String.format(PTB, "%.2f", v)

@Composable
fun Modal(st: HomeState, pal: Pal, mode: String) {
    val wide = mode == "perf" || mode == "all"
    Box(Modifier.fillMaxSize().background(Color(0x73000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(if (wide) 600.dp else 540.dp).clip(RoundedCornerShape(18.dp)).background(pal.modal).padding(21.dp)
        ) {
            val ink = if (pal.modal == Color.White) Color(0xFF14161C) else Color(0xFFF2F5FF)
            val dim = if (pal.modal == Color.White) Color(0xFF6B7080) else Color(0xFF9AA3C0)
            val opt = if (pal.modal == Color.White) Color(0xFFF1F3F8) else Color(0x14FFFFFF)
            val m = Mp(ink, dim, opt)
            when (mode) {
                "menu" -> {
                    Title("Ajustes do Início", "", m)
                    st.MENU.forEachIndexed { i, t -> Opt(t + "  ›", i == st.sel, m) }
                    Hint("▲▼ escolhe · OK abre · Voltar fecha", m)
                }
                "tema" -> ThemeList(st, m)
                "org" -> OrgList(st, m)
                "mais" -> AppGrid(st, st.hidden, 3, "Demais aplicativos", "Apps ocultos da tela inicial", pal, m)
                "all" -> AppGrid(st, st.apps, 4, "Todos os aplicativos", "${st.apps.size} instalados", pal, m)
                "hdmi" -> {
                    Title("Entradas HDMI", "Escolha a entrada", m)
                    if (st.ports.isEmpty()) T("Nenhuma entrada HDMI foi encontrada pelo sistema.", m.dim, 13f, modifier = Modifier.padding(top = 10.dp))
                    st.ports.forEachIndexed { i, p ->
                        OptRow(i == st.sel, m) {
                            T(p.name, m.ink, 14f, modifier = Modifier.weight(1f))
                            T(p.detail, if (p.connected) Color(0xFF4CD38A) else m.dim, 12f)
                        }
                    }
                    Hint("▲▼ escolhe · OK muda de entrada · Voltar fecha", m)
                }
                "update" -> UpdateView(st, m)
                "perf" -> PerfView(m)
                "pair" -> PairView(st, m)
            }
        }
    }
}

class Mp(val ink: Color, val dim: Color, val opt: Color)

@Composable
private fun Title(t: String, s: String, m: Mp) {
    T(t, m.ink, 18f, FontWeight.ExtraBold)
    if (s.isNotEmpty()) T(s, m.dim, 12f, modifier = Modifier.padding(top = 3.dp), lines = 2)
}

@Composable
private fun Hint(t: String, m: Mp) = T(t, m.dim, 11f, modifier = Modifier.padding(top = 14.dp))

@Composable
private fun Opt(t: String, f: Boolean, m: Mp) = OptRow(f, m) { T(t, m.ink, 14f) }

@Composable
private fun OptRow(f: Boolean, m: Mp, pad: Dp = 11.dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val sh = RoundedCornerShape(11.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 7.dp).clip(sh).background(m.opt)
            .border(if (f) 2.5.dp else 0.dp, if (f) Color(0xFF8B6CFF) else Color.Transparent, sh).padding(horizontal = 14.dp, vertical = pad),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
private fun ThemeList(st: HomeState, m: Mp) {
    Title("Tema da tela inicial", "A tela muda enquanto você navega · OK confirma", m)
    val ls = rememberLazyListState()
    LaunchedEffect(st.sel) { ls.animateScrollToItem((st.sel - 2).coerceAtLeast(0)) }
    LazyColumn(state = ls, modifier = Modifier.heightIn(max = 260.dp)) {
        itemsIndexed(THEMES) { i, (name, res) ->
            OptRow(i == st.sel, m, 6.dp) {
                Box(Modifier.size(54.dp, 30.dp).clip(RoundedCornerShape(6.dp))) {
                    when (i) {
                        0 -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White, Color(0xFFDFE5EF)))))
                        1 -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF241A55), Color(0xFF05060D)))))
                        else -> Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                T(name, m.ink, 14f, modifier = Modifier.padding(start = 12.dp).weight(1f))
                if (i == st.theme) T("✓", Color(0xFF8B6CFF), 15f, FontWeight.Bold)
            }
        }
    }
    Hint("▲▼ escolhe · OK confirma · Voltar volta", m)
}

@Composable
private fun OrgList(st: HomeState, m: Mp) {
    Title("Organizar aplicativos", "Na tela inicial aparecem só os marcados; os ocultos ficam no +", m)
    val ls = rememberLazyListState()
    LaunchedEffect(st.sel) { ls.animateScrollToItem((st.sel - 2).coerceAtLeast(0)) }
    LazyColumn(state = ls, modifier = Modifier.heightIn(max = 270.dp)) {
        itemsIndexed(st.apps, key = { _, a -> a.pkg }) { i, a ->
            val f = i == st.sel
            OptRow(f, m, 7.dp) {
                Box(Modifier.size(42.dp, 24.dp).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(a.c1, a.c2)))) {
                    (a.banner ?: a.icon)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                T((if (f && st.moving) "↕ " else "") + a.label, m.ink, 14f, modifier = Modifier.padding(start = 12.dp).weight(1f))
                T(if (a.visible) "na tela inicial ✓" else "oculto (+)", if (a.visible) Color(0xFF4CD38A) else m.dim, 12f)
            }
        }
    }
    Hint(if (st.moving) "MOVENDO: ▲▼ muda a posição · OK solta" else "▲▼ escolhe · OK mostra/oculta · ▶ segura para mover · Voltar salva", m)
}

@Composable
private fun AppGrid(st: HomeState, list: List<AppEntry>, cols: Int, title: String, sub: String, pal: Pal, m: Mp) {
    Title(title, sub, m)
    if (list.isEmpty()) { T("Nenhum app oculto.", m.dim, 13f, modifier = Modifier.padding(top = 12.dp)); return }
    val rows = list.chunked(cols)
    val ls = rememberLazyListState()
    LaunchedEffect(st.sel) { ls.animateScrollToItem((st.sel / cols - 1).coerceAtLeast(0)) }
    val w = if (cols == 3) 150.dp else 126.dp
    LazyColumn(state = ls, modifier = Modifier.heightIn(max = 290.dp).padding(top = 6.dp)) {
        itemsIndexed(rows) { r, row ->
            Row(Modifier.padding(vertical = 7.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { c, a -> AppTile(a, st.sel == r * cols + c, pal, w, w * 9 / 16) }
            }
        }
    }
    Hint("◀▶▲▼ escolhe · OK abre · Voltar fecha", m)
}

@Composable
private fun UpdateView(st: HomeState, m: Mp) {
    val c = LocalContext.current
    Title("Atualizar o AstroLume Home", "Versão instalada: ${Updater.current(c)}", m)
    val t = when {
        st.upProgress in 0..99 -> "Baixando… ${st.upProgress}%"
        st.upProgress == 100 -> "Pronto! Confirme a instalação na tela que abriu."
        st.upProgress == -1 -> "Não consegui baixar. Confira a internet e tente de novo."
        st.upStatus == "checking" -> "Procurando a versão mais nova…"
        st.upStatus == "new" -> "Versão ${st.upRemote} disponível. Aperte OK para baixar e instalar."
        st.upStatus == "same" -> "Você já está na versão mais nova."
        else -> "Não consegui consultar agora. Confira a internet."
    }
    T(t, m.ink, 14f, modifier = Modifier.padding(top = 14.dp), lines = 3)
    Hint("Voltar fecha", m)
}

// ---------------- análise de desempenho ----------------

private fun barColor(p: Float, warn: Float, crit: Float) = when {
    p >= crit -> Color(0xFFFF5A6E); p >= warn -> Color(0xFFFFB84D); else -> Color(0xFF4CD38A)
}

@Composable
private fun Bar(label: String, value: Float, max: Float, txt: String, warn: Float, crit: Float, sub: String?, m: Mp) {
    val p = if (max > 0) (value / max * 100f).coerceIn(0f, 100f) else 0f
    Column(Modifier.padding(top = 9.dp)) {
        Row(Modifier.fillMaxWidth()) {
            T(label, m.ink, 12.5f, FontWeight.Bold, Modifier.weight(1f))
            T(txt, m.ink, 12.5f)
        }
        Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x40808080))) {
            Box(Modifier.fillMaxWidth(p / 100f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(barColor(p, warn, crit)))
        }
        if (sub != null) T(sub, m.dim, 10f, modifier = Modifier.padding(top = 3.dp), lines = 2)
    }
}

@Composable
private fun PerfView(m: Mp) {
    val s = Perf.snap
    Title("Análise de desempenho", "TCL A400M · Realtek · GPU PowerVR · Android 14 · atualiza a cada segundo", m)
    val (dot, msg) = when {
        s.connected -> Color(0xFF4CD38A) to "Conectado pela depuração sem fio · dados reais da TV"
        s.status == "conectando" -> Color(0xFFFFB84D) to "Conectando à depuração sem fio…"
        s.status == "wifi_off" -> Color(0xFFFF5A6E) to "Reative a depuração sem fio (Opções do desenvolvedor) · CPU, temperatura e GPU indisponíveis"
        s.status == "pair" -> Color(0xFFFFB84D) to "Falta parear este app com a TV · aperte OK para parear"
        else -> Color(0xFFFF5A6E) to "Sem conexão com a depuração sem fio · aperte OK para parear de novo"
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        T(msg, dot, 10.5f, modifier = Modifier.padding(start = 6.dp), lines = 2)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            val cpu = s.cpu
            Bar("CPU (${s.cores.size.takeIf { it > 0 } ?: 4} núcleos)", cpu ?: 0f, 100f, cpu?.let { "${it.toInt()}%" } ?: "—", 60f, 85f, if (cpu == null) "precisa da depuração sem fio" else null, m)
            if (s.cores.isNotEmpty()) Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                s.cores.forEachIndexed { i, v ->
                    Column(Modifier.weight(1f)) {
                        T("núcleo ${i + 1}", m.dim, 9.5f)
                        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(26.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x40808080)), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight((v / 100f).coerceIn(0.02f, 1f)).background(barColor(v, 60f, 85f)))
                        }
                        T("${v.toInt()}%", m.ink, 9.5f, modifier = Modifier.padding(top = 2.dp))
                        s.freqMhz.getOrNull(i)?.let { T("${f2(it / 1000f)} GHz", m.dim, 9.5f) }
                    }
                }
            }
            val g = s.gpuMemMb
            Bar("GPU · memória de vídeo", g ?: 0f, 1024f, g?.let { "${it.toInt()} MB" } ?: "—", 60f, 85f,
                when {
                    s.gpuDriver != null -> "driver PowerVR ${s.gpuDriver} · ${s.gpuErrors ?: 0} erros · % de uso não liberado pela TV"
                    else -> "% de uso não liberado pela TV"
                }, m)
        }
        Column(Modifier.weight(1f)) {
            val tc = s.tempC
            val stTxt = when {
                tc == null -> listOf("normal", "leve", "moderada", "alta", "grave", "crítica", "desligando").getOrElse(s.thermal) { "—" } + " (situação térmica do Android)"
                tc < 60 -> "normal · sensor único da TV (${s.tempName})"
                tc < 70 -> "morna · sensor único da TV (${s.tempName})"
                else -> "quente · sensor único da TV (${s.tempName})"
            }
            Bar("Temperatura do chip (CPU + GPU)", tc ?: 0f, 90f, tc?.let { "${it.toInt()} °C" } ?: "—", 66f * 100 / 90, 78f * 100 / 90, stTxt, m)
            if (s.top.isNotEmpty()) {
                T("Quem mais usa a CPU", m.ink, 12.5f, FontWeight.Bold, Modifier.padding(top = 10.dp))
                for ((n, v) in s.top) Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    T(n, m.dim, 11f, modifier = Modifier.weight(1f))
                    T("${f1(v)}%", m.dim, 11f)
                }
            }
            Bar("Memória RAM", s.ramUsed, s.ramTotal, "${f2(s.ramUsed)} de ${f2(s.ramTotal)} GB", 75f, 90f, "livre: ${f2(s.ramTotal - s.ramUsed)} GB", m)
        }
    }
    Bar("Armazenamento", s.stoUsed, s.stoTotal, "${f1(s.stoUsed)} de ${s.stoTotal.toInt()} GB", 80f, 92f, "apps, dados e cache · livre: ${f1(s.stoTotal - s.stoUsed)} GB", m)
    if (s.hist.size > 1) {
        T("Uso da CPU no último minuto", m.dim, 10f, modifier = Modifier.padding(top = 8.dp))
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val n = 60
            val dx = size.width / (n - 1)
            val pts = s.hist.takeLast(n)
            val off = n - pts.size
            for (i in 1 until pts.size) drawLine(
                Color(0xFF8B6CFF), Offset((off + i - 1) * dx, size.height * (1 - pts[i - 1] / 100f)), Offset((off + i) * dx, size.height * (1 - pts[i] / 100f)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
    Hint(if (s.connected) "Voltar fecha" else "OK pareia com a TV · Voltar fecha", m)
}

// ---------------- pareamento ----------------

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, m: Mp, fr: FocusRequester, numeric: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 10.dp)) {
        T(label, m.dim, 11.5f)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = m.ink, fontSize = 17.sp, fontFamily = Inter),
            cursorBrush = SolidColor(Color(0xFF8B6CFF)),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri),
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().focusRequester(fr).onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(10.dp)).background(m.opt)
                .border(if (focused) 2.5.dp else 1.dp, if (focused) Color(0xFF8B6CFF) else Color(0x33808080), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun PairView(st: HomeState, m: Mp) {
    val c = LocalContext.current
    var addr by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val f1r = remember { FocusRequester() }
    val f2r = remember { FocusRequester() }
    val btn = remember { FocusRequester() }
    var btnFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { f1r.requestFocus() } catch (e: Throwable) {} }
    Title("Parear com a TV", "Uma vez só. Depois o AstroLume Home conecta sozinho.", m)
    T(
        "1. Abra Configurações → Sistema → Opções do desenvolvedor → Depuração sem fio.\n" +
            "2. Escolha \"Parear o dispositivo com um código de pareamento\".\n" +
            "3. Digite aqui o IP:porta e o código de 6 dígitos que aparecem.",
        m.ink, 12f, modifier = Modifier.padding(top = 10.dp), lines = 4,
    )
    Field("IP e porta (ex.: 192.168.0.104:41211)", addr, { addr = it.trim() }, m, f1r, false)
    Field("Código de pareamento (6 dígitos)", code, { code = it.filter { ch -> ch.isDigit() }.take(6) }, m, f2r, true)
    Spacer(Modifier.height(12.dp))
    val doPair: () -> Unit = {
        val host = addr.substringBeforeLast(':', "127.0.0.1").ifBlank { "127.0.0.1" }
        val port = addr.substringAfterLast(':').toIntOrNull()
        when {
            port == null || code.length != 6 -> st.pairMsg = "Confira o IP:porta e os 6 dígitos."
            st.pairBusy -> {}
            else -> {
                st.pairBusy = true
                st.pairMsg = "Pareando…"
                val cd = code
                thread {
                    val ok = Adb.pair(c, host, port ?: 0, cd) && Adb.connect(c)
                    st.pairBusy = false
                    st.pairMsg = if (ok) "Pareado! Voltando para a análise…" else "Não pareou (${Adb.lastError.take(80)}). Gere um código novo e tente de novo."
                    if (ok) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ st.open("perf"); Perf.start(c) }, 1200)
                }
            }
        }
    }
    Box(
        Modifier.focusRequester(btn).onFocusChanged { btnFocus = it.isFocused }.focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { doPair(); true } else false
            }
            .clip(RoundedCornerShape(10.dp)).background(if (btnFocus) Color(0xFF8B6CFF) else m.opt)
            .padding(horizontal = 22.dp, vertical = 9.dp)
    ) { T(if (st.pairBusy) "Pareando…" else "Parear", if (btnFocus) Color.White else m.ink, 14f, FontWeight.Bold) }
    if (st.pairMsg.isNotEmpty()) T(st.pairMsg, m.dim, 11.5f, modifier = Modifier.padding(top = 8.dp), lines = 2)
    Hint("▲▼ muda o campo · OK digita · Voltar cancela", m)
}
