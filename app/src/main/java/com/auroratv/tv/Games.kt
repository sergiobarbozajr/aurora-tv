package com.auroratv.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** Jogos de navegador jogados na TV, com controle Bluetooth (ou o controle remoto). */
object Games {
    class G(val name: String, val label: String, val url: String, val desc: String, val heavy: Boolean = false)

    /** Jogos gratuitos (versões livres, shareware ou jogos gratuitos por natureza). */
    val FREE = listOf(
        G("Quake", "Até 8 jogadores", "https://q1.pieter.com/", "Clássico de tiro de 1996, versão shareware livre, rodando no navegador. Multiplayer até 8 jogadores."),
        G("Quake II", "Até 8 jogadores", "https://q2.pieter.com/", "A sequência de Quake no navegador, com multiplayer até 8 jogadores."),
        G("Quake III Arena", "Funciona com controle", "https://q3.pieter.com/", "Arena de tiro rápida. Funciona com controle."),
        G("Urban Terror", "Multiplayer gratuito", "https://ut.pieter.com/", "Tiro tático multiplayer, jogo gratuito."),
        G("Return to Castle Wolfenstein", "380 MB na 1ª vez", "https://rtcw.pieter.com/", "Primeiro carregamento baixa cerca de 380 MB; depois fica guardado. Pesado para TVs de entrada.", heavy = true),
        G("CS Surf", "Mapas de surf e ranking", "https://surfd.net/", "Mapas clássicos de surf com ranking.")
    )

    /** Motores feitos por fãs que pedem o arquivo do SEU jogo original (não vem jogo junto). */
    val OWN_COPY = listOf(
        G("Halo: Combat Evolved", "Usa a imagem do seu disco", "https://mitchellhynes.com/halo/", "Até 128 jogadores. O site pede a imagem do SEU disco original de Halo; o jogo não vem junto.", heavy = true),
        G("Skate 3", "Usa a sua cópia convertida", "https://skate.aaddpp.lol/", "Motor feito por fã. Pede a SUA cópia do jogo convertida; o jogo não vem junto.", heavy = true)
    )

    const val TEST_URL = "file:///android_asset/controle.html"

    fun cards(): List<RowData> {
        fun mk(list: List<G>, base: Int) = list.mapIndexed { i, g ->
            mkCard(g.name, g.label, "game", base + i, g.url, if (g.heavy) "heavy" else "", emptyList(), g.desc)
        }
        return listOf(
            RowData("Jogos gratuitos no navegador", mk(FREE, 0)),
            RowData("Use a sua própria cópia do jogo", mk(OWN_COPY, 20)),
            RowData("Controle", listOf(mkCard("Testar controle", "Veja cada botão funcionando", "game", 40, TEST_URL, "", emptyList(), "Aperte os botões do controle Bluetooth e veja qual tecla o jogo recebe.")))
        )
    }

    private val covers = HashMap<String, String>()

    /** Imagem do jogo: a capa que o próprio site publica (og:image). */
    fun cover(pageUrl: String): String {
        if (!pageUrl.startsWith("http")) return ""
        covers[pageUrl]?.let { return it }
        val r = try {
            val c = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DESKTOP_UA)
            }
            val html = c.inputStream.bufferedReader().use { it.readText().take(200_000) }
            val m = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"'](?:og:image|twitter:image)[\"']", RegexOption.IGNORE_CASE).find(html)
            val u = m?.groupValues?.get(1) ?: ""
            if (u.isEmpty()) "" else URL(URL(pageUrl), u).toString()
        } catch (e: Throwable) {
            ""
        }
        covers[pageUrl] = r
        return r
    }

    const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}

/**
 * Tela do jogo: navegador em tela cheia. O controle Bluetooth vira teclado:
 * analógico esquerdo = W A S D, analógico direito e direcional = setas (virar/mirar),
 * A = Espaço (pular), B / R2 = Ctrl (atirar), X = E (usar), Y = R (recarregar),
 * L1 = Q, R1 = F, L2 = Shift (correr), Start = Enter, Select = Esc (menu do jogo).
 * Voltar do controle remoto: 1x abre o menu do jogo (Esc), 2x sai.
 */
class GameActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var hint: TextView
    private var lastBack = 0L
    private val held = HashSet<Int>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val url = intent?.getStringExtra("url") ?: Games.TEST_URL
        if (!(url.startsWith("https://") || url.startsWith("http://") || url == Games.TEST_URL)) {
            finish()
            return
        }
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            allowFileAccess = false
            userAgentString = Games.DESKTOP_UA
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: ""
                return !(s == "http" || s == "https")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.i("AuroraGame", m.message().take(300))
                return true
            }
        }
        hint = TextView(this).apply {
            text = "Controle: analógico = andar · setas = virar · A pula · B/R2 atira · Start = Enter · Voltar 2x sai"
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
        }
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 40 })
        setContentView(root)
        web.isFocusable = true
        web.requestFocus()
        web.loadUrl(url)
        android.util.Log.i("AuroraGame", "abrindo $url")
        hint.postDelayed({ hint.visibility = View.GONE }, 9000)
    }

    private fun send(code: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0))
    }

    private fun map(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_SPACE
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_R2 -> KeyEvent.KEYCODE_CTRL_LEFT
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_E
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_R
        KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_Q
        KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_F
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_THUMBL -> KeyEvent.KEYCODE_SHIFT_LEFT
        KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_ENTER
        KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_ESCAPE
        KeyEvent.KEYCODE_BUTTON_THUMBR -> KeyEvent.KEYCODE_V
        KeyEvent.KEYCODE_DPAD_CENTER -> KeyEvent.KEYCODE_ENTER
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                if (now - lastBack < 2000) {
                    finish()
                } else {
                    lastBack = now
                    send(KeyEvent.KEYCODE_ESCAPE, true)
                    send(KeyEvent.KEYCODE_ESCAPE, false)
                    hint.text = "Menu do jogo aberto (Esc). Aperte Voltar de novo para sair do jogo."
                    hint.visibility = View.VISIBLE
                    hint.removeCallbacks(hideHint)
                    hint.postDelayed(hideHint, 2500)
                }
            }
            return true
        }
        val m = map(code)
        if (m != 0) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) send(m, true)
            if (event.action == KeyEvent.ACTION_UP) send(m, false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val hideHint = Runnable { hint.visibility = View.GONE }

    private fun axisKey(v: Float, neg: Int, pos: Int) {
        val t = 0.45f
        fun set(k: Int, on: Boolean) {
            if (k == 0) return
            if (on && held.add(k)) send(k, true)
            if (!on && held.remove(k)) send(k, false)
        }
        set(neg, v < -t)
        set(pos, v > t)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val src = ev.source
        if ((src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && ev.action == MotionEvent.ACTION_MOVE) {
            // analógico esquerdo: andar (W A S D)
            axisKey(ev.getAxisValue(MotionEvent.AXIS_X), KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D)
            axisKey(ev.getAxisValue(MotionEvent.AXIS_Y), KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S)
            // analógico direito: virar e olhar (setas)
            val rx = ev.getAxisValue(MotionEvent.AXIS_Z).let { if (abs(it) > 0.01f) it else ev.getAxisValue(MotionEvent.AXIS_RX) }
            val ry = ev.getAxisValue(MotionEvent.AXIS_RZ).let { if (abs(it) > 0.01f) it else ev.getAxisValue(MotionEvent.AXIS_RY) }
            axisKey(rx, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            axisKey(ry, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
            // direcional em forma de eixo (muitos controles Bluetooth): setas
            axisKey(ev.getAxisValue(MotionEvent.AXIS_HAT_X), KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            axisKey(ev.getAxisValue(MotionEvent.AXIS_HAT_Y), KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
            // gatilhos analógicos
            val r2 = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
            val l2 = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
            axisKey(r2, 0, KeyEvent.KEYCODE_CTRL_LEFT)
            axisKey(l2, 0, KeyEvent.KEYCODE_SHIFT_LEFT)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) {
            for (k in held) send(k, false)
            held.clear()
            web.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }
}
