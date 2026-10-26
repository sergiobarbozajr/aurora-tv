package com.auroratv.tv

import android.media.audiofx.Equalizer

/**
 * Editor de som do AuroraTV com o equalizador padrão do Android, que funciona com áudio 5.1 e
 * estéreo sem cortar o som. Voz = faixas de 250 Hz a 4 kHz (diálogos); ambiente = graves e agudos.
 * Sem ajuste (tudo em zero) nenhum efeito é ligado: o áudio passa direto.
 */
object AudioFx {
    class Eq(
        val voiceTreble: Int, val voiceBass: Int, val voiceAcoustic: Int,
        val ambTreble: Int, val ambBass: Int, val ambAcoustic: Int,
        val voiceVol: Int, val ambVol: Int
    )

    private var session = 0
    private var eq = Eq(0, 0, 0, 0, 0, 0, 0, 0)
    private var e: Equalizer? = null
    var player: androidx.media3.exoplayer.ExoPlayer? = null

    fun attach(s: Int) {
        session = s
        rebuild()
    }

    fun apply(x: Eq) {
        eq = x
        rebuild()
    }

    private fun flat(x: Eq) = x.voiceTreble == 0 && x.voiceBass == 0 && x.voiceAcoustic == 0 && x.ambTreble == 0 &&
        x.ambBass == 0 && x.ambAcoustic == 0 && x.voiceVol == 0 && x.ambVol == 0

    private fun rebuild() {
        try { e?.release() } catch (t: Throwable) {}
        e = null
        if (session == 0 || flat(eq)) return
        try {
            val q = Equalizer(0, session)
            val min = q.bandLevelRange[0].toInt()
            val max = q.bandLevelRange[1].toInt()
            val step = 150 // 1,5 dB por passo (em milibéis)
            for (i in 0 until q.numberOfBands.toInt()) {
                val hz = q.getCenterFreq(i.toShort()) / 1000
                var v = 0
                when {
                    hz < 150 -> v += (eq.ambBass + eq.ambVol) * step
                    hz < 500 -> v += (eq.voiceBass + eq.voiceVol) * step
                    hz < 1800 -> v += (eq.voiceAcoustic + eq.voiceVol) * step
                    hz < 6000 -> v += (eq.voiceTreble + eq.voiceVol) * step
                    else -> v += (eq.ambTreble + eq.ambVol + eq.ambAcoustic / 2) * step
                }
                q.setBandLevel(i.toShort(), v.coerceIn(min, max).toShort())
            }
            q.enabled = true
            e = q
        } catch (t: Throwable) {
            e = null
        }
    }
}
