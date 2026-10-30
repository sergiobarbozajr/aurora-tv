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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
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

    /** Onde o filme/episódio vai continuar (ms) e de onde continuou, para o aviso na tela. */
    private var resumeAt = 0L
    var resumedFrom by mutableStateOf(0L)
        private set
    private var saveSec = 0

    /** Guarda o ponto atual. Perto do fim (últimos 5% ou 2 min) apaga, para o próximo play começar do início. */
    fun savePos() {
        val c = card ?: return
        if (c.kind == "stream" || !everReady) return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur > 0 && (pos > dur * 0.95 || dur - pos < 120_000L)) Store.resumeSet(url, 0L)
        else if (pos > 20_000L) Store.resumeSet(url, pos)
    }

    /** "Começar do início" no menu do player. */
    fun restart() {
        Store.resumeSet(url, 0L)
        resumeAt = 0L
        resumedFrom = 0L
        player.seekTo(0L)
        player.play()
    }

    /** Não nulo = tela SEM SINAL com o diagnóstico. */
    var diag by mutableStateOf<Diag?>(null)
        private set

    /** Verdadeiro até aparecer o primeiro quadro (mostra "Conectando…"). */
    var connecting by mutableStateOf(false)
        private set

    /** 1 = online, 2 = instável, 3 = offline. Chave: nome do canal. */
    val health = mutableStateMapOf<String, Int>()

    /**
     * O que não reproduziu nesta sessão. Fica oculto das listas até você abrir o app de novo —
     * assim um canal que caiu agora não fica no caminho, mas ganha nova chance amanhã.
     */
    val falhou = mutableStateMapOf<String, Boolean>()

    /** Chamado quando o servidor recusa a sessão (401/403): o app refaz o login em silêncio. */
    var onAuthExpired: (() -> Unit)? = null

    var card: Card? = null
        private set
    private var url = ""
    private var attempt = 0
    private var swappedFormat = false
    private var bufferingSec = 0
    private var everReady = false
    private val delays = longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)
    private var limitMode = false
    private var limitTries = 0
    private val triedExt = HashSet<String>()

    /** Tempo máximo esperando o vídeo começar antes de chamar de "sem sinal". */
    val patienceSec = 25

    /** Chamado quando a TV não aguenta o realce de imagem: o app desliga o realce e segue tocando. */
    var onEnhanceFailed: (() -> Unit)? = null
    private var effectsOn = false

    // conexão reforçada: OkHttp com pool de conexões, nova tentativa automática de conexão
    // e redirecionamentos entre http e https
    private val ok = okhttp3.OkHttpClient.Builder()
        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
        .build()
    private val ds = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(ok as okhttp3.Call.Factory).setUserAgent("okhttp/4.12.0"))

    // como os players de IPTV: o MPEG-TS de canal ao vivo nem sempre começa num quadro-chave,
    // então o leitor aceita quadros não-chave e detecta as unidades de acesso por conta própria
    private val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
    private val extractors = DefaultExtractorsFactory().setTsExtractorFlags(tsFlags).setConstantBitrateSeekingEnabled(true)

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioFx.attach(audioSessionId)
        }

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
    }

    private fun build(): ExoPlayer {
        val load = DefaultLoadControl.Builder()
            // buffer equilibrado para a memória da TV: menos pausas por falta de RAM e coleta de lixo
            .setBufferDurationsMs(15000, 50000, 1500, 3000)
            .build()
        // se o decodificador principal da TV falhar, tenta o próximo disponível
        // Som IA entra na cadeia de áudio do player (PCM estéreo e 5.1)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(SomIaProcessor()))
                    .build()
        }.setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds, extractors))
            .setLoadControl(load)
            .build()
        p.playWhenReady = true
        // mantém Wi-Fi e processador acordados durante a reprodução (streams longos sem toque no controle)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        // áudio e legenda em português do Brasil quando o conteúdo tiver essa faixa
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("pt-BR", "pt", "por")
            .setPreferredTextLanguages("pt-BR", "pt", "por")
            .build()
        AudioFx.player = p
        AudioFx.attach(p.audioSessionId)
        p.addListener(listener)
        return p
    }

    /** O player atual. Muda quando é preciso recriar (por exemplo, para tirar o realce de imagem). */
    var player: ExoPlayer by mutableStateOf(build())
        private set

    init {
        loadHealth()
        current = this
    }

    /** Troca o player por um novo, sem efeitos de vídeo, e retoma o que estava tocando. */
    private fun rebuild() {
        val old = player
        val pos = old.currentPosition
        val vod = card?.kind != "stream"
        old.removeListener(this.listener)
        player = build()
        effectsOn = false
        aiKey = ""
        try {
            old.release()
        } catch (e: Exception) {
        }
        if (card != null) {
            prepare()
            if (vod && pos > 0) player.seekTo(pos)
        }
    }

    /** O que o Assistente IA está fazendo agora (selo no player). */
    var aiLabel by mutableStateOf("")
        private set
    private var aiKey = ""
    private var aiMode = 1

    /** A TV perdeu quadros com o reforço neste vídeo: segue sem processamento na GPU. */
    private var gpuOff = false
    private val dropHist = LongArray(10)
    private var dropIdx = 0

    /**
     * Assistente IA de imagem (modo 0 desligado, 1 automático, 2..4 manual).
     * Sem efeito nenhum = o vídeo vai direto para o hardware da TV (super-resolução e AI-PQ do chip).
     */
    fun setAi(mode: Int) {
        aiMode = mode
        val plan = ImageAi.plan(mode, player.videoFormat, isHdrFormat(player), gpuOff)
        aiLabel = plan.label
        if (plan.key == aiKey && (plan.effects.isEmpty() == !effectsOn)) return
        android.util.Log.i("AuroraAI", "plano=${plan.key} rótulo=${plan.label} vídeo=${player.videoFormat?.width}x${player.videoFormat?.height} ${ImageAi.summary()}")
        aiKey = plan.key
        if (plan.effects.isEmpty()) {
            if (effectsOn) main.post { rebuild() }
            return
        }
        try {
            player.setVideoEffects(plan.effects)
            effectsOn = true
        } catch (e: Throwable) {
            effectsOn = false
        }
    }

    /** Quadros perdidos pelo decodificador nos últimos 10 s (medido de verdade na TV). */
    var droppedLast10s = 0L
        private set

    private fun watchDrops() {
        val dc = player.videoDecoderCounters ?: return
        dc.ensureUpdated()
        val now = dc.droppedBufferCount.toLong() + dc.skippedOutputBufferCount
        val old = dropHist[dropIdx]
        dropHist[dropIdx] = now
        dropIdx = (dropIdx + 1) % dropHist.size
        droppedLast10s = if (old in 1..now) now - old else 0L
        // com o reforço ligado, a TV começou a engasgar: desliga só o reforço e segue tocando
        if (effectsOn && player.isPlaying && droppedLast10s > 24) {
            android.util.Log.i("AuroraAI", "fluidez: $droppedLast10s quadros perdidos em 10 s, reforço desligado")
            gpuOff = true
            aiKey = ""
            dropHist.fill(0L)
            main.post {
                rebuild()
                setAi(aiMode)
            }
        }
    }

    // vigia o buffer: carregando por tempo demais = conexão lenta
    private val watch = object : Runnable {
        override fun run() {
            val c = card
            if (c != null && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
                bufferingSec++
                if (bufferingSec == patienceSec) {
                    if (everReady && c.kind == "stream") mark(c.name, 2)
                    diag = Diag(
                        "Carregando devagar", "Rede · buffer",
                        "o vídeo não chegou em $patienceSec s (conexão lenta ou servidor ocupado)",
                        "reconectando…"
                    )
                    retryLater()
                }
            } else bufferingSec = 0
            if (c != null && player.isPlaying) watchDrops()
            // a cada 10 s tocando, guarda o ponto (se a TV desligar de repente, não perde)
            if (c != null && player.isPlaying && ++saveSec >= 10) {
                saveSec = 0
                savePos()
            }
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
        limitMode = false
        limitTries = 0
        triedExt.clear()
        everReady = false
        diag = null
        connecting = true
        bufferingSec = 0
        resumeAt = if (c.kind == "stream") 0L else Store.resumeGet(u)
        gpuOff = false
        aiKey = ""
        dropHist.fill(0L)
        // o vídeo anterior estava com reforço: começa o novo num player limpo (direto para a IA da TV)
        if (effectsOn) {
            val old = player
            old.removeListener(listener)
            player = build()
            effectsOn = false
            try {
                old.release()
            } catch (e: Exception) {
            }
        }
        resumedFrom = resumeAt
        main.removeCallbacks(retryRun)
        prepare()
    }

    fun stop() {
        savePos()
        resumedFrom = 0L
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
        // reconectando no meio do filme: continua de onde estava, não do começo
        if (card?.kind != "stream" && everReady && player.currentPosition > 1000L) resumeAt = player.currentPosition
        try {
            val b = MediaItem.Builder().setUri(url)
            val low = url.lowercase()
            if (low.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (low.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD)
            val item = b.build()
            val src = when {
                low.contains(".m3u8") -> HlsMediaSource.Factory(ds)
                    .setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true))
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item)
                low.contains(".mpd") -> DashMediaSource.Factory(ds).createMediaSource(item)
                else -> ProgressiveMediaSource.Factory(ds, extractors).createMediaSource(item)
            }
            if (card?.kind != "stream" && resumeAt > 0L) player.setMediaSource(src, resumeAt) else player.setMediaSource(src)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            diag = Diag("Endereço inválido", "Player", e.javaClass.simpleName, "verifique a lista")
        }
    }

    private val retryRun = Runnable { prepare() }

    private fun retryLater() {
        val c = card ?: return
        if (limitMode) {
            // conta em uso em outra tela: tenta a cada 10 s, por até 5 minutos, até a outra tela liberar
            limitTries++
            if (limitTries > 30) {
                diag = diag?.let { Diag(it.title, it.layer, it.detail, "a outra tela não liberou · OK tenta de novo · Voltar sai") }
                return
            }
            diag = diag?.let { Diag(it.title, it.layer, it.detail, "reconectando sozinho quando a outra tela fechar · tentativa $limitTries em 10 s") }
            main.removeCallbacks(retryRun)
            main.postDelayed(retryRun, 10000)
            return
        }
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
        // filmes e episódios: tenta as outras extensões que o servidor costuma aceitar
        if (url.contains("/movie/") || url.contains("/series/")) {
            val base = url.substringBeforeLast(".")
            val cur = url.substringAfterLast(".")
            triedExt.add(cur)
            for (e in listOf("mp4", "mkv", "ts", "m3u8", "avi")) {
                if (!triedExt.contains(e)) {
                    triedExt.add(e)
                    url = "$base.$e"
                    return true
                }
            }
            return false
        }
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
            httpCode == 401 -> {
                diag = Diag("Acesso recusado", "Autenticação", "o servidor respondeu 401 (sessão expirada)", "renovando o login")
                onAuthExpired?.invoke()
            }
            httpCode == 403 || httpCode == 429 || httpCode == 458 || httpCode == 509 -> {
                // quase sempre: a conta já está aberta em outro aparelho ou outro app de IPTV
                diag = Diag(
                    "Conta em uso em outra tela", "Autenticação · limite de telas",
                    "o servidor respondeu $httpCode: o limite de conexões da assinatura foi atingido",
                    "renovando o login"
                )
                if (!limitMode) onAuthExpired?.invoke()
                limitMode = true
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
            code in 7000..7999 -> {
                // a TV não aguentou o processamento de imagem: desliga o reforço neste vídeo e toca sem ele
                gpuOff = true
                aiKey = ""
                onEnhanceFailed?.invoke()
                main.post {
                    rebuild()
                    setAi(aiMode)
                }
                return
            }
            code in 4000..4999 -> diag = Diag("Falha ao decodificar", "Decodificador da TV", e.errorCodeName, "reiniciando o vídeo")
            else -> diag = Diag("Falha na reprodução", "Player", e.errorCodeName, "nova tentativa")
        }
        if (everReady) card?.let { if (it.kind == "stream") mark(it.name, 2) }
        retryLater()
    }

    // ---------------------------------------------------------------- saúde dos canais

    private fun mark(name: String, s: Int) {
        if (s == 3) falhou[name] = true
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
        player.removeListener(listener)
        player.release()
    }

    companion object {
        /** O player em uso, para o app pausar e guardar o ponto quando a TV dorme ou sai do app. */
        var current: Playback? = null
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
