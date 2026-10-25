package com.auroratv.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import org.json.JSONArray

/** Cartão exibido nas fileiras. kind: stream | vod | series | antenna | app | login | empty */
data class Card(
    val name: String,
    val label: String,
    val kind: String,
    val hueA: Color,
    val hueB: Color,
    val url: String? = null,
    val extra: String = "",
    val pkgs: List<String> = emptyList(),
    val desc: String = "",
    val img: String = ""
)

data class RowData(val title: String, val cards: List<Card>)

data class Section(val name: String, val icon: String, val kind: String)

val SECTIONS = listOf(
    Section("Início", "home", "rows"),
    Section("Pesquisar", "search", "search"),
    Section("Minhas Listas", "star", "lists"),
    Section("Streams", "popcorn", "rows"),
    Section("Filmes", "film", "rows"),
    Section("Séries", "series", "rows"),
    Section("Novelas", "heart", "rows"),
    Section("Documentários", "doc", "rows"),
    Section("Esporte", "ball", "rows"),
    Section("Notícias", "news", "rows"),
    Section("TV ao vivo", "tv", "live"),
    Section("Configurações", "gear", "settings"),
    Section("Sair do app", "power", "exit")
)

object Palette {
    private val pairs = listOf(
        Color(0xFF2B3F7A) to Color(0xFF101A33),
        Color(0xFF5A2B46) to Color(0xFF1A0B12),
        Color(0xFF1F5A5A) to Color(0xFF0B1F20),
        Color(0xFF5A3A1F) to Color(0xFF1D1208),
        Color(0xFF2A5E4A) to Color(0xFF0A2213),
        Color(0xFF3A2A5E) to Color(0xFF120C22),
        Color(0xFF22476B) to Color(0xFF0A1626),
        Color(0xFF5E2A46) to Color(0xFF1A0B12)
    )

    fun pick(i: Int): Pair<Color, Color> = pairs[((i % pairs.size) + pairs.size) % pairs.size]
    fun pickFor(s: String): Pair<Color, Color> = pick(s.hashCode() and 0x7fffffff)
}

fun mkCard(name: String, label: String, kind: String, idx: Int, url: String? = null, extra: String = "", pkgs: List<String> = emptyList(), desc: String = ""): Card {
    val h = Palette.pick(idx)
    return Card(name, label, kind, h.first, h.second, url, extra, pkgs, desc)
}

// ---------- Streams (apps instalados na TV) ----------

fun streamRows(): List<RowData> {
    fun app(n: String, label: String, i: Int, vararg p: String) =
        mkCard(n, label, "app", i, null, n, p.toList(), "Abre o app $n instalado na TV")
    return listOf(
        RowData(
            "Com assinatura (entre com a sua conta)", listOf(
                app("Netflix", "Assinatura", 1, "com.netflix.ninja"),
                app("Prime Video", "Assinatura", 6, "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
                app("Disney+", "Assinatura", 0, "com.disney.disneyplus"),
                app("Max", "Assinatura", 5, "com.wbd.stream"),
                app("Apple TV+", "Assinatura", 2, "com.apple.atve.androidtv.appletv"),
                app("Globoplay", "Assinatura", 3, "com.globo.globotv")
            )
        ),
        RowData(
            "Grátis, com anúncios", listOf(
                app("YouTube", "Grátis", 1, "com.google.android.youtube.tv"),
                app("Pluto TV", "Grátis", 5, "tv.pluto.android"),
                app("Mercado Play", "Grátis", 3),
                app("Plex", "Grátis", 4, "com.plexapp.android")
            )
        )
    )
}

// ---------- Opções de configuração ----------

val QUALITY_NAMES = listOf("Máxima (recomendado)", "4K (2160p)", "Full HD (1080p)", "HD (720p)", "Economia (480p)")
val ENHANCE_NAMES = listOf("Desligado", "Médio", "Alto", "Ultra")
val SOUND_NAMES = listOf("Desligado", "Tema estelar · baixo", "Tema estelar · médio", "Tema estelar · alto")
val METEOR_NAMES = listOf("Ligado", "Desligado")

class SettingItem(val name: String, val edit: String?)

val SETTING_ITEMS = listOf(
    SettingItem("Qualidade de vídeo", "quality"),
    SettingItem("Realce de imagem", "enh"),
    SettingItem("Clima e cidade", "city"),
    SettingItem("Som do aplicativo", "sound"),
    SettingItem("Meteoro ao apertar OK", "meteor"),
    SettingItem("Rede e internet", null),
    SettingItem("Guia de imagem (TCL A400M)", null),
    SettingItem("Teste de tela", null),
    SettingItem("Mapear controle remoto", null),
    SettingItem("Bloquear anúncios (DNS)", null),
    SettingItem("Abrir ajustes do Google TV", null),
    SettingItem("Conta IPTV", null),
    SettingItem("Atualização do app", null),
    SettingItem("Sobre o aparelho", null)
)

// ---------- Armazenamento local ----------

object Store {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("aurora", Context.MODE_PRIVATE)
    }

    private fun int(k: String, d: Int = 0) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    var quality: Int
        get() = int("quality")
        set(v) = putInt("quality", v)
    var enhance: Int
        get() = int("enh")
        set(v) = putInt("enh", v)
    var city: Int
        get() = int("city")
        set(v) = putInt("city", v)
    var sound: Int
        get() = int("sound")
        set(v) = putInt("sound", v)
    var meteor: Int
        get() = int("meteor")
        set(v) = putInt("meteor", v)

    var keymap: String
        get() = sp.getString("keymap", "") ?: ""
        set(v) = sp.edit().putString("keymap", v).apply()

    var iptvUrl: String
        get() = sp.getString("iptv_url", "") ?: ""
        set(v) = sp.edit().putString("iptv_url", v).apply()
    var iptvUser: String
        get() = sp.getString("iptv_user", "") ?: ""
        set(v) = sp.edit().putString("iptv_user", v).apply()
    var iptvPass: String
        get() = sp.getString("iptv_pass", "") ?: ""
        set(v) = sp.edit().putString("iptv_pass", v).apply()

    private fun list(k: String, def: List<String>): MutableList<String> {
        val raw = sp.getString(k, null) ?: return def.toMutableList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }.toMutableList()
        } catch (e: Exception) {
            def.toMutableList()
        }
    }

    private fun saveList(k: String, l: List<String>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        sp.edit().putString(k, a.toString()).apply()
    }

    fun favs(): MutableList<String> = list("favs", emptyList())
    fun saveFavs(l: List<String>) = saveList("favs", l)
    fun history(): MutableList<String> = list("history", emptyList())
    fun pushHistory(name: String) {
        val h = history()
        h.remove(name)
        h.add(0, name)
        saveList("history", h.take(20))
    }
}
