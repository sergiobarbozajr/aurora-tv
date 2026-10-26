package com.auroratv.tv

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/**
 * Reforço de graves para a soundbar: BassBoost + equalizador nas faixas mais baixas.
 * Vale para tudo o que toca dentro do AuroraTV. Também tenta o mix geral da TV (sessão 0);
 * se o sistema não permitir, fica só no app.
 */
object AudioFx {
    private val strength = intArrayOf(0, 450, 750, 1000)
    private val eqGainMb = intArrayOf(0, 300, 600, 900)
    private var session = 0
    private var level = 0
    private var bass: BassBoost? = null
    private var eq: Equalizer? = null
    private var globalBass: BassBoost? = null

    fun attach(s: Int) {
        session = s
        rebuild()
    }

    fun apply(l: Int) {
        level = l.coerceIn(0, strength.size - 1)
        rebuild()
    }

    private fun release() {
        try { bass?.release() } catch (e: Throwable) {}
        try { eq?.release() } catch (e: Throwable) {}
        try { globalBass?.release() } catch (e: Throwable) {}
        bass = null
        eq = null
        globalBass = null
    }

    private fun boost(b: BassBoost) {
        if (b.strengthSupported) b.setStrength(strength[level].toShort())
        b.enabled = true
    }

    private fun rebuild() {
        release()
        if (level == 0) return
        if (session != 0) {
            try {
                bass = BassBoost(0, session).also { boost(it) }
            } catch (e: Throwable) {
            }
            try {
                eq = Equalizer(0, session).also { e ->
                    val range = e.bandLevelRange
                    val max = range[1].toInt()
                    val n = e.numberOfBands.toInt()
                    for (i in 0 until n) {
                        val hz = e.getCenterFreq(i.toShort()) / 1000
                        val g = when {
                            hz <= 100 -> eqGainMb[level]
                            hz <= 300 -> eqGainMb[level] / 2
                            else -> 0
                        }
                        e.setBandLevel(i.toShort(), minOf(g, max).toShort())
                    }
                    e.enabled = true
                }
            } catch (e: Throwable) {
            }
        }
        try {
            @Suppress("DEPRECATION")
            globalBass = BassBoost(0, 0).also { boost(it) }
        } catch (e: Throwable) {
        }
    }
}
