package com.auroratv.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Utilidades de rede em thread separada, com retorno na thread principal. */
object Net {

    private val main = Handler(Looper.getMainLooper())

    private fun get(urlStr: String, timeoutMs: Int = 15000): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /** Clima pela Open-Meteo. onResult recebe um texto já pronto ("26 °C · nublado") ou null. */
    fun fetchWeather(city: City, onResult: (String?) -> Unit) {
        thread {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}" +
                "&longitude=${city.lon}&current=temperature_2m,weather_code&timezone=auto"
            val body = get(url)
            var text: String? = null
            if (body != null) {
                try {
                    val cur = JSONObject(body).getJSONObject("current")
                    val temp = cur.getDouble("temperature_2m").toInt()
                    val code = cur.optInt("weather_code", -1)
                    text = "$temp °C" + if (code >= 0) " · ${weatherName(code)}" else ""
                } catch (e: Exception) {
                }
            }
            main.post { onResult(text) }
        }
    }

    private fun weatherName(code: Int): String = when {
        code == 0 -> "céu limpo"
        code <= 2 -> "poucas nuvens"
        code == 3 -> "nublado"
        code <= 48 -> "neblina"
        code <= 57 -> "garoa"
        code <= 67 -> "chuva"
        code <= 77 -> "neve"
        code <= 82 -> "pancadas de chuva"
        else -> "tempestade"
    }

    /** Baixa e interpreta uma lista M3U, devolvendo os canais permitidos. */
    fun loadM3u(src: ListSource, onResult: (List<Item>) -> Unit) {
        thread {
            val body = get(src.url, 20000)
            val out = ArrayList<Item>()
            if (body != null) {
                val onlyLower = src.only?.map { it.lowercase() }
                var pendingName = ""
                for (rawLine in body.split("\n")) {
                    val line = rawLine.trim()
                    when {
                        line.startsWith("#EXTINF:") -> {
                            val comma = line.lastIndexOf(',')
                            pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        }
                        line.startsWith("#") -> {}
                        line.isNotEmpty() && pendingName.isNotEmpty() -> {
                            val allow = onlyLower == null || onlyLower.contains(pendingName.lowercase())
                            if (allow) {
                                out.add(
                                    Item(
                                        pendingName, src.source, "Canal aberto pela internet",
                                        line, true, null, null, androidx.compose.ui.graphics.Color(0xFF1A2C44)
                                    )
                                )
                            }
                            pendingName = ""
                        }
                    }
                }
            }
            main.post { onResult(out) }
        }
    }

    /** Teste simples de velocidade: baixa ~10 MB e calcula Mb/s. */
    fun speedTest(onResult: (String) -> Unit) {
        thread {
            val bytes = 10_000_000L
            val url = "https://speed.cloudflare.com/__down?bytes=$bytes"
            var result = "não foi possível medir."
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 20000
                }
                val start = System.currentTimeMillis()
                var total = 0L
                conn.inputStream.use { ins ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break; total += n
                    }
                }
                val ms = System.currentTimeMillis() - start
                if (ms > 0 && total > 0) {
                    val mbps = total * 8.0 / (ms * 1000.0)
                    result = "${(Math.round(mbps * 10) / 10.0)} Mb/s"
                }
            } catch (e: Exception) {
            }
            main.post { onResult(result) }
        }
    }
}
