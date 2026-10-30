package com.auroratv.tv

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import android.graphics.Color as AColor

/** Última tecla apertada (ms). Enquanto o usuário navega, o fundo fica parado e a GPU fica toda para os menus. */
@Volatile
var navAt = 0L

/** Relógio de quadros: cada leitura de .value dentro de um desenho redesenha a cada frame. */
/** Relógio único das animações (fundo, destaques, moedas): 30 quadros/s e parado enquanto você navega. */
object Clock {
    val tick = mutableStateOf(0L)

    /** Segundos, para usar dentro de graphicsLayer/drawBehind (só redesenha, não recompõe). */
    fun sec(): Float = (tick.value / 1_000_000L).toFloat() / 1000f

    /** Vai e volta suave entre a e b no período dado (s). */
    fun wave(period: Float, a: Float, b: Float): Float = a + (b - a) * (0.5f - 0.5f * cos(sec() * 2f * PI.toFloat() / period))

    /** 0..1 repetindo no período dado (s). */
    fun loop(period: Float): Float = (sec() % period) / period
}

@Composable
fun frameClock(active: Boolean): State<Long> {
    val s = Clock.tick
    LaunchedEffect(active) {
        if (active) {
            // 30 quadros por segundo bastam para o fundo; sobra processamento para a navegação
            var last = 0L
            while (true) {
                val parado = android.os.SystemClock.uptimeMillis() - navAt
                // navegando: fundo congelado até 0,9 s depois da última tecla
                if (parado < 900L) {
                    kotlinx.coroutines.delay(120)
                    continue
                }
                // um minuto sem ninguém mexer: o fundo descansa. Sem isso a TV fica
                // redesenhando a tela inicial por horas, gastando GPU e esquentando à toa.
                if (parado > 60_000L) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                androidx.compose.runtime.withFrameNanos {
                    if (it - last >= 32_000_000L) {
                        last = it
                        s.value = it
                    }
                }
            }
        }
    }
    return s
}

// ---------------------------------------------------------------- meteoros

class Met(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val len: Float, val f: Float)

class MeteorEngine {
    val list = ArrayList<Met>()
    private var next = 1500f
    private val rnd = java.util.Random()
    private var lastNanos = 0L

    fun spawn(kind: String) {
        val f = 0.45f + rnd.nextFloat() * 1.25f
        val m: Met
        if (kind == "enter") {
            m = Met(0.95f, 0.07f, -0.0105f * f, 0.0022f * f, 1f, 0.24f, f)
        } else {
            val r = rnd.nextFloat()
            m = when {
                r < 0.4f -> Met(0.4f + rnd.nextFloat() * 0.6f, rnd.nextFloat() * 0.3f, (-0.0085f - rnd.nextFloat() * 0.005f) * f, (0.004f + rnd.nextFloat() * 0.003f) * f, 1f, 0.2f, f)
                r < 0.8f -> Met(rnd.nextFloat() * 0.5f, rnd.nextFloat() * 0.3f, (0.0085f + rnd.nextFloat() * 0.005f) * f, (0.004f + rnd.nextFloat() * 0.003f) * f, 1f, 0.2f, f)
                else -> Met(0.3f + rnd.nextFloat() * 0.6f, -0.02f, (rnd.nextFloat() - 0.5f) * 0.002f * f, (0.0095f + rnd.nextFloat() * 0.003f) * f, 1f, 0.22f, f)
            }
        }
        list.add(m)
    }

    fun update(nowNanos: Long, ambient: Boolean) {
        if (lastNanos == 0L) {
            lastNanos = nowNanos
            return
        }
        val dt = ((nowNanos - lastNanos) / 1_000_000f).coerceIn(0f, 50f)
        lastNanos = nowNanos
        if (ambient) {
            next -= dt
            if (next < 0f) {
                spawn("")
                next = 2500f + rnd.nextFloat() * 4500f
            }
        }
        val it = list.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.x += m.vx * dt / 16f
            m.y += m.vy * dt / 16f
            m.life -= dt / (1500f / m.f)
            if (m.life <= 0f) it.remove()
        }
    }
}

private fun DrawScope.drawMeteor(m: Met) {
    val sp = hypot(m.vx, m.vy)
    if (sp == 0f) return
    val ux = m.vx / sp
    val uy = m.vy / sp
    val u = size.width / 1280f
    val len = m.len * max(size.width, size.height * 1.78f)
    val hx = m.x * size.width
    val hy = m.y * size.height
    val tx = hx - ux * len
    val ty = hy - uy * len
    val al = min(1f, m.life * 1.4f)
    val head = Offset(hx, hy)
    val tail = Offset(tx, ty)
    drawLine(
        Brush.linearGradient(
            listOf(Color(0xFF5AAAFF).al(0.55f * al), Color(0xFF286EFF).al(0.22f * al), Color(0x00146EFF)),
            start = head, end = tail
        ),
        head, tail, strokeWidth = 16f * u, cap = StrokeCap.Round
    )
    drawLine(
        Brush.linearGradient(
            listOf(Color.White.al(al), Color(0xFF9ACDFF).al(0.7f * al), Color(0x005096FF)),
            start = head, end = tail
        ),
        head, Offset(hx - ux * len * 0.9f, hy - uy * len * 0.9f), strokeWidth = 3.6f * u, cap = StrokeCap.Round
    )
    val rnd = java.util.Random((m.life * 1000f).toInt().toLong())
    for (j in 0 until 3) {
        val f = 0.15f + rnd.nextFloat() * 0.5f
        drawCircle(
            Color(0xFF78BEFF).al(0.5f * al * rnd.nextFloat()),
            (1.2f + rnd.nextFloat() * 1.8f) * u,
            Offset(hx - ux * len * f + (rnd.nextFloat() - 0.5f) * 9f * u, hy - uy * len * f + (rnd.nextFloat() - 0.5f) * 9f * u)
        )
    }
    drawCircle(
        Brush.radialGradient(listOf(Color.White.al(al), Color(0xFF96CDFF).al(0.5f * al), Color(0x006EB4FF)), center = head, radius = 17f * u),
        17f * u, head
    )
}

/** Camada de meteoros desenhada por cima da interface. */
@Composable
fun MeteorLayer(tick: State<Long>, engine: MeteorEngine, ambient: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        engine.update(tick.value, ambient)
        for (m in engine.list) drawMeteor(m)
    }
}

// ---------------------------------------------------------------- fundo

