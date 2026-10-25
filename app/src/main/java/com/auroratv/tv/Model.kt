package com.auroratv.tv

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

data class City(val name: String, val lat: Double, val lon: Double)

data class ListSource(val url: String, val source: String, val only: List<String>?)

data class Item(
    val title: String,
    val source: String,
    val desc: String,
    val url: String?,
    val live: Boolean,
    val kind: String?,   // "antenna" para canais que só vêm pela antena
    val app: String?,    // app oficial da emissora
    val color: Color
)

data class Hero(val tag: String, val title: String, val desc: String, val color: Color)

data class Tab(
    val name: String,
    val guide: Boolean,
    val rowTitle: String,
    val hero: Hero,
    val items: MutableList<Item>
)

data class Catalog(
    val cities: List<City>,
    val lists: List<ListSource>,
    val tabs: List<Tab>
)

/** Converte "0xRRGGBBAA" (formato do Roku) em Color do Compose. */
fun parseColor(s: String?, fallback: Color = Color(0xFF141414)): Color {
    if (s == null) return fallback
    return try {
        val hex = s.removePrefix("0x").removePrefix("#")
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        val a = if (hex.length >= 8) hex.substring(6, 8).toInt(16) else 255
        Color(r, g, b, a)
    } catch (e: Exception) {
        fallback
    }
}

fun parseCatalog(json: String): Catalog {
    val root = JSONObject(json)

    val cities = ArrayList<City>()
    root.optJSONArray("cities")?.let { arr ->
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            cities.add(City(c.getString("name"), c.getDouble("lat"), c.getDouble("lon")))
        }
    }
    if (cities.isEmpty()) cities.add(City("Macaé", -22.37, -41.79))

    val lists = ArrayList<ListSource>()
    root.optJSONArray("lists")?.let { arr ->
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val only = l.optJSONArray("only")?.let { oa ->
                (0 until oa.length()).map { oa.getString(it) }
            }
            lists.add(ListSource(l.getString("url"), l.optString("source", "Internet"), only))
        }
    }

    val tabs = ArrayList<Tab>()
    val tabsArr = root.getJSONArray("tabs")
    for (i in 0 until tabsArr.length()) {
        val t = tabsArr.getJSONObject(i)
        val heroObj = t.optJSONObject("hero")
        val hero = Hero(
            heroObj?.optString("tag", "") ?: "",
            heroObj?.optString("title", "") ?: "",
            heroObj?.optString("desc", "") ?: "",
            parseColor(heroObj?.optString("color"), Color(0xFF1A1A1A))
        )
        val items = ArrayList<Item>()
        t.optJSONArray("items")?.let { ia ->
            for (j in 0 until ia.length()) {
                val it = ia.getJSONObject(j)
                val url = if (it.has("url") && it.getString("url").isNotEmpty()) it.getString("url") else null
                items.add(
                    Item(
                        it.optString("title", ""),
                        it.optString("source", ""),
                        it.optString("desc", ""),
                        url,
                        it.optBoolean("live", false),
                        if (it.has("kind")) it.getString("kind") else null,
                        if (it.has("app")) it.getString("app") else null,
                        parseColor(it.optString("color"))
                    )
                )
            }
        }
        tabs.add(
            Tab(
                t.getString("name"),
                t.optBoolean("guide", false),
                t.optString("rowTitle", ""),
                hero,
                items
            )
        )
    }
    return Catalog(cities, lists, tabs)
}
