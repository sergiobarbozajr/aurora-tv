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

/** Relógio de quadros: cada leitura de .value dentro de um desenho redesenha a cada frame. */
@Composable
fun frameClock(active: Boolean): State<Long> {
    val s = remember { mutableStateOf(0L) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                androidx.compose.runtime.withFrameNanos { s.value = it }
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
            listOf(Color(0xFF5AAAFF).copy(alpha = 0.55f * al), Color(0xFF286EFF).copy(alpha = 0.22f * al), Color(0x00146EFF)),
            start = head, end = tail
        ),
        head, tail, strokeWidth = 16f * u, cap = StrokeCap.Round
    )
    drawLine(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = al), Color(0xFF9ACDFF).copy(alpha = 0.7f * al), Color(0x005096FF)),
            start = head, end = tail
        ),
        head, Offset(hx - ux * len * 0.9f, hy - uy * len * 0.9f), strokeWidth = 3.6f * u, cap = StrokeCap.Round
    )
    val rnd = java.util.Random((m.life * 1000f).toInt().toLong())
    for (j in 0 until 3) {
        val f = 0.15f + rnd.nextFloat() * 0.5f
        drawCircle(
            Color(0xFF78BEFF).copy(alpha = 0.5f * al * rnd.nextFloat()),
            (1.2f + rnd.nextFloat() * 1.8f) * u,
            Offset(hx - ux * len * f + (rnd.nextFloat() - 0.5f) * 9f * u, hy - uy * len * f + (rnd.nextFloat() - 0.5f) * 9f * u)
        )
    }
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = al), Color(0xFF96CDFF).copy(alpha = 0.5f * al), Color(0x006EB4FF)), center = head, radius = 17f * u),
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
}

/** Fundo: nebulosa em movimento lento, estrelas piscando, estrelas pulsando e poeira estelar. */
@Composable
fun BackgroundFx(tick: State<Long>) {
    val inf = rememberInfiniteTransition(label = "bg")
    val sc by inf.animateFloat(
        initialValue = 1.0f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(60000, easing = LinearEasing), RepeatMode.Reverse),
        label = "sc"
    )
    val d = remember { StarData() }
    Box(Modifier.fillMaxSize().background(Color(0xFF02030A))) {
        Image(
            painterResource(R.drawable.bg_nebula), null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = sc
                scaleY = sc
                translationX = -(sc - 1f) * size.width * 0.2f
            },
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color(0x38030509)))
        Canvas(Modifier.fillMaxSize()) {
            val now = tick.value
            val t = now / 1_000_000_000f
            val dt = if (d.last == 0L) 0f else ((now - d.last) / 1_000_000f).coerceIn(0f, 50f)
            d.last = now
            val u = size.width / 1280f
            val w = size.width
            val h = size.height
            for (i in 0 until d.n) {
                val a = 0.45f + 0.55f * sin(t * d.ss[i] + d.sp[i])
                val x = d.sx[i] * w
                val y = d.sy[i] * h
                drawCircle(Color.White.copy(alpha = a * 0.8f), d.sr[i] * u * 0.9f, Offset(x, y))
                if (d.big[i]) {
                    val l = (7f + 5f * a) * u
                    val c = Color(0xFFC8E1FF).copy(alpha = a * 0.55f)
                    drawLine(c, Offset(x - l, y), Offset(x + l, y), strokeWidth = 0.8f * u)
                    drawLine(c, Offset(x, y - l), Offset(x, y + l), strokeWidth = 0.8f * u)
                }
            }
            for (i in 0 until d.pn) {
                val p = ((t / d.pe[i]) + d.ph[i]) % 1f
                val sn = 0.5f + 0.5f * sin(p * 6.283f)
                val c = Offset(d.px[i] * w, d.py[i] * h)
                val col = d.pc[i]
                val hr = (12f + 12f * sn) * u
                drawCircle(
                    Brush.radialGradient(listOf(col.copy(alpha = 0.55f + 0.3f * sn), col.copy(alpha = 0f)), center = c, radius = hr),
                    hr, c
                )
                drawCircle(Color.White.copy(alpha = 0.65f + 0.35f * sn), (1.1f + 0.7f * sn) * u, c)
                drawCircle(col.copy(alpha = (1f - p) * 0.38f), (3f + 34f * p) * u, c, style = Stroke(width = 1f * u))
            }
            for (i in 0 until d.dn) {
                d.dx[i] += d.dvx[i] * dt
                d.dy[i] += d.dvy[i] * dt
                if (d.dx[i] > 0.34f || d.dy[i] < -0.02f) {
                    d.dx[i] = (i % 5) * 0.01f
                    d.dy[i] = 1.02f
                }
                val a = d.da[i] * (0.55f + 0.45f * sin(t / 1.4f + d.dp[i])) * (1f - d.dx[i] / 0.34f * 0.7f)
                drawCircle(Color(0xFFEBF4FF).copy(alpha = a.coerceIn(0f, 1f)), d.dr[i] * u, Offset(d.dx[i] * w, d.dy[i] * h))
            }
        }
    }
}

