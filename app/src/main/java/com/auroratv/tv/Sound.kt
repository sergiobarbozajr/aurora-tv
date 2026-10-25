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

/** Tema estelar de piano, gerado por código (sem arquivo de áudio) e tocado em ciclo. */
object Sound {
    private const val SR = 22050
    private val vol = floatArrayOf(0f, 0.25f, 0.5f, 0.9f)
    private val lock = Any()
    private var track: AudioTrack? = null
    private var building = false
    private var level = 0
    private var paused = false

    fun apply(l: Int) {
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
        synchronized(lock) {
            paused = p
            val t = track ?: return
            try {
                if (p) t.pause() else if (level > 0) t.play()
            } catch (e: Exception) {}
        }
    }

    fun release() {
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