private class StarData {
    val rnd = java.util.Random(7)
    val n = 110
    val sx = FloatArray(n) { rnd.nextFloat() }
    val sy = FloatArray(n) { rnd.nextFloat() }
    val sr = FloatArray(n) { 0.4f + rnd.nextFloat() * 1.3f }
    val sp = FloatArray(n) { rnd.nextFloat() * 6.28f }
    val ss = FloatArray(n) { 0.6f + rnd.nextFloat() * 2f }
    val big = BooleanArray(n) { rnd.nextFloat() < 0.08f }
    val dn = 70
    val dx = FloatArray(dn) { rnd.nextFloat() * 0.3f }
    val dy = FloatArray(dn) { rnd.nextFloat() }
    val dr = FloatArray(dn) { 0.3f + rnd.nextFloat() * 1.1f }
    val dvx = FloatArray(dn) { 0.00004f + rnd.nextFloat() * 0.00012f }
    val dvy = FloatArray(dn) { -0.00003f - rnd.nextFloat() * 0.00008f }
    val da = FloatArray(dn) { 0.2f + rnd.nextFloat() * 0.6f }
    val dp = FloatArray(dn) { rnd.nextFloat() * 6.28f }
    val pn = 14
    val px = FloatArray(pn) { 0.3f + rnd.nextFloat() * 0.68f }
    val py = FloatArray(pn) { 0.05f + rnd.nextFloat() * 0.9f }
    val pe = FloatArray(pn) { 2.4f + rnd.nextFloat() * 3.2f }
    val ph = FloatArray(pn) { rnd.nextFloat() }
    val pc = Array(pn) {
        when (it % 3) {
            0 -> Color(0xFFAAD2FF)
            1 -> Color(0xFFFFDCAF)
            else -> Color(0xFFEBF0FF)
        }
    }
    var last = 0L

    // poeira do deserto e brilhos do mar
    val qn = 140
    val qx = FloatArray(qn) { rnd.nextFloat() }
    val qy = FloatArray(qn) { 0.25f + rnd.nextFloat() * 0.75f }
    val qv = FloatArray(qn) { 0.00002f + rnd.nextFloat() * 0.00009f }
    val qr = FloatArray(qn) { 0.4f + rnd.nextFloat() * 1.8f }
    val qa = FloatArray(qn) { 0.15f + rnd.nextFloat() * 0.5f }
    val qp = FloatArray(qn) { rnd.nextFloat() * 6.28f }
}

private class Orbit(val r: Float, val period: Float, val size: Float, val c1: Color, val c2: Color, val phase: Float)

private val ORBITS = listOf(
    Orbit(150f, 16f, 8f, Color(0xFFB8D8FF), Color(0xFF3A5A9A), 0.3f),
    Orbit(235f, 27f, 13f, Color(0xFFFFC8A0), Color(0xFF9A3A22), 2.1f),
    Orbit(330f, 41f, 11f, Color(0xFFA8F0E8), Color(0xFF1F6A70), 4.0f),
    Orbit(445f, 63f, 21f, Color(0xFFF2DDB8), Color(0xFF8A6440), 1.2f),
    Orbit(560f, 95f, 9f, Color(0xFFDDE8FF), Color(0xFF5A6A9A), 5.1f)
)

/** Planetas girando em volta do sol do tema Órbitas, em perspectiva. */
private fun DrawScope.orbits(t: Float, u: Float) {
    val cx = size.width * 0.70f
    val cy = size.height * 0.50f
    val tilt = 0.32f
    for (o in ORBITS) {
        val rx = o.r * u
        val ry = rx * tilt
        drawOval(Color.White.copy(alpha = 0.10f), Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), style = Stroke(width = 1f * u))
    }
    for (o in ORBITS) {
        val a = t / o.period * 2f * PI.toFloat() + o.phase
        val rx = o.r * u
        val x = cx + rx * cos(a)
        val y = cy + rx * tilt * sin(a)
        val behind = sin(a) < 0f
        val near = kotlin.math.abs(x - cx) < 70f * u
        val alpha = if (behind && near) 0.15f else if (behind) 0.75f else 1f
        val r = o.size * u * (if (behind) 0.9f else 1.05f)
        // luz vinda do sol
        val dx = cx - x
        val dy = cy - y
        val dl = max(1f, hypot(dx, dy))
        val hl = Offset(x + dx / dl * r * 0.45f, y + dy / dl * r * 0.45f)
        drawCircle(o.c1.copy(alpha = 0.18f * alpha), r * 2.2f, Offset(x, y))
        drawCircle(
            Brush.radialGradient(listOf(o.c1.copy(alpha = alpha), o.c2.copy(alpha = alpha), Color(0xFF05060C).copy(alpha = alpha)), center = hl, radius = r * 1.6f),
            r, Offset(x, y)
        )
    }
}


/** Poeira em 3 camadas de profundidade: longe (fina e lenta), meio, perto (grande, rápida e desfocada). */
private fun DrawScope.dustLayers(t: Float, d: StarData, col: Color, wind: Float, yMin: Float, yMax: Float, alpha: Float, u: Float) {
    val w = size.width
    val h = size.height
    val n = d.qn
    for (i in 0 until n) {
        val layer = i % 3
        val sp = floatArrayOf(0.25f, 0.6f, 1.6f)[layer]
        val x = (((d.qx[i] + t * wind * sp * (0.6f + d.qv[i] * 6000f)) % 1f) + 1f) % 1f
        val y = yMin + d.qy[i].coerceIn(0f, 1f) * (yMax - yMin) + 0.012f * sin(t * (0.5f + sp) + d.qp[i])
        val a = alpha * d.qa[i] * (0.65f + 0.35f * sin(t * 1.3f + d.qp[i]))
        val c = Offset(x * w, y * h)
        when (layer) {
            0 -> drawCircle(col.al(a * 0.8f), (0.5f + d.qr[i] * 0.35f) * u, c)
            1 -> drawCircle(col.al(a), (1.0f + d.qr[i] * 0.7f) * u, c)
            else -> {
                // perto: grão maior e macio (dois círculos, sem degradê, bem mais leve)
                val r = (2.6f + d.qr[i] * 2.2f) * u
                drawCircle(col.al(a * 0.18f), r, c)
                drawCircle(col.al(a * 0.35f), r * 0.5f, c)
            }
        }
    }
}

/** Véu de poeira/névoa alongado (elipse macia) que atravessa a tela com o vento. */
private fun DrawScope.veil(cx: Float, cy: Float, rx: Float, ry: Float, col: Color, a: Float) {
    if (a <= 0.003f) return
    withTransform({ scale(rx / ry, 1f, Offset(cx, cy)) }) {
        drawCircle(Brush.radialGradient(listOf(col.al(a), col.al(a * 0.45f), col.al(0f)), center = Offset(cx, cy), radius = ry), ry, Offset(cx, cy))
    }
}

