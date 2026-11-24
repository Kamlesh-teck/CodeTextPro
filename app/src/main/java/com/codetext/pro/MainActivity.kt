package com.codetext.pro

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val uris = ConcurrentHashMap<String, Uri>()
    private var counter = 0
    private var pendingId = ""
    private var pendingContent = ""

    private val pdfDir by lazy { File(cacheDir, "pdf") }
    private val viewDir by lazy { File(cacheDir, "pdfview") }
    @Volatile private var pdfName = "document.pdf"
    private var printWv: WebView? = null

    private fun curPdf() = File(pdfDir, "current.pdf")

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readFile(uri)
        }

    private val createLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) writeFile(pendingId, uri, pendingContent)
        }

    private val pdfSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) copyPdfTo(uri)
        }

    private val pdfOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPdf(uri)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pdfDir.mkdirs()
        viewDir.mkdirs()

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/pdfout/", WebViewAssetLoader.InternalStoragePathHandler(this, viewDir))
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

    private fun cleanName(n: String): String {
        var s = n.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (s.isEmpty()) s = "document"
        if (!s.endsWith(".pdf", true)) s += ".pdf"
        return s
    }

    /* ---------- Text फाइलें ---------- */

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

    /* ---------- PDF ---------- */

    private fun showPdf() {
        val token = System.currentTimeMillis().toString()
        val r = PdfTools.render(curPdf(), viewDir, token, 100)
        val o = JSONObject()
            .put("token", token)
            .put("total", r[0])
            .put("shown", r[1])
            .put("name", pdfName)
        js("onPdfReady", o.toString())
    }

    private fun copyPdfTo(uri: Uri) {
        Thread {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { o ->
                    FileInputStream(curPdf()).use { it.copyTo(o) }
                } ?: throw Exception("फाइल लिख नहीं सका")
                js("onToast", "PDF सेव हो गया ✓")
            } catch (e: Exception) {
                js("onError", "सेव नहीं हो सका: ${e.message}")
            }
        }.start()
    }

    private fun importPdf(uri: Uri) {
        Thread {
            try {
                pdfDir.mkdirs()
                var total = 0L
                contentResolver.openInputStream(uri)?.use { i ->
                    FileOutputStream(curPdf()).use { o ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 50L * 1024 * 1024) throw Exception("PDF 50 MB से बड़ी है")
                            o.write(buf, 0, n)
                        }
                    }
                } ?: throw Exception("फाइल खुल नहीं सकी")
                pdfName = cleanName(displayName(uri))
                showPdf()
            } catch (e: Throwable) {
                js("onError", "PDF खुल नहीं सका: ${e.message}")
            }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface
        fun appVersion(): String = "4.0"

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

        @JavascriptInterface
        fun makePdf(text: String, opts: String) {
            Thread {
                try {
                    if (text.length > 500_000) throw Exception("टेक्स्ट बहुत बड़ा है (अधिकतम 5 लाख अक्षर)")
                    val o = JSONObject(opts)
                    pdfName = cleanName(o.optString("name"))
                    PdfTools.make(text, o, curPdf())
                    showPdf()
                } catch (e: Throwable) {
                    js("onError", "PDF नहीं बनी: ${e.message}")
                }
            }.start()
        }

        @JavascriptInterface
        fun openPdf() {
            runOnUiThread { pdfOpenLauncher.launch(arrayOf("application/pdf")) }
        }

        @JavascriptInterface
        fun savePdf() {
            runOnUiThread { pdfSaveLauncher.launch(pdfName) }
        }

        @JavascriptInterface
        fun sharePdf() {
            runOnUiThread {
                try {
                    val src = curPdf()
                    if (!src.exists()) throw Exception("पहले PDF बनाएँ")
                    val f = File(pdfDir, pdfName)
                    src.copyTo(f, overwrite = true)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(i, "PDF शेयर करें"))
                } catch (e: Exception) {
                    js("onError", "शेयर नहीं हो सका: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun printPdf() {
            runOnUiThread {
                try {
                    if (!curPdf().exists()) throw Exception("पहले PDF बनाएँ")
                    val pm = getSystemService(PRINT_SERVICE) as PrintManager
                    pm.print(pdfName, PdfTools.FileAdapter(curPdf(), pdfName), null)
                } catch (e: Exception) {
                    js("onError", "Print नहीं हो सका: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun printHtml(html: String, opts: String) {
            runOnUiThread {
                try {
                    val o = JSONObject(opts)
                    val name = cleanName(o.optString("name"))
                    printWv?.destroy()
                    val w = WebView(this@MainActivity)
                    w.settings.javaScriptEnabled = false
                    w.settings.allowFileAccess = false
                    w.settings.allowContentAccess = false
                    var done = false
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, url: String?) {
                            if (done) return
                            done = true
                            var size = when (o.optString("size")) {
                                "A5" -> PrintAttributes.MediaSize.ISO_A5
                                "Letter" -> PrintAttributes.MediaSize.NA_LETTER
                                else -> PrintAttributes.MediaSize.ISO_A4
                            }
                            if (o.optString("orient") == "landscape") size = size.asLandscape()
                            val m = (o.optDouble("margin", 20.0) * 39.37).toInt()
                            val a = PrintAttributes.Builder()
                                .setMediaSize(size)
                                .setMinMargins(PrintAttributes.Margins(m, m, m, m))
                                .setResolution(PrintAttributes.Resolution("r", "300 dpi", 300, 300))
                                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                .build()
                            val pm = getSystemService(PRINT_SERVICE) as PrintManager
                            pm.print(name, v.createPrintDocumentAdapter(name), a)
                        }
                    }
                    printWv = w
                    w.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                } catch (e: Exception) {
                    js("onError", "HTML Print नहीं हो सका: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        printWv?.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
