package com.dicklamman.pikaraoke

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The PiKaraoke player, as an app.
 *
 * The splash screen is a page served by the PiKaraoke server, so the app is a
 * browser that shows one address and gets out of the way: no address bar, no
 * scrollbars, and the screen is kept awake while it plays.
 *
 * The address is found rather than typed, because the player usually runs on a
 * television with a remote control and no keyboard. The app asks the network
 * for a PiKaraoke server, and only asks for an address when that fails.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var setupPanel: LinearLayout
    private lateinit var setupStatus: TextView
    private lateinit var setupAddress: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    /** The address the player is showing, or null while none is known. */
    private var serverUrl: String? = null

    /** True while the app is looking for a server, so it does not look twice. */
    private var searching = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web_view)
        setupPanel = findViewById(R.id.setup_panel)
        setupStatus = findViewById(R.id.setup_status)
        setupAddress = findViewById(R.id.setup_address)

        configureWebView()

        findViewById<Button>(R.id.setup_connect).setOnClickListener {
            val typed = setupAddress.text.toString().trim()
            if (typed.isNotEmpty()) {
                connect(normalizeUrl(typed))
            }
        }
        findViewById<Button>(R.id.setup_search).setOnClickListener {
            startSearch()
        }

        // The player runs for hours, so the screen must not sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val fromIntent = intent?.getStringExtra(EXTRA_SERVER_URL)
        if (!fromIntent.isNullOrBlank()) {
            // The app was started with an address, so there is nothing to look for.
            connect(normalizeUrl(fromIntent))
        } else {
            startSearch()
        }
    }

    /** Set the web view up for the splash screen's scripting, media and socket. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings: WebSettings = webView.settings
        // The splash screen is a page that talks to the server over Socket.IO
        // and plays video, so it needs scripting and media.
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // The server is reached over plain HTTP on the local network, which is
        // what a PiKaraoke box serves.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Everything stays inside the app: the splash screen is the
                // whole experience, and a link out of it would leave the
                // player showing something else.
                return true
            }

            override fun onPageStarted(
                view: WebView,
                url: String,
                favicon: android.graphics.Bitmap?
            ) {
                // A page has started loading, so the address was good enough to
                // reach something. The panel goes away, and comes back if the
                // load turns out to have failed.
                hideSetup()
            }

            override fun onPageFinished(view: WebView, url: String) {
                hideSetup()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // Only the page itself failing matters. A missing image or a
                // blocked font should not throw the player back to the setup
                // screen while the splash is otherwise working.
                if (request.isForMainFrame) {
                    showSetup(getString(R.string.setup_could_not_connect))
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    /**
     * Look for a PiKaraoke server on the network.
     *
     * The search runs off the main thread, because it waits on the network,
     * and the answer comes back on it.
     */
    private fun startSearch() {
        if (searching) {
            return
        }
        searching = true
        showSetup(getString(R.string.setup_searching))

        background.execute {
            val servers = Discovery.findServers()
            mainHandler.post {
                searching = false
                val server = servers.firstOrNull()
                if (server != null) {
                    connect(server.url)
                } else {
                    showSetup(getString(R.string.setup_not_found))
                }
            }
        }
    }

    /**
     * Show the splash screen from a server at the given address.
     *
     * The setup panel stays up until the page arrives, so that a server which
     * cannot be reached leaves an address to fix rather than a blank screen.
     */
    private fun connect(url: String) {
        serverUrl = url
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_SERVER_URL, url)
            .apply()

        setupStatus.text = getString(R.string.setup_connecting)
        webView.loadUrl("$url/splash")
    }

    /** Show the setup panel with a message, and hide the player. */
    private fun showSetup(message: String) {
        setupStatus.text = message
        setupPanel.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    /** Hide the setup panel, so the player is the only thing on the screen. */
    private fun hideSetup() {
        setupPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /**
     * A server address without a scheme, or with a trailing slash, is what a
     * person types on a TV remote, so both are put right here rather than
     * being left to fail with a blank page.
     */
    private fun normalizeUrl(url: String): String {
        var candidate = url.trim()
        if (candidate.isEmpty()) {
            return DEFAULT_SERVER_URL
        }
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "http://$candidate"
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.dropLast(1)
        }
        return candidate
    }

    /**
     * A back press goes back inside the page. There is no page history to leave
     * from, so the press that would close the app is swallowed instead.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                webView.goBack()
            } else {
                // Back from the setup panel returns to the player that was
                // showing before, rather than closing the app.
                showSetup(getString(R.string.setup_not_found))
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        background.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** The address the app starts with before one is found. */
        const val DEFAULT_SERVER_URL = "http://192.168.0.10:5555"

        /** Passed in an intent to open the app on a particular server. */
        const val EXTRA_SERVER_URL = "com.dicklamman.pikaraoke.SERVER_URL"

        private const val PREFS_NAME = "pikaraoke"
        private const val PREF_SERVER_URL = "server_url"
    }
}