private fun smooth(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/** Planeta gigante com anéis, desenhado na resolução da TV (nítido em 4K), girando devagar, com uma lua em órbita. */
private fun DrawScope.planet(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w * 0.69f
    val cy = h * 0.47f
    val r = h * 0.30f
    val tilt = -14f
    val ringBack = Color(0xFFCDB89A)
    // lua: atrás do planeta na metade da órbita
    val ma = t * 0.045f
    val mx = cx + cos(ma) * r * 2.5f
    val my = cy + sin(ma) * r * 0.42f - r * 0.15f
    val moonBehind = sin(ma) < 0f
    fun moon() {
        val mr = r * 0.085f
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFE8E4DC), Color(0xFF8E8A84), Color(0xFF25242A)), center = Offset(mx - mr * 0.35f, my - mr * 0.35f), radius = mr * 1.4f),
            mr, Offset(mx, my)
        )
    }
    if (moonBehind) moon()
    // anéis: metade de trás
    withTransform({ rotate(tilt, Offset(cx, cy)) }) {
        for (k in 0 until 7) {
            val rr = r * (1.42f + k * 0.11f)
            val a = floatArrayOf(0.35f, 0.55f, 0.18f, 0.6f, 0.45f, 0.12f, 0.3f)[k]
            drawArc(ringBack.al(a * 0.75f), 180f, 180f, false, Offset(cx - rr, cy - rr * 0.22f), Size(rr * 2, rr * 0.44f), style = Stroke(width = r * 0.075f))
        }
    }
    // corpo do planeta: faixas de nuvens que deslizam (rotação) + tempestade oval
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r)) }
    clipPath(disc) {
        val bands = arrayOf(
            Color(0xFFE9D6B4), Color(0xFFCFAE82), Color(0xFFE2C79E), Color(0xFFB98E62), Color(0xFFDCC09A),
            Color(0xFFC49C70), Color(0xFFEAD9BC), Color(0xFFAF8458), Color(0xFFD8BA90), Color(0xFFC8A47A)
        )
        val nb = 34
        val bh = 2f * r / nb
        for (k in 0 until nb) {
            val y = cy - r + k * bh
            val col = bands[(k * 7 + k / 3) % bands.size]
            drawRect(col, Offset(cx - r, y), Size(2f * r, bh + 1.5f))
        }
        val sxp = cx - r + (((t * 0.012f) % 1f) * 2.6f - 0.3f) * r
        withTransform({ scale(1.9f, 1f, Offset(sxp, cy + r * 0.28f)) }) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFB8653A), Color(0xFFD08A5A), Color(0x00D08A5A)), center = Offset(sxp, cy + r * 0.28f), radius = r * 0.09f),
                r * 0.09f, Offset(sxp, cy + r * 0.28f)
            )
        }
        // sombra dos anéis sobre o planeta
        withTransform({ rotate(tilt, Offset(cx, cy)) }) {
            drawOval(Color(0x66000000), Offset(cx - r * 1.6f, cy + r * 0.07f), Size(r * 3.2f, r * 0.13f))
        }
        // luz vinda da esquerda-alto e lado da noite
        drawCircle(
            Brush.radialGradient(listOf(Color(0x00000000), Color(0x22000000), Color(0xCC000000)), center = Offset(cx - r * 0.45f, cy - r * 0.4f), radius = r * 1.9f),
            r * 1.05f, Offset(cx, cy)
        )
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xD9000000)), startX = cx + r * 0.15f, endX = cx + r), Offset(cx - r, cy - r), Size(2f * r, 2f * r))
    }
    // atmosfera na borda iluminada
    drawCircle(Color(0x33FFF1D8), r * 1.01f, Offset(cx, cy), style = Stroke(width = r * 0.025f))
    // anéis: metade da frente
    withTransform({ rotate(tilt, Offset(cx, cy)) }) {
        for (k in 0 until 7) {
            val rr = r * (1.42f + k * 0.11f)
            val a = floatArrayOf(0.35f, 0.55f, 0.18f, 0.6f, 0.45f, 0.12f, 0.3f)[k]
            drawArc(ringBack.al(a), 0f, 180f, false, Offset(cx - rr, cy - rr * 0.22f), Size(rr * 2, rr * 0.44f), style = Stroke(width = r * 0.075f))
        }
    }
    if (!moonBehind) moon()
}