// ---------------------------------------------------------------- logo 3D

object LogoPainter {
    fun paint(size: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = size
        textAlign = Paint.Align.LEFT
    }

    fun baseline(p: Paint, cy: Float): Float {
        val fm = p.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    fun gap(size: Float) = size * 0.053f

    fun width(size: Float): Float {
        val p = paint(size)
        return p.measureText("Aurora") + gap(size) + p.measureText("TV")
    }

    /** Desenha "AuroraTV" com volume (camadas), brilho e degradê. x0 = esquerda, cy = centro vertical. */
    fun draw(nc: android.graphics.Canvas, x0: Float, cy: Float, size: Float, alpha: Float, glow: Boolean) {
        val p = paint(size)
        val base = baseline(p, cy)
        val wA = p.measureText("Aurora")
        val gp = gap(size)
        val xT = x0 + wA + gp
        val depth = max(3, (size * 0.14f).toInt())
        val k = size / 150f
        val a255 = (255 * alpha).toInt().coerceIn(0, 255)
        for (i in depth downTo 1) {
            val m = 1f - i / depth.toFloat()
            p.shader = null
            p.color = AColor.argb(a255, (18 + 34 * m).toInt(), (52 + 70 * m).toInt(), (104 + 70 * m).toInt())
            if (i == depth && glow) p.setShadowLayer(size * 0.3f, 0f, 0f, AColor.argb((150 * alpha).toInt(), 108, 198, 255)) else p.clearShadowLayer()
            nc.drawText("Aurora", x0 + i * 0.55f * k, base + i * 0.8f * k, p)
            nc.drawText("TV", xT + i * 0.55f * k, base + i * 0.8f * k, p)
        }
        p.clearShadowLayer()
        p.shader = LinearGradient(
            0f, base - size * 0.75f, 0f, base + size * 0.2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCECFF.toInt(), 0xFF8FB8E8.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        p.alpha = a255
        nc.drawText("Aurora", x0, base, p)
        p.shader = LinearGradient(
            0f, base - size * 0.75f, 0f, base + size * 0.2f,
            intArrayOf(0xFFE9F8FF.toInt(), 0xFF6CC6FF.toInt(), 0xFF2F86D8.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        p.alpha = a255
        nc.drawText("TV", xT, base, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, size * 0.009f)
        p.color = AColor.argb((178 * alpha).toInt(), 255, 255, 255)
        nc.drawText("Aurora", x0, base, p)
        nc.drawText("TV", xT, base, p)
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
    "power" to "M12 3v8M6.4 6.8a8 8 0 1 0 11.2 0"
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
        val rim = Color(0xFFBFE4FF).copy(alpha = 0.6f)
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

// ---------------------------------------------------------------- abertura

class IntroData(
    val x0: Float, val cy: Float, val size: Float, val ix: Float, val iy: Float,
    val ax: FloatArray, val ay: FloatArray, val adx: FloatArray, val ady: FloatArray, val adl: FloatArray, val afr: BooleanArray,
    val tx: FloatArray, val ty: FloatArray, val tsx: FloatArray, val tsy: FloatArray, val tw: FloatArray, val tdl: FloatArray
)

private fun buildIntroData(): IntroData {
    val size = 150f
    val cy = 360f
    val p = LogoPainter.paint(size)
    val wA = p.measureText("Aurora")
    val wT = p.measureText("TV")
    val gp = LogoPainter.gap(size)
    val x0 = (1280f - (wA + gp + wT)) / 2f
    val base = LogoPainter.baseline(p, cy)
    val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    p.color = AColor.WHITE
    val px = IntArray(1280 * 720)

    fun sample(txt: String, x: Float, w: Float): List<FloatArray> {
        bmp.eraseColor(0)
        c.drawText(txt, x, base, p)
        bmp.getPixels(px, 0, 1280, 0, 0, 1280, 720)
        val out = ArrayList<FloatArray>()
        var y = (cy - size * 0.75f).toInt().coerceAtLeast(0)
        val yEnd = (cy + size * 0.75f).toInt().coerceAtMost(719)
        while (y < yEnd) {
            var xx = x.toInt().coerceAtLeast(0)
            val xEnd = (x + w + 6f).toInt().coerceAtMost(1279)
            while (xx < xEnd) {
                if ((px[y * 1280 + xx] ushr 24) > 128) out.add(floatArrayOf(xx.toFloat(), y.toFloat()))
                xx += 3
            }
            y += 3
        }
        return out
    }

    val ix = x0 + wA - 34f
    val iy = cy - 8f
    val rnd = java.util.Random(5)
    val a = sample("Aurora", x0, wA)
    val t = sample("TV", x0 + wA + gp, wT)
    val an = a.size
    val ax = FloatArray(an)
    val ay = FloatArray(an)
    val adx = FloatArray(an)
    val ady = FloatArray(an)
    val adl = FloatArray(an)
    val afr = BooleanArray(an)
    for (i in 0 until an) {
        val q = a[i]
        ax[i] = q[0]
        ay[i] = q[1]
        val dx = q[0] - ix
        val dy = q[1] - iy
        val d = hypot(dx, dy)
        val fr = d < 150f || rnd.nextFloat() < 0.04f
        val ang = Math.atan2(dy.toDouble(), dx.toDouble()).toFloat() + (rnd.nextFloat() - 0.5f) * 0.6f
        val m = if (fr) (60f + rnd.nextFloat() * 220f) * (if (d < 150f) 1.2f else 0.6f) else 0f
        adx[i] = cos(ang) * m
        ady[i] = sin(ang) * m
        adl[i] = rnd.nextFloat() * 0.5f
        afr[i] = fr
    }
    val tn = t.size
    val tx = FloatArray(tn)
    val ty = FloatArray(tn)
    val tsx = FloatArray(tn)
    val tsy = FloatArray(tn)
    val tw = FloatArray(tn)
    val tdl = FloatArray(tn)
    for (i in 0 until tn) {
        tx[i] = t[i][0]
        ty[i] = t[i][1]
        tsx[i] = ix + (rnd.nextFloat() - 0.5f) * 30f
        tsy[i] = iy + (rnd.nextFloat() - 0.5f) * 30f
        tw[i] = (rnd.nextFloat() - 0.5f) * 140f
        tdl[i] = rnd.nextFloat() * 0.45f
    }
    bmp.recycle()
    return IntroData(x0, cy, size, ix, iy, ax, ay, adx, ady, adl, afr, tx, ty, tsx, tsy, tw, tdl)
}

private fun cl(x: Float) = max(0f, min(1f, x))
private fun eo(x: Float): Float {
    val v = 1f - cl(x)
    return 1f - v * v * v
}

private fun ei(x: Float): Float {
    val v = cl(x)
    return v * v
}

private fun eio(x: Float): Float {
    val v = cl(x)
    return if (v < 0.5f) 2f * v * v else 1f - (-2f * v + 2f) * (-2f * v + 2f) / 2f
}

/** Abertura: meteoro vindo de uma galáxia distante bate em "Aurora" e forma "TV". Qualquer tecla pula (feito fora). */
@Composable
fun IntroAnim(onDone: () -> Unit) {
    var data by remember { mutableStateOf<IntroData?>(null) }
    var tSec by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.Default) { buildIntroData() }
    }
    LaunchedEffect(data) {
        if (data == null) return@LaunchedEffect
        var start = -1L
        while (true) {
            val n = androidx.compose.runtime.withFrameNanos { it }
            if (start < 0) start = n
            tSec = (n - start) / 1_000_000_000f
            if (tSec > 6.3f) {
                onDone()
                break
            }
        }
    }
    val pAur = remember { Paint().apply { strokeCap = Paint.Cap.SQUARE; isAntiAlias = false } }
    val pTv = remember { Paint().apply { strokeCap = Paint.Cap.SQUARE; isAntiAlias = false } }
    Box(Modifier.fillMaxSize().background(Color(0xE0020309))) {
        Canvas(Modifier.fillMaxSize()) {
            val d = data ?: return@Canvas
            val t = tSec
            val u = size.width / 1280f
            val ti = 2.1f
            val fadeIn = cl(t / 0.9f)
            val a3 = eio((t - ti - 1.7f) / 0.8f)
            val pk = 1f - a3
            val nc = drawContext.canvas.nativeCanvas
            // Aurora
            run {
                val n = d.ax.size
                val arr = FloatArray(n * 2)
                for (i in 0 until n) {
                    var f = 0f
                    if (d.afr[i] && t > ti) f = eo((t - ti) / 0.45f) * (1f - eio((t - ti - 0.6f - d.adl[i]) / 1.1f))
                    arr[i * 2] = (d.ax[i] + d.adx[i] * f) * u
                    arr[i * 2 + 1] = (d.ay[i] + d.ady[i] * f) * u
                }
                pAur.strokeWidth = 3.4f * u
                pAur.color = AColor.argb((255 * cl(fadeIn * pk * 0.95f)).toInt(), 255, 255, 255)
                nc.drawPoints(arr, 0, n * 2, pAur)
            }
            // TV
            if (t > ti + 0.12f) {
                val n = d.tx.size
                val arr = FloatArray(n * 2)
                for (i in 0 until n) {
                    val s = eio((t - ti - 0.12f - d.tdl[i]) / 1.25f)
                    val sw = sin(s * PI.toFloat())
                    arr[i * 2] = (d.tsx[i] + (d.tx[i] - d.tsx[i]) * s + sw * d.tw[i]) * u
                    arr[i * 2 + 1] = (d.tsy[i] + (d.ty[i] - d.tsy[i]) * s - sw * Math.abs(d.tw[i]) * 0.6f) * u
                }
                val s0 = eio((t - ti - 0.5f) / 1.5f)
                pTv.strokeWidth = 3.4f * u
                pTv.color = AColor.argb((255 * cl(pk)).toInt(), (255 - (255 - 108) * s0).toInt(), (255 - (255 - 198) * s0).toInt(), 255)
                nc.drawPoints(arr, 0, n * 2, pTv)
            }
            // galáxia distante
            val gx = 1215f * u
            val gy = 38f * u
            if (t < 2.6f) {
                val ga = cl(t / 0.6f) * (1f - cl((t - 1.7f) / 0.9f))
                if (ga > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0xFFFFF0D2).copy(alpha = 0.9f * ga), Color(0xFFBEAAFF).copy(alpha = 0.5f * ga), Color(0xFF5A82FF).copy(alpha = 0.22f * ga), Color(0x003C5AFF)),
                            center = Offset(gx, gy), radius = 70f * u
                        ),
                        70f * u, Offset(gx, gy)
                    )
                    for (arm in 0 until 2) for (i in 0 until 70) {
                        val th = i * 0.14f
                        val rr = (5f + th * 6.5f) * u * 0.6f
                        val xx = gx + cos(th + arm * PI.toFloat()) * rr
                        val yy = gy + sin(th + arm * PI.toFloat()) * rr * 0.46f
                        drawCircle(Color(0xFFD2E1FF).copy(alpha = (0.55f * ga * (1f - i / 90f)).coerceIn(0f, 1f)), 1.1f * u, Offset(xx, yy))
                    }
                }
            }
            // meteoro
            if (t > 0.8f && t < ti) {
                val s = ei((t - 0.8f) / (ti - 0.8f))
                val sx0 = 1215f
                val sy0 = 38f
                val hx = sx0 + (d.ix - sx0) * s
                val hy = sy0 + (d.iy - sy0) * s
                val dxn = d.ix - sx0
                val dyn = d.iy - sy0
                val nn = hypot(dxn, dyn)
                val ux = dxn / nn
                val uy = dyn / nn
                val k = 0.2f + 0.8f * s
                val len = (120f + 440f * s) * u
                val head = Offset(hx * u, hy * u)
                val tail = Offset(head.x - ux * len, head.y - uy * len)
                drawLine(
                    Brush.linearGradient(listOf(Color(0xFF6EB9FF).copy(alpha = 0.7f), Color(0xFF286EFF).copy(alpha = 0.28f), Color(0x00146EFF)), start = head, end = tail),
                    head, tail, strokeWidth = 34f * k * u, cap = StrokeCap.Round
                )
                drawLine(
                    Brush.linearGradient(listOf(Color.White, Color(0xFFA0D2FF).copy(alpha = 0.75f), Color(0x005096FF)), start = head, end = tail),
                    head, Offset(head.x - ux * len * 0.9f, head.y - uy * len * 0.9f), strokeWidth = 7f * k * u, cap = StrokeCap.Round
                )
                val rnd = java.util.Random((t * 60f).toInt().toLong())
                for (j in 0 until 8) {
                    val f = 0.1f + rnd.nextFloat() * 0.7f
                    drawCircle(
                        Color(0xFF82C3FF).copy(alpha = 0.7f * rnd.nextFloat()),
                        (1.5f + rnd.nextFloat() * 2.5f) * u,
                        Offset(head.x - ux * len * f + (rnd.nextFloat() - 0.5f) * 26f * u, head.y - uy * len * f + (rnd.nextFloat() - 0.5f) * 26f * u)
                    )
                }
                drawCircle(
                    Brush.radialGradient(listOf(Color.White, Color(0xFF96CDFF).copy(alpha = 0.6f), Color(0x006EB4FF)), center = head, radius = 34f * k * u),
                    34f * k * u, head
                )
            }
            // impacto
            if (t >= ti && t < ti + 1.1f) {
                val kk = t - ti
                val fl = max(0f, 1f - kk / 0.45f)
                val c = Offset(d.ix * u, d.iy * u)
                if (fl > 0f) {
                    val r = (60f + 260f * eo(kk / 0.45f)) * u
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = fl), Color(0xFF82C3FF).copy(alpha = 0.5f * fl), Color(0x005096FF)), center = c, radius = r),
                        r, c
                    )
                }
                drawCircle(Color(0xFFAAD7FF).copy(alpha = max(0f, 0.8f - kk / 0.9f)), (20f + 520f * eo(kk / 1.0f)) * u, c, style = Stroke(width = 3f * u))
            }
            // logo 3D
            if (a3 > 0f) {
                nc.save()
                nc.scale(u, u)
                LogoPainter.draw(nc, d.x0, d.cy, d.size, a3, true)
                nc.restore()
            }
        }
    }
}
