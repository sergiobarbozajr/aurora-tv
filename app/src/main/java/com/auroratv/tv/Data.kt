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
    Section("Música", "music", "rows"),
    Section("Adulto", "lock", "rows"),
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

// ---------- Música (apps instalados na TV + canais abertos de música) ----------

fun musicRows(openMusic: List<Card>, iptvMusic: List<RowData>): List<RowData> {
    // extra "busca": se o app não estiver instalado, abre a busca da Play Store pelo nome
    fun app(n: String, label: String, i: Int, vararg p: String) =
        mkCard(n, label, "app", i, null, "busca", p.toList(), "Abre o app $n instalado na TV")
    val out = ArrayList<RowData>()
    out.add(
        RowData(
            "Apps de música", listOf(
                app("YouTube Music", "Grátis ou Premium", 1, "com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music"),
                app("Spotify", "Grátis ou Premium", 4, "com.spotify.tv.android", "com.spotify.music"),
                app("Amazon Music", "Assinatura", 6, "com.amazon.music.tv", "com.amazon.mp3"),
                app("Deezer", "Grátis ou Premium", 5, "deezer.android.tv", "deezer.android.app"),
                app("TIDAL", "Assinatura", 0, "com.aspiro.tidal"),
                app("Apple Music", "Assinatura", 2, "com.apple.android.music")
            )
        )
    )
    out.add(
        RowData(
            "Videoclipes", listOf(
                app("YouTube", "Clipes e shows", 1, "com.google.android.youtube.tv"),
                app("Pluto TV", "Canais de música", 5, "tv.pluto.android")
            )
        )
    )
    if (openMusic.isNotEmpty()) out.add(RowData("Canais de música abertos", openMusic))
    out.addAll(iptvMusic)
    out.add(RowData("Rádios e podcasts", listOf(app("TuneIn Radio", "Grátis", 3, "tunein.player"))))
    return out
}

// ---------- Opções de configuração ----------

val QUALITY_NAMES = listOf("Máxima (recomendado)", "4K (2160p)", "Full HD (1080p)", "HD (720p)", "Economia (480p)")
val ENHANCE_NAMES = listOf("Desligado", "Automático", "Manual · Médio", "Manual · Alto", "Manual · Ultra")
val SOUND_NAMES = listOf("Desligado", "Tema espacial · baixo", "Tema espacial · médio", "Tema espacial · alto")
val METEOR_NAMES = listOf("Ligado", "Desligado")
val THEME_NAMES = listOf("Galáxia", "Planetas", "Órbitas", "Mar alienígena", "Deserto", "Plutão gelado", "Cratera ao sol", "Marte", "Tempestade em Júpiter", "Vênus vulcânico", "Terra vista da Lua")
val BASS_NAMES = listOf("Desligado", "Médio", "Alto", "Máximo")
val KEYS_NAMES = listOf("Desligado", "Clique suave", "Tecla de celular", "Bolha", "Madeira", "Cristal", "Digital")
val HIDEOFF_NAMES = listOf("Mostrar", "Ocultar")
val SCREEN_NAMES = listOf("Original", "Preencher", "Zoom", "16:9", "4:3", "Personalizado")

class SettingItem(val name: String, val edit: String?)

