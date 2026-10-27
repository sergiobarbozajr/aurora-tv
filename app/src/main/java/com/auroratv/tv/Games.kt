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
            RowData(
                "Controles", listOf(
                    mkCard("Mapear controles", "Controle da TV ou Bluetooth", "gamemap", 41, null, "", emptyList(), "Escolha qual botão faz cada ação: pular, atirar, andar… Tem perfil pronto para jogar só com o controle remoto da TV."),
                    mkCard("Testar controle", "Veja cada botão funcionando", "game", 40, TEST_URL, "", emptyList(), "Aperte os botões e veja qual tecla o jogo recebe.")
                )
            )
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

    /** Ações do jogo e a tecla que cada uma envia. */
    val ACTIONS = listOf(
        "Andar para frente" to KeyEvent.KEYCODE_W,
        "Andar para trás" to KeyEvent.KEYCODE_S,
        "Andar para a esquerda" to KeyEvent.KEYCODE_A,
        "Andar para a direita" to KeyEvent.KEYCODE_D,
        "Virar à esquerda" to KeyEvent.KEYCODE_DPAD_LEFT,
        "Virar à direita" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "Olhar para cima" to KeyEvent.KEYCODE_PAGE_UP,
        "Olhar para baixo" to KeyEvent.KEYCODE_PAGE_DOWN,
        "Pular" to KeyEvent.KEYCODE_SPACE,
        "Atirar" to KeyEvent.KEYCODE_CTRL_LEFT,
        "Usar / abrir" to KeyEvent.KEYCODE_E,
        "Recarregar" to KeyEvent.KEYCODE_R,
        "Correr" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "Agachar" to KeyEvent.KEYCODE_C,
        "Trocar arma" to KeyEvent.KEYCODE_Q,
        "Placar" to KeyEvent.KEYCODE_TAB,
        "Confirmar (Enter)" to KeyEvent.KEYCODE_ENTER,
        "Menu do jogo (Esc)" to KeyEvent.KEYCODE_ESCAPE
    )

    fun mapping(): MutableMap<Int, Int> {
        val m = LinkedHashMap<Int, Int>()
        for (p in Store.gameMap.split(";")) {
            val a = p.split(":")
            if (a.size == 2) {
                val k = a[0].toIntOrNull()
                val v = a[1].toIntOrNull()
                if (k != null && v != null) m[k] = v
            }
        }
        return m
    }

    fun save(m: Map<Int, Int>) {
        Store.gameMap = m.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    /** Perfil para jogar só com o controle remoto da TV. */
    fun remotePreset(): Map<Int, Int> = linkedMapOf(
        KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_W,
        KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_S,
        KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER to KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_ENTER to KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CHANNEL_UP to KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_CHANNEL_DOWN to KeyEvent.KEYCODE_E,
        KeyEvent.KEYCODE_PROG_RED to KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_PROG_GREEN to KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_PROG_YELLOW to KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_PROG_BLUE to KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to KeyEvent.KEYCODE_ESCAPE
    )

    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "Seta ↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Seta ↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Seta ←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Seta →"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "OK"
        KeyEvent.KEYCODE_CHANNEL_UP -> "Canal +"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "Canal −"
        KeyEvent.KEYCODE_PROG_RED -> "Botão vermelho"
        KeyEvent.KEYCODE_PROG_GREEN -> "Botão verde"
        KeyEvent.KEYCODE_PROG_YELLOW -> "Botão amarelo"
        KeyEvent.KEYCODE_PROG_BLUE -> "Botão azul"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Número " + (code - KeyEvent.KEYCODE_0)
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace("BUTTON_", "Botão ").replace('_', ' ')
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
    private lateinit var root: FrameLayout
    private lateinit var cursor: View
    private var cx = 0f
    private var cy = 0f
    private var full: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null

    /** Controle remoto da TV = cursor (setas movem, OK clica); controle Bluetooth = teclas do jogo. */
    private var remoteKeys = false
    private var custom: Map<Int, Int> = emptyMap()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        remoteKeys = Store.gameRemoteKeys
        custom = Games.mapping()
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

            // tela cheia pedida pelo jogo
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                full?.let { root.removeView(it) }
                full = view
                fullCb = callback
                root.addView(view, 1, FrameLayout.LayoutParams(-1, -1))
            }

            override fun onHideCustomView() {
                full?.let { root.removeView(it) }
                full = null
                fullCb = null
            }
        }
        hint = TextView(this).apply {
            text = "Controle remoto: setas movem o cursor, OK clica (nome, PLAY). Controle Bluetooth: analógico anda · A pula · B/R2 atira · Start = Enter. Menu alterna o remoto para teclas. Voltar 2x sai"
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
        }
        root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        cursor = object : View(this) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) {
                if (remoteKeys) return
                val path = android.graphics.Path().apply {
                    moveTo(cx, cy); lineTo(cx, cy + 34f); lineTo(cx + 9f, cy + 26f); lineTo(cx + 16f, cy + 40f)
                    lineTo(cx + 22f, cy + 37f); lineTo(cx + 15f, cy + 24f); lineTo(cx + 27f, cy + 24f); close()
                }
                p.style = android.graphics.Paint.Style.FILL; p.color = Color.WHITE; c.drawPath(path, p)
                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.BLACK; c.drawPath(path, p)
            }
        }
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 40 })
        setContentView(root)
        web.isFocusable = true
        web.requestFocus()
        web.loadUrl(url)
        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            cursor.invalidate()
        }
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

    private fun click() {
        val t = SystemClock.uptimeMillis()
        val target: View = full ?: web
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0)
        d.source = InputDevice.SOURCE_TOUCHSCREEN
        u.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(d)
        target.dispatchTouchEvent(u)
        d.recycle()
        u.recycle()
    }

    private fun isPad(e: KeyEvent): Boolean =
        e.isFromSource(InputDevice.SOURCE_GAMEPAD) || e.isFromSource(InputDevice.SOURCE_JOYSTICK) || KeyEvent.isGamepadButton(e.keyCode)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // mapeamento feito pelo usuário (controle Bluetooth sempre; controle remoto quando não está no modo cursor)
        val mine = custom[code]
        if (mine != null && code != KeyEvent.KEYCODE_BACK && (isPad(event) || remoteKeys)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) send(mine, true)
            if (event.action == KeyEvent.ACTION_UP) send(mine, false)
            return true
        }
        // controle remoto: cursor na tela (o controle Bluetooth continua indo direto para o jogo)
        if (!isPad(event)) {
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_GUIDE) {
                if (event.action == KeyEvent.ACTION_UP) {
                    remoteKeys = !remoteKeys
                    cursor.invalidate()
                    hint.text = if (remoteKeys) "Controle remoto agora manda as setas e o OK para o jogo. Menu volta ao cursor." else "Cursor ligado: setas movem, OK clica."
                    hint.visibility = View.VISIBLE
                    hint.removeCallbacks(hideHint)
                    hint.postDelayed(hideHint, 2500)
                }
                return true
            }
            if (!remoteKeys && code != KeyEvent.KEYCODE_BACK) {
                val step = (root.height / 40f) * (1 + minOf(event.repeatCount, 12) / 3f)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> cx -= step
                        KeyEvent.KEYCODE_DPAD_RIGHT -> cx += step
                        KeyEvent.KEYCODE_DPAD_UP -> cy -= step
                        KeyEvent.KEYCODE_DPAD_DOWN -> cy += step
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) click()
                        else -> return super.dispatchKeyEvent(event)
                    }
                    // na borda, rola a página
                    if (cy < 10f) web.scrollBy(0, -(step * 2).toInt())
                    if (cy > root.height - 10f) web.scrollBy(0, (step * 2).toInt())
                    cx = cx.coerceIn(0f, root.width - 4f)
                    cy = cy.coerceIn(0f, root.height - 4f)
                    cursor.invalidate()
                    return true
                }
                if (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return true
                return super.dispatchKeyEvent(event)
            }
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && full != null) {
                fullCb?.onCustomViewHidden()
                full?.let { root.removeView(it) }
                full = null
                return true
            }
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


