package com.astrolume.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_medium, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
val InterDisplay = FontFamily(Font(R.font.inter_display_black_italic, FontWeight.Black, FontStyle.Italic))

/** Cores de cada tema (mesmos do simulador): 0 claro, 1 espacial, 2+ imagens do AstroLume TV. */
class Pal(
    val ink: Color, val dim: Color, val card: Color, val cardHi: Color, val line: Color,
    val modal: Color, val focus: Color, val dark: Boolean, val image: Boolean,
)

fun palette(theme: Int): Pal = when {
    theme == 0 -> Pal(Color(0xFF14161C), Color(0xFF6B7080), Color(0xA6FFFFFF), Color.White, Color(0x0F000000), Color.White, Color.White, false, false)
    theme == 1 -> Pal(Color(0xFFF2F5FF), Color(0xFF9AA3C0), Color(0x12FFFFFF), Color(0x2EFFFFFF), Color(0x14FFFFFF), Color(0xFF141826), Color(0xFFCFE9FF), true, false)
    else -> Pal(Color(0xFFF2F5FF), Color(0xFFDFE4F2), Color(0x80080A12), Color(0x38FFFFFF), Color(0x24FFFFFF), Color(0xFF141826), Color.White, true, true)
}

val THEMES = listOf(
    "Claro" to 0, "Espacial escuro (AstroLume)" to 0,
    "Galáxia" to R.drawable.bg_nebula, "Planetas" to R.drawable.bg_planets, "Órbitas" to R.drawable.bg_orbits,
    "Mar alienígena" to R.drawable.bg_ocean, "Deserto" to R.drawable.bg_desert, "Plutão gelado" to R.drawable.bg_pluto,
    "Cratera ao sol" to R.drawable.bg_crater, "Marte" to R.drawable.bg_mars, "Tempestade em Júpiter" to R.drawable.bg_jupiter,
    "Vênus vulcânico" to R.drawable.bg_lava, "Terra vista da Lua" to R.drawable.bg_earthrise,
)

/** Fundo estático (sem desfoque em tempo real: a GPU da TV agradece). */
@Composable
fun Backdrop(theme: Int) {
    when {
        theme == 0 -> Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFFEEF1F6), Color(0xFFDFE5EF)), Offset(0f, 0f), Offset(900f, 1500f)))
                .drawWithCache {
                    val b = Brush.radialGradient(listOf(Color.White, Color.Transparent), Offset(size.width * .3f, 0f), size.width * .55f)
                    onDrawBehind { drawRect(b) }
                }
        )
        theme == 1 -> Box(
            Modifier.fillMaxSize().background(Color(0xFF05060D)).drawWithCache {
                val w = size.width; val h = size.height
                val n1 = Brush.radialGradient(listOf(Color(0x663A2A7A), Color.Transparent), Offset(w * .75f, -h * .1f), w * .5f)
                val n2 = Brush.radialGradient(listOf(Color(0x550F3A5A), Color.Transparent), Offset(w * .1f, h * .4f), w * .45f)
                val r = Random(7)
                val stars = List(520) {
                    val band = r.nextFloat() < .4f
                    val y = if (band) h * .8f + (r.nextFloat() + r.nextFloat() + r.nextFloat() - 1.5f) * h * .12f else r.nextFloat() * h
                    Triple(Offset(r.nextFloat() * w, y), .4f + r.nextFloat() * 1.3f, .25f + r.nextFloat() * .7f)
                }
                onDrawBehind {
                    drawRect(n1); drawRect(n2)
                    for ((o, rad, a) in stars) drawCircle(Color(0xFFE6ECFF).copy(alpha = a), rad * density / 2f, o)
                }
            }
        )
        else -> Box(Modifier.fillMaxSize()) {
            val res = THEMES[theme.coerceIn(2, THEMES.size - 1)].second
            Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    val a = Brush.horizontalGradient(0f to Color(0x8C000000), .55f to Color(0x26000000), 1f to Color(0x0D000000))
                    val b = Brush.verticalGradient(.6f to Color.Transparent, 1f to Color(0x73000000))
                    onDrawBehind { drawRect(a); drawRect(b) }
                }
            )
        }
    }
}

/** Ícones de linha simples (os mesmos do simulador), desenhados numa grade 24×24. */
@Composable
fun LineIcon(name: String, color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
        fun p(vararg pts: Float, close: Boolean = false) {
            val path = Path()
            path.moveTo(pts[0] * s, pts[1] * s)
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
            if (close) path.close()
            drawPath(path, color, style = st)
        }
        fun rr(x: Float, y: Float, w: Float, h: Float) =
            drawRoundRect(color, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(2 * s), style = st)
        when (name) {
            "tv" -> { rr(3f, 5f, 18f, 12f); p(8f, 21f, 16f, 21f) }
            "hdmi" -> { p(6f, 4f, 18f, 4f, 18f, 11f, 16f, 14f, 8f, 14f, 6f, 11f, close = true); p(9f, 14f, 9f, 20f, 15f, 20f, 15f, 14f) }
            "grid" -> { for (x in listOf(6f, 12f, 18f)) for (y in listOf(6f, 12f, 18f)) drawCircle(color, 1.4f * s, Offset(x * s, y * s)) }
            "gear" -> {
                drawCircle(color, 3f * s, Offset(12f * s, 12f * s), style = st)
                p(12f, 2f, 12f, 5f); p(12f, 19f, 12f, 22f); p(2f, 12f, 5f, 12f); p(19f, 12f, 22f, 12f)
                p(5f, 5f, 7f, 7f); p(17f, 17f, 19f, 19f); p(5f, 19f, 7f, 17f); p(17f, 7f, 19f, 5f)
            }
            "set" -> { rr(3f, 5f, 18f, 12f); p(7f, 9f, 13f, 9f); p(7f, 13f, 17f, 13f) }
            else -> {}
        }
    }
}