/** Efeitos das cenas fotográficas (temas 5 a 10), presos aos pontos da própria imagem, com profundidade. */
private fun DrawScope.scene(theme: Int, t: Float, u: Float, d: StarData) {
    val w = size.width
    val h = size.height
    // a imagem (1408 x 768) é cortada para preencher a tela: converte um ponto dela para a tela
    val s = max(w / 1408f, h / 768f)
    val ox = (1408f * s - w) / 2f
    val oy = (768f * s - h) / 2f
    fun im(x: Float, y: Float) = Offset(x * s - ox, y * s - oy)
    fun glow(c: Offset, r: Float, col: Color, a: Float) {
        drawCircle(Brush.radialGradient(listOf(col.al(a), col.al(0f)), center = c, radius = r), r, c)
    }
    when (theme) {
        5 -> {
            // Plutão: sol distante cintilando, névoa rasteira e cristais de gelo em profundidade
            val sun = im(1140f, 124f)
            val p = 0.5f + 0.5f * sin(t * 1.3f)
            glow(sun, (40f + 14f * p) * s, Color(0xFFE8F2FF), 0.5f)
            for (k in 0 until 3) {
                val x = ((t * (0.010f + k * 0.004f) + k * 0.37f) % 1.4f - 0.2f) * w
                veil(x, h * (0.34f + k * 0.03f), 420f * s, 40f * s, Color(0xFFD7E3F2), 0.10f)
            }
            dustLayers(t, d, Color(0xFFEAF4FF), -0.004f, 0.05f, 1f, 0.7f, u)
        }
        6 -> {
            // cratera: sol intenso pulsando, raio de luz e poeira suspensa contra o sol
            val sun = im(1105f, 152f)
            val p = 0.5f + 0.5f * sin(t * 0.9f)
            glow(sun, (230f + 40f * p) * s, Color(0xFFFFF4DC), 0.28f + 0.1f * p)
            glow(sun, (110f + 10f * p) * s, Color.White, 0.32f)
            drawLine(Color(0xFFFFF4DC).al(0.14f + 0.08f * p), Offset(sun.x - 320f * s, sun.y), Offset(sun.x + 320f * s, sun.y), strokeWidth = 2.5f * s)
            dustLayers(t, d, Color(0xFFF2EBDD), 0.003f, 0.25f, 1f, 0.55f, u)
        }
        7 -> {
            // Marte: véus de poeira atravessando com o vento, rajadas e areia soprando rente ao chão
            val sun = im(1103f, 196f)
            val gust = smooth(sin(t * 0.45f) * 1.4f - 0.4f)
            glow(sun, (120f + 20f * gust) * s, Color(0xFFFFE8DC), 0.32f * (1f - 0.5f * gust))
            for (k in 0 until 6) {
                val sp = 0.010f + k * 0.0035f
                val x = (1.3f - ((t * sp + k * 0.23f) % 1.6f)) * w
                val y = h * (0.30f + k * 0.11f)
                veil(x, y, (260f + k * 60f) * s, (36f + k * 10f) * s, Color(0xFFD9A58C), 0.07f + 0.10f * gust + k * 0.008f)
            }
            drawRect(Color(0xFFC98E74).al(0.04f + 0.10f * gust))
            // areia soprando: grãos com rastro, rente ao chão
            for (i in 0 until d.qn / 2) {
                val sp = 0.06f + d.qv[i] * 900f
                val x = (((d.qx[i] - t * sp) % 1f) + 1f) % 1f
                val y = 0.72f + d.qy[i] * 0.28f
                val a = d.qa[i] * (0.5f + 0.5f * gust)
                val c = Offset(x * w, y * h)
                drawLine(Brush.horizontalGradient(listOf(Color(0xFFE6B79E).al(a), Color(0x00E6B79E)), startX = c.x, endX = c.x + 40f * u), c, Offset(c.x + 40f * u, c.y), strokeWidth = (0.8f + d.qr[i] * 0.5f) * u)
            }
            dustLayers(t, d, Color(0xFFE2B49E), -0.010f, 0.30f, 1f, 0.55f + 0.3f * gust, u)
        }
        8 -> {
            // Júpiter: raios ramificados, clarão nas nuvens, névoa e poeira dourada
            for (k in 0 until 3) {
                val x = ((t * (0.008f + k * 0.003f) + k * 0.41f) % 1.5f - 0.25f) * w
                veil(x, h * (0.50f + k * 0.04f), 380f * s, 32f * s, Color(0xFFD9B98A), 0.09f)
            }
            for (bolt in 0 until 2) {
                val period = if (bolt == 0) 1.9f else 3.3f
                val slot = kotlin.math.floor((t + bolt * 0.7f) / period).toInt()
                val within = (t + bolt * 0.7f) - slot * period
                val rr = java.util.Random(slot * 7919L + bolt * 104729L)
                if (rr.nextFloat() < 0.85f && within < 0.55f) {
                    val flick = if (within < 0.07f || (within > 0.14f && within < 0.22f) || (within > 0.30f && within < 0.34f)) 1f else 0.3f
                    val fade = (1f - within / 0.55f) * flick
                    val x0 = (0.08f + rr.nextFloat() * 0.84f) * w
                    // clarão nas nuvens de cima
                    glow(Offset(x0 + (rr.nextFloat() - 0.5f) * 200f * s, h * 0.28f), 420f * s, Color(0xFFFFE6C2), 0.28f * fade)
                    drawRect(Color(0xFFFFE9C8).al(0.07f * fade))
                    fun branch(sx: Float, sy: Float, segs: Int, width: Float, depth: Int) {
                        var x = sx
                        var y = sy
                        for (k2 in 0 until segs) {
                            val nx = x + (rr.nextFloat() - 0.5f) * 52f * s
                            val ny = y + (16f + rr.nextFloat() * 22f) * s
                            drawLine(Color(0xFFFFD9A0).al(0.30f * fade), Offset(x, y), Offset(nx, ny), strokeWidth = width * 4f, cap = StrokeCap.Round)
                            drawLine(Color(0xFFFFF8EE).al(0.95f * fade), Offset(x, y), Offset(nx, ny), strokeWidth = width, cap = StrokeCap.Round)
                            if (depth < 2 && rr.nextFloat() < 0.28f) branch(nx, ny, 3 + rr.nextInt(3), width * 0.55f, depth + 1)
                            x = nx
                            y = ny
                        }
                        glow(Offset(x, y), 90f * s, Color(0xFFFFF0D8), 0.4f * fade)
                    }
                    branch(x0, h * (0.36f + rr.nextFloat() * 0.08f), 7 + rr.nextInt(5), 2.4f * s, 0)
                }
            }
            dustLayers(t, d, Color(0xFFE8C48E), 0.008f, 0.2f, 0.9f, 0.45f, u)
        }
        9 -> {
            // Vênus vulcânico: névoa de enxofre em faixas, lava escorrendo nos rios, brasas e poeira em profundidade
            val path = listOf(im(0f, 562f), im(170f, 520f), im(330f, 500f), im(520f, 452f), im(640f, 470f), im(760f, 455f), im(930f, 418f), im(1010f, 390f))
            for ((k, c) in path.withIndex()) {
                val p = 0.5f + 0.5f * sin(t * (0.9f + k * 0.13f) + k)
                glow(c, (70f + 30f * p) * s, Color(0xFFFF8A2A), 0.14f + 0.10f * p)
            }
            // brilho andando ao longo do rio de lava
            for (k in 0 until 9) {
                val f = ((t * 0.035f + k / 9f) % 1f) * (path.size - 1)
                val i0 = f.toInt().coerceIn(0, path.size - 2)
                val fr = f - i0
                val a0 = path[i0]
                val a1 = path[i0 + 1]
                val c = Offset(a0.x + (a1.x - a0.x) * fr, a0.y + (a1.y - a0.y) * fr)
                glow(c, 26f * s, Color(0xFFFFC870), 0.45f)
            }
            for (k in 0 until 4) {
                val x = ((t * (0.006f + k * 0.0025f) + k * 0.29f) % 1.5f - 0.25f) * w
                veil(x, h * (0.18f + k * 0.12f), 460f * s, (46f + k * 8f) * s, Color(0xFFD6C98C), 0.09f)
            }
            for (i in 0 until d.qn / 2) {
                val life = ((t * (0.04f + d.qv[i] * 250f) + d.qp[i]) % 1f)
                val x = d.qx[i] * w + sin(t * 1.2f + d.qp[i]) * 10f * u
                val y = h * (0.78f - life * 0.55f)
                drawCircle(Color(0xFFFFB050).al(d.qa[i] * (1f - life) * 0.8f), d.qr[i] * 0.8f * u, Offset(x, y))
            }
            dustLayers(t, d, Color(0xFFCDB58A), 0.004f, 0.1f, 1f, 0.45f, u)
        }
        10 -> {
            // Terra vista da Lua: atmosfera, nuvens passando sobre a Terra, poeira lunar baixa e véus raros
            val e = im(393f, 155f)
            val er = 66f * s
            val p = 0.5f + 0.5f * sin(t * 0.7f)
            val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(e.x - er, e.y - er, e.x + er, e.y + er)) }
            clipPath(disc) {
                for (k in 0 until 4) {
                    val x = e.x - er + (((t * 0.006f + k * 0.27f) % 1f) * 2.6f - 0.3f) * er
                    veil(x, e.y + (k - 1.5f) * er * 0.38f, er * 0.55f, er * 0.12f, Color.White, 0.22f)
                }
            }
            drawCircle(Color(0xFF7FC4FF).al(0.22f + 0.12f * p), er * 1.04f, e, style = Stroke(width = (3f + 2f * p) * s))
            glow(e, er * 1.9f, Color(0xFF6FB6FF), 0.18f)
            val ev = ((t % 11f) / 11f)
            if (ev < 0.35f) veil(w * (0.2f + ev * 1.6f), h * 0.86f, 300f * s, 26f * s, Color(0xFFD9D6CF), 0.10f * sin(ev / 0.35f * PI.toFloat()))
            dustLayers(t, d, Color(0xFFE2DED6), 0.0025f, 0.66f, 1f, 0.5f, u)
        }
    }
}

