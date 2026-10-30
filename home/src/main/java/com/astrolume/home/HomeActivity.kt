package com.astrolume.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/** Estado da tela inicial (navegação pelo controle igual ao simulador aprovado). */
class HomeState(private val ctx: Context) {
    val apps = mutableStateListOf<AppEntry>()
    var loaded by mutableStateOf(false)
    var theme by mutableIntStateOf(Prefs.theme)
    var expanded by mutableStateOf(false)
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(0)
    var modal by mutableStateOf<String?>(null)
    var sel by mutableIntStateOf(0)
    var moving by mutableStateOf(false)
    var weather by mutableStateOf<Info.Weather?>(null)
    var quotes by mutableStateOf<List<Info.Quote>>(emptyList())
    var ports by mutableStateOf<List<Inputs.Port>>(emptyList())
    var toast by mutableStateOf("")
    var toastAt by mutableLongStateOf(0L)
    var lastKey = System.currentTimeMillis()
    // atualização
    var upStatus by mutableStateOf("")
    var upUrl: String? = null
    var upProgress by mutableIntStateOf(-2)
    // pareamento
    var pairBusy by mutableStateOf(false)
    var pairMsg by mutableStateOf("")

    val visible get() = apps.filter { it.visible }
    val hidden get() = apps.filter { !it.visible }

    val INPUTS = listOf("TV ao Vivo" to "tv", "Entradas HDMI" to "hdmi")
    val SHORT = listOf("Todos os Apps" to "grid", "Ajustes do Início" to "gear", "Ajustes da TV" to "set")
    val MENU = listOf("Tema da tela inicial", "Organizar aplicativos", "Análise de desempenho", "Atualizar o AstroLume Home")

    fun rowLen(r: Int) = when (r) { 0 -> visible.size + 1; 1 -> INPUTS.size + SHORT.size; else -> 2 }

    fun say(t: String) { toast = t; toastAt = System.currentTimeMillis() }

    fun reloadApps() {
        thread(name = "apps") {
            val list = Apps.load(ctx)
            Handler(Looper.getMainLooper()).post {
                apps.clear(); apps.addAll(list); loaded = true
                col = col.coerceIn(0, (rowLen(row) - 1).coerceAtLeast(0))
            }
        }
    }

    fun refreshInfo() {
        thread(name = "info") {
            val w = Info.weather()
            val q = Info.quotes()
            Handler(Looper.getMainLooper()).post {
                if (w != null) weather = w
                if (q.isNotEmpty()) quotes = q
            }
        }
    }

    fun goHome() {
        if (modal == "perf") Perf.stop()
        modal = null; moving = false; expanded = false; row = 0; col = 0
    }

    fun open(m: String, s: Int = 0) { modal = m; sel = s }

    private fun closeModal() { if (modal == "perf") Perf.stop(); modal = null; moving = false }

