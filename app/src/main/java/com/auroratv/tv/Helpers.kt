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
        val net = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(net)
        val lp = cm.getLinkProperties(net)
        val tipo = when {
            caps == null -> "sem conexão"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Cabo de rede (Ethernet)"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados móveis"
            else -> "desconhecida"
        }
        val out = ArrayList<String>()
        out.add("Conexão: $tipo")
        if (tipo == "Wi-Fi") {
            try {
                val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val info = wm.connectionInfo
                if (info != null) {
                    val ssid = info.ssid?.replace("\"", "") ?: ""
                    out.add(
                        "Rede: " + if (ssid.isNotEmpty() && !ssid.contains("unknown", true)) ssid
                        else "(permita a localização para o app mostrar o nome do Wi-Fi)"
                    )
                    val f = info.frequency
                    val faixa = when {
                        f >= 5925 -> "6 GHz"
                        f >= 4900 -> "5 GHz"
                        f > 0 -> "2,4 GHz"
                        else -> "?"
                    }
                    val canal = when {
                        f in 2412..2484 -> if (f == 2484) 14 else (f - 2407) / 5
                        f in 5000..5900 -> (f - 5000) / 5
                        f >= 5925 -> (f - 5950) / 5
                        else -> 0
                    }
                    out.add("Faixa: $faixa" + if (canal > 0) " · canal $canal" else "")
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val std = when (info.wifiStandard) {
                            4 -> "Wi-Fi 4 (802.11n)"
                            5 -> "Wi-Fi 5 (802.11ac)"
                            6 -> "Wi-Fi 6 (802.11ax)" + if (f >= 5925) " · 6E" else ""
                            7 -> "Wi-Fi 6E/7"
                            8 -> "Wi-Fi 7 (802.11be)"
                            1 -> "Wi-Fi antigo (a/b/g)"
                            else -> ""
                        }
                        if (std.isNotEmpty()) out.add("Padrão: $std")
                    }
                    val rssi = info.rssi
                    val bars = when {
                        rssi >= -55 -> "▮▮▮▮ Excelente"
                        rssi >= -65 -> "▮▮▮▯ Bom"
                        rssi >= -75 -> "▮▮▯▯ Regular"
                        else -> "▮▯▯▯ Fraco"
                    }
                    out.add("Sinal: $bars ($rssi dBm)")
                    if (info.linkSpeed > 0) out.add("Velocidade do link: ${info.linkSpeed} Mbps")
                }
            } catch (e: Exception) {
            }
        }
        try {
            if (lp != null) {
                val ip = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
                if (ip != null) out.add("IP da TV: $ip")
                val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
                if (gw != null) out.add("Roteador: $gw")
                val dns = lp.dnsServers.mapNotNull { it.hostAddress }.take(2)
                if (dns.isNotEmpty()) out.add("DNS: " + dns.joinToString(", "))
            }
        } catch (e: Exception) {
        }
        if (caps != null && caps.linkDownstreamBandwidthKbps > 0) out.add("Banda estimada: ${caps.linkDownstreamBandwidthKbps / 1000} Mb/s")
        out.joinToString(nl)
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun applyQuality(player: ExoPlayer, idx: Int) {
    try {
        val b = player.trackSelectionParameters.buildUpon()
        when (idx) {
            1 -> b.setMaxVideoSize(3840, 2160)
            2 -> b.setMaxVideoSize(1920, 1080)
            3 -> b.setMaxVideoSize(1280, 720)
            4 -> b.setMaxVideoSize(854, 480)
            else -> {
                b.clearVideoSizeConstraints()
                b.setForceHighestSupportedBitrate(true)
            }
        }
        player.trackSelectionParameters = b.build()
    } catch (e: Throwable) {}
}

fun isHdrFormat(player: ExoPlayer): Boolean {
    val t = player.videoFormat?.colorInfo?.colorTransfer
    return t == androidx.media3.common.C.COLOR_TRANSFER_ST2084 || t == androidx.media3.common.C.COLOR_TRANSFER_HLG
}

