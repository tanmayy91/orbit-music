package app.orbitmusic.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.orbitmusic.OrbitMusicApp
import app.orbitmusic.core.designsystem.component.GlassButton
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.GridDots
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Type
import app.orbitmusic.ui.common.NameDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Signs in to Discord on Discord's own login page and seals only the session token in Android's
 * Keystore via [app.orbitmusic.discord.DiscordRpcClient]. Orbit Music never sees a password.
 */
class DiscordSignInActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var checking by mutableStateOf(false)
    private var pageLoading by mutableStateOf(true)
    private var loadProgress by mutableIntStateOf(0)
    private var stopped by mutableStateOf<String?>(null)
    private var loadFailed by mutableStateOf<String?>(null)
    private var showTokenDialog by mutableStateOf(false)
    private var tokenCaptured = false
    private var mountCheckJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        var ready by mutableStateOf(false)
        if (savedInstanceState == null) wipe { runOnUiThread { ready = true } } else ready = true

        setContent {
            BackHandler(enabled = showTokenDialog) { showTokenDialog = false }
            BackHandler(enabled = !showTokenDialog && webView?.canGoBack() == true) {
                webView?.goBack()
            }

            Box(Modifier.fillMaxSize().background(Ink.Black)) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt("Sign in to Discord", Type.Title, modifier = Modifier.weight(1f))
                        GlassPill(
                            "Paste token",
                            onClick = { showTokenDialog = true },
                            small = true,
                            modifier = Modifier.testTag("discord_signin_paste_token"),
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassPill(
                            "Cancel",
                            onClick = { finish() },
                            small = true,
                            modifier = Modifier.testTag("discord_signin_cancel"),
                        )
                    }
                    Txt(
                        "Sign in on Discord's page for Rich Presence. Only your session token is kept, sealed in this phone's Keystore.",
                        Type.Meta,
                        color = Ink.Grey,
                        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
                    )
                    stopped?.let {
                        Txt(
                            it,
                            Type.Meta,
                            color = Ink.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                    Box(Modifier.fillMaxSize().background(Ink.Black)) {
                        if (ready) {
                            AndroidView(
                                factory = ::buildWebView,
                                onRelease = {
                                    mountCheckJob?.cancel()
                                    if (webView === it) webView = null
                                    it.destroy()
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (ready && pageLoading && loadFailed == null && !checking) {
                            Column(
                                Modifier.fillMaxSize().background(Ink.Black),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                GridDots()
                                Spacer(Modifier.height(14.dp))
                                Txt(
                                    if (loadProgress in 1..99) "Loading Discord ($loadProgress%)…" else "Loading Discord…",
                                    Type.Body,
                                    color = Ink.Grey,
                                )
                            }
                        }
                        loadFailed?.let { reason ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .background(Ink.Black)
                                    .padding(horizontal = 28.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Txt("Couldn\u2019t show Discord\u2019s login page", Type.Headline, align = TextAlign.Center)
                                Spacer(Modifier.height(10.dp))
                                Txt(reason, Type.Aside, color = Ink.Grey, align = TextAlign.Center)
                                Spacer(Modifier.height(22.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GlassPill(
                                        text = "Reload",
                                        small = true,
                                        modifier = Modifier.testTag("discord_signin_reload"),
                                        onClick = {
                                            loadFailed = null
                                            stopped = null
                                            pageLoading = true
                                            loadProgress = 0
                                            webView?.loadUrl(LOGIN_URL)
                                        },
                                    )
                                    GlassButton(
                                        text = "Paste token",
                                        lit = true,
                                        modifier = Modifier.testTag("discord_signin_fallback_token"),
                                        onClick = { showTokenDialog = true },
                                    )
                                }
                            }
                        }
                        if (checking) {
                            Column(
                                Modifier.fillMaxSize().background(Ink.Black),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                GridDots()
                                Spacer(Modifier.height(14.dp))
                                Txt("Linking Discord", Type.Body, color = Ink.Grey)
                            }
                        }
                    }
                }

                if (showTokenDialog) {
                    NameDialog(
                        title = "Discord token",
                        initial = "",
                        onDone = { rawToken ->
                            showTokenDialog = false
                            if (rawToken.isBlank()) return@NameDialog
                            verifyTokenAndFinish(rawToken)
                        },
                        onDismiss = { showTokenDialog = false },
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView = WebView(context).apply {
        val view = this
        webView = view
        setBackgroundColor(AndroidColor.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            safeBrowsingEnabled = true
            // Remove Android WebView markers ("; wv") so Discord/Cloudflare serves the normal login page.
            val cleanedUa = userAgentString.orEmpty()
                .replace("; wv)", ")")
                .replace("; wv;", ";")
                .replace(Regex("""\bVersion/\S+\s*"""), "")
                .trim()
            userAgentString = cleanedUa.ifEmpty { FALLBACK_CHROME_UA }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadProgress = newProgress
                if (newProgress >= 95) {
                    pageLoading = false
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme.equals("https", ignoreCase = true)) return false
                if (request.isForMainFrame) {
                    stopped = "Blocked non-HTTPS redirect (${request.url.scheme ?: "unknown"})."
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                mountCheckJob?.cancel()
                loadFailed = null
                pageLoading = true
                inspectUrl(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoading = false
                inspectUrl(view, url)
                scheduleBlankPageCheck(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                pageLoading = false
                loadFailed = error.description?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Could not reach Discord. Check your internet connection or paste your token."
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame || errorResponse.statusCode < 400) return
                pageLoading = false
                loadFailed = "Discord returned HTTP ${errorResponse.statusCode}. You can reload or paste your token."
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                pageLoading = false
                loadFailed = "The page's SSL certificate didn't check out, so it was stopped."
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                pageLoading = false
                if (webView === view) webView = null
                view.destroy()
                loadFailed = "The browser view stopped unexpectedly. Tap Reload or paste your Discord token."
                return true
            }
        }
        loadUrl(LOGIN_URL)
    }

    private fun scheduleBlankPageCheck(view: WebView, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val host = uri.host?.lowercase() ?: return
        if (!host.endsWith("discord.com") && !host.endsWith("discordapp.com")) return
        val path = uri.path.orEmpty()
        if (path.startsWith("/app") || path.startsWith("/channels")) return

        mountCheckJob?.cancel()
        mountCheckJob = lifecycleScope.launch {
            delay(4_000L)
            if (!isActive || checking || tokenCaptured || loadFailed != null) return@launch
            view.evaluateJavascript(CHECK_MOUNTED_JS) { result ->
                val clean = result?.trim()?.trim('"')
                if (clean == "blank" && !checking && !tokenCaptured && loadFailed == null) {
                    loadFailed = "Discord's web page rendered blank in this device's WebView. Tap Reload or use Paste token above."
                }
            }
        }
    }

    private fun inspectUrl(view: WebView, url: String) {
        if (tokenCaptured || checking) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val host = uri.host?.lowercase() ?: return
        if (!host.endsWith("discord.com") && !host.endsWith("discordapp.com")) return
        val path = uri.path.orEmpty()
        if (!path.startsWith("/app") && !path.startsWith("/channels")) return

        lifecycleScope.launch {
            var attempts = 0
            while (isActive && !tokenCaptured && attempts < 12) {
                attempts++
                view.evaluateJavascript(EXTRACT_TOKEN_JS) { result ->
                    if (tokenCaptured || checking) return@evaluateJavascript
                    val token = result?.trim()?.trim('"')?.replace("\\\"", "")?.takeIf {
                        it.isNotBlank() && it != "null" && it != "undefined"
                    } ?: return@evaluateJavascript

                    tokenCaptured = true
                    verifyTokenAndFinish(token)
                }
                delay(600L)
            }
        }
    }

    private fun verifyTokenAndFinish(token: String) {
        checking = true
        stopped = null
        loadFailed = null
        val container = (application as OrbitMusicApp).container
        lifecycleScope.launch {
            val verified = container.discordRpc.verifyAndSaveToken(token)
            if (verified.isSuccess) {
                wipe { runOnUiThread { finish() } }
            } else {
                tokenCaptured = false
                checking = false
                stopped = verified.exceptionOrNull()?.message ?: "Could not verify Discord token."
            }
        }
    }

    private fun wipe(then: () -> Unit) {
        CookieManager.getInstance().apply {
            removeAllCookies { then() }
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }

    override fun onDestroy() {
        mountCheckJob?.cancel()
        if (isFinishing && !checking) wipe {}
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://discord.com/login"
        private const val FALLBACK_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val CHECK_MOUNTED_JS = """
            (function() {
                try {
                    var app = document.getElementById('app-mount');
                    var hasContent = app && app.children && app.children.length > 0;
                    var textLen = document.body && document.body.innerText ? document.body.innerText.trim().length : 0;
                    return (hasContent || textLen > 20) ? 'ok' : 'blank';
                } catch (e) { return 'ok'; }
            })()
        """
        private const val EXTRACT_TOKEN_JS = """
            (function() {
                try {
                    var t = window.localStorage && window.localStorage.getItem('token');
                    if (t) return t;
                    var f = document.createElement('iframe');
                    document.body.appendChild(f);
                    t = f.contentWindow && f.contentWindow.localStorage && f.contentWindow.localStorage.getItem('token');
                    f.remove();
                    return t || '';
                } catch (e) { return ''; }
            })()
        """

        fun intent(context: Context) = Intent(context, DiscordSignInActivity::class.java)
    }
}