/** Água e calor de verdade: distorce a própria imagem (Android 13+), sem desenhar por cima. */
private const val WATER_AGSL = """
uniform shader content;
uniform float2 res;
uniform float t;
uniform float h0;
uniform float mode;
uniform float y0;
uniform float y1;
half4 main(float2 p) {
    float sc = res.y / 1080.0;
    float y = p.y / res.y;
    float2 q = p;
    if (mode < 0.5) {
        if (y > h0) {
            float d = (y - h0) / (1.0 - h0);
            float a = (1.5 + 9.0 * d) * sc;
            q.x += sin(p.y * (0.09 / sc) / (0.25 + d) + t * 1.7) * a * 0.6 + sin(p.y * 0.031 / sc - t * 0.9 + p.x * 0.004 / sc) * a * 0.5;
            q.y += sin(p.x * 0.012 / sc + t * 1.3 + d * 6.0) * a * 0.45 * d;
            half4 c = content.eval(q);
            float sp = pow(max(0.0, sin(p.x * 0.02 / sc + p.y * 0.09 / sc - t * 2.2) * sin(p.y * 0.05 / sc + t * 1.1)), 12.0) * (1.0 - d) * 0.22;
            half hs = half(sp);
            return c + half4(hs, hs, hs, 0.0);
        }
        return content.eval(p);
    }
    if (y > y0 && y < y1) {
        float k = sin((y - y0) / (y1 - y0) * 3.14159);
        q.x += sin(p.y * 0.35 / sc + t * 7.0) * 1.6 * sc * k;
        q.y += sin(p.x * 0.05 / sc + t * 4.0) * 1.2 * sc * k;
    }
    return content.eval(q);
}
"""

private var waterShader: Any? = null

@androidx.annotation.RequiresApi(33)
private fun waterEffect(theme: Int, t: Float, w: Float, h: Float): androidx.compose.ui.graphics.RenderEffect {
    val sh = (waterShader as? android.graphics.RuntimeShader) ?: android.graphics.RuntimeShader(WATER_AGSL).also { waterShader = it }
    sh.setFloatUniform("res", w, h)
    sh.setFloatUniform("t", t)
    sh.setFloatUniform("h0", if (theme == 8) 0.47f else 0.56f)
    sh.setFloatUniform("mode", if (theme == 9) 1f else 0f)
    sh.setFloatUniform("y0", 0.40f)
    sh.setFloatUniform("y1", 0.80f)
    return android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content").asComposeRenderEffect()
}

/** Fundo: nebulosa em movimento lento, estrelas piscando, estrelas pulsando e poeira estelar. */
@Composable
fun BackgroundFx(tick: State<Long>, theme: Int = 0) {
    // zoom lento do espaço e água/calor: seguem o relógio do fundo (param junto quando você navega)
    val slow = remember(tick) { androidx.compose.runtime.derivedStateOf { tick.value / 66_666_666L } }
    val d = remember { StarData() }
    // véu por tema: temas claros (mar, deserto) escurecem mais, para o texto claro manter contraste
    val veil = when (theme) {
        3 -> 0.42f
        4 -> 0.58f
        1 -> 0.26f
        5 -> 0.30f
        6 -> 0.26f
        7 -> 0.50f
        8 -> 0.24f
        9 -> 0.46f
        10 -> 0.22f
        else -> 0.22f
    }
    // o véu vai dentro da própria imagem (antes era outra camada do tamanho da tela):
    // uma camada a menos para a TV pintar 30 vezes por segundo
    val veilColor = Color(0xFF030509).copy(alpha = veil)
    // só o tema dos planetas deixa a imagem translúcida, então só ele precisa de cor atrás
    Box(if (theme == 1) Modifier.fillMaxSize().background(Color(0xFF02030A)) else Modifier.fillMaxSize()) {
        Image(
            painterResource(
                when (theme) {
                    1 -> R.drawable.bg_nebula
                    2 -> R.drawable.bg_orbits
                    3 -> R.drawable.bg_ocean
                    4 -> R.drawable.bg_desert
                    5 -> R.drawable.bg_pluto
                    6 -> R.drawable.bg_crater
                    7 -> R.drawable.bg_mars
                    8 -> R.drawable.bg_jupiter
                    9 -> R.drawable.bg_lava
                    10 -> R.drawable.bg_earthrise
                    else -> R.drawable.bg_nebula
                }
            ), null,
            Modifier.fillMaxSize().graphicsLayer {
                // só o espaço profundo flutua; nos outros o horizonte e o sol ficam fixos
                if (theme == 0) {
                    val ts = slow.value * 0.066666f
                    val sc = 1.03f - 0.03f * cos(ts * 2f * PI.toFloat() / 120f)
                    scaleX = sc
                    scaleY = sc
                    translationX = -(sc - 1f) * size.width * 0.2f
                }
                // planetas: o fundo é só o céu escuro; o planeta é desenhado nítido por cima
                if (theme == 1) alpha = 0.35f
                // água e calor de verdade (Android 13+)
                if (android.os.Build.VERSION.SDK_INT >= 33 && (theme == 3 || theme == 8 || theme == 9)) {
                    // 15 quadros por segundo bastam para a água; metade do trabalho da GPU
                    renderEffect = waterEffect(theme, slow.value * 0.066666f, size.width, size.height)
                }
            },
            contentScale = ContentScale.Crop,
            // Lua: luz fria e contraste mais forte nas pedras
            colorFilter = if (theme == 10) androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix(
                    floatArrayOf(
                        1.12f, 0f, 0f, 0f, -14f,
                        0f, 1.12f, 0f, 0f, -12f,
                        0f, 0f, 1.16f, 0f, -8f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            ) else androidx.compose.ui.graphics.ColorFilter.tint(veilColor, androidx.compose.ui.graphics.BlendMode.SrcOver)
        )
        // só a Lua usa filtro de cor próprio, então só ela ainda precisa do véu em separado
        if (theme == 10) Box(Modifier.fillMaxSize().background(veilColor))
        Canvas(Modifier.fillMaxSize()) {
            // sombra de cima e de baixo: só nas faixas onde ela aparece. Antes era um degradê
            // do tamanho da tela inteira, com o meio todo transparente — pintura jogada fora.
            val topH = size.height * 0.25f
            val botH = size.height * 0.20f
            drawRect(Brush.verticalGradient(0f to Color(0x33030509), 1f to Color.Transparent, endY = topH), size = Size(size.width, topH))
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x99030509), startY = size.height - botH, endY = size.height),
                topLeft = Offset(0f, size.height - botH), size = Size(size.width, botH),
            )
            val now = tick.value
            val t = now / 1_000_000_000f
            val dt = if (d.last == 0L) 0f else ((now - d.last) / 1_000_000f).coerceIn(0f, 50f)
            d.last = now
            val u = size.width / 1280f
            val w = size.width
            val h = size.height
            if (theme == 2) orbits(t, u)
            if (theme == 3) {
                // névoa baixa passando sobre o mar (a água em si ondula pelo efeito da imagem)
                for (k in 0 until 3) {
                    val x = ((t * (0.007f + k * 0.003f) + k * 0.33f) % 1.5f - 0.25f) * w
                    veil(x, h * (0.55f + k * 0.035f), 420f * u, 26f * u, Color(0xFFE2ECF5), 0.07f)
                }
            }
            if (theme == 4) {
                // pulso do sol e poeira levada pelo vento
                val pul = 0.5f + 0.5f * sin(t * 0.8f)
                val sc0 = Offset(w * 0.70f, h * 0.36f)
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFFFF2C8).al(0.30f + 0.15f * pul), Color.Transparent), center = sc0, radius = (170f + 30f * pul) * u),
                    (170f + 30f * pul) * u, sc0
                )
                for (i in 0 until d.qn) {
                    d.qx[i] += d.qv[i] * dt * 3f
                    if (d.qx[i] > 1.02f) {
                        d.qx[i] = -0.02f
                    }
                    val yy = d.qy[i] + 0.02f * sin(t * 0.7f + d.qp[i])
                    drawCircle(Color(0xFFFFD9A8).al(d.qa[i] * (0.6f + 0.4f * sin(t + d.qp[i]))), d.qr[i] * u, Offset(d.qx[i] * w, yy * h))
                }
            }
            if (theme >= 5) scene(theme, t, u, d)
            for (i in 0 until d.n) {
                if (theme == 3 || theme == 4 || theme == 7 || theme == 8 || theme == 9) break
                if ((theme == 5 || theme == 6 || theme == 10) && d.sy[i] > 0.3f) continue
                val a = 0.45f + 0.55f * sin(t * d.ss[i] + d.sp[i])
                val x = d.sx[i] * w
                val y = d.sy[i] * h
                drawCircle(Color.White.al(a * 0.8f), d.sr[i] * u * 0.9f, Offset(x, y))
                if (d.big[i]) {
                    val l = (7f + 5f * a) * u
                    val c = Color(0xFFC8E1FF).al(a * 0.55f)
                    drawLine(c, Offset(x - l, y), Offset(x + l, y), strokeWidth = 0.8f * u)
                    drawLine(c, Offset(x, y - l), Offset(x, y + l), strokeWidth = 0.8f * u)
                }
            }
            if (theme == 1) planet(t)
            for (i in 0 until (if (theme == 0) d.pn else 0)) {
                val p = ((t / d.pe[i]) + d.ph[i]) % 1f
                val sn = 0.5f + 0.5f * sin(p * 6.283f)
                val c = Offset(d.px[i] * w, d.py[i] * h)
                val col = d.pc[i]
                val hr = (12f + 12f * sn) * u
                // brilho em três anéis chapados: o degradê radial criava um efeito novo a cada
                // quadro para cada estrela, e isso é das contas mais caras que existem aqui
                val gl = 0.55f + 0.3f * sn
                drawCircle(col.al(gl * 0.16f), hr, c)
                drawCircle(col.al(gl * 0.30f), hr * 0.62f, c)
                drawCircle(
                    col.al(gl * 0.55f),
                    hr * 0.3f, c
                )
                drawCircle(Color.White.al(0.65f + 0.35f * sn), (1.1f + 0.7f * sn) * u, c)
                drawCircle(col.al((1f - p) * 0.38f), (3f + 34f * p) * u, c, style = Stroke(width = 1f * u))
            }
            for (i in 0 until (if (theme <= 2) d.dn else 0)) {
                d.dx[i] += d.dvx[i] * dt
                d.dy[i] += d.dvy[i] * dt
                if (d.dx[i] > 0.34f || d.dy[i] < -0.02f) {
                    d.dx[i] = (i % 5) * 0.01f
                    d.dy[i] = 1.02f
                }
                val a = d.da[i] * (0.55f + 0.45f * sin(t / 1.4f + d.dp[i])) * (1f - d.dx[i] / 0.34f * 0.7f)
                drawCircle(Color(0xFFEBF4FF).al(a.coerceIn(0f, 1f)), d.dr[i] * u, Offset(d.dx[i] * w, d.dy[i] * h))
            }
        }
    }
}