/** Efeitos do realce de imagem (só usados em vídeo SDR e com nível maior que zero). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun enhanceEffects(level: Int): List<androidx.media3.common.Effect> = when (level) {
    1 -> listOf(
        androidx.media3.effect.Brightness(0.03f),
        androidx.media3.effect.Contrast(0.08f),
        androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(6f).build()
    )
    2 -> listOf(
        androidx.media3.effect.Brightness(0.06f),
        androidx.media3.effect.Contrast(0.16f),
        androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(14f).build()
    )
    else -> listOf(
        androidx.media3.effect.Brightness(0.10f),
        androidx.media3.effect.Contrast(0.26f),
        androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(24f).build()
    )
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

/** Cor com transparência sempre dentro de 0..1 (valores fora disso derrubam o app). */
fun androidx.compose.ui.graphics.Color.al(a: Float): androidx.compose.ui.graphics.Color = copy(alpha = a.coerceIn(0f, 1f))

fun openNetworkSettings(context: android.content.Context) {
    val tries = listOf(
        android.provider.Settings.ACTION_WIRELESS_SETTINGS,
        android.provider.Settings.ACTION_SETTINGS
    )
    for (a in tries) {
        try {
            val i = android.content.Intent(a)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return
        } catch (e: Exception) {
        }
    }
}


/** Guia de imagem para a TV em que o app está instalado (marca e modelo detectados), não fixo num modelo. */
fun imageGuide(): String {
    val man = android.os.Build.MANUFACTURER.trim()
    val model = android.os.Build.MODEL.trim()
    val n = man.lowercase()
    val nl = "\n"
    val head = "Sua TV: " + man.replaceFirstChar { it.uppercase() } + " " + model + nl + nl
    val tips = when {
        n.contains("tcl") || n.contains("semp") -> listOf(
            "Modo de imagem: Cinema ou Filme (mais fiel)",
            "Local dimming / Mini-LED (se tiver): Alto",
            "Nitidez: baixa (0 a 15); a IA de imagem já cuida dos detalhes",
            "Contraste dinâmico: Médio",
            "Movimento (MEMC): Baixo para filmes, Médio para esporte"
        )
        n.contains("sony") -> listOf(
            "Modo de imagem: Personalizado ou Cinema",
            "Realidade criada / Reality Creation: Automático",
            "Nitidez: 50 (neutro)",
            "Motionflow: Desligado para filmes, Suave para esporte",
            "Modo HDR: Automático"
        )
        n.contains("philips") || n.contains("tpv") -> listOf(
            "Estilo de imagem: Filme ou ISF Dia",
            "Nitidez: baixa a média",
            "Perfect Natural Motion: Mínimo para filmes",
            "Contraste dinâmico: Médio"
        )
        n.contains("xiaomi") -> listOf(
            "Modo de imagem: Filme",
            "Nitidez: baixa",
            "MEMC: Desligado para filmes, Baixo para esporte",
            "Contraste dinâmico: Médio"
        )
        n.contains("hisense") || n.contains("toshiba") -> listOf(
            "Modo de imagem: Filme / Cinema",
            "Nitidez: baixa",
            "Motion Clearance (MEMC): Desligado para filmes",
            "Contraste dinâmico: Baixo ou Médio"
        )
        n.contains("google") || n.contains("amazon") || n.contains("nvidia") || n.contains("amlogic") || n.contains("rockchip") ->
            listOf(
                "Este é um aparelho ligado na TV (box). Ajuste a imagem no menu da própria TV:",
                "Modo de imagem: Filme ou Cinema",
                "Nitidez: baixa",
                "Suavização de movimento: Desligada para filmes",
                "No box: Configurações > Tela > resolução no máximo e HDR automático"
            )
        else -> listOf(
            "Modo de imagem: Filme, Cinema ou Padrão",
            "Nitidez: baixa (o excesso cria contornos brancos)",
            "Suavização de movimento: Desligada para filmes, baixa para esporte",
            "Contraste dinâmico: Médio",
            "Temperatura de cor: Quente ou Normal"
        )
    }
    return head + "Ajustes recomendados no menu da própria TV:" + nl + tips.joinToString(nl) { "• $it" } + nl + nl + ImageAi.summary()
}
