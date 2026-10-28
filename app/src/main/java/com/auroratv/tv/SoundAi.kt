package com.auroratv.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Som IA: escuta o áudio do filme bloco a bloco (≈20 ms) e reconhece a cena pela assinatura do som:
 *  - ação / explosão: ataque súbito de energia com muito grave → mais impacto no grave;
 *  - diálogo: energia concentrada na faixa da voz (300–3000 Hz), sem ataques → voz mais clara;
 *  - música / ambiente: o resto → leve presença.
 * Um limitador suave impede que o reforço estoure. Funciona em estéreo e 5.1 (PCM 16 bits).
 * Áudio Dolby enviado direto para a TV (passthrough) não passa por aqui: quem trata é a própria TV.
 */
object SomIa {
    @Volatile var enabled = true

    /** Cena reconhecida agora (para o selo no player). */
    @Volatile var scene = ""
}

class SomIaProcessor : BaseAudioProcessor() {
    private var ch = 2
    private var rate = 48000

    // filtros de um polo por canal: grave (<150 Hz), voz baixa (<300 Hz) e voz alta (<3 kHz)
    private var lp150 = FloatArray(2)
    private var lp300 = FloatArray(2)
    private var lp3k = FloatArray(2)
    private var a150 = 0f
    private var a300 = 0f
    private var a3k = 0f

    // análise (no sinal mono)
    private var mLow = 0f
    private var mV300 = 0f
    private var mV3k = 0f
    private var slowRms = 0.02f
    private var actionHold = 0
    private var dialogHold = 0

    // ganhos atuais (suavizados) e limitador
    private var gLow = 1f
    private var gVoice = 1f
    private var env = 0f
    private var ys = FloatArray(2)
    private var pcm = ShortArray(4096)

    private fun coef(fc: Float): Float = exp(-2.0 * PI * fc / rate).toFloat()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        ch = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        lp150 = FloatArray(ch)
        lp300 = FloatArray(ch)
        lp3k = FloatArray(ch)
        ys = FloatArray(ch)
        a150 = coef(150f)
        a300 = coef(300f)
        a3k = coef(3000f)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        if (!SomIa.enabled) {
            out.put(inputBuffer)
            out.flip()
            gLow = 1f
            gVoice = 1f
            SomIa.scene = ""
            return
        }
        val inb = inputBuffer.order(ByteOrder.nativeOrder())
        val start = inb.position()
        val frames = n / (2 * ch)
        // lê o bloco inteiro de uma vez (bem mais leve que ler amostra por amostra)
        val total = frames * ch
        if (pcm.size < total) pcm = ShortArray(total)
        inb.asShortBuffer().get(pcm, 0, total)

        // 1) análise da cena no bloco
        var eT = 0f
        var eL = 0f
        var eV = 0f
        for (f in 0 until frames) {
            var m = 0f
            val o = f * ch
            for (c in 0 until ch) m += pcm[o + c] / 32768f
            m /= ch
            mLow = a150 * mLow + (1 - a150) * m
            mV300 = a300 * mV300 + (1 - a300) * m
            mV3k = a3k * mV3k + (1 - a3k) * m
            val v = mV3k - mV300
            eT += m * m
            eL += mLow * mLow
            eV += v * v
        }
        val fr = frames.coerceAtLeast(1).toFloat()
        val rms = sqrt(eT / fr)
        val lowShare = if (eT > 1e-9f) eL / eT else 0f
        val voiceShare = if (eT > 1e-9f) eV / eT else 0f
        val transient = rms / (slowRms + 1e-4f)
        slowRms = slowRms * 0.96f + rms * 0.04f
        val blocksPerSec = rate / fr
        if (rms > 0.004f) {
            if (transient > 1.9f && lowShare > 0.33f) actionHold = (blocksPerSec * 0.9f).toInt()
            else if (voiceShare > 0.42f && lowShare < 0.30f && transient < 1.5f) dialogHold = (blocksPerSec * 0.6f).toInt()
        }
        val tLow: Float
        val tVoice: Float
        when {
            actionHold > 0 -> {
                actionHold--
                tLow = 1.9f
                tVoice = 1.0f
                SomIa.scene = "Ação"
            }
            dialogHold > 0 -> {
                dialogHold--
                tLow = 0.9f
                tVoice = 1.55f
                SomIa.scene = "Diálogo"
            }
            rms > 0.004f -> {
                tLow = 1.15f
                tVoice = 1.12f
                SomIa.scene = "Ambiente"
            }
            else -> {
                tLow = gLow
                tVoice = gVoice
            }
        }
        // ataque rápido para explosões, volta suave
        gLow += (tLow - gLow) * (if (tLow > gLow) 0.5f else 0.08f)
        gVoice += (tVoice - gVoice) * 0.15f

        // 2) aplicação por canal
        val kLow = gLow - 1f
        val kVoice = gVoice - 1f
        val center = if (ch >= 6) 2 else -1 // 5.1: FL FR FC LFE BL BR
        val lfe = if (ch >= 6) 3 else -1
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until ch) {
                val x = pcm[f * ch + c] / 32768f
                lp150[c] = a150 * lp150[c] + (1 - a150) * x
                lp300[c] = a300 * lp300[c] + (1 - a300) * x
                lp3k[c] = a3k * lp3k[c] + (1 - a3k) * x
                val voice = lp3k[c] - lp300[c]
                val vk = when {
                    center < 0 -> kVoice // estéreo: voz nos dois lados
                    c == center -> kVoice
                    c < 2 -> kVoice * 0.3f
                    else -> 0f
                }
                val lk = if (c == lfe) kLow * 1.2f else if (center >= 0 && c == center) 0f else kLow
                val y = x + lk * lp150[c] + vk * voice
                ys[c] = y
                val a = if (y < 0) -y else y
                if (a > peak) peak = a
            }
            // limitador suave: segura picos acima de -1 dB sem cortar
            env = if (peak > env) peak else env * 0.9995f
            val g = if (env > 0.89f) 0.89f / env else 1f
            for (c in 0 until ch) {
                val v = (ys[c] * g * 32767f).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        }
        inputBuffer.position(start + n)
        out.flip()
    }

    override fun onFlush() {
        lp150.fill(0f)
        lp300.fill(0f)
        lp3k.fill(0f)
        env = 0f
    }

    override fun onReset() {
        onFlush()
        gLow = 1f
        gVoice = 1f
        SomIa.scene = ""
    }
}
