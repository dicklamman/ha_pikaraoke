package com.dicklamman.pikaraoke

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.webkit.WebChromeClient.CustomViewCallback
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
        findViewById<Button>(R.id.setup_back_to_player).setOnClickListener {
            val current = serverUrl ?: savedServerUrl()
            if (!current.isNullOrBlank()) {
                connect(normalizeUrl(current))
            }
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

        // The address field brings up the keyboard when it is chosen, so a
        // person with a keyboard can type straight away, while someone using
        // a remote can walk past it without the keyboard ever appearing.
        setupAddress.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showKeyboard()
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
        val saved = savedServerUrl()
        if (!fromIntent.isNullOrBlank()) {
            // The app was started with an address, so there is nothing to look for.
            connect(normalizeUrl(fromIntent))
        } else if (!saved.isNullOrBlank()) {
            // The address was entered before and is kept, so there is nothing
            // to ask for again: the player goes straight to the server.
            setupAddress.setText(saved)
            connect(normalizeUrl(saved))
        } else {
            // The address can be typed in from the start, and the search runs
            // at the same time: whichever finds a usable server first wins,
            // and the other stays available for the user to use.
            //
            // The focus starts on the address, and the keyboard comes up with
            // it, because the address is the one thing the user may want to
            // do here. A player on a television with a remote control ignores
            // the keyboard and the search answers instead.
            setupAddress.requestFocus()
            setupAddress.post { showKeyboard() }
            startSearch()
        }
    }

    /** The address the app used last time, if there was one. */
    private fun savedServerUrl(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_SERVER_URL, null)
    }

    /** Bring up the keyboard, so the address can be typed in. */
    private fun showKeyboard() {
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.showSoftInput(setupAddress, InputMethodManager.SHOW_IMPLICIT)
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
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(
                view: View,
                callback: CustomViewCallback
            ) {
                // A video that asks for the whole screen, such as the one the
                // splash screen plays, is shown in the web view itself. The
                // default handling takes the activity out of the picture,
                // which on a television means the player is replaced by
                // whatever the system puts in its place.
                webView.visibility = View.VISIBLE
            }
        }
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
     * Put the keyboard away.
     *
     * The address has been given, so the keyboard has nothing left to do.
     * On a television it covers half the screen, and it is the one thing
     * that gets in the way of the player.
     *
     * The focus moves to the button that starts the connection, so the
     * arrow keys of a remote have something to land on.
     */
    private fun hideKeyboard() {
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(setupAddress.windowToken, 0)
        // The address gives up the focus, so the keyboard does not come back
        // on its own, and the button that starts the connection takes it.
        setupAddress.clearFocus()
        findViewById<Button>(R.id.setup_connect)?.requestFocus()
    }

    /**
     * Show the splash screen from a server at the given address.
     *
     * The setup panel stays up until the page arrives, so that a server which
     * cannot be reached leaves an address to fix rather than a blank screen.
     */
    private fun connect(url: String) {
        serverUrl = url
        // The address is settled, so the keyboard is not needed any more.
        hideKeyboard()
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
        // The way back to the player is only there when there is a player to
        // go back to.
        findViewById<Button>(R.id.setup_back_to_player).visibility =
            if (serverUrl.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Hide the setup panel, so the player is the only thing on the screen. */
    private fun hideSetup() {
        setupPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        // The player takes the focus, so the arrow keys of a remote reach the
        // splash screen and not the buttons of a panel that is no longer
        // there.
        webView.requestFocus()
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
     * A back press asks whether to leave the app.
     *
     * The player is the whole experience and the page has no history to go
     * back to, so a press that would otherwise close the app asks first. A
     * player on a television is often left running, and a stray press from a
     * remote control should not end the song.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                webView.goBack()
            } else {
                confirmExit()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Ask whether the app should be closed.
     *
     * Confirming closes the app for good. Declining returns to the player,
     * which is where a player belongs. Changing the address is offered here
     * too, because the back button no longer leads to the address screen.
     *
     * The question is a layout of its own, so that every answer is a button
     * the arrow keys of a television remote can land on. The stock dialog
     * buttons cannot be reached that way, which would leave someone sitting
     * in front of the television with no way to answer.
     */
    private fun confirmExit() {
        val dialog = AlertDialog.Builder(this)
            .setView(R.layout.dialog_exit)
            .create()

        // The remote's back button answers the question the same way the
        // player itself does: it does not close the app by accident.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }

        dialog.setOnShowListener {
            // The player is paused while the question is on screen, so the
            // song does not play to nobody behind a dialog.
            webView.onPause()

            // The focus starts on the answer that keeps the player running,
            // so a stray press of the middle of the remote does nothing.
            val stay = dialog.findViewById<Button>(R.id.exit_stay)
            stay?.requestFocus()
            wireUpAnswerKeys(dialog)
        }
        dialog.setOnDismissListener {
            // The dialog may have closed the app, in which case the player
            // must not be woken up again.
            if (!isFinishing) {
                webView.onResume()
            }
        }

        // The layout is only put together once the question is on screen, so
        // the answers are wired up afterwards.
        dialog.show()

        dialog.findViewById<Button>(R.id.exit_stay)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<Button>(R.id.exit_confirm)?.setOnClickListener {
            dialog.dismiss()
            finish()
        }
        dialog.findViewById<Button>(R.id.exit_change_address)?.setOnClickListener {
            dialog.dismiss()
            showAddressScreen()
        }
    }

    /**
     * Make the arrow keys of a remote walk the answers one at a time.
     *
     * The answers are stacked down the screen, so the up and down keys move
     * between them. The order is fixed here rather than left to the system,
     * because the system is free to skip a button, and a remote that skips
     * an answer is a remote that presses the wrong thing.
     */
    private fun wireUpAnswerKeys(dialog: AlertDialog) {
        // The answers, in the order they are shown.
        val order = listOf(R.id.exit_stay, R.id.exit_confirm, R.id.exit_change_address)

        for ((index, id) in order.withIndex()) {
            val button = dialog.findViewById<Button>(id) ?: continue
            val next = order[(index + 1) % order.size]
            val previous = order[(index - 1 + order.size) % order.size]

            button.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                val target = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> next
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> previous
                    else -> return@setOnKeyListener false
                }
                dialog.findViewById<Button>(target)?.requestFocus()
                true
            }
        }
    }

    /**
     * Open the address screen.
     *
     * The back button asks whether to leave, so the address screen is
     * reached from the exit question instead.
     */
    private fun showAddressScreen() {
        setupAddress.setText(serverUrl ?: savedServerUrl() ?: "")
        showSetup(getString(R.string.setup_change_address))
        // The address is where the question leads, so the focus goes there.
        // The keyboard stays away until the address is asked for, because
        // the person may be using a remote rather than a keyboard.
        setupAddress.requestFocus()
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