// ---------------------------------------------------------------- logo 3D

object LogoPainter {
    /** Nome do app no logo: "AstroLume" + "TV". */
    const val WORD = "AstroLume"

    /** Fonte do logo (Inter Display Black Itálica, licença OFL). Definida ao abrir o app. */
    var face: Typeface? = null

    /** Textura lunar branca do "TV" (crateras). Gerada uma vez em segundo plano. */
    @Volatile
    var moon: Bitmap? = null

    @Synchronized
    fun ensureMoon() {
        if (moon == null) moon = moonBitmap(192)
    }

    fun paint(size: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        textSize = size
        textAlign = Paint.Align.LEFT
    }

    fun baseline(p: Paint, cy: Float): Float {
        val fm = p.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    fun gap(size: Float) = size * 0.09f

    fun width(size: Float): Float {
        val p = paint(size)
        return p.measureText(WORD) + gap(size) + p.measureText("TV")
    }

    /** "AstroLume" sólido: volume, degradê ciano → violeta → rosa, reflexo de vidro e brilho correndo. */
    fun drawWord(nc: android.graphics.Canvas, x0: Float, base: Float, size: Float, alpha: Float, sweepT: Float) {
        val p = paint(size)
        val w = p.measureText(WORD)
        val a255 = (255 * alpha).toInt().coerceIn(0, 255)
        val depth = max(3, (size * 0.12f).toInt())
        for (i in depth downTo 1) {
            val m = 1f - i / depth.toFloat()
            p.color = AColor.argb(a255, (30 + 40 * m).toInt(), (22 + 40 * m).toInt(), (70 + 80 * m).toInt())
            nc.drawText(WORD, x0 + i * size * 0.0038f, base + i * size * 0.0055f, p)
        }
        p.shader = LinearGradient(x0, 0f, x0 + w, 0f, intArrayOf(0xFF7FE9FF.toInt(), 0xFF9B84FF.toInt(), 0xFFFF7FD8.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        p.setShadowLayer(size * 0.25f, 0f, 0f, AColor.argb((140 * alpha).toInt(), 150, 120, 255))
        p.alpha = a255
        nc.drawText(WORD, x0, base, p)
        p.clearShadowLayer()
        p.shader = LinearGradient(0f, base - size * 0.8f, 0f, base, intArrayOf(0x8CFFFFFF.toInt(), 0x14FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 0.5f), Shader.TileMode.CLAMP)
        p.alpha = a255
        nc.drawText(WORD, x0, base, p)
        if (sweepT >= 0f) {
            val sx = x0 - 200f * size / 118f + ((sweepT * 260f * size / 118f) % (w + 400f * size / 118f))
            val band = 60f * size / 118f
            p.shader = LinearGradient(sx - band, 0f, sx + band, 0f, intArrayOf(0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            p.alpha = a255
            nc.drawText(WORD, x0, base, p)
        }
    }

    /** "TV" branco lunar com crateras de impacto. */
    fun drawTv(nc: android.graphics.Canvas, x: Float, base: Float, size: Float, alpha: Float) {
        val p = paint(size)
        val a255 = (255 * alpha).toInt().coerceIn(0, 255)
        val mb = moon
        p.setShadowLayer(size * 0.07f, size * 0.025f, size * 0.035f, AColor.argb((190 * alpha).toInt(), 0, 0, 0))
        if (mb != null) {
            val sh = android.graphics.BitmapShader(mb, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(size / 215f, size / 215f); postTranslate(x, 0f) })
            p.shader = sh
        } else p.color = AColor.rgb(226, 228, 234)
        p.alpha = a255
        nc.drawText("TV", x, base, p)
        p.clearShadowLayer()
        p.shader = LinearGradient(0f, base - size * 0.8f, 0f, base + size * 0.1f, intArrayOf(0x2EFFFFFF, 0x00FFFFFF, 0x38000014), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        p.alpha = a255
        nc.drawText("TV", x, base, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, size * 0.011f)
        p.color = AColor.argb((140 * alpha).toInt(), 255, 255, 255)
        nc.drawText("TV", x, base, p)
    }

    /** Logo completo "AstroLume" + "TV". x0 = esquerda, cy = centro vertical. */
    fun draw(nc: android.graphics.Canvas, x0: Float, cy: Float, size: Float, alpha: Float, glow: Boolean) {
        val p = paint(size)
        val base = baseline(p, cy)
        drawWord(nc, x0, base, size, alpha, -1f)
        drawTv(nc, x0 + p.measureText(WORD) + gap(size), base, size, alpha)
    }
}

@Composable
fun LogoSmall(heightPx: Float) {
    // heightPx = tamanho de fonte em px
    val w = LogoPainter.width(heightPx) + heightPx * 0.2f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val wDp = with(density) { w.toDp() }
    val hDp = with(density) { (heightPx * 1.35f).toDp() }
    Canvas(Modifier.size(wDp, hDp)) {
        LogoPainter.draw(drawContext.canvas.nativeCanvas, 2f, size.height / 2f, heightPx, 1f, true)
    }
}


// ---------------------------------------------------------------- ícones

private val ICONS = mapOf(
    "music" to "M9 18V5l12-2v13M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
    "home" to "M3 10.5 12 3l9 7.5M5 9.5V21h14V9.5",
    "search" to "M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0M20 20l-3.5-3.5",
    "star" to "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 17.9 6.8 19.6l1-5.8L3.5 9.7l5.9-.9z",
    "film" to "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4",
    "popcorn" to "M3 20.5h18M4.5 20.5v-5a2 2 0 0 1 2-2h11a2 2 0 0 1 2 2v5M6.5 7a2.1 2.1 0 1 0 4.2 0a2.1 2.1 0 1 0 -4.2 0M8.6 9.4v4M8.8 11l4.6-.8M13.6 8.4l.7 4.4h4.6l.7-4.4z",
    "series" to "M6 11l1.5 10h9L18 11zM10.3 11l.4 10M13.7 11l-.4 10M5.6 11a2.6 2.6 0 0 1 .9-4.6 3 3 0 0 1 5.5-1.6 3 3 0 0 1 5.5 1.6 2.6 2.6 0 0 1 .9 4.6",
    "heart" to "M12 20s-7-4.4-7-9.5A3.7 3.7 0 0 1 12 7a3.7 3.7 0 0 1 7 3.5C19 15.6 12 20 12 20z",
    "doc" to "M5 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M12.5 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M4.8 10h9.4a1.8 1.8 0 0 1 1.8 1.8v5.4a1.8 1.8 0 0 1-1.8 1.8H4.8A1.8 1.8 0 0 1 3 17.2v-5.4A1.8 1.8 0 0 1 4.8 10zM16 13l5-2.5v8L16 16",
    "ball" to "M7.1 4.8a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0M9.2 8l.9 6M9.6 10.2L14 6.8M9.6 10.2L6 12.2M10.1 14l-3 7M10.1 14l4.4 6M14.8 4.6a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0M14.9 4.6h4.6M17.2 2.2v4.8",
    "news" to "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM7 9h6M7 13h10M7 16h10",
    "tv" to "M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM8 21l4-3 4 3",
    "gear" to "M8.7 12a3.3 3.3 0 1 0 6.6 0a3.3 3.3 0 1 0 -6.6 0M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2",
    "power" to "M12 3v8M6.4 6.8a8 8 0 1 0 11.2 0",
    "globe" to "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0M3.6 9h16.8M3.6 15h16.8M12 3a15 15 0 0 1 0 18a15 15 0 0 1 0-18"
)

private val iconCache = HashMap<String, Path>()

private fun iconPath(name: String): Path {
    return iconCache.getOrPut(name) {
        try {
            PathParser().parsePathString(ICONS[name] ?: "").toPath()
        } catch (e: Exception) {
            Path()
        }
    }
}

@Composable
fun NavIcon(name: String, color: Color, sizeDp: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(sizeDp)) {
        val s = size.width / 24f
        val path = iconPath(name)
        drawContext.canvas.save()
        drawContext.canvas.scale(s, s)
        drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawContext.canvas.restore()
    }
}


// ---------------------------------------------------------------- cena da família (Streams)

@Composable
fun FamilyScene(modifier: Modifier) {
    Canvas(modifier) {
        // viewBox 1000x260, alinhado à direita/baixo (cover)
        val k = max(size.width / 1000f, size.height / 260f)
        val ox = size.width - 1000f * k
        val oy = size.height - 260f * k
        fun X(v: Float) = ox + v * k
        fun Y(v: Float) = oy + v * k
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF05070D))))
        drawOval(
            Brush.radialGradient(listOf(Color(0x8C6CC6FF), Color(0x006CC6FF)), center = Offset(X(720f), Y(90f)), radius = 330f * k),
            topLeft = Offset(X(390f), Y(-60f)), size = Size(660f * k, 300f * k)
        )
        drawRoundRect(Color(0x736CC6FF), Offset(X(510f), Y(18f)), Size(420f * k, 150f * k), CornerRadius(14f * k))
        drawRoundRect(Color(0xFF04060B), Offset(X(520f), Y(26f)), Size(400f * k, 136f * k), CornerRadius(9f * k))
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF2B6FD6), Color(0xFF7A4BD0), Color(0xFFFF9A5A)), start = Offset(X(528f), Y(33f)), end = Offset(X(912f), Y(155f))),
            Offset(X(528f), Y(33f)), Size(384f * k, 122f * k), CornerRadius(4f * k)
        )
        drawCircle(Color(0x2EFFFFFF), 26f * k, Offset(X(820f), Y(70f)))
        drawRect(Color(0xFF10151F), Offset(X(690f), Y(162f)), Size(60f * k, 6f * k))
        drawRoundRect(Color(0xFF10141F), Offset(X(410f), Y(186f)), Size(620f * k, 90f * k), CornerRadius(26f * k))
        val body = Color(0xFF06080E)
        val rim = Color(0xFFBFE4FF).al(0.6f)
        fun person(cx: Float, headY: Float, r: Float, shoulderW: Float) {
            drawCircle(body, r * k, Offset(X(cx), Y(headY)))
            drawRoundRect(body, Offset(X(cx - shoulderW / 2f), Y(headY + r + 6f)), Size(shoulderW * k, 90f * k), CornerRadius(shoulderW * 0.4f * k))
            drawArc(rim, 200f, 140f, false, Offset(X(cx - r), Y(headY - r)), Size(2f * r * k, 2f * r * k), style = Stroke(width = 3f * k))
        }
        person(508f, 176f, 21f, 76f)
        person(614f, 198f, 15f, 58f)
        person(716f, 201f, 14f, 52f)
        person(808f, 176f, 21f, 76f)
        // balde de pipoca
        val bucket = Path().apply {
            moveTo(X(655f), Y(236f)); lineTo(X(660f), Y(260f)); lineTo(X(700f), Y(260f)); lineTo(X(705f), Y(236f)); close()
        }
        drawPath(bucket, Color(0xFFE8344A))
        for (sx in listOf(668f, 680f, 692f)) drawLine(Color.White, Offset(X(sx), Y(236f)), Offset(X(sx), Y(260f)), strokeWidth = 5f * k)
        for (p in listOf(Triple(662f, 232f, 7f), Triple(676f, 228f, 8f), Triple(691f, 229f, 8f), Triple(704f, 233f, 6f), Triple(684f, 222f, 6f))) {
            drawCircle(Color(0xFFFFF6DC), p.third * k, Offset(X(p.first), Y(p.second)))
        }
    }
}

