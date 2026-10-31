package com.auroratv.tv

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/**
 * Antena digital da TV.
 *
 * A lista de canais do Android junta TODAS as fontes de TV do aparelho, e apps como o Pluto TV
 * também se registram ali. Por isso aqui só olhamos a fonte do tipo sintonizador — a antena de
 * verdade — e só os canais que a TV realmente encontrou na busca.
 *
 * Se não houver antena, ou se ela nunca foi sintonizada, a lista volta vazia e a fileira
 * simplesmente não aparece no app.
 */
object Antena {

    class Canal(val id: Long, val nome: String, val numero: String)

    /** A entrada de antena da TV, se existir. */
    private fun sintonizador(c: Context): String? = try {
        val tim = c.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        tim?.tvInputList?.firstOrNull { it.type == TvInputInfo.TYPE_TUNER }?.id
    } catch (e: Throwable) {
        null
    }

    /** Os canais que a TV achou na antena. Vazio = sem antena ou sem busca feita. */
    fun canais(c: Context): List<Canal> {
        val entrada = sintonizador(c) ?: return emptyList()
        val out = ArrayList<Canal>()
        try {
            val cur = c.contentResolver.query(
                TvContract.buildChannelsUriForInput(entrada),
                arrayOf(
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_BROWSABLE,
                ),
                null, null, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            ) ?: return emptyList()
            cur.use {
                while (it.moveToNext()) {
                    // canal escondido pela própria TV não entra
                    if (it.getInt(3) == 0) continue
                    val nome = it.getString(1).orEmpty().trim()
                    val num = it.getString(2).orEmpty().trim()
                    if (nome.isEmpty() && num.isEmpty()) continue
                    out.add(Canal(it.getLong(0), nome.ifEmpty { "Canal $num" }, num))
                }
            }
        } catch (e: Throwable) {
            return emptyList()
        }
        return out
    }

    /** Vira cartões do app, já com o número do canal para abrir direto. */
    fun cards(c: Context): List<Card> = canais(c).mapIndexed { i, ch ->
        mkCard(
            ch.nome,
            if (ch.numero.isBlank()) "Antena" else "Antena · " + ch.numero,
            "antenna", i, null, ch.id.toString(), emptyList(),
            "Canal da antena digital" + if (ch.numero.isBlank()) "" else " · " + ch.numero,
        )
    }

    /** Abre o canal na TV. Devolve false se não deu, para o app avisar em vez de abrir outra coisa. */
    fun abrir(c: Context, card: Card): Boolean {
        val id = card.extra.toLongOrNull() ?: return false
        return try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, TvContract.buildChannelUri(id))
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