    // ---------- teclas ----------
    fun key(k: String) {
        lastKey = System.currentTimeMillis()
        val back = k == "back"
        val m = modal
        if (m != null) {
            when (m) {
                "mais", "all" -> {
                    val list = if (m == "mais") hidden else apps
                    val cols = if (m == "mais") 3 else 4
                    val n = list.size
                    when (k) {
                        "right" -> sel = (sel + 1).coerceAtMost(n - 1)
                        "left" -> sel = (sel - 1).coerceAtLeast(0)
                        "down" -> sel = (sel + cols).coerceAtMost(n - 1)
                        "up" -> sel = (sel - cols).coerceAtLeast(0)
                        "ok" -> if (n > 0) { launch(list[sel.coerceIn(0, n - 1)]); closeModal() }
                    }
                    if (back) closeModal()
                }
                "org" -> {
                    val n = apps.size
                    if (moving) {
                        if (k == "up" && sel > 0) { val a = apps[sel]; apps[sel] = apps[sel - 1]; apps[sel - 1] = a; sel-- }
                        if (k == "down" && sel < n - 1) { val a = apps[sel]; apps[sel] = apps[sel + 1]; apps[sel + 1] = a; sel++ }
                        if (k == "ok" || back || k == "left") { moving = false; Apps.save(apps) }
                    } else {
                        when (k) {
                            "up" -> sel = (sel - 1).coerceAtLeast(0)
                            "down" -> sel = (sel + 1).coerceAtMost(n - 1)
                            "right" -> moving = true
                            "ok" -> if (n > 0) {
                                val a = apps[sel]
                                if (a.visible && visible.size <= 1) say("Deixe pelo menos 1 app na tela inicial")
                                else { a.visible = !a.visible; Apps.save(apps) }
                            }
                        }
                        if (back) { Apps.save(apps); open("menu", 1); col = 0; say("Organização salva") }
                    }
                }
                "perf" -> if (back) { Perf.stop(); open("menu", 2) } else if (k == "ok" && !Perf.snap.connected) { Perf.stop(); pairMsg = ""; open("pair") }
                "pair" -> if (back) { open("perf"); Perf.start(ctx) }
                "update" -> {
                    if (k == "ok" && upStatus == "new" && upUrl != null && upProgress < 0) {
                        upProgress = 0
                        Updater.downloadAndInstall(ctx, upUrl!!) { p -> upProgress = p }
                    }
                    if (back) open("menu", 3)
                }
                "menu" -> {
                    when (k) {
                        "up" -> sel = (sel - 1).coerceAtLeast(0)
                        "down" -> sel = (sel + 1).coerceAtMost(MENU.size - 1)
                        "ok" -> when (sel) {
                            0 -> open("tema", theme)
                            1 -> open("org", 0)
                            2 -> { open("perf"); Perf.start(ctx) }
                            else -> { open("update"); upStatus = "checking"; upProgress = -2
                                Updater.check(ctx) { r -> upStatus = r.status; upUrl = r.apk; upRemote = r.remote } }
                        }
                    }
                    if (back) closeModal()
                }
                "tema" -> {
                    when (k) {
                        "up" -> sel = (sel - 1).coerceAtLeast(0)
                        "down" -> sel = (sel + 1).coerceAtMost(THEMES.size - 1)
                        "ok" -> say("Tema: " + THEMES[sel].first)
                    }
                    if (k == "up" || k == "down" || k == "ok") { theme = sel; Prefs.theme = sel }
                    if (back) open("menu", 0)
                }
                "hdmi" -> {
                    val n = ports.size
                    when (k) {
                        "up" -> sel = (sel - 1).coerceAtLeast(0)
                        "down" -> sel = (sel + 1).coerceAtMost((n - 1).coerceAtLeast(0))
                        "ok" -> if (n > 0) {
                            val p = ports[sel]
                            if (!Inputs.open(ctx, p)) say("Não consegui abrir ${p.name}")
                            closeModal()
                        }
                    }
                    if (back) closeModal()
                }
            }
            return
        }
        when (k) {
            "right" -> col = (col + 1).coerceAtMost(rowLen(row) - 1)
            "left" -> col = (col - 1).coerceAtLeast(0)
            "down" -> if (!expanded) expanded = true else if (row < 2) {
                val ratio = col.toFloat() / (rowLen(row) - 1).coerceAtLeast(1)
                row++; col = Math.round(ratio * (rowLen(row) - 1))
            }
            "up" -> if (row > 0) {
                val ratio = col.toFloat() / (rowLen(row) - 1).coerceAtLeast(1)
                row--; col = Math.round(ratio * (rowLen(row) - 1))
            } else if (expanded) expanded = false
            "ok" -> ok()
            "back" -> if (expanded) { expanded = false; row = 0 }
        }
    }

    var upRemote by mutableStateOf("")

    private fun launch(a: AppEntry) {
        if (!Inputs.start(ctx, a.intent())) say("Não consegui abrir ${a.label}")
    }

    private fun ok() {
        when (row) {
            0 -> {
                val v = visible
                if (col >= v.size) open("mais") else launch(v[col])
            }
            1 -> when (col) {
                0 -> if (!Inputs.liveTv(ctx)) say("A TV ao vivo não está disponível neste aparelho")
                1 -> { ports = Inputs.hdmi(ctx); open("hdmi") }
                2 -> open("all")
                3 -> open("menu")
                else -> if (!Inputs.start(ctx, Intent(Settings.ACTION_SETTINGS))) say("Não consegui abrir os ajustes")
            }
            else -> if (col == 0) say("Previsão de ${weather?.city ?: Info.CITY}: atualiza a cada 30 minutos") else say("Cotações atualizam a cada 15 minutos")
        }
    }

    /** volta ao repouso depois de um tempo sem usar o controle */
    fun idle() {
        if (modal == null && expanded && System.currentTimeMillis() - lastKey > 60_000) { expanded = false; row = 0; col = 0 }
    }
}

class HomeActivity : ComponentActivity() {
    private lateinit var st: HomeState
    private val h = Handler(Looper.getMainLooper())
    private var lastInfo = 0L

    private val pkgRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { st.reloadApps() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            st.idle()
            val now = System.currentTimeMillis()
            if (now - lastInfo > 15 * 60_000L) { lastInfo = now; st.refreshInfo() }
            h.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        st = HomeState(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { st.key("back") }
        })
        st.reloadApps()
        androidx.core.content.ContextCompat.registerReceiver(this, pkgRx, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        test(intent)
        setContent { HomeRoot(st) }
    }

    /** ganchos do teste automático no emulador */
    private fun test(i: Intent?) {
        val t = i?.getStringExtra("home_test") ?: return
        when {
            t.startsWith("theme:") -> t.removePrefix("theme:").toIntOrNull()?.let { st.theme = it.coerceIn(0, THEMES.size - 1) }
            t == "expand" -> st.expanded = true
            t == "perf" -> { st.open("perf"); Perf.start(this) }
            t == "menu" -> st.open("menu")
            t == "org" -> st.open("org")
            t == "hdmi" -> { st.ports = Inputs.hdmi(this); st.open("hdmi") }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // botão Início: volta para o repouso
        if (intent.getStringExtra("home_test") != null) test(intent) else st.goHome()
    }

    override fun onResume() {
        super.onResume()
        h.removeCallbacks(ticker)
        h.post(ticker)
        if (st.modal == "perf") Perf.start(this)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(ticker)
        Perf.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgRx) } catch (e: Throwable) {}
    }
}
