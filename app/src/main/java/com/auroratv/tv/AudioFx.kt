package com.auroratv.tv

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.PresetReverb

/**
 * Editor de som do AuroraTV. Separa a faixa de frequências da voz (diálogos, ~250 Hz a 4 kHz)
 * da faixa do ambiente (graves, agudos e espaço da trilha e dos efeitos) e ajusta cada uma:
 * agudo, grave, acústico e volume. Equalizador de 11 faixas + limitador para não distorcer.
 * Vale para tudo o que toca dentro do app.
 */
object AudioFx {
    class Eq(
        val voiceTreble: Int, val voiceBass: Int, val voiceAcoustic: Int,
        val ambTreble: Int, val ambBass: Int, val ambAcoustic: Int,
        val voiceVol: Int, val ambVol: Int
    )

    // centro de cada faixa (Hz)
    private val cut = floatArrayOf(60f, 120f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 8000f, 14000f)
    private const val STEP_DB = 1.5f

    private var session = 0
    private var eq = Eq(0, 0, 0, 0, 0, 0, 0, 0)
    private var dp: DynamicsProcessing? = null
    private var reverb: PresetReverb? = null
    var player: androidx.media3.exoplayer.ExoPlayer? = null

    fun attach(s: Int) {
        session = s
        rebuild()
    }

    fun apply(e: Eq) {
        eq = e
        rebuild()
    }

    private fun isFlat(e: Eq) = e.voiceTreble == 0 && e.voiceBass == 0 && e.voiceAcoustic == 0 && e.ambTreble == 0 &&
        e.ambBass == 0 && e.ambAcoustic == 0 && e.voiceVol == 0 && e.ambVol == 0

    /** Ganho (dB) de cada faixa a partir dos controles. */
    private fun gains(e: Eq): FloatArray {
        val g = FloatArray(cut.size)
        for (i in cut.indices) {
            val f = cut[i]
            var v = 0f
            val voice = f in 250f..4000f
            // ambiente: graves e agudos fora da voz
            if (f <= 120f) v += e.ambBass * STEP_DB
            if (f >= 8000f) v += e.ambTreble * STEP_DB
            // voz: corpo (grave), presença/madeira (acústico) e clareza (agudo)
            if (f == 250f || f == 400f) v += e.voiceBass * STEP_DB
            if (f == 630f || f == 1000f) v += e.voiceAcoustic * STEP_DB
            if (f == 1600f || f == 2500f || f == 4000f) v += e.voiceTreble * STEP_DB
            // volumes separados
            v += (if (voice) e.voiceVol else e.ambVol) * STEP_DB
            g[i] = v.coerceIn(-12f, 12f)
        }
        return g
    }

    private fun release() {
        try { dp?.release() } catch (e: Throwable) {}
        dp = null
        try { player?.clearAuxEffectInfo() } catch (e: Throwable) {}
        try { reverb?.release() } catch (e: Throwable) {}
        reverb = null
    }

    private fun rebuild() {
        release()
        if (session == 0 || isFlat(eq)) return
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, cut.size, false, 0, false, 0, true
            ).build()
            val d = DynamicsProcessing(0, session, cfg)
            val g = gains(eq)
            for (i in cut.indices) d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cut[i], g[i]))
            // limitador: segura os picos quando os ganhos sobem
            d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1.5f, 0f))
            d.enabled = true
            dp = d
        } catch (e: Throwable) {
        }
        // acústico do ambiente: sensação de sala (reverberação leve), só para valores positivos
        if (eq.ambAcoustic > 0) {
            try {
                val preset = when (eq.ambAcoustic) {
                    1, 2 -> PresetReverb.PRESET_SMALLROOM
                    3 -> PresetReverb.PRESET_MEDIUMROOM
                    4 -> PresetReverb.PRESET_LARGEROOM
                    else -> PresetReverb.PRESET_LARGEHALL
                }
                val r = PresetReverb(1, 0)
                r.preset = preset
                r.enabled = true
                reverb = r
                player?.setAuxEffectInfo(androidx.media3.common.AuxEffectInfo(r.id, 0.35f + 0.08f * eq.ambAcoustic))
            } catch (e: Throwable) {
            }
        }
    }
}
