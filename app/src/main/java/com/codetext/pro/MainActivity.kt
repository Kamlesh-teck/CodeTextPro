package com.codetext.pro

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val uris = ConcurrentHashMap<String, Uri>()
    private var counter = 0
    private var pendingId = ""
    private var pendingContent = ""

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readFile(uri)
        }

    private val createLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) writeFile(pendingId, uri, pendingContent)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }

        webView.addJavascriptInterface(Bridge(), "Android")
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleBack && window.handleBack()") { r ->
                    if (r != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun js(fn: String, payload: String) {
        runOnUiThread {
            webView.evaluateJavascript("window.$fn(${JSONObject.quote(payload)})", null)
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment ?: "file.txt"
    }

    private fun readFile(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("फाइल खुल नहीं सकी")
                if (bytes.size > 5 * 1024 * 1024) throw Exception("फाइल 5 MB से बड़ी है")
                val check = minOf(bytes.size, 8000)
                for (i in 0 until check) {
                    if (bytes[i].toInt() == 0) throw Exception("यह टेक्स्ट फाइल नहीं लगती")
                }
                val id = "f" + (++counter)
                uris[id] = uri
                val o = JSONObject()
                    .put("id", id)
                    .put("name", displayName(uri))
                    .put("content", String(bytes, Charsets.UTF_8))
                js("onFileOpened", o.toString())
            } catch (e: Exception) {
                js("onError", "खोल नहीं सका: ${e.message}")
            }
        }.start()
    }

    private fun writeFile(id: String, uri: Uri, content: String) {
        Thread {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw Exception("फाइल लिख नहीं सका")
                uris[id] = uri
                val o = JSONObject().put("id", id).put("name", displayName(uri))
                js("onSaved", o.toString())
            } catch (e: Exception) {
                js("onError", "सेव नहीं हो सका: ${e.message}")
            }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface
        fun appVersion(): String = "2.0"

        @JavascriptInterface
        fun openFile() {
            runOnUiThread { openLauncher.launch(arrayOf("*/*")) }
        }

        @JavascriptInterface
        fun save(id: String, content: String) {
            val uri = uris[id]
            if (uri == null) saveAs(id, "untitled.txt", content)
            else writeFile(id, uri, content)
        }

        @JavascriptInterface
        fun saveAs(id: String, name: String, content: String) {
            pendingId = id
            pendingContent = content
            runOnUiThread { createLauncher.launch(name) }
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
