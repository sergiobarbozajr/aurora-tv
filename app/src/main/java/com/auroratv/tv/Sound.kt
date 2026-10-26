package com.auroratv.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Tema espacial de piano e violino, original, gerado por código (sem arquivo de áudio) e tocado em ciclo. */
object Sound {
    private const val SR = 22050
    private val vol = floatArrayOf(0f, 0.25f, 0.5f, 0.9f)
    private val lock = Any()
    private var track: AudioTrack? = null
    private var building = false
    private var level = 0
    private var paused = false
    private var appCtx: android.content.Context? = null
    private var fileUri: String? = null
    private var mp: android.media.MediaPlayer? = null
    private var clickTrack: AudioTrack? = null

    /** Sons das teclas (toque curto, como teclado de celular). */
    @Volatile
    var keysOn = true

    fun init(c: android.content.Context) {
        appCtx = c.applicationContext
    }

    /** Música do aparelho: um arquivo de áudio do usuário (pendrive ou memória). null = tema do app. */
    fun useFile(uri: String?) {
        fileUri = uri
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        apply(level)
    }

    private fun applyFile(l: Int) {
        try { track?.pause() } catch (e: Exception) {}
        val ctx = appCtx ?: return
        if (l == 0) {
            try { mp?.pause() } catch (e: Exception) {}
            return
        }
        try {
            var m = mp
            if (m == null) {
                m = android.media.MediaPlayer()
                m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                m.setDataSource(ctx, android.net.Uri.parse(fileUri))
                m.isLooping = true
                m.prepare()
                mp = m
            }
            m.setVolume(vol[l], vol[l])
            if (!paused) m.start()
        } catch (e: Exception) {
            try { mp?.release() } catch (e2: Exception) {}
            mp = null
        }
    }

    /** Tipo do som de tecla: 0 = desligado; 1 a 6 = estilos. */
    @Volatile
    var keyType = 1

    /** Volume das teclas, 0 a 10 (independente do volume da TV para o vídeo). */
    var keyVol = 6
    private val clickExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var clickBuilt = -1