val SETTING_ITEMS = listOf(
    SettingItem("Qualidade de vídeo", "quality"),
    SettingItem("Assistente IA de imagem", "enh"),
    SettingItem("Tema do app", "theme"),
    SettingItem("Clima e cidade", "city"),
    SettingItem("Som do aplicativo", null),
    SettingItem("Canais fora do ar", "hideoff"),
    SettingItem("Rede e internet", null),
    SettingItem("Guia de imagem", null),
    SettingItem("Teste de tela", null),
    SettingItem("Mapear controle remoto", null),
    SettingItem("Conta IPTV", null),
    SettingItem("Conteúdo adulto", null),
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

    /** Ponto onde parou em cada filme/episódio (ms). 0 = começar do início. */
    fun resumeGet(k: String): Long = sp.getLong("rs:" + k.hashCode(), 0L)
    fun resumeSet(k: String, v: Long) {
        if (v <= 0L) sp.edit().remove("rs:" + k.hashCode()).apply() else sp.edit().putLong("rs:" + k.hashCode(), v).apply()
    }
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    var quality: Int
        get() = int("quality")
        set(v) = putInt("quality", v)
    /** Assistente IA de imagem: 0 desligado, 1 automático (padrão), 2..4 manual. */
    var enhance: Int
        get() = int("ai_mode", 1)
        set(v) = putInt("ai_mode", v)
    var city: Int
        get() = int("city")
        set(v) = putInt("city", v)
    var sound: Int
        get() = int("sound", 2)
        set(v) = putInt("sound", v)
    var meteor: Int
        get() = int("meteor")
        set(v) = putInt("meteor", v)

    /** Som IA: 1 automático (padrão), 0 desligado. */
    var somIa: Int
        get() = int("som_ia", 1)
        set(v) = putInt("som_ia", v)

    /** Volume do som das teclas, 0 a 10. */
    var keyVol: Int
        get() = int("key_vol", 6)
        set(v) = putInt("key_vol", v)

    var health: String
        get() = sp.getString("health", "") ?: ""
        set(v) = sp.edit().putString("health", v).apply()
    var screenMode: Int
        get() = int("screen_mode")
        set(v) = putInt("screen_mode", v)
    var keySound: Int
        get() = int("key_sound", 1)
        set(v) = putInt("key_sound", v)
    var musicUri: String
        get() = sp.getString("music_uri", "") ?: ""
        set(v) = sp.edit().putString("music_uri", v).apply()
    var musicName: String
        get() = sp.getString("music_name", "") ?: ""
        set(v) = sp.edit().putString("music_name", v).apply()

    /** Senha do conteúdo adulto. Começa em 0000 e você pode trocar nas configurações. */
    var adultPin: String
        get() = sp.getString("adult_pin", "0000") ?: "0000"
        set(v) = sp.edit().putString("adult_pin", v).apply()

    /** Quantos acessos cada aba guarda. */
    const val SLOTS = 3

    /** Contas de IPTV: espaço 0 usa as chaves antigas; 1 e 2 são as contas extras. */
    fun acc(k: Int): Triple<String, String, String> =
        if (k == 0) Triple(iptvUrl, iptvUser, iptvPass)
        else Triple(sp.getString("iptv_url_$k", "") ?: "", sp.getString("iptv_user_$k", "") ?: "", sp.getString("iptv_pass_$k", "") ?: "")

    fun setAcc(k: Int, url: String, user: String, pass: String) {
        if (k == 0) {
            iptvUrl = url
            iptvUser = user
            iptvPass = pass
        } else sp.edit().putString("iptv_url_$k", url).putString("iptv_user_$k", user).putString("iptv_pass_$k", pass).apply()
    }

    fun anyAcc(): Boolean = (0 until SLOTS).any { acc(it).first.isNotBlank() }

    /**
     * TV online pelo navegador: até 5 endereços guardados, só nome e endereço.
     * Não guarda usuário nem senha — o login, quando o site pedir, é feito na própria página.
     */
    fun site(k: Int): Pair<String, String> =
        Pair(sp.getString("web_name_$k", "") ?: "", sp.getString("web_url_$k", "") ?: "")

    fun setSite(k: Int, name: String, url: String) {
        sp.edit().putString("web_name_$k", name).putString("web_url_$k", url).apply()
    }

    /** Transmissões de rede guardadas (até 5): só o endereço, como no "abrir fluxo" do VLC. */
    fun net(k: Int): String = sp.getString("net_$k", "") ?: ""

    fun setNet(k: Int, url: String) {
        sp.edit().putString("net_$k", url).apply()
    }

    /** Primeiro espaço livre entre os 5, ou -1 se já estiverem todos ocupados. */
    fun freeSite(): Int = (0 until SLOTS).firstOrNull { site(it).second.isBlank() } ?: -1

    fun eqVal(k: String): Int = int("eq_$k", 0)
    fun setEqVal(k: String, v: Int) = putInt("eq_$k", v)
    fun eq(): AudioFx.Eq = AudioFx.Eq(
        eqVal("vt"), eqVal("vb"), eqVal("va"), eqVal("at"), eqVal("ab"), eqVal("aa"), eqVal("vv"), eqVal("av")
    )

    var bass: Int
        get() = int("bass")
        set(v) = putInt("bass", v)
    var theme: Int
        get() = int("theme")
        set(v) = putInt("theme", v)
    var hideOffline: Int
        get() = int("hide_off")
        set(v) = putInt("hide_off", v)

    /** Ajuste personalizado de tela: "largura,altura,x,y" por canal/conteúdo ou "global". */
    fun screenAdj(key: String): IntArray? {
        val raw = sp.getString("adj_" + key, null) ?: return null
        val p = raw.split(",").mapNotNull { it.toIntOrNull() }
        return if (p.size == 4) p.toIntArray() else null
    }

    fun saveScreenAdj(key: String, v: IntArray?) {
        if (v == null) sp.edit().remove("adj_" + key).apply()
        else sp.edit().putString("adj_" + key, v.joinToString(",")).apply()
    }

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
