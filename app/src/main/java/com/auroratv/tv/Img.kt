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

    private val ogCache = HashMap<String, String>()

    /** Imagem de destaque que a própria página publica (og:image): capa da matéria, ícone oficial na Play Store. */
    fun og(pageUrl: String): String {
        if (!pageUrl.startsWith("http")) return ""
        synchronized(ogCache) { ogCache[pageUrl]?.let { return it } }
        val r = try {
            val c = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
            }
            val html = c.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                // páginas grandes (Play Store) trazem a imagem depois de muitos scripts: lê até achar
                while (sb.length < 2_500_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.contains("og:image") && sb.indexOf(">", sb.lastIndexOf("og:image")) > 0) break
                }
                sb.toString()
            }
            val m = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"'](?:og:image|twitter:image)[\"']", RegexOption.IGNORE_CASE).find(html)
            val u = m?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
            if (u.isEmpty()) "" else URL(URL(pageUrl), u).toString()
        } catch (e: Throwable) {
            ""
        }
        android.util.Log.i("AuroraImg", "og " + pageUrl.take(90) + " -> " + (if (r.isEmpty()) "sem imagem" else "ok"))
        synchronized(ogCache) { ogCache[pageUrl] = r }
        return r
    }

    /** App não instalado: ícone oficial publicado na Play Store, para o cartão ter a identidade do serviço. */
    suspend fun storeIcon(c: Card): ImageBitmap? {
        val key = "app:" + c.name
        cache.get(key)?.let { return it }
        // página do app na Play Store (versão de TV ou de celular, a que tiver ícone publicado)
        val extra = mapOf(
            "netflix" to "com.netflix.mediaclient", "prime" to "com.amazon.avod.thirdpartyclient", "disney" to "com.disney.disneyplus",
            "max" to "com.wbd.stream", "apple tv" to "com.apple.atve.androidtv.appletv", "globoplay" to "com.globo.globotv",
            "youtube" to "com.google.android.youtube", "pluto" to "tv.pluto.android", "plex" to "com.plexapp.android", "mercado play" to "com.mercadolibre"
        )
        val n = c.name.lowercase()
        val pkgs = (c.pkgs + extra.filterKeys { n.startsWith(it) }.values).distinct()
        if (pkgs.isEmpty()) return null
        var u = ""
        for (pkg in pkgs) {
            u = withContext(Dispatchers.IO) { og("https://play.google.com/store/apps/details?id=$pkg&hl=pt_BR") }
            if (u.isNotEmpty()) break
        }
        if (u.isEmpty()) return null
        val b = web(u) ?: return null
        cache.put(key, b)
        cache.put("$key:icon", b)
        return b
    }

    fun isIcon(c: Card): Boolean = cache.get("app:" + c.name + ":icon") != null
}

@Composable
fun rememberCardImage(c: Card): ImageBitmap? {
    val context = LocalContext.current
    val wiki = c.img.isEmpty() && (c.kind == "vod" || c.kind == "series")
    val key = if (c.kind == "app") "app:" + c.name else if (wiki) "wiki:" + c.name
    else if (c.kind == "news" && c.img.isEmpty() && c.extra.startsWith("http")) "news:" + c.extra else c.img
    var b by remember(key) { mutableStateOf(if (key.isEmpty()) null else Img.peek(key)) }
    LaunchedEffect(key) {
        if (b == null && key.isNotEmpty()) {
            b = when {
                c.kind == "app" -> Img.app(context, c).first ?: Img.storeIcon(c)
                c.kind == "news" && c.img.isEmpty() -> {
                    // matéria sem foto no feed: usa a imagem que a própria página da notícia publica
                    val u = withContext(Dispatchers.IO) { Img.og(c.extra) }
                    if (u.isNotEmpty()) Img.web(u) else null
                }
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
