package com.dicklamman.pikaraoke

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
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
 * for a PiKaraoke server, and the address can be typed in when the search does
 * not find one, or when the one it found cannot be reached.
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

    /**
     * The address to try when the server's own address cannot be reached.
     *
     * A server behind a router, or a container, reports an address that other
     * devices cannot use, while the address the discovery answer came from is
     * one that demonstrably reached it.
     */
    private var fallbackUrl: String? = null

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
            connectToTypedAddress()
        }
        findViewById<Button>(R.id.setup_search).setOnClickListener {
            startSearch()
        }
        // The keyboard's own go action connects, so the address can be typed
        // and confirmed without reaching for a button.
        setupAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                connectToTypedAddress()
                true
            } else {
                false
            }
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
            // The address can be typed in from the start, and the search runs
            // at the same time: whichever finds a usable server first wins,
            // and the other stays available for the user to use.
            setupAddress.setText(savedServerUrl() ?: "")
            setupAddress.requestFocus()
            // The keyboard is brought up, because the address is the one thing
            // the user may want to do here. A player on a television with a
            // remote control ignores it and the search answers instead.
            setupAddress.post {
                val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                keyboard.showSoftInput(setupAddress, InputMethodManager.SHOW_IMPLICIT)
            }
            startSearch()
        }
    }

    /** The address the app used last time, if there was one. */
    private fun savedServerUrl(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_SERVER_URL, null)
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
                // A load has started, so the address is at least well formed.
                // The panel stays up until the page actually arrives, because a
                // load that starts and then fails would otherwise leave a
                // blank screen with no way back.
                setupStatus.text = getString(R.string.setup_connecting)
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
                if (!request.isForMainFrame) {
                    return
                }
                // The address the server gave may be one that cannot be reached
                // from here, so the address the discovery answer came from is
                // tried before giving up.
                val fallback = fallbackUrl
                fallbackUrl = null
                if (fallback != null) {
                    connect(fallback)
                } else {
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
            // A search must never take the app down with it: a network that
            // behaves oddly is a reason to ask for an address, not a crash.
            val servers = try {
                Discovery(this@MainActivity).findServers()
            } catch (e: Exception) {
                Log.w("PiKaraoke", "The search failed", e)
                emptyList()
            }
            mainHandler.post {
                searching = false
                val server = servers.firstOrNull()
                if (server != null) {
                    // The server was found. Its own address is tried first,
                    // because that is the one its own splash screen shows and
                    // the one other devices on the network use. The address the
                    // answer came from is kept as a fallback, for when that one
                    // cannot be reached from here.
                    fallbackUrl = server.sourceUrl
                    setupAddress.setText(server.url)
                    connect(server.url)
                } else {
                    showSetup(getString(R.string.setup_not_found))
                }
            }
        }
    }

    /** Connect to the address that was typed in. */
    private fun connectToTypedAddress() {
        val typed = setupAddress.text.toString().trim()
        if (typed.isNotEmpty()) {
            // The user has said which address to use, so the fallback from a
            // search no longer applies.
            fallbackUrl = null
            connect(normalizeUrl(typed))
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
     * A back press goes back inside the page.
     *
     * The player is the whole experience, so a press that would leave it
     * brings up the address screen instead of closing the app: the address
     * may need changing, and there is nowhere else to go.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                webView.goBack()
            } else {
                showSetup(getString(R.string.setup_change_address))
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
