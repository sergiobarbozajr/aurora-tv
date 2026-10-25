package com.auroratv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Diagnóstico mostrado na tela SEM SINAL: o que houve, em que camada e o que o app está fazendo. */
class Diag(val title: String, val layer: String, val detail: String, val fix: String)

/**
 * Motor de reprodução: ExoPlayer configurado para IPTV, com classificação de erros,
 * correção automática (nova tentativa com espera crescente, troca .m3u8/.ts, renovação de login,
 * volta ao ponto ao vivo) e registro da saúde de cada canal.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Playback(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    val player: ExoPlayer

    /** Não nulo = tela SEM SINAL com o diagnóstico. */
    var diag by mutableStateOf<Diag?>(null)
        private set

    /** Verdadeiro até aparecer o primeiro quadro (mostra "Conectando…"). */
    var connecting by mutableStateOf(false)
        private set

    /** 1 = online, 2 = instável, 3 = offline. Chave: nome do canal. */
    val health = mutableStateMapOf<String, Int>()

    /** Chamado quando o servidor recusa a sessão (401/403): o app refaz o login em silêncio. */
    var onAuthExpired: (() -> Unit)? = null

    private var card: Card? = null
    private var url = ""
    private var attempt = 0
    private var swappedFormat = false
    private var bufferingSec = 0
    private var everReady = false
    private val delays = longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setAllowCrossProtocolRedirects(true) // servidores IPTV costumam redirecionar http <-> https
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 1500, 4000)
            .build()
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setLoadControl(load)
            .build()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    everReady = true
                    connecting = false
                    diag = null
                    attempt = 0
                    bufferingSec = 0
                    card?.let { if (it.kind == "stream") mark(it.name, 1) }
                }
            }
        })
        loadHealth()
    }

    // vigia o buffer: carregando por tempo demais = conexão lenta
    private val watch = object : Runnable {
        override fun run() {
            val c = card
            if (c != null && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
                bufferingSec++
                if (bufferingSec == 15) {
                    if (everReady && c.kind == "stream") mark(c.name, 2)
                    diag = Diag(
                        "Carregando devagar", "Rede · buffer",
                        "o vídeo não chegou em 15 s (conexão lenta ou servidor ocupado)",
                        "reconectando…"
                    )
                    retryLater()
                }
            } else bufferingSec = 0
            main.postDelayed(this, 1000)
        }
    }

    init {
        main.postDelayed(watch, 1000)
    }

    fun start(c: Card) {
        val u = c.url ?: return
        card = c
        url = u
        attempt = 0
        swappedFormat = false
        everReady = false
        diag = null
        connecting = true
        bufferingSec = 0
        main.removeCallbacks(retryRun)
        prepare()
    }

    fun stop() {
        main.removeCallbacks(retryRun)
        card = null
        diag = null
        connecting = false
        try {
            player.stop()
        } catch (e: Exception) {
        }
    }

    /** OK na tela SEM SINAL: tenta de novo na hora. */
    fun retryNow() {
        if (card == null) return
        attempt = 0
        main.removeCallbacks(retryRun)
        diag = diag?.let { Diag(it.title, it.layer, it.detail, "tentando agora…") }
        prepare()
    }

    private fun prepare() {
        try {
            val b = MediaItem.Builder().setUri(url)
            val low = url.lowercase()
            if (low.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (low.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD)
            player.setMediaItem(b.build())
            player.prepare()
            player.play()
        } catch (e: Exception) {
            diag = Diag("Endereço inválido", "Player", e.javaClass.simpleName, "verifique a lista")
        }
    }

    private val retryRun = Runnable { prepare() }

    private fun retryLater() {
        val c = card ?: return
        if (attempt >= delays.size) {
            if (c.kind == "stream") mark(c.name, 3)
            diag = diag?.let { Diag(it.title, it.layer, it.detail, "não voltou depois de ${delays.size} tentativas · OK tenta de novo · Voltar sai") }
            return
        }
        val d = delays[attempt]
        attempt++
        diag = diag?.let { Diag(it.title, it.layer, it.detail, it.fix.substringBefore(" ·") + " · tentativa $attempt de ${delays.size} em ${d / 1000} s") }
        main.removeCallbacks(retryRun)
        main.postDelayed(retryRun, d)
    }

    private fun swapFormat(): Boolean {
        if (swappedFormat || !url.contains("/live/")) return false
        val n = when {
            url.endsWith(".m3u8") -> url.removeSuffix(".m3u8") + ".ts"
            url.endsWith(".ts") -> url.removeSuffix(".ts") + ".m3u8"
            else -> return false
        }
        url = n
        swappedFormat = true
        return true
    }

    private fun handleError(e: PlaybackException) {
        connecting = false
        var httpCode = 0
        var dns = false
        var cause: Throwable? = e.cause
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) httpCode = cause.responseCode
            if (cause is java.net.UnknownHostException) dns = true
            cause = cause.cause
            depth++
        }
        val code = e.errorCode
        when {
            code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                // ficou para trás do ao vivo: volta ao ponto atual sem mostrar erro
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            dns -> diag = Diag("Servidor não encontrado", "Rede · DNS", "o nome do servidor não foi resolvido", "tentando de novo")
            httpCode == 401 || httpCode == 403 -> {
                diag = Diag(
                    "Acesso recusado", "Autenticação",
                    "o servidor respondeu $httpCode (sessão expirada ou limite de telas da conta)",
                    "renovando o login"
                )
                onAuthExpired?.invoke()
            }
            httpCode == 404 || httpCode == 410 -> {
                diag = Diag("Canal fora do ar", "Servidor", "o servidor respondeu $httpCode", "tentando outro formato")
                if (swapFormat()) {
                    prepare()
                    return
                }
            }
            httpCode >= 500 -> diag = Diag("Servidor com problema", "Servidor", "o servidor respondeu $httpCode", "nova tentativa")
            httpCode > 0 -> diag = Diag("Resposta inesperada", "Servidor", "código $httpCode", "nova tentativa")
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                diag = Diag("Tempo esgotado", "Rede", "o servidor demorou demais", "nova tentativa")
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                diag = Diag("Sem conexão com o servidor", "Rede", "a conexão caiu ou não abriu", "nova tentativa")
            code in 3000..3999 -> {
                diag = Diag("Formato não reconhecido", "Formato do vídeo", e.errorCodeName, "trocando .m3u8 ↔ .ts")
                if (swapFormat()) {
                    prepare()
                    return
                }
            }
            code in 4000..4999 -> diag = Diag("Falha ao decodificar", "Decodificador da TV", e.errorCodeName, "reiniciando o vídeo")
            else -> diag = Diag("Falha na reprodução", "Player", e.errorCodeName, "nova tentativa")
        }
        if (everReady) card?.let { if (it.kind == "stream") mark(it.name, 2) }
        retryLater()
    }

    // ---------------------------------------------------------------- saúde dos canais

    private fun mark(name: String, s: Int) {
        if (health[name] == s) return
        health[name] = s
        saveHealth()
    }

    private fun saveHealth() {
        try {
            val o = JSONObject()
            for ((k, v) in health) o.put(k, v)
            Store.health = o.toString()
        } catch (e: Exception) {
        }
    }

    private fun loadHealth() {
        try {
            val o = JSONObject(Store.health.ifBlank { "{}" })
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                health[k] = o.optInt(k, 0)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Teste leve de disponibilidade: só dos canais que você usa (favoritos e recentes, no máximo 15),
     * para não sobrecarregar o servidor nem bloquear a conta.
     */
    fun checkChannels(cards: List<Card>) {
        val list = cards.filter { it.kind == "stream" && it.url != null }.take(15)
        if (list.isEmpty()) return
        thread {
            for (c in list) {
                val u = c.url ?: continue
                var s = 3
                try {
                    val t0 = System.currentTimeMillis()
                    val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 6000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "okhttp/4.12.0")
                        setRequestProperty("Range", "bytes=0-2047")
                    }
                    val code = conn.responseCode
                    if (code in 200..299 || code == 206) {
                        conn.inputStream.use { it.read(ByteArray(2048)) }
                        s = if (System.currentTimeMillis() - t0 > 4000) 2 else 1
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                }
                val name = c.name
                main.post { mark(name, s) }
                try {
                    Thread.sleep(800)
                } catch (e: InterruptedException) {
                }
            }
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        player.release()
    }

    companion object {
        fun trackLabel(f: androidx.media3.common.Format, n: Int): String {
            val lang = f.language
            val name = when {
                !f.label.isNullOrBlank() -> f.label!!
                !lang.isNullOrBlank() && lang != "und" -> java.util.Locale(lang).getDisplayLanguage(java.util.Locale("pt", "BR")).replaceFirstChar { it.uppercase() }
                else -> "Faixa $n"
            }
            val extra = ArrayList<String>()
            if (f.channelCount > 0) extra.add(if (f.channelCount >= 6) "5.1" else if (f.channelCount == 2) "estéreo" else "${f.channelCount} canais")
            if ((f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) extra.add("audiodescrição")
            return if (extra.isEmpty()) name else name + " · " + extra.joinToString(" · ")
        }
    }
}
