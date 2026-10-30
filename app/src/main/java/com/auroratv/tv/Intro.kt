package com.auroratv.tv

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Abertura do AstroLume: um meteoro cai, se parte, e os cacos se juntam formando o nome.
 *
 * Tudo é desenhado pensando na TV: o fundo com as estrelas é pintado uma vez numa imagem
 * e depois só reaproveitado, e os milhares de cacos saem em três chamadas de desenho em vez
 * de uma por caco. Assim a abertura roda lisa até numa TV de entrada.
 */

// tempos, em segundos
private const val I_FALL = 1.5f     // o meteoro cai
private const val I_BURST = 2.15f   // fim do estouro
private const val I_WORD = 3.25f    // os cacos terminam de formar a palavra
private const val I_SHINE = 3.7f    // o reflexo atravessa o nome
private const val I_OUT = 4.7f      // começa a escurecer
private const val I_END = 5.0f

private const val VW = 1280f
private const val VH = 720f
private val IMPACT_X = VW / 2f - 300f
private val IMPACT_Y = VH / 2f - 4f

/** Um caco: onde ele tem que chegar, para onde ele voa e quando ele sai. */
private class Shard(val tx: Float, val ty: Float, val ang: Float, val rad: Float, val lag: Float, val sz: Float, val tone: Int)

private class IntroBits(val shards: List<Shard>, val sky: Bitmap)

private fun fall(p: Float): FloatArray {
    val sx = VW + 180f
    val sy = -200f
    val e = p * p // vai acelerando
    return floatArrayOf(sx + (IMPACT_X - sx) * e, sy + (IMPACT_Y - sy) * e)
}

/** Fundo com estrelas e nebulosa, pintado uma vez só. */
private fun skyBitmap(): Bitmap {
    val w = 640
    val h = 360
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bm)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawColor(AColor.rgb(5, 6, 13))
    p.shader = RadialGradient(w * .72f, h * .18f, w * .55f, AColor.argb(56, 58, 80, 190), AColor.argb(0, 5, 6, 13), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = RadialGradient(w * .2f, h * .8f, w * .5f, AColor.argb(40, 150, 80, 220), AColor.argb(0, 5, 6, 13), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null
    val r = java.util.Random(11)
    for (i in 0 until 260) {
        p.color = AColor.argb((50 + r.nextInt(150)), 226, 236, 255)
        c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 0.2f + r.nextFloat() * 0.7f, p)
    }
    return bm
}

/** Os cacos nascem dos pixels do nome: cada ponto do texto vira o destino de um caco. */
private fun buildBits(tf: android.graphics.Typeface?): IntroBits {
    val w = VW.toInt()
    val h = VH.toInt()
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val c = ACanvas(bm)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.typeface = tf
    p.textSize = 132f
    p.letterSpacing = 0.076f
    p.textAlign = Paint.Align.CENTER
    p.color = AColor.WHITE
    val fm = p.fontMetrics
    c.drawText(LogoPainter.WORD, VW / 2f + 5f, VH / 2f - 6f - (fm.ascent + fm.descent) / 2f, p)
    // ALPHA_8: um byte por pixel, que é justamente a opacidade da letra ali
    val buf = java.nio.ByteBuffer.allocate(w * h)
    bm.copyPixelsToBuffer(buf)
    val a = buf.array()
    bm.recycle()
    val r = java.util.Random(5)
    val out = ArrayList<Shard>(2600)
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            if ((a[y * w + x].toInt() and 0xFF) > 130) {
                out.add(
                    Shard(
                        x.toFloat(), y.toFloat(),
                        r.nextFloat() * 6.283f,
                        sqrt(r.nextFloat()),
                        r.nextFloat() * 0.38f,
                        0.6f + r.nextFloat() * 1.1f,
                        r.nextInt(3),
                    )
                )
            }
            x += 4
        }
        y += 4
    }
    return IntroBits(out, skyBitmap())
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun ease3(t: Float) = 1f - (1f - t).pow(3)

