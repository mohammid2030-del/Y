package com.nova.iptv.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Plays a YouTube trailer inside the app (official embedded player in a full-screen WebView). */
class TrailerActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (key.isEmpty()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                // Keep everything inside the player; ignore links that would leave it.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customView?.let { root.removeView(it) }
                    customView = view
                    customCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    web.visibility = View.GONE
                }

                override fun onHideCustomView() = exitCustomView()
            }
        }
        root.addView(web)
        setContentView(root)

        // YouTube's official embedded player on our web page (docs/trailer.html on GitHub Pages): YouTube needs a real
        // web origin. The page reports ready / playing / ended / error; a video that can't be embedded opens in YouTube.
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun event(type: String, extra: String?) {
                runOnUiThread {
                    when (type) {
                        "ended" -> finish()
                        "error" -> openInYouTube(key)
                    }
                }
            }
        }, "NovaTrailer")
        val hl = if (resources.configuration.locales[0].language == "ar") "ar" else "en"
        web.loadUrl("$PLAYER_PAGE?v=$key&hl=$hl")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) exitCustomView() else finish()
            }
        })
    }

    private fun openInYouTube(key: String) {
        val app = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$key"))
        val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$key"))
        runCatching { startActivity(app) }.recoverCatching { startActivity(web) }
        finish()
    }

    private fun exitCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web.visibility = View.VISIBLE
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() {
        if (::web.isInitialized) { web.loadUrl("about:blank"); web.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val PLAYER_PAGE = "https://mohammid2030-del.github.io/Y/trailer.html"
        fun start(context: Context, youtubeKey: String) {
            context.startActivity(Intent(context, TrailerActivity::class.java).putExtra(EXTRA_KEY, youtubeKey))
        }
    }
}
