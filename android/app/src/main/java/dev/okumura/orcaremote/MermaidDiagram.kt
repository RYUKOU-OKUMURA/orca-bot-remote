package dev.okumura.orcaremote

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.io.IOException

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net/mermaid/"
private const val PAGE = "${ASSET_ORIGIN}index.html"

@Composable
internal fun MermaidDiagram(source: String) {
    val dark = isSystemInDarkTheme()
    key(source, dark) {
        var failed by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf(false) }
        var height by remember { mutableIntStateOf(72) }
        if (failed) {
            Text("図として表示できませんでした", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CodeBlock(source, "mermaid")
        } else {
            Box(Modifier.fillMaxWidth().height(height.dp)) {
                DiagramView(source, dark, false, Modifier.fillMaxSize()) { size ->
                    if (size < 0) failed = true else if (size > 0) height = size.coerceIn(48, 420)
                }
                Box(Modifier.matchParentSize().semantics { contentDescription = "Mermaid 図を拡大" }
                    .clickable(role = Role.Button) { expanded = true })
            }
            if (expanded) {
                Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding()) {
                            OrcaTextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) {
                                Text("閉じる")
                            }
                            DiagramView(source, dark, true, Modifier.fillMaxSize()) { if (it < 0) failed = true }
                        }
                    }
                }
            }
        }
    }
}

private fun localAsset(assets: AssetManager, url: String): WebResourceResponse {
    val mime = when (url) {
        PAGE -> "text/html"
        "${ASSET_ORIGIN}mermaid.min.js", "${ASSET_ORIGIN}diagram.js" -> "application/javascript"
        else -> null
    }
    if (mime != null) {
        try {
            return WebResourceResponse(mime, "UTF-8", assets.open("mermaid/${url.substringAfterLast('/')}"))
        } catch (_: IOException) {
            // An unavailable renderer takes the same source-code fallback as an invalid diagram.
        }
    }
    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION") // Explicitly disable Web SQL on WebView versions that still implement it.
@Composable
private fun DiagramView(source: String, dark: Boolean, zoom: Boolean, modifier: Modifier, onSize: (Int) -> Unit) {
    val background = MaterialTheme.colorScheme.surface.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(background)
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    blockNetworkLoads = true
                    setSupportZoom(zoom)
                    builtInZoomControls = zoom
                    displayZoomControls = false
                    useWideViewPort = true
                }
                if (!zoom) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun report(height: Int) { post { onSize(height) } }
                }, "DiagramSize")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        localAsset(context.assets, request.url.toString())

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    override fun onPageFinished(view: WebView, url: String) {
                        if (url != PAGE) return
                        val theme = if (dark) "dark" else "default"
                        val script = "typeof renderDiagram === 'function' && (renderDiagram(${JSONObject.quote(source)}, '$theme'), true)"
                        view.evaluateJavascript(script) { if (it != "true") onSize(-1) }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        onSize(-1)
                        return true
                    }
                }
                loadUrl(PAGE)
            }
        },
        onRelease = { view ->
            view.removeJavascriptInterface("DiagramSize")
            view.destroy()
        },
    )
}
