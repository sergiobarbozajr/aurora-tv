package com.astrolume.home

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri

/** Entradas da TV: antena (TV ao vivo) e as portas HDMI, lidas do próprio sistema. */
object Inputs {
    class Port(val id: String, val name: String, val detail: String, val connected: Boolean)

    fun hdmi(c: Context): List<Port> {
        val tim = c.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val list = try { tim.tvInputList } catch (e: Throwable) { emptyList() }
        return list.filter { it.type == TvInputInfo.TYPE_HDMI || (it.isPassthroughInput && it.type != TvInputInfo.TYPE_TUNER && it.parentId == null) }
            .sortedBy { it.loadLabel(c).toString() }
            .map {
                val label = it.loadLabel(c).toString()
                val custom = try { it.loadCustomLabel(c)?.toString() } catch (e: Throwable) { null }
                val st = try { tim.getInputState(it.id) } catch (e: Throwable) { TvInputManager.INPUT_STATE_CONNECTED }
                val on = st != TvInputManager.INPUT_STATE_DISCONNECTED
                Port(it.id, label, listOfNotNull(custom?.takeIf { s -> s.isNotBlank() && s != label }, if (on) "conectado" else "sem aparelho").joinToString(" · "), on)
            }
    }

    fun open(c: Context, p: Port): Boolean = start(c, Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(p.id)))

    /** Antena: abre o app de TV ao vivo do sistema. */
    fun liveTv(c: Context): Boolean {
        if (start(c, Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI))) return true
        if (start(c, Intent(Intent.ACTION_VIEW, Uri.parse("content://android.media.tv/channel")))) return true
        val pm = c.packageManager
        for (pk in listOf("com.tcl.tv", "com.tcl.tvinput", "com.google.android.tv", "com.android.tv")) {
            val i = pm.getLeanbackLaunchIntentForPackage(pk) ?: pm.getLaunchIntentForPackage(pk) ?: continue
            if (start(c, i)) return true
        }
        return false
    }

    fun start(c: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
        true
    } catch (e: Throwable) {
        false
    }
}