    private fun clickPcm(type: Int): ShortArray {
        val dur = when (type) { 3 -> 0.06; 6 -> 0.05; else -> 0.035 }
        val n = (dur * SR).toInt()
        val pcm = ShortArray(n)
        val rnd = java.util.Random(type.toLong())
        for (i in 0 until n) {
            val x = i.toDouble() / SR
            val v = when (type) {
                // 1 clique suave
                1 -> (sin(2 * PI * 1750.0 * x) * 0.6 + sin(2 * PI * 2600.0 * x) * 0.25) * exp(-x * 140.0) * 0.35
                // 2 tecla de celular (dois tons, como o discador)
                2 -> (sin(2 * PI * 852.0 * x) + sin(2 * PI * 1336.0 * x)) * 0.5 * exp(-x * 60.0) * 0.32
                // 3 bolha (tom que sobe)
                3 -> sin(2 * PI * (500.0 + 2600.0 * x / dur) * x) * exp(-x * 55.0) * 0.38
                // 4 madeira (batida curta e grave)
                4 -> (sin(2 * PI * 620.0 * x) * 0.7 + (rnd.nextDouble() * 2 - 1) * 0.3) * exp(-x * 190.0) * 0.45
                // 5 cristal (agudo com brilho)
                5 -> (sin(2 * PI * 3520.0 * x) * 0.5 + sin(2 * PI * 5280.0 * x) * 0.3) * exp(-x * 90.0) * 0.25
                // 6 digital (bipe quadrado curto)
                else -> (if (sin(2 * PI * 1200.0 * x) >= 0) 1.0 else -1.0) * exp(-x * 70.0) * 0.12
            }
            pcm[i] = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    /** Toque de tecla: roda numa linha separada, para não atrasar a navegação. */
    fun click() {
        val type = keyType
        if (type == 0 || keyVol <= 0) return
        clickExec.execute {
            try {
                var t = clickTrack
                if (t == null || clickBuilt != type) {
                    try { t?.release() } catch (e: Throwable) {}
                    val pcm = clickPcm(type)
                    t = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    t.write(pcm, 0, pcm.size)
                    clickTrack = t
                    clickBuilt = type
                }
                t.stop()
                t.reloadStaticData()
                val v = (keyVol.coerceIn(0, 10) / 10f).let { it * it }
                t.setVolume(v)
                if (v > 0f) t.play()
            } catch (e: Throwable) {
            }
        }
    }

    fun apply(l: Int) {
        if (fileUri != null) {
            level = l
            applyFile(l)
            return
        }
        try { mp?.pause() } catch (e: Exception) {}
        synchronized(lock) {
            level = l
            if (l == 0) {
                try { track?.pause() } catch (e: Exception) {}
                return
            }
            val t = track
            if (t != null) {
                try {
                    t.setVolume(vol[level])
                    if (!paused) t.play()
                } catch (e: Exception) {}
                return
            }
            if (building) return
            building = true
        }
        thread {
            try {
                val pcm = render()
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(pcm, 0, pcm.size)
                t.setLoopPoints(0, pcm.size, -1)
                synchronized(lock) {
                    track = t
                    building = false
                    t.setVolume(vol[level])
                    if (level > 0 && !paused) t.play()
                }
            } catch (e: Throwable) {
                synchronized(lock) { building = false }
            }
        }
    }

    /** Pausa durante vídeos e quando o app sai de cena. */
    fun setPaused(p: Boolean) {
        try {
            val m = mp
            if (m != null) {
                if (p) m.pause() else if (level > 0 && fileUri != null) m.start()
            }
        } catch (e: Exception) {}
        if (fileUri != null) {
            paused = p
            return
        }
        synchronized(lock) {
            paused = p
            val t = track ?: return
            try {
                if (p) t.pause() else if (level > 0) t.play()
            } catch (e: Exception) {}
        }
    }

    fun release() {
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        try { clickTrack?.release() } catch (e: Exception) {}
        clickTrack = null
        synchronized(lock) {
            try { track?.stop() } catch (e: Exception) {}
            try { track?.release() } catch (e: Exception) {}
            track = null
        }
    }

    private val chords = arrayOf(
        intArrayOf(50, 57, 61, 66, 69, 73),
        intArrayOf(47, 54, 59, 62, 66, 71),
        intArrayOf(43, 50, 55, 59, 62, 66),
        intArrayOf(45, 52, 57, 61, 64, 69)
    )
    private val seq = intArrayOf(0, 2, 3, 4, 5, 4, 3, 2)

    private fun render(): ShortArray {
        val stepSec = 0.62
        val steps = 32
        val total = (steps * stepSec * SR).toInt()
        val buf = FloatArray(total)
        val rnd = java.util.Random(11)

        fun note(midi: Int, startSec: Double, amp: Float, durSec: Double) {
            val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val n = (durSec * SR).toInt()
            val s0 = (startSec * SR).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val env = min(1.0, t / 0.012) * exp(-t * 2.3)
                val ph = 2.0 * PI * f * t
                val v = (sin(ph) + 0.35 * sin(2 * ph) + 0.12 * sin(3 * ph)) * env * amp
                val idx = (s0 + i) % total
                buf[idx] += v.toFloat()
            }
        }

        for (s in 0 until steps) {
            val chord = chords[(s / 8) % 4]
            val k = s % 8
            val t0 = s * stepSec
            if (k == 0) note(chord[0] - 12, t0, 0.5f, 3.4)
            val m = chord[seq[k] % chord.size] + (if (k % 4 == 3) 12 else 0)
            note(m, t0, if (k == 0) 0.30f else 0.22f, 2.8)
            if (rnd.nextFloat() < 0.22f) note(chord[5] + 12 + (if (rnd.nextBoolean()) 0 else 2), t0 + 0.27, 0.10f, 2.6)
        }

        // violino: notas longas, entrada lenta, vibrato suave (soma de harmônicos de corda friccionada)
        fun violin(midi: Int, startSec: Double, amp: Float, durSec: Double) {
            val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val n = (durSec * SR).toInt()
            val s0 = (startSec * SR).toInt()
            val att = 0.9
            val rel = 1.2
            var ph = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val env = min(1.0, t / att) * min(1.0, (durSec - t) / rel).coerceAtLeast(0.0)
                val vib = 1.0 + 0.0045 * sin(2 * PI * 5.2 * t) * min(1.0, t / 1.5)
                ph += 2.0 * PI * f * vib / SR
                var v = 0.0
                for (h in 1..7) v += sin(h * ph) / (h * 1.15)
                val idx = (s0 + i) % total
                buf[idx] += (v * env * amp).toFloat()
            }
        }

        // linha lenta do violino sobre os acordes: duas notas longas por acorde
        val line = intArrayOf(4, 3, 3, 2, 4, 5, 2, 3)
        for (c in 0 until 8) {
            val chord = chords[(c / 2) % 4]
            val t0 = c * 4 * stepSec + 0.3
            violin(chord[line[c] % chord.size] + 12, t0, 0.055f, 4 * stepSec + 1.0)
        }

        // órgão: acordes longos e graves, entrada bem lenta, como um pad de igreja no espaço
        fun organ(midi: Int, startSec: Double, amp: Float, durSec: Double) {
            val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val n = (durSec * SR).toInt()
            val s0 = (startSec * SR).toInt()
            val harm = doubleArrayOf(1.0, 0.5, 0.33, 0.22, 0.0, 0.0, 0.0, 0.12)
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val env = min(1.0, t / 1.6) * min(1.0, (durSec - t) / 1.6).coerceAtLeast(0.0)
                val ph = 2.0 * PI * f * t
                var v = 0.0
                for (h in harm.indices) if (harm[h] > 0) v += harm[h] * sin((h + 1) * ph)
                val idx = (s0 + i) % total
                buf[idx] += (v * env * amp).toFloat()
            }
        }
        for (c in 0 until 4) {
            val chord = chords[c]
            val t0 = c * 8 * stepSec
            val dur = 8 * stepSec + 1.6
            organ(chord[0], t0, 0.05f, dur)
            organ(chord[1], t0, 0.035f, dur)
            organ(chord[2], t0, 0.03f, dur)
        }

        // tique de relógio ao fundo, a cada dois passos (marca o tempo, como na trilha que inspira o tema)
        val tickLen = (0.012 * SR).toInt()
        val rnd2 = java.util.Random(3)
        for (s in 0 until steps step 2) {
            val s0 = (s * stepSec * SR).toInt()
            for (i in 0 until tickLen) {
                val env = 1.0 - i.toDouble() / tickLen
                val v = (rnd2.nextDouble() * 2 - 1) * env * env * 0.09 + sin(2 * PI * 2400.0 * i / SR) * env * 0.05
                buf[(s0 + i) % total] += v.toFloat()
            }
        }

        // eco suave
        val d = (0.38 * SR).toInt()
        val out = buf.copyOf()
        for (r in 1..3) {
            val g = 0.4f.pow(r) * 0.9f
            for (i in 0 until total) out[(i + r * d) % total] += buf[i] * g
        }
        var peak = 0f
        for (v in out) peak = max(peak, abs(v))
        val scale = if (peak > 0f) 0.8f / peak else 1f
        val pcm = ShortArray(total)
        for (i in 0 until total) pcm[i] = (out[i] * scale * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        return pcm
    }
}
