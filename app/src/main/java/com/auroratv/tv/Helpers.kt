package com.auroratv.tv

import androidx.media3.exoplayer.ExoPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
fun networkInfo(context: android.content.Context): String {
    val nl = "\n"
    return try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val tipo = when {
            caps == null -> "sem conexão"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Cabo de rede"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados móveis"
            else -> "desconhecida"
        }
        var linhas = "Conexão: $tipo"
        try {
            val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val info = wm.connectionInfo
            if (info != null && tipo == "Wi-Fi") {
                val ssid = info.ssid?.replace("\"", "") ?: ""
                if (ssid.isNotEmpty() && !ssid.contains("unknown", true)) linhas += nl + "Rede: $ssid"
                val ipi = info.ipAddress
                if (ipi != 0) linhas += nl + "IP: " + "%d.%d.%d.%d".format(ipi and 0xff, (ipi shr 8) and 0xff, (ipi shr 16) and 0xff, (ipi shr 24) and 0xff)
                if (info.linkSpeed > 0) linhas += nl + "Velocidade do link Wi-Fi: ${info.linkSpeed} Mbps"
                linhas += nl + "Sinal: ${info.rssi} dBm"
            }
        } catch (e: Exception) {}
        if (caps != null && caps.linkDownstreamBandwidthKbps > 0) {
            linhas += nl + "Banda estimada: ${caps.linkDownstreamBandwidthKbps / 1000} Mb/s"
        }
        linhas
    } catch (e: Exception) {
        "Não foi possível ler os dados da rede."
    }
}

fun openDisplaySettings(context: android.content.Context) {
    try {
        val i = android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    } catch (e: Exception) {
        try {
            val i = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e2: Exception) {}
    }
}

fun applyQuality(player: ExoPlayer, idx: Int) {
    try {
        val b = player.trackSelectionParameters.buildUpon()
        when (idx) {
            1 -> b.setMaxVideoSize(3840, 2160)
            2 -> b.setMaxVideoSize(1920, 1080)
            3 -> b.setMaxVideoSize(1280, 720)
            4 -> b.setMaxVideoSize(854, 480)
            else -> b.clearVideoSizeConstraints()
        }
        player.trackSelectionParameters = b.build()
    } catch (e: Throwable) {}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun applyEnhance(player: ExoPlayer, level: Int) {
    try {
        val fx: List<androidx.media3.common.Effect> = when (level) {
            1 -> listOf(
                androidx.media3.effect.Brightness(0.05f),
                androidx.media3.effect.Contrast(0.12f),
                androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(10f).build()
            )
            2 -> listOf(
                androidx.media3.effect.Brightness(0.10f),
                androidx.media3.effect.Contrast(0.22f),
                androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(22f).build()
            )
            else -> emptyList()
        }
        player.setVideoEffects(fx)
    } catch (e: Throwable) {}
}

@Suppress("DEPRECATION")
fun hdrLine(context: android.content.Context): String {
    return try {
        val display = (context as? MainActivity)?.windowManager?.defaultDisplay
        val caps = display?.hdrCapabilities
        val types = caps?.supportedHdrTypes ?: IntArray(0)
        val names = ArrayList<String>()
        for (t in types) {
            when (t) {
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> names.add("HDR10")
                android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> names.add("HLG")
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> names.add("HDR10+")
                android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> names.add("Dolby Vision")
            }
        }
        if (names.isEmpty()) "Faixa dinâmica da tela: SDR (HDR não detectado nesta saída)"
        else "Faixa dinâmica suportada pela TV: " + names.joinToString(", ")
    } catch (e: Exception) {
        "Faixa dinâmica da tela: não foi possível ler."
    }
}


fun nowLabel(): String {
    val fmt = SimpleDateFormat("HH:mm | EEE, d MMM", Locale("pt", "BR"))
    return fmt.format(Date())
}
