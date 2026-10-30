package com.auroratv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * TV online: abre um endereço da internet dentro do app.
 *
 * Numa TV não há mouse, e boa parte dos sites de vídeo só responde a clique. Por isso a tela
 * tem dois modos, trocados pela tecla de menu (ou segurando OK):
 *  - seta: o controle move uma setinha pela tela e OK clica onde ela está;
 *  - foco: as setas pulam entre links e botões, como num site feito para TV.
 *
 * Fica numa tela própria, separada do resto do app: o navegador é pesado e assim ele não
 * atrapalha a fluidez da tela inicial, e some da memória quando você sai.
 */
class WebActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var dot: View
    private lateinit var hint: TextView
    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null

    /** modo seta ligado (a maioria dos sites precisa de clique) */
    private var cursor = true
    private var cx = 0f
    private var cy = 0f
    private var step = 26f
    private var lastMove = 0L
    private var okDown = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.getStringExtra("url").orEmpty().ifBlank { "https://www.google.com" }
        root = FrameLayout(this)
        root.setBackgroundColor(AColor.BLACK)

        web = WebView(this)
        web.setBackgroundColor(AColor.BLACK)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            // muitos sites entregam um player melhor para computador do que para TV
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, u: String?) {
                bar.visibility = View.GONE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                bar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                bar.progress = p
            }

            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                // vídeo em tela cheia
                if (fullView != null || view == null) return
                fullView = view
                fullCb = cb
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                dot.visibility = View.GONE
            }

            override fun onHideCustomView() {
                fullView?.let { root.removeView(it) }
                fullView = null
                try {
                    fullCb?.onCustomViewHidden()
                } catch (e: Throwable) {
                }
                if (cursor) dot.visibility = View.VISIBLE
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(4)))

        // a setinha do controle
        dot = View(this)
        dot.background = resources.getDrawable(android.R.drawable.presence_online, null)
        root.addView(dot, FrameLayout.LayoutParams(dp(22), dp(22)))

        hint = TextView(this)
        hint.setTextColor(AColor.WHITE)
        hint.setBackgroundColor(0xCC000000.toInt())
        hint.setPadding(dp(14), dp(8), dp(14), dp(8))
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        hint.text = "Setas movem a seta · OK clica · Menu troca para navegação por foco · Voltar sai"
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        hint.postDelayed({ hint.visibility = View.GONE }, 6000)

        setContentView(root)
        web.post {
            cx = root.width / 2f
            cy = root.height / 2f
            step = (root.width / 48f).coerceAtLeast(16f)
            place()
        }
        web.loadUrl(url)
        web.requestFocus()
    }

    private lateinit var bar: ProgressBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun place() {
        val p = dot.layoutParams as FrameLayout.LayoutParams
        p.leftMargin = (cx - dp(11)).toInt()
        p.topMargin = (cy - dp(11)).toInt()
        dot.layoutParams = p
        dot.visibility = if (cursor && fullView == null) View.VISIBLE else View.GONE
    }

    private fun move(dx: Float, dy: Float) {
        val now = SystemClock.uptimeMillis()
        // segurando a seta, ela anda mais rápido
        val fast = if (now - lastMove < 150) 3.2f else 1f
        lastMove = now
        cx = (cx + dx * step * fast).coerceIn(0f, (root.width - 1).toFloat())
        cy = (cy + dy * step * fast).coerceIn(0f, (root.height - 1).toFloat())
        place()
    }

    private fun tap() {
        val t = SystemClock.uptimeMillis()
        for (act in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, act, cx, cy, 0)
            web.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun toast(m: String) {
        hint.text = m
        hint.visibility = View.VISIBLE
        hint.removeCallbacks(hide)
        hint.postDelayed(hide, 2500)
    }

    private val hide = Runnable { hint.visibility = View.GONE }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (fullView != null && code == KeyEvent.KEYCODE_BACK) {
            web.webChromeClient?.onHideCustomView()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_PROG_YELLOW -> {
                cursor = !cursor
                place()
                toast(if (cursor) "Modo seta: as setas movem a seta e OK clica" else "Modo foco: as setas pulam entre os botões do site")
                if (!cursor) web.requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!cursor) return super.onKeyDown(code, e)
                if (okDown == 0L) okDown = SystemClock.uptimeMillis()
                return true
            }
        }
        if (cursor) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1f, 0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1f, 0f); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { move(0f, -1f); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { move(0f, 1f); return true }
            }
        }
        return super.onKeyDown(code, e)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        if (cursor && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            val held = SystemClock.uptimeMillis() - okDown
            okDown = 0L
            // segurar OK também troca de modo, para quem não tem tecla de menu no controle
            if (held > 700) {
                cursor = false
                place()
                web.requestFocus()
                toast("Modo foco: as setas pulam entre os botões do site")
            } else tap()
            return true
        }
        return super.onKeyUp(code, e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullView != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        try {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        /** Completa o endereço quando a pessoa digita só "site.com". */
        fun fix(u: String): String {
            val t = u.trim()
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        fun open(c: Context, url: String) {
            try {
                c.startActivity(Intent(c, WebActivity::class.java).putExtra("url", fix(url)))
            } catch (e: Throwable) {
            }
        }
    }
}