// ---------------------------------------------------------------- abertura (AstroLume)

private fun cl(x: Float) = max(0f, min(1f, x))



/** Ruído procedural (gera pedra, crateras e fumaça sem nenhuma imagem no APK). */
private object Noise {
    fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 69069
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)).toLong() and 0xffffffffL) / 4294967296f
    }

    fun v(x: Float, y: Float, s: Int): Float {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = xf * xf * (3 - 2 * xf)
        val w = yf * yf * (3 - 2 * yf)
        val a = hash(xi, yi, s)
        val b = hash(xi + 1, yi, s)
        val c = hash(xi, yi + 1, s)
        val d = hash(xi + 1, yi + 1, s)
        return a + (b - a) * u + (c - a) * w + (a - b - c + d) * u * w
    }

    fun fbm(x: Float, y: Float, o: Int, s: Int): Float {
        var f = 0f
        var a = 0.5f
        var fr = 1f
        for (i in 0 until o) {
            f += a * v(x * fr, y * fr, s + i * 17)
            fr *= 2.03f
            a *= 0.5f
        }
        return f
    }

    fun ridged(x: Float, y: Float, o: Int, s: Int): Float {
        var f = 0f
        var a = 0.5f
        var fr = 1f
        for (i in 0 until o) {
            f += a * (1f - kotlin.math.abs(v(x * fr, y * fr, s + i * 31) * 2f - 1f))
            fr *= 2.1f
            a *= 0.5f
        }
        return f
    }
}

