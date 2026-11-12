package com.allora.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

class Tab(val session: GeckoSession) {
    var url: String = ""
    var title: String = ""
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
}

class MainActivity : AppCompatActivity() {

    companion object {
        // GeckoRuntime must only be created once per process.
        private var sharedRuntime: GeckoRuntime? = null

        private fun runtimeFor(activity: MainActivity): GeckoRuntime {
            sharedRuntime?.let { return it }
            val rt = GeckoRuntime.create(activity.applicationContext)
            rt.settings.contentBlocking.setAntiTracking(ContentBlocking.AntiTracking.STRICT)
            sharedRuntime = rt
            return rt
        }

        private val WEB_SCHEMES = setOf(
            "http", "https", "about", "data", "blob", "file",
            "view-source", "moz-extension", "resource"
        )
    }

    private lateinit var runtime: GeckoRuntime
    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var homeView: ImageView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var btnBack: TextView
    private lateinit var btnForward: TextView
    private lateinit var btnTabs: TextView

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private var desktopMode = false
    private var lastTouchTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.geckoView)
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progressBar)
        homeView = findViewById(R.id.homeView)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnTabs = findViewById(R.id.btnTabs)

        runtime = runtimeFor(this)

        // Remember when the user last touched the page: used to tell real
        // link clicks apart from pop-ups opened by scripts.
        geckoView.setOnTouchListener { _, _ ->
            lastTouchTime = SystemClock.uptimeMillis()
            false
        }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isGo) {
                loadFromBar()
                true
            } else {
                false
            }
        }

        btnBack.setOnClickListener { current?.session?.goBack() }
        btnForward.setOnClickListener { current?.session?.goForward() }
        findViewById<TextView>(R.id.btnReload).setOnClickListener {
            current?.session?.reload()
        }
        btnTabs.setOnClickListener { showTabsDialog() }
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { showMenuDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = current
                when {
                    tab != null && tab.canGoBack -> tab.session.goBack()
                    tabs.size > 1 && tab != null -> closeTab(tab)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        createTab(intent?.dataString)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { createTab(it) }
    }

    // ---------------------------------------------------------------- tabs

    private fun createTab(url: String?, openNow: Boolean = true): Tab {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(userAgentMode())
            .viewportMode(viewportMode())
            .build()
        val session = GeckoSession(settings)
        val tab = Tab(session)
        attachDelegates(tab)
        tabs.add(tab)
        if (openNow) {
            session.open(runtime)
            switchTo(tab)
            if (!url.isNullOrEmpty()) {
                tab.url = url
                session.loadUri(url)
                updateUi()
            }
        }
        return tab
    }

    private fun switchTo(tab: Tab) {
        current?.let { old ->
            if (old !== tab) old.session.setActive(false)
        }
        if (geckoView.session != null) geckoView.releaseSession()
        geckoView.setSession(tab.session)
        tab.session.setActive(true)
        current = tab
        progressBar.progress = 0
        progressBar.visibility = View.INVISIBLE
        urlBar.setText(tab.url)
        updateUi()
    }

    // A session created for a pop-up/new window is opened by Gecko itself,
    // so wait until it is open before showing it.
    private fun switchWhenOpen(tab: Tab, attempts: Int = 40) {
        if (!tabs.contains(tab)) return
        if (tab.session.isOpen) {
            switchTo(tab)
        } else if (attempts > 0) {
            handler.postDelayed({ switchWhenOpen(tab, attempts - 1) }, 50)
        }
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.remove(tab)
        val wasCurrent = tab === current
        if (wasCurrent) {
            if (geckoView.session === tab.session) geckoView.releaseSession()
            current = null
        }
        tab.session.close()
        if (tabs.isEmpty()) {
            createTab(null)
        } else if (wasCurrent) {
            switchTo(tabs[minOf(index, tabs.size - 1)])
        } else {
            updateUi()
        }
    }

    private fun attachDelegates(tab: Tab) {
        val session = tab.session

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                tab.url = url ?: ""
                if (tab === current) updateUi()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                if (tab === current) updateUi()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                if (tab === current) updateUi()
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                if (AdBlocker.isBlocked(uri)) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                // Block scripted jumps to other apps (market://, intent:// ...)
                val scheme = (Uri.parse(uri).scheme ?: "").lowercase()
                if (!request.hasUserGesture && scheme !in WEB_SCHEMES) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null // allow
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                // Pop-up blocker: only allow a new window if the user just touched the page.
                val userInitiated = SystemClock.uptimeMillis() - lastTouchTime < 1500
                if (!userInitiated || AdBlocker.isBlocked(uri)) return null
                val newTab = createTab(null, openNow = false)
                switchWhenOpen(newTab)
                return GeckoResult.fromValue(newTab.session)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (tab === current) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 5
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (tab === current) progressBar.visibility = View.INVISIBLE
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (tab === current) progressBar.progress = progress
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title ?: ""
            }

            override fun onCloseRequest(session: GeckoSession) {
                closeTab(tab)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                setFullScreen(fullScreen)
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun updateUi() {
        val tab = current ?: return
        if (!urlBar.hasFocus()) urlBar.setText(tab.url)
        val showHome = tab.url.isEmpty() || tab.url == "about:blank"
        homeView.visibility = if (showHome) View.VISIBLE else View.GONE
        btnBack.alpha = if (tab.canGoBack) 1f else 0.35f
        btnForward.alpha = if (tab.canGoForward) 1f else 0.35f
        btnTabs.text = tabs.size.toString()
    }

    private fun setFullScreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun loadFromBar() {
        val target = normalize(urlBar.text.toString())
        if (target.isEmpty()) return
        val tab = current ?: return
        tab.url = target
        tab.session.loadUri(target)
        urlBar.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        updateUi()
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        val hasScheme = t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("about:") || t.startsWith("file://")
        if (hasScheme) return t
        val looksLikeHost = !t.contains(" ") && (t.contains(".") || t.startsWith("localhost"))
        return if (looksLikeHost) "https://$t"
        else "https://duckduckgo.com/?q=" + Uri.encode(t)
    }

    private fun userAgentMode() =
        if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private fun viewportMode() =
        if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        else GeckoSessionSettings.VIEWPORT_MODE_MOBILE

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        tabs.forEach {
            it.session.settings.setUserAgentMode(userAgentMode())
            it.session.settings.setViewportMode(viewportMode())
        }
        current?.session?.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    }

    private fun showTabsDialog() {
        val titles = tabs.map { t ->
            val name = when {
                t.title.isNotEmpty() -> t.title
                t.url.isNotEmpty() -> t.url
                else -> "New tab"
            }
            (if (t === current) "● " else "○ ") + name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(titles) { _, which -> switchTo(tabs[which]) }
            .setNeutralButton("New tab") { _, _ -> createTab(null) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMenuDialog() {
        val items = arrayOf(
            "➕  New tab",
            if (desktopMode) "📱  Switch to mobile site" else "🖥  Request desktop site",
            "❌  Close this tab"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> createTab(null)
                    1 -> toggleDesktopMode()
                    2 -> current?.let { closeTab(it) }
                }
            }
            .show()
    }
}