/** Tela de mapeamento: escolha a ação e aperte o botão (do controle da TV ou do Bluetooth). */
class GameMapActivity : ComponentActivity() {
    private lateinit var list: android.widget.LinearLayout
    private lateinit var status: TextView
    private var sel = 0
    private var waiting = -1
    private var m: MutableMap<Int, Int> = LinkedHashMap()
    private val rows = ArrayList<TextView>()

    // primeiras linhas: perfis prontos; depois cada ação
    private val presets = listOf("Perfil: controle remoto da TV", "Perfil: controle Bluetooth (padrão)", "Começar no modo cursor: ")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        m = Games.mapping()
        val sc = android.widget.ScrollView(this)
        sc.setBackgroundColor(0xFF070A12.toInt())
        list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(80, 40, 80, 40)
        }
        val title = TextView(this).apply {
            text = "Mapear controles dos jogos"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        status = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFF9FB2D0.toInt())
            setPadding(0, 8, 0, 18)
        }
        list.addView(title)
        list.addView(status)
        val n = presets.size + Games.ACTIONS.size
        for (i in 0 until n) {
            val t = TextView(this).apply {
                textSize = 19f
                setPadding(26, 14, 26, 14)
            }
            rows.add(t)
            list.addView(t, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
        }
        sc.addView(list)
        setContentView(sc)
        refresh()
    }

    private fun refresh() {
        for ((i, t) in rows.withIndex()) {
            val txt = if (i < presets.size) {
                if (i == 2) presets[i] + (if (Store.gameRemoteKeys) "não (remoto joga direto)" else "sim (remoto = cursor)") else presets[i]
            } else {
                val (name, key) = Games.ACTIONS[i - presets.size]
                val btns = m.filterValues { it == key }.keys.joinToString(" · ") { Games.keyName(it) }
                name + "   →   " + (if (waiting == i) "aperte o botão…" else btns.ifEmpty { "padrão" })
            }
            t.text = txt
            val on = i == sel
            t.setTextColor(if (on) Color.BLACK else Color.WHITE)
            t.setBackgroundColor(if (on) Color.WHITE else if (i < presets.size) 0xFF1B2540.toInt() else 0xFF121828.toInt())
        }
        status.text = if (waiting >= 0) "Aperte agora o botão que vai fazer essa ação (Voltar cancela)."
        else "▲▼ escolhe · OK muda · Voltar salva e sai. Menu do controle remoto alterna cursor/teclas dentro do jogo."
        rows.getOrNull(sel)?.let { r -> r.post { r.requestRectangleOnScreen(android.graphics.Rect(0, 0, r.width, r.height)) } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val code = event.keyCode
        if (waiting >= 0) {
            if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_HOME) {
                val target = Games.ACTIONS[waiting - presets.size].second
                m.remove(code)
                m[code] = target
                Games.save(m)
            }
            waiting = -1
            refresh()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> sel = maxOf(0, sel - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> sel = minOf(rows.size - 1, sel + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> when (sel) {
                0 -> {
                    m.clear(); m.putAll(Games.remotePreset()); Games.save(m)
                    Store.gameRemoteKeys = true
                    status.text = "Perfil do controle remoto aplicado."
                }
                1 -> {
                    m.clear(); Games.save(m)
                    Store.gameRemoteKeys = false
                }
                2 -> Store.gameRemoteKeys = !Store.gameRemoteKeys
                else -> waiting = sel
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                finish()
                return true
            }
        }
        refresh()
        return true
    }
}
