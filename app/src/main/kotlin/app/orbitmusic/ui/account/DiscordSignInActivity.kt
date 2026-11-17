package app.orbitmusic.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.orbitmusic.OrbitMusicApp
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.GridDots
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Type
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
    private var stopped by mutableStateOf<String?>(null)
    private var tokenCaptured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        var ready by mutableStateOf(false)
        if (savedInstanceState == null) wipe { ready = true } else ready = true

        setContent {
            Column(Modifier.fillMaxSize().background(Ink.Black).safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("Sign in to Discord", Type.Title, modifier = Modifier.weight(1f))
                    GlassPill(
                        "Cancel",
                        onClick = { finish() },
                        small = true,
                        modifier = Modifier.testTag("discord_signin_cancel"),
                    )
                }
                Txt(
                    "Sign in on Discord's page to link Rich Presence. Only your session token is kept, sealed in this phone's Keystore.",
                    Type.Meta,
                    color = Ink.Grey,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
                )
                stopped?.let {
                    Txt(it, Type.Meta, color = Ink.Grey, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
                Box(Modifier.fillMaxSize()) {
                    if (ready) {
                        AndroidView(factory = ::buildWebView, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize())
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
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView = WebView(context).apply {
        val view = this
        webView = view
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme.equals("https", ignoreCase = true)) return false
                if (request.isForMainFrame) stopped = "Non-HTTPS redirects aren't allowed while signing in."
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                inspectUrl(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                inspectUrl(view, url)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                stopped = "The page's certificate didn't check out, so it was stopped."
            }
        }
        loadUrl(LOGIN_URL)
    }

    private fun inspectUrl(view: WebView, url: String) {
        if (tokenCaptured || checking) return
        val uri = Uri.parse(url)
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
                    checking = true
                    val container = (application as OrbitMusicApp).container
                    lifecycleScope.launch {
                        val verified = container.discordRpc.verifyAndSaveToken(token)
                        if (verified.isSuccess) {
                            wipe { finish() }
                        } else {
                            tokenCaptured = false
                            checking = false
                            stopped = verified.exceptionOrNull()?.message ?: "Could not verify Discord token."
                        }
                    }
                }
                delay(600L)
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
        if (isFinishing && !checking) wipe {}
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://discord.com/login"
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
