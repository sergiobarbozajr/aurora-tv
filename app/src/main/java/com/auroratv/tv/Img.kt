package com.auroratv.tv

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Imagens dos cartões: logos de canais (internet) e ícones/banners oficiais dos apps instalados na TV. */
object Img {
    private val cache = LruCache<String, ImageBitmap>(400)
    private val failed = HashSet<String>()

    fun peek(key: String): ImageBitmap? = cache.get(key)

    private val softCache = LruCache<ImageBitmap, ImageBitmap>(24)

    /** Versão desfocada barata: a imagem reduzida a 48 px e ampliada de volta (sem efeito pesado na GPU). */
    fun soft(src: ImageBitmap): ImageBitmap {
        softCache.get(src)?.let { return it }
        return try {
            val b = src.asAndroidBitmap()
            val w = 48
            val h = maxOf(1, (b.height * w) / maxOf(1, b.width))
            val small = android.graphics.Bitmap.createScaledBitmap(b, w, h, true)
            val r = small.asImageBitmap()
            softCache.put(src, r)
            r
        } catch (e: Throwable) {
            src
        }
    }

    suspend fun web(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        if (failed.contains(url)) return@withContext null
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "okhttp/4.12.0")
            }
            if (c.responseCode !in 200..299) {
                failed.add(url)
                return@withContext null
            }
            val bytes = c.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 320 && o.outHeight / (s * 2) >= 180) s *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            if (bmp == null) {
                failed.add(url)
                null
            } else {
                val ib = bmp.asImageBitmap()
                cache.put(url, ib)
                ib
            }
        } catch (e: Throwable) {
            failed.add(url)
            null
        }
    }

    /** Pacote instalado para um cartão de app: pelos nomes de pacote conhecidos ou pelo nome do app. */
    fun installedPkg(context: Context, c: Card): String? {
        val pm = context.packageManager
        for (p in c.pkgs) {
            if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) return p
        }
        return try {
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val token = Iptv.norm(c.name.substringBefore(" "))
            pm.queryIntentActivities(q, 0).firstOrNull { Iptv.norm(it.loadLabel(pm).toString()).contains(token) }?.activityInfo?.packageName
        } catch (e: Throwable) {
            null
        }
    }

    /** Banner oficial do app (o mesmo que aparece na tela inicial do Google TV), ou o ícone. */
    suspend fun app(context: Context, c: Card): Pair<ImageBitmap?, Boolean> = withContext(Dispatchers.Default) {
        val key = "app:" + c.name
        cache.get(key)?.let { return@withContext Pair(it, true) }
        try {
            val pkg = installedPkg(context, c) ?: return@withContext Pair(null, false)
            val pm = context.packageManager
            var banner = true
            val d = pm.getApplicationBanner(pkg) ?: run {
                banner = false
                pm.getApplicationIcon(pkg)
            }
            val w = if (banner) 320 else 192
            val h = if (banner) 180 else 192
            val ib = d.toBitmap(w, h).asImageBitmap()
            cache.put(key, ib)
            if (!banner) cache.put("$key:icon", ib)
            Pair(ib, banner)
        } catch (e: Throwable) {
            Pair(null, false)
        }
    }

    fun isIcon(c: Card): Boolean = cache.get("app:" + c.name + ":icon") != null
}

@Composable
fun rememberCardImage(c: Card): ImageBitmap? {
    val context = LocalContext.current
    val wiki = c.img.isEmpty() && (c.kind == "vod" || c.kind == "series")
    val key = if (c.kind == "app") "app:" + c.name else if (wiki) "wiki:" + c.name else c.img
    var b by remember(key) { mutableStateOf(if (key.isEmpty()) null else Img.peek(key)) }
    LaunchedEffect(key) {
        if (b == null && key.isNotEmpty()) {
            b = when {
                c.kind == "app" -> Img.app(context, c).first
                wiki -> {
                    // sem capa na lista: procura pelo nome na Wikipédia
                    val u = withContext(Dispatchers.IO) { Wiki.cover(c.name, c.kind == "series") }
                    if (u.isNotEmpty()) Img.web(u) else null
                }
                else -> Img.web(c.img)
            }
        }
    }
    return b
}
