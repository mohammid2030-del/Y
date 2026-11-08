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

        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
            <body><iframe src="https://www.youtube-nocookie.com/embed/$key?autoplay=1&playsinline=1&rel=0&modestbranding=1&fs=1"
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen
            referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>
        """.trimIndent()
        // An https origin is required for YouTube's embedded player to accept the request.
        web.loadDataWithBaseURL("https://nova-iptv.app/", html, "text/html", "utf-8", null)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) exitCustomView() else finish()
            }
        })
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
        fun start(context: Context, youtubeKey: String) {
            context.startActivity(Intent(context, TrailerActivity::class.java).putExtra(EXTRA_KEY, youtubeKey))
        }
    }
}
