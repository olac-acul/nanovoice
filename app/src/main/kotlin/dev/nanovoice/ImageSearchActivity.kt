package dev.nanovoice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.ui.Icons
import dev.nanovoice.ui.NanoTheme
import java.io.File
import java.util.Base64

/**
 * A reverse image search for one photo on TinEye, in a browser page inside the app. The photo goes into TinEye's own
 * upload form, as if picked by hand there (the way the open-source "Search by Image" extension does it): there is no
 * free API. If that fails, the page's own upload button is offered the same photo. The page has no account and no
 * cookies of the person's browser, and everything it stored is wiped when it closes.
 */
class ImageSearchActivity : ComponentActivity() {
    enum class Engine(val id: String, val label: String) {
        /** Finds where the same image appears online; TinEye deletes uploads after the search. */
        TinEye("tineye", "TinEye"),
        ;

        companion object {
            fun byId(id: String?) = entries.firstOrNull { it.id == id }
        }
    }

    private lateinit var engine: Engine
    private lateinit var photo: File
    private lateinit var photoBytes: ByteArray
    private var web: WebView? = null
    private var uploaded = false
    private val handler = Handler(Looper.getMainLooper())
    private val notice = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = Engine.byId(intent.getStringExtra(EXTRA_ENGINE)) ?: return finish()
        // Each page has its own copy, named in the intent: two pages never upload or delete each other's photo.
        val name = intent.getStringExtra(EXTRA_FILE)?.takeIf { it.matches(Regex("[0-9a-f-]{36}\\.jpg")) } ?: return finish()
        photo = File(searchDir(this), name).takeIf { it.isFile } ?: return finish()
        photoBytes = runCatching { photo.readBytes() }.getOrNull() ?: return finish()
        val container = (application as NanoVoiceApp).container
        setContent {
            val settings by container.settingsStore.settings.collectAsState()
            NanoTheme(settings.themeMode, settings.dynamicColors) {
                BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: finish() }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.safeDrawingPadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).clickable { finish() },
                                contentAlignment = Alignment.Center,
                            ) { Icons.Close(MaterialTheme.colorScheme.onSurface) }
                            Text(engine.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = ::start) { Text(stringResource(R.string.search_retry)) }
                            TextButton(onClick = ::openInBrowser) { Text(stringResource(R.string.search_in_browser)) }
                        }
                        notice.value?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        AndroidView(factory = { context -> createWebView(context).also { web = it; start() } }, modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context) = WebView(context).apply {
        settings.javaScriptEnabled = true // TinEye is a web app
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Sites may serve a "browser not supported" page to the WebView's own user agent.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        webViewClient = object : WebViewClient() {
            // Only web pages: no intent:, market: or file: links out of this page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"

            override fun onPageFinished(view: WebView, url: String) {
                // The photo goes into TinEye's own page only: not a site a link or a redirect leads to.
                if (!uploaded && isTinEye(url)) inject(view)
            }
        }
        webChromeClient = object : WebChromeClient() {
            // The page's own upload button gets this photo, nothing else from the phone.
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(arrayOf(FileProvider.getUriForFile(this@ImageSearchActivity, "$packageName.photos", photo)))
                return true
            }
        }
    }

    /** (Again) from the start page: after a cookie consent page, for instance. */
    private fun start() {
        val view = web ?: return
        uploaded = false
        notice.value = null
        handler.removeCallbacksAndMessages(null)
        when (engine) {
            Engine.TinEye -> view.loadUrl("https://tineye.com/")
        }
    }

    private fun inject(view: WebView) {
        val b64 = Base64.getEncoder().encodeToString(photoBytes)
        view.evaluateJavascript("window.__nvB64='$b64';", null)
        val selector = "input#upload-box, input[type=file]"
        val script = """(function(){
            if (!window.__nvFile && window.__nvB64) {
              var b = atob(window.__nvB64), a = new Uint8Array(b.length);
              for (var i = 0; i < b.length; i++) a[i] = b.charCodeAt(i);
              window.__nvFile = new File([a], 'photo.jpg', {type: 'image/jpeg'});
              delete window.__nvB64;
            }
            var input = document.querySelector('$selector');
            if (!input || !window.__nvFile) return 'wait';
            var dt = new DataTransfer(); dt.items.add(window.__nvFile); input.files = dt.files;
            input.dispatchEvent(new Event('input', {bubbles: true}));
            input.dispatchEvent(new Event('change', {bubbles: true}));
            delete window.__nvFile;
            return 'ok';
        })()"""
        var tries = 0
        fun attempt() {
            if (uploaded) return
            view.evaluateJavascript(script) { result ->
                when {
                    result?.contains("ok") == true -> uploaded = true
                    ++tries < MAX_TRIES -> handler.postDelayed(::attempt, RETRY_MS)
                    else -> notice.value = R.string.search_upload_by_hand
                }
            }
        }
        attempt()
    }

    private fun openInBrowser() {
        val url = web?.url?.takeIf { it.startsWith("https://") } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web?.run {
            clearHistory()
            clearCache(true)
            destroy()
        }
        web = null
        // Nothing of the search stays on the phone: the engines' cookies, storage and the photo copy.
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        if (isFinishing) photo.delete()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ENGINE = "engine"
        private const val EXTRA_FILE = "file"
        private const val MAX_TRIES = 40
        private const val RETRY_MS = 250L

        private fun searchDir(context: Context) = File(context.cacheDir, "search")

        internal fun isTinEye(url: String): Boolean {
            val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            val host = u.host?.lowercase() ?: return false
            return u.scheme == "https" && (host == "tineye.com" || host.endsWith(".tineye.com"))
        }

        /** Opens the search for [image]; from a non-activity context (an agent action) in a new task. */
        fun open(context: Context, image: AgentImage, engine: Engine) {
            val name = "${java.util.UUID.randomUUID()}.jpg"
            File(searchDir(context), name).apply { parentFile?.mkdirs(); writeBytes(image.bytes) }
            val intent = Intent(context, ImageSearchActivity::class.java).putExtra(EXTRA_ENGINE, engine.id).putExtra(EXTRA_FILE, name)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