@Composable
fun IntroAnim(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val tf = remember { try { ResourcesCompat.getFont(ctx, R.font.inter_light) } catch (e: Throwable) { null } }
    var bits by remember { mutableStateOf<IntroBits?>(null) }
    var tSec by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        bits = withContext(Dispatchers.Default) { buildBits(tf) }
    }
    LaunchedEffect(bits) {
        if (bits == null) return@LaunchedEffect
        var start = -1L
        while (true) {
            val n = androidx.compose.runtime.withFrameNanos { it }
            if (start < 0) start = n
            tSec = (n - start) / 1_000_000_000f
            if (tSec > I_END) {
                onDone()
                break
            }
        }
    }

    val pt = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val ptTxt = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val ptBmp = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val add = remember { android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
    // os cacos saem em três grupos de cor: três chamadas de desenho no lugar de milhares
    val pts = remember { Array(3) { FloatArray(0) } }

    Canvas(Modifier.fillMaxSize()) {
        val b = bits ?: return@Canvas
        val t = tSec
        val u = size.width / VW
        val nc = drawContext.canvas.nativeCanvas
        nc.save()
        nc.scale(u, u)

        // fundo pronto
        ptBmp.alpha = 255
        nc.drawBitmap(b.sky, null, android.graphics.RectF(0f, 0f, VW, VH), ptBmp)

        // ---------- 1) o meteoro caindo
        if (t < I_FALL) {
            val p = t / I_FALL
            pt.xfermode = add
            pt.shader = null
            var i = 1
            while (i <= 46) {
                val k = i / 46f
                val q = max(0f, p - k * 0.34f)
                val pos = fall(q)
                val f = 1f - k
                val rr = (1.6f + 21f * f.pow(1.9f)) * (0.45f + 0.55f * p)
                pt.color = AColor.argb(
                    (255f * (0.2f * f * f + 0.03f)).toInt().coerceIn(0, 255),
                    lerp(150f, 196f, k).toInt(), lerp(200f, 120f, k).toInt(), 255,
                )
                nc.drawCircle(pos[0], pos[1], rr, pt)
                i++
            }
            val m = fall(p)
            pt.shader = RadialGradient(
                m[0], m[1], 46f,
                intArrayOf(AColor.argb(242, 255, 255, 255), AColor.argb(191, 122, 170, 255), AColor.argb(0, 122, 77, 255)),
                floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP,
            )
            nc.drawCircle(m[0], m[1], 46f, pt)
            pt.shader = null
            pt.color = AColor.WHITE
            nc.drawCircle(m[0], m[1], 7f, pt)
            pt.xfermode = null
        }

        // ---------- 2) o estouro e os cacos indo para o lugar das letras
        if (t >= I_FALL) {
            val k = ((t - I_FALL) / (I_BURST - I_FALL)).coerceIn(0f, 1f)
            val w = ((t - I_BURST) / (I_WORD - I_BURST)).coerceIn(0f, 1f)
            val pull = ease3(w)

            if (k < .5f) {
                val a = 1f - k * 2f
                pt.xfermode = add
                pt.shader = RadialGradient(
                    IMPACT_X, IMPACT_Y, 520f * (0.3f + k * 2f),
                    intArrayOf(AColor.argb((217 * a).toInt(), 255, 255, 255), AColor.argb((128 * a).toInt(), 122, 140, 255), AColor.argb(0, 5, 6, 13)),
                    floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP,
                )
                nc.drawRect(0f, 0f, VW, VH, pt)
                pt.shader = null
                pt.xfermode = null
            }
            if (k < 1f) {
                pt.style = Paint.Style.STROKE
                pt.strokeWidth = 3f * (1f - k) + .6f
                pt.color = AColor.argb((127 * max(0f, 1f - k)).toInt(), 176, 140, 255)
                nc.drawCircle(IMPACT_X, IMPACT_Y, 40f + k * 720f, pt)
                pt.style = Paint.Style.FILL
            }

            if (pull < .99f) {
                val n = b.shards.size
                for (g in 0 until 3) if (pts[g].size < n * 2) pts[g] = FloatArray(n * 2)
                val cnt = IntArray(3)
                for (q in b.shards) {
                    val kk = ((k - q.lag) / (1f - q.lag)).coerceIn(0f, 1f)
                    val dist = (40f + q.rad * 330f) * ease3(kk) * (1f - pull * .92f)
                    val bx = IMPACT_X + cos(q.ang) * dist
                    val by = IMPACT_Y + sin(q.ang) * dist * .8f
                    val g = q.tone
                    val i2 = cnt[g] * 2
                    pts[g][i2] = lerp(bx, q.tx, pull)
                    pts[g][i2 + 1] = lerp(by, q.ty, pull)
                    cnt[g]++
                }
                pt.xfermode = add
                pt.strokeCap = Paint.Cap.SQUARE
                val alpha = ((0.5f + 0.5f * (1f - pull)) * min(1f, k * 6f + .15f) * 255f).toInt().coerceIn(0, 255)
                for (g in 0 until 3) {
                    pt.color = when (g) {
                        0 -> AColor.argb(alpha, 238, 246, 255)
                        1 -> AColor.argb(alpha, 130, 160, 255)
                        else -> AColor.argb(alpha, 198, 146, 255)
                    }
                    pt.strokeWidth = if (g == 0) 2.2f else 2.6f
                    nc.drawPoints(pts[g], 0, cnt[g] * 2, pt)
                }
                pt.xfermode = null
            }
        }

        // ---------- 3) o nome
        if (t >= I_WORD - 0.65f) {
            val a = min(1f, (t - (I_WORD - 0.65f)) / 0.65f)
            val ai = (a * 255).toInt().coerceIn(0, 255)
            ptTxt.typeface = tf
            ptTxt.textSize = 132f
            ptTxt.letterSpacing = 0.076f
            ptTxt.style = Paint.Style.FILL
            val fm = ptTxt.fontMetrics
            val by = VH / 2f - 6f - (fm.ascent + fm.descent) / 2f
            ptTxt.shader = LinearGradient(
                VW / 2f - 330f, 0f, VW / 2f + 330f, 0f,
                intArrayOf(AColor.argb(ai, 58, 123, 255), AColor.argb(ai, 122, 77, 255), AColor.argb(ai, 192, 139, 255)),
                floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP,
            )
            ptTxt.setShadowLayer(38f, 0f, 0f, AColor.argb((191 * a).toInt(), 122, 110, 255))
            nc.drawText(LogoPainter.WORD, VW / 2f + 5f, by, ptTxt)
            ptTxt.clearShadowLayer()
            // fio de luz branca por cima das letras
            ptTxt.shader = null
            ptTxt.style = Paint.Style.STROKE
            ptTxt.strokeWidth = 0.7f
            ptTxt.color = AColor.argb((127 * a).toInt(), 255, 255, 255)
            nc.drawText(LogoPainter.WORD, VW / 2f + 5f, by, ptTxt)
            ptTxt.style = Paint.Style.FILL

            // o reflexo atravessa uma vez
            if (t > I_SHINE && t < I_SHINE + 1.2f) {
                val s = (t - I_SHINE) / 1.2f
                val x = lerp(VW / 2f - 420f, VW / 2f + 420f, s)
                ptTxt.shader = LinearGradient(
                    x - 150f, 0f, x + 150f, 0f,
                    intArrayOf(AColor.argb(0, 255, 255, 255), AColor.argb(191, 255, 255, 255), AColor.argb(0, 255, 255, 255)),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP,
                )
                nc.drawText(LogoPainter.WORD, VW / 2f + 5f, by, ptTxt)
            }

            // TV
            if (t > I_WORD - 0.15f) {
                val bb = min(1f, (t - (I_WORD - 0.15f)) / 0.55f)
                val bi = (bb * 255).toInt().coerceIn(0, 255)
                ptTxt.textSize = 44f
                ptTxt.letterSpacing = 0.41f
                val fm2 = ptTxt.fontMetrics
                ptTxt.shader = LinearGradient(
                    VW / 2f - 70f, 0f, VW / 2f + 70f, 0f,
                    intArrayOf(AColor.argb(bi, 58, 123, 255), AColor.argb(bi, 122, 77, 255), AColor.argb(bi, 192, 139, 255)),
                    floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP,
                )
                ptTxt.setShadowLayer(26f, 0f, 0f, AColor.argb((230 * bb).toInt(), 170, 140, 255))
                nc.drawText("TV", VW / 2f + 9f, VH / 2f + 78f + (1f - bb) * 10f - (fm2.ascent + fm2.descent) / 2f, ptTxt)
                ptTxt.clearShadowLayer()
            }
            ptTxt.shader = null
        }

        // escurece no fim
        if (t > I_OUT) {
            pt.color = AColor.argb((255 * min(1f, (t - I_OUT) / (I_END - I_OUT))).toInt().coerceIn(0, 255), 5, 6, 13)
            nc.drawRect(0f, 0f, VW, VH, pt)
        }
        nc.restore()
    }
}
