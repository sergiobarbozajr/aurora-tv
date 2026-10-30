package com.astrolume.home

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale

/** Clima (Open-Meteo, sem chave) e cotações (AwesomeAPI e Yahoo), lidos direto da internet. */
object Info {
    class Hour(val label: String, val icon: String, val temp: Int)
    class Weather(
        val city: String, val temp: Int, val icon: String, val text: String,
        val max: Int, val min: Int, val hours: List<Hour>, val sunrise: String, val sunset: String,
    )
    class Quote(val symbol: String, val name: String, val value: String, val change: Double)

    // Macaé (RJ)
    const val CITY = "Macaé"
    private const val LAT = -22.371
    private const val LON = -41.787

    fun get(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; TV) AstroLumeHome/1.0")
        }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Throwable) {
        null
    }

    fun describe(code: Int, night: Boolean): Pair<String, String> = when (code) {
        0 -> (if (night) "🌙" else "☀️") to "Céu limpo"
        1 -> (if (night) "🌙" else "🌤️") to "Poucas nuvens"
        2 -> "⛅" to "Parcialmente nublado"
        3 -> "☁️" to "Nublado"
        45, 48 -> "🌫️" to "Neblina"
        in 51..57 -> "🌦️" to "Garoa"
        in 61..67 -> "🌧️" to "Chuva"
        in 71..77 -> "🌨️" to "Neve"
        in 80..82 -> "🌦️" to "Pancadas de chuva"
        in 95..99 -> "⛈️" to "Trovoadas"
        else -> "☁️" to "Nublado"
    }

    fun weather(): Weather? {
        val body = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=America%2FSao_Paulo&forecast_days=2"
        ) ?: return null
        return try {
            val o = JSONObject(body)
            val cur = o.getJSONObject("current")
            val (ic, tx) = describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 0)
            val h = o.getJSONObject("hourly")
            val times = h.getJSONArray("time")
            val now = LocalDateTime.parse(cur.getString("time"))
            var start = 0
            for (i in 0 until times.length()) if (LocalDateTime.parse(times.getString(i)).isAfter(now)) { start = i; break }
            val hours = (start until minOf(start + 6, times.length())).map { i ->
                val t = LocalDateTime.parse(times.getString(i))
                Hour("${t.hour}h", describe(h.getJSONArray("weather_code").getInt(i), h.getJSONArray("is_day").getInt(i) == 0).first,
                    Math.round(h.getJSONArray("temperature_2m").getDouble(i)).toInt())
            }
            val d = o.getJSONObject("daily")
            fun hm(s: String) = LocalDateTime.parse(s).let { String.format(Locale.US, "%d:%02d", it.hour, it.minute) }
            Weather(
                CITY, Math.round(cur.getDouble("temperature_2m")).toInt(), ic, tx,
                Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                hours, hm(d.getJSONArray("sunrise").getString(0)), hm(d.getJSONArray("sunset").getString(0)),
            )
        } catch (e: Throwable) {
            null
        }
    }

    private fun brl(v: Double, dec: Int): String {
        val s = String.format(Locale.US, "%,.${dec}f", v)
        return s.replace(',', '#').replace('.', ',').replace('#', '.')
    }

    fun quotes(): List<Quote> {
        val out = ArrayList<Quote>()
        get("https://economia.awesomeapi.com.br/json/last/USD-BRL,EUR-BRL,BTC-BRL")?.let { body ->
            try {
                val o = JSONObject(body)
                fun add(key: String, sym: String, name: String, dec: Int) {
                    val q = o.optJSONObject(key) ?: return
                    out.add(Quote(sym, name, "R$ " + brl(q.getString("bid").toDouble(), dec), q.optString("pctChange", "0").toDoubleOrNull() ?: 0.0))
                }
                add("USDBRL", "$", "Dólar", 2)
                add("EURBRL", "€", "Euro", 2)
                add("BTCBRL", "₿", "Bitcoin", 0)
            } catch (e: Throwable) {
            }
        }
        get("https://query1.finance.yahoo.com/v8/finance/chart/%5EBVSP?range=1d&interval=1d")?.let { body ->
            try {
                val m = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                val p = m.getDouble("regularMarketPrice")
                val prev = m.optDouble("chartPreviousClose", m.optDouble("previousClose", p))
                out.add(Quote("B3", "Ibovespa", brl(p, 0), if (prev > 0) (p - prev) / prev * 100 else 0.0))
            } catch (e: Throwable) {
            }
        }
        return out
    }
}
