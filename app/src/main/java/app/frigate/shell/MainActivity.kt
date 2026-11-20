package app.frigate.shell

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.text.DateFormat

/**
 * Hosts Frigate's own web UI full-screen in a WebView. The page is untouched; the app
 * adds what a browser tab can't: trust for Frigate's self-signed certificate, a session
 * that survives restarts, real fullscreen video, the back gesture, system bars that follow
 * Frigate's theme, and downloads.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var downloader: Downloader

    private lateinit var screen: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var web: WebView
    private lateinit var errorView: View
    private lateinit var errorUrl: TextView
    private lateinit var errorDetail: TextView
    private lateinit var fullscreen: FrameLayout

    /** The server currently loaded. */
    private var server: Uri? = null
    private var bridgeOrigin: String? = null
    private var bridgeScript: ScriptHandler? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val pendingSsl = mutableListOf<SslErrorHandler>()
    private var sslDialog: AlertDialog? = null

    private var mainFrameFailed = false
    private var lastPathWasLogin = false
    private var pageDark: Boolean? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> exitFullscreen()
                web.canGoBack() -> web.goBack()
            }
        }
    }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
            filePathCallback = null
        }

    private val setup =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when {
                result.resultCode == RESULT_OK -> loadServer()
                prefs.serverUrl == null -> finish()
                server == null -> loadServer()
            }
        }

    private lateinit var updater: Updater
    private var pendingUpdate: Updater.Release? = null

    /** Back from Android's "Install unknown apps" screen for this app. */
    private val allowInstalls =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val release = pendingUpdate ?: return@registerForActivityResult
            if (packageManager.canRequestPackageInstalls()) {
                downloadAndInstall(release)
            } else {
                Toast.makeText(this, R.string.update_needs_permission, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySystemBars(dark = systemIsDark())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        downloader = Downloader(applicationContext, prefs)

        screen = findViewById(R.id.screen)
        content = findViewById(R.id.content)
        web = findViewById(R.id.web)
        errorView = findViewById(R.id.error)
        errorUrl = findViewById(R.id.error_url)
        errorDetail = findViewById(R.id.error_detail)
        fullscreen = findViewById(R.id.fullscreen)

        findViewById<View>(R.id.error_retry).setOnClickListener { loadServer() }
        findViewById<View>(R.id.error_change_server).setOnClickListener { openSetup() }

        // Keep the page between the status bar and the navigation bar / keyboard. Frigate
        // only pads for safe areas when installed as a PWA, which a WebView never is.
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        setUpWebView()
        onBackPressedDispatcher.addCallback(this, backCallback)
        handleIntent(intent, fresh = true)

        updater = Updater(applicationContext)
        // Release builds only: a locally built debug APK is signed with a different key,
        // so a release could never install over it.
        if (savedInstanceState == null && !BuildConfig.DEBUG) updater.check(::offerUpdate)
    }

    // ---- self-update -----------------------------------------------------------------

    private fun offerUpdate(release: Updater.Release) {
        if (isFinishing || isDestroyed) return
        val message = buildString {
            append(getString(R.string.update_body, release.version, BuildConfig.VERSION_NAME))
            if (release.notes.isNotBlank()) append("\n\n").append(release.notes.take(800))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun startUpdate(release: Updater.Release) {
        pendingUpdate = release
        if (packageManager.canRequestPackageInstalls()) {
            downloadAndInstall(release)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_body)
            .setPositiveButton(R.string.update_permission_open) { _, _ ->
                allowInstalls.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                )
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(release: Updater.Release) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading, release.version))
            .setView(box)
            .setCancelable(false)
            .show()
        updater.download(
            release,
            onProgress = { pct ->
                if (pct >= 0) {
                    bar.isIndeterminate = false
                    bar.progress = pct
                }
            },
            onDone = { file ->
                dialog.dismiss()
                try {
                    updater.install(file)
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.update_failed, e.message), Toast.LENGTH_LONG).show()
                }
            },
            onError = { error ->
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.update_failed, error), Toast.LENGTH_LONG).show()
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fresh = false)
    }

    private fun handleIntent(intent: Intent?, fresh: Boolean) {
        when {
            intent?.action == ACTION_SERVER_SETTINGS || prefs.serverUrl == null -> openSetup()
            fresh -> loadServer()
        }
    }

    override fun onStart() {
        super.onStart()
        web.onResume()
        web.resumeTimers()
    }

    override fun onStop() {
        // Stops JS timers and marks the page hidden, so live streams don't keep pulling
        // video while the app is in the background.
        web.onPause()
        web.pauseTimers()
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        sslDialog?.dismiss()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // live views autoplay, as in a browser
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false) // target=_blank stays in shouldOverrideUrlLoading
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString FrigateAndroid/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Frigate has its own dark theme; never let WebView invert the page.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, null, userAgent, contentDisposition, mimeType)
        }
    }

    private fun loadServer() {
        val url = prefs.serverUrl ?: return openSetup()
        val uri = Uri.parse(url)
        server = uri
        installBridge(Prefs.origin(uri))
        // Forget WebView's in-memory "proceed" decisions so a forgotten or changed pin is
        // re-checked; a still-pinned certificate is accepted again without a prompt.
        web.clearSslPreferences()
        errorView.isVisible = false
        web.loadUrl(url)
    }

    private fun openSetup() {
        setup.launch(Intent(this, SetupActivity::class.java))
    }

    // ---- page <-> app bridge -------------------------------------------------------

    private fun installBridge(origin: String) {
        if (origin == bridgeOrigin) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        if (bridgeOrigin != null) WebViewCompat.removeWebMessageListener(web, Bridge.NAME)
        bridgeScript?.remove()
        bridgeScript = null

        val rules = setOf(origin)
        WebViewCompat.addWebMessageListener(web, Bridge.NAME, rules) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) message.data?.let(::onBridgeMessage)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            bridgeScript = WebViewCompat.addDocumentStartJavaScript(web, Bridge.SCRIPT, rules)
        }
        bridgeOrigin = origin
    }

    private fun onBridgeMessage(json: String) {
        val msg = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return
        }
        when (msg.optString("type")) {
            "theme" -> onPageTheme(msg.optString("bg"), msg.optBoolean("dark"))
            "download" -> startDownload(
                url = msg.optString("url"),
                name = msg.optString("name").ifBlank { null },
                userAgent = web.settings.userAgentString,
                contentDisposition = null,
                mimeType = msg.optString("mime").ifBlank { null },
            )
        }
    }

    /** Paints the status/navigation bar areas with the page background, icons to match. */
    private fun onPageTheme(cssBackground: String, dark: Boolean) {
        val color = parseCssColor(cssBackground) ?: if (dark) Color.BLACK else Color.WHITE
        screen.setBackgroundColor(color)
        web.setBackgroundColor(color)
        if (pageDark != dark) {
            pageDark = dark
            applySystemBars(dark)
        }
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(style, style)
    }

    private fun systemIsDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun startDownload(
        url: String,
        name: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        when {
            url.startsWith("data:") -> downloader.fromDataUrl(url, name)
            url.startsWith("blob:") -> web.evaluateJavascript(
                "window.__frigateShellBlob && window.__frigateShellBlob(" +
                    JSONObject.quote(url) + "," + JSONObject.quote(name ?: "") + ")",
                null,
            )
            url.startsWith("http://") || url.startsWith("https://") ->
                downloader.fromUrl(url, name, userAgent, contentDisposition, mimeType)
        }
    }

    // ---- fullscreen ----------------------------------------------------------------

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreen.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullscreen.isVisible = true
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateBackCallback()
    }

    private fun leaveFullscreen() {
        val view = customView ?: return
        fullscreen.removeView(view)
        fullscreen.isVisible = false
        customView = null
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateBackCallback()
    }

    private fun exitFullscreen() {
        val callback = customViewCallback
        leaveFullscreen()
        callback?.onCustomViewHidden()
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = customView != null || web.canGoBack()
    }

    // ---- errors and certificates -----------------------------------------------------

    private fun showError(detail: String?) {
        errorUrl.text = server?.toString().orEmpty()
        errorDetail.text = detail.orEmpty()
        errorView.isVisible = true
    }

    private fun showTrustDialog(hostPort: String, cert: X509Certificate, fingerprint: String, changed: Boolean) {
        val until = DateFormat.getDateInstance(DateFormat.MEDIUM).format(cert.notAfter)
        val body = getString(
            if (changed) R.string.cert_body_changed else R.string.cert_body_new,
            hostPort,
            Tls.pretty(fingerprint),
            cert.subjectX500Principal.name,
            until,
        )
        sslDialog = AlertDialog.Builder(this)
            .setTitle(if (changed) R.string.cert_title_changed else R.string.cert_title_new)
            .setMessage(body)
            .setPositiveButton(R.string.cert_trust) { _, _ ->
                prefs.setPin(hostPort, fingerprint)
                resolveSsl(trust = true)
            }
            .setNegativeButton(R.string.cert_cancel) { _, _ -> resolveSsl(trust = false) }
            .setOnCancelListener { resolveSsl(trust = false) }
            .show()
    }

    private fun resolveSsl(trust: Boolean) {
        val handlers = pendingSsl.toList()
        pendingSsl.clear()
        handlers.forEach { if (trust) it.proceed() else it.cancel() }
        if (!trust && handlers.isNotEmpty()) showError(getString(R.string.error_cert_rejected))
    }

    private fun isServerOrigin(uri: Uri): Boolean {
        val srv = server ?: return false
        return Prefs.origin(uri) == Prefs.origin(srv)
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            return when (url.scheme?.lowercase()) {
                "http", "https" -> if (isServerOrigin(url)) {
                    false
                } else {
                    openExternally(url) // docs, GitHub, etc. open in the browser
                    true
                }
                "about", "data", "blob", "javascript" -> false
                else -> {
                    openExternally(url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            mainFrameFailed = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!mainFrameFailed) errorView.isVisible = false
            // Fallback for WebViews without document-start scripts.
            if (bridgeScript == null && bridgeOrigin != null &&
                Prefs.origin(Uri.parse(url)) == bridgeOrigin
            ) {
                view.evaluateJavascript(Bridge.SCRIPT, null)
            }
            updateBackCallback()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // Frigate bounces to /login and back with full page loads. Drop that from the
            // back stack so Back never lands on a stale login form.
            val isLogin = Uri.parse(url).path.orEmpty().trimEnd('/').endsWith("/login")
            if (isLogin || lastPathWasLogin) {
                view.post {
                    view.clearHistory()
                    updateBackCallback()
                }
            }
            lastPathWasLogin = isLogin
            updateBackCallback()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                showError(error.description?.toString())
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val srv = server
            val cert = error.certificate?.x509Certificate
            val url = Uri.parse(error.url)
            if (srv == null || cert == null || Prefs.hostPort(url) != Prefs.hostPort(srv)) {
                handler.cancel()
                return
            }
            val hostPort = Prefs.hostPort(srv)
            val fingerprint = Tls.fingerprint(cert)
            val pinned = prefs.pinFor(hostPort)
            if (pinned == fingerprint) {
                handler.proceed()
                return
            }
            pendingSsl += handler
            if (sslDialog?.isShowing != true) {
                showTrustDialog(hostPort, cert, fingerprint, changed = pinned != null)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) =
            enterFullscreen(view, callback)

        override fun onHideCustomView() = leaveFullscreen()

        // WebView otherwise paints a grey play-button poster on every <video> until it
        // has a frame; Frigate's players expect a browser's transparent default.
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                pickFiles.launch(params.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                filePathCallback = null
                false
            }
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) Log.d("FrigateWeb", "${message.messageLevel()}: ${message.message()}")
            return true
        }
    }

    companion object {
        const val ACTION_SERVER_SETTINGS = "app.frigate.shell.SERVER_SETTINGS"

        private val CSS_RGB = Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+%?))?\s*\)""")

        fun parseCssColor(css: String): Int? {
            val m = CSS_RGB.find(css) ?: return null
            val (r, g, b, a) = m.destructured
            val alpha = when {
                a.isEmpty() -> 1f
                a.endsWith("%") -> (a.dropLast(1).toFloatOrNull() ?: 100f) / 100f
                else -> a.toFloatOrNull() ?: 1f
            }
            if (alpha < 0.5f) return null
            return Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }
    }
}
