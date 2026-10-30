package com.astrolume.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/** Preferências do launcher (tema, ordem e apps ocultos). Tudo fica só na TV. */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = c.getSharedPreferences("home", Context.MODE_PRIVATE)
    }

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** ordem dos apps (pacotes) */
    var order: List<String>
        get() = sp.getString("order", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("order", v.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    /** pacotes já vistos: app instalado depois aparece no fim da faixa */
    var seen: Set<String>
        get() = sp.getStringSet("seen", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("seen", v).apply()
}

class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val leanback: Boolean,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val c1: Color,
    val c2: Color,
) {
    var visible by mutableStateOf(true)

    fun intent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        component = this@AppEntry.component
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

object Apps {
    // nomes amigáveis dos apps que vêm com a TV
    private val NICE = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.disney.disneyplus" to "Disney+",
        "com.wbd.stream" to "Max",
        "com.globo.globotv" to "Globoplay",
        "com.spotify.tv.android" to "Spotify",
        "com.auroratv.tv" to "AstroLume TV",
    )

    /** primeiros da faixa quando ainda não há ordem salva */
    private val FIRST = listOf(
        "com.auroratv.tv", "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.wbd.stream", "com.globo.globotv", "com.spotify.tv.android",
    )

    private fun bmp(d: Drawable?, w: Int, h: Int): Bitmap? = try {
        d?.toBitmap(w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) {
        null
    }

    private fun colors(b: Bitmap?, pkg: String): Pair<Color, Color> {
        val base = try {
            if (b == null) null else {
                val p = Palette.from(b).maximumColorCount(12).generate()
                (p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb
            }
        } catch (e: Throwable) {
            null
        } ?: (0xFF000000.toInt() or (pkg.hashCode() and 0x7F7F7F))
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(base, hsl)
        val light = hsl.copyOf().also { it[2] = (it[2] + 0.12f).coerceAtMost(0.62f); it[1] = (it[1] * 1.05f).coerceAtMost(1f) }
        val dark = hsl.copyOf().also { it[2] = (it[2] * 0.55f).coerceAtLeast(0.08f) }
        return Color(ColorUtils.HSLToColor(light)) to Color(ColorUtils.HSLToColor(dark))
    }

    /** Todos os apps que abrem pela tela inicial (com faixa da TV quando existir). Roda fora da tela principal. */
    fun load(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val seenPk = HashSet<String>()
        val raw = ArrayList<Pair<ResolveInfo, Boolean>>()
        fun q(cat: String, lb: Boolean) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val list = try { pm.queryIntentActivities(i, PackageManager.MATCH_ALL) } catch (e: Throwable) { emptyList() }
            for (r in list) {
                val pk = r.activityInfo.packageName
                if (pk == c.packageName || !seenPk.add(pk)) continue
                raw.add(r to lb)
            }
        }
        q(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        q(Intent.CATEGORY_LAUNCHER, false)
        val bw = (240 * c.resources.displayMetrics.density / 2f).toInt().coerceIn(160, 480)
        val bh = bw * 9 / 16
        val iz = (bw * 0.4f).toInt()
        val apps = raw.map { (r, lb) ->
            val ai = r.activityInfo
            val cn = ComponentName(ai.packageName, ai.name)
            val bannerD = try { ai.loadBanner(pm) ?: pm.getApplicationBanner(ai.packageName) } catch (e: Throwable) { null }
            val banner = bmp(bannerD, bw, bh)
            val icon = if (banner == null) bmp(try { ai.loadIcon(pm) } catch (e: Throwable) { null }, iz, iz) else null
            val (a, b) = colors(banner ?: icon, ai.packageName)
            AppEntry(
                ai.packageName, cn, NICE[ai.packageName] ?: (r.loadLabel(pm)?.toString() ?: ai.packageName),
                lb, banner?.asImageBitmap(), icon?.asImageBitmap(), a, b,
            )
        }
        return arrange(apps)
    }

    /** aplica a ordem salva; apps novos entram no fim e visíveis */
    fun arrange(apps: List<AppEntry>): List<AppEntry> {
        val order = Prefs.order
        val hidden = Prefs.hidden
        val seen = Prefs.seen
        val byPk = apps.associateBy { it.pkg }
        val out = ArrayList<AppEntry>()
        if (order.isEmpty()) {
            FIRST.mapNotNullTo(out) { byPk[it] }
            apps.filter { it.pkg !in FIRST }.sortedBy { it.label.lowercase() }.forEach { out.add(it) }
        } else {
            order.mapNotNullTo(out) { byPk[it] }
            apps.filter { it.pkg !in order }.sortedBy { it.label.lowercase() }.forEach { out.add(it) }
        }
        // primeira vez: mostra no máximo 9 na faixa, o resto vai para o +
        val firstRun = seen.isEmpty()
        out.forEachIndexed { i, a ->
            a.visible = when {
                a.pkg in hidden -> false
                firstRun -> i < 9
                else -> true
            }
        }
        if (firstRun) Prefs.hidden = out.filter { !it.visible }.map { it.pkg }.toSet()
        Prefs.seen = seen + out.map { it.pkg }
        return out
    }

    fun save(apps: List<AppEntry>) {
        Prefs.order = apps.map { it.pkg }
        Prefs.hidden = apps.filter { !it.visible }.map { it.pkg }.toSet()
    }
}