private class Crater(val x: Float, val y: Float, val r: Float, val dp: Float)

/** Relevo (altura) com crateras, repetindo nas bordas; luz vinda do alto à esquerda. */
private fun relief(S: Int, craters: List<Crater>, base: (Int, Int) -> Float): FloatArray {
    val h = FloatArray(S * S)
    for (y in 0 until S) for (x in 0 until S) {
        var v = base(x, y)
        for (c in craters) {
            var dx = x - c.x
            var dy = y - c.y
            if (dx > S / 2) dx -= S
            if (dx < -S / 2) dx += S
            if (dy > S / 2) dy -= S
            if (dy < -S / 2) dy += S
            val q = kotlin.math.sqrt(dx * dx + dy * dy) / c.r
            if (q < 1f) v -= c.dp * (1f - q * q) else if (q < 1.4f) v += c.dp * 0.42f * (1f - (q - 1f) / 0.4f)
        }
        h[y * S + x] = v
    }
    return h
}

private fun shade(h: FloatArray, S: Int, x: Int, y: Int, k: Float): Float {
    val xl = (x - 1 + S) % S
    val xr = (x + 1) % S
    val yu = (y - 1 + S) % S
    val yd = (y + 1) % S
    var nx = (h[y * S + xl] - h[y * S + xr]) * k
    var ny = (h[yu * S + x] - h[yd * S + x]) * k
    var nz = 1f
    val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
    nx /= nl
    ny /= nl
    nz /= nl
    return max(0f, nx * -0.55f + ny * -0.6f + nz * 0.58f)
}

/** Rocha escura do meteoro (basalto com crateras e veios). */
private fun rockBitmap(S: Int): Bitmap {
    val r = java.util.Random(42)
    val cr = List(30) { Crater(r.nextFloat() * S, r.nextFloat() * S, 4f + r.nextFloat() * r.nextFloat() * 34f, 0.08f + r.nextFloat() * 0.16f) }
    val h = relief(S, cr) { x, y -> Noise.fbm(x / 70f, y / 70f, 5, 3) * 0.75f + Noise.ridged(x / 48f, y / 48f, 3, 9) * 0.35f }
    val px = IntArray(S * S)
    for (y in 0 until S) for (x in 0 until S) {
        val sh = shade(h, S, x, y, 7f)
        val m = Noise.fbm(x / 32f, y / 32f, 3, 77)
        val sp = if (Noise.hash(x, y, 5) > 0.996f) 0.5f else 0f
        val k = 0.22f + sh * 1.05f + sp
        px[y * S + x] = AColor.rgb(min(255f, (52 + 60 * m) * k).toInt(), min(255f, (48 + 52 * m) * k).toInt(), min(255f, (45 + 40 * m) * k).toInt())
    }
    return Bitmap.createBitmap(px, S, S, Bitmap.Config.ARGB_8888)
}

/** Superfície lunar branca com crateras de impacto (para o "TV"). */
fun moonBitmap(S: Int): Bitmap {
    val r = java.util.Random(77)
    val cr = List(60) { Crater(r.nextFloat() * S, r.nextFloat() * S, 3f + Math.pow(r.nextFloat().toDouble(), 2.2).toFloat() * 38f, 0.12f + r.nextFloat() * 0.2f) }
    val h = relief(S, cr) { x, y -> Noise.fbm(x / 55f, y / 55f, 4, 41) * 0.35f }
    val px = IntArray(S * S)
    for (y in 0 until S) for (x in 0 until S) {
        val sh = shade(h, S, x, y, 9f)
        val mar = Noise.fbm(x / 95f, y / 95f, 3, 90)
        val alb = 232f - 55f * cl((mar - 0.45f) * 3f) + 12f * (Noise.fbm(x / 7f, y / 7f, 2, 5) - 0.5f)
        val v = min(255f, alb * (0.5f + 0.62f * sh)).toInt()
        px[y * S + x] = AColor.rgb(v, v, min(255, (v * 1.03f).toInt()))
    }
    return Bitmap.createBitmap(px, S, S, Bitmap.Config.ARGB_8888)
}

