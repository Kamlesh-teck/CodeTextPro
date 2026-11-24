package com.codetext.pro

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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

    // File Manager की स्थिति (सिर्फ io थ्रेड से छुई जाती है)
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("ctp", MODE_PRIVATE) }
    private val entries = ConcurrentHashMap<String, DocumentFile>()
    private val stack = ArrayList<DocumentFile>()
    private var eid = 0

    private class Row(val f: DocumentFile, val name: String, val dir: Boolean, val size: Long, val time: Long)

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

    private val treeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) { }
                prefs.edit().putString("tree", uri.toString()).apply()
                io.execute {
                    stack.clear()
                    DocumentFile.fromTreeUri(this, uri)?.let { stack.add(it) }
                    listDir()
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importInto(uri)
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

    private fun safeName(n: String): String =
        n.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun mimeFor(n: String): String {
        val e = n.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "text/plain"
    }

    /* ---------- Recent ---------- */

    private fun addRecent(uri: Uri, name: String) {
        try {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e2: Exception) { }
            }
            synchronized(prefs) {
                val old = JSONArray(prefs.getString("recent", "[]"))
                val arr = JSONArray()
                val us = uri.toString()
                arr.put(JSONObject().put("uri", us).put("name", name).put("time", System.currentTimeMillis()))
                for (i in 0 until old.length()) {
                    val o = old.getJSONObject(i)
                    if (o.getString("uri") != us && arr.length() < 20) arr.put(o)
                }
                prefs.edit().putString("recent", arr.toString()).apply()
            }
        } catch (e: Exception) { }
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
                val name = displayName(uri)
                val o = JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("content", String(bytes, Charsets.UTF_8))
                js("onFileOpened", o.toString())
                addRecent(uri, name)
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
                val name = displayName(uri)
                val o = JSONObject().put("id", id).put("name", name)
                js("onSaved", o.toString())
                addRecent(uri, name)
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

    /* ---------- File Manager ---------- */

    private fun startFolder() {
        try {
            if (stack.isEmpty()) {
                val s = prefs.getString("tree", null)
                if (s != null) {
                    val u = Uri.parse(s)
                    val ok = contentResolver.persistedUriPermissions.any { it.uri == u }
                    val d = if (ok) DocumentFile.fromTreeUri(this, u) else null
                    if (d != null && d.exists()) stack.add(d)
                }
            }
            listDir()
        } catch (e: Exception) {
            js("onError", "फोल्डर खुल नहीं सका: ${e.message}")
        }
    }

    private fun listDir() {
        try {
            if (stack.isEmpty()) {
                js("onFolder", JSONObject().put("has", false).toString())
                return
            }
            val dir = stack.last()
            entries.clear()
            val rows = dir.listFiles()
                .map { Row(it, it.name ?: "?", it.isDirectory, it.length(), it.lastModified()) }
                .sortedWith(compareBy<Row>({ !it.dir }, { it.name.lowercase() }))
            val arr = JSONArray()
            for (r in rows.take(300)) {
                val id = "e" + (++eid)
                entries[id] = r.f
                arr.put(
                    JSONObject().put("id", id).put("name", r.name).put("dir", r.dir)
                        .put("size", r.size).put("time", r.time)
                )
            }
            val path = stack.joinToString(" / ") { it.name ?: "" }
            js(
                "onFolder",
                JSONObject().put("has", true).put("path", path).put("depth", stack.size - 1)
                    .put("more", rows.size > 300).put("items", arr).toString()
            )
        } catch (e: Exception) {
            js("onError", "फोल्डर पढ़ नहीं सका: ${e.message}")
        }
    }

    private fun createEntry(name: String, isDir: Boolean) {
        try {
            val dir = stack.lastOrNull() ?: throw Exception("पहले फोल्डर चुनें")
            val n = safeName(name)
            if (n.isEmpty()) throw Exception("नाम खाली है")
            if (dir.findFile(n) != null) throw Exception("इस नाम की फाइल/फोल्डर पहले से है")
            val f = (if (isDir) dir.createDirectory(n) else dir.createFile(mimeFor(n), n))
                ?: throw Exception("बन नहीं सका")
            if (!isDir && f.name != n) f.renameTo(n)
            listDir()
        } catch (e: Exception) {
            js("onError", "नहीं बन सका: ${e.message}")
        }
    }

    private fun importInto(uri: Uri) {
        io.execute {
            try {
                val dir = stack.lastOrNull() ?: throw Exception("पहले फोल्डर चुनें")
                val n = safeName(displayName(uri))
                if (dir.findFile(n) != null) throw Exception("इस नाम की फाइल पहले से है")
                val f = dir.createFile(contentResolver.getType(uri) ?: mimeFor(n), n)
                    ?: throw Exception("फाइल बन नहीं सकी")
                contentResolver.openInputStream(uri)?.use { i ->
                    contentResolver.openOutputStream(f.uri, "wt")?.use { o -> i.copyTo(o) }
                        ?: throw Exception("लिख नहीं सका")
                } ?: throw Exception("पढ़ नहीं सका")
                if (f.name != n) f.renameTo(n)
                js("onToast", "Import हो गया ✓")
                listDir()
            } catch (e: Exception) {
                js("onError", "Import नहीं हो सका: ${e.message}")
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun appVersion(): String = "5.0"

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
        fun setTheme(dark: Boolean) {
            runOnUiThread {
                val bg = if (dark) 0xFF0F1115.toInt() else 0xFFF4F5F7.toInt()
                val nav = if (dark) 0xFF1A1D24.toInt() else 0xFFFFFFFF.toInt()
                window.statusBarColor = bg
                window.navigationBarColor = nav
                val c = WindowInsetsControllerCompat(window, window.decorView)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
                webView.setBackgroundColor(bg)
            }
        }

        /* ----- PDF ----- */

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

        /* ----- File Manager ----- */

        @JavascriptInterface
        fun fmStart() { io.execute { startFolder() } }

        @JavascriptInterface
        fun fmPick() { runOnUiThread { treeLauncher.launch(null) } }

        @JavascriptInterface
        fun fmEnter(id: String) {
            io.execute {
                val f = entries[id]
                if (f != null && f.isDirectory) { stack.add(f); listDir() }
            }
        }

        @JavascriptInterface
        fun fmUp() {
            io.execute {
                if (stack.size > 1) stack.removeAt(stack.size - 1)
                listDir()
            }
        }

        @JavascriptInterface
        fun fmOpen(id: String) {
            io.execute {
                val f = entries[id]
                if (f == null) {
                    js("onError", "फाइल नहीं मिली, सूची रिफ्रेश करें")
                } else if ((f.name ?: "").endsWith(".pdf", true)) {
                    importPdf(f.uri)
                } else {
                    readFile(f.uri)
                }
            }
        }

        @JavascriptInterface
        fun fmNew(name: String, isDir: Boolean) {
            io.execute { createEntry(name, isDir) }
        }

        @JavascriptInterface
        fun fmRename(id: String, name: String) {
            io.execute {
                try {
                    val f = entries[id] ?: throw Exception("फाइल नहीं मिली")
                    val n = safeName(name)
                    if (n.isEmpty()) throw Exception("नाम खाली है")
                    if (!f.renameTo(n)) throw Exception("नाम बदला नहीं जा सका")
                    listDir()
                } catch (e: Exception) {
                    js("onError", "Rename नहीं हुआ: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun fmDelete(id: String) {
            io.execute {
                try {
                    val f = entries[id] ?: throw Exception("फाइल नहीं मिली")
                    if (!f.delete()) throw Exception("हट नहीं सका")
                    listDir()
                } catch (e: Exception) {
                    js("onError", "Delete नहीं हुआ: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun fmShare(id: String) {
            runOnUiThread {
                try {
                    val f = entries[id] ?: throw Exception("फाइल नहीं मिली")
                    if (f.isDirectory) throw Exception("फोल्डर शेयर नहीं हो सकता")
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = f.type ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, f.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(i, "शेयर करें"))
                } catch (e: Exception) {
                    js("onError", "शेयर नहीं हो सका: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun fmImport() { runOnUiThread { importLauncher.launch(arrayOf("*/*")) } }

        /* ----- Recent ----- */

        @JavascriptInterface
        fun recentList(): String {
            val out = JSONArray()
            try {
                val a = JSONArray(prefs.getString("recent", "[]"))
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val id = "r" + (++counter)
                    uris[id] = Uri.parse(o.getString("uri"))
                    out.put(
                        JSONObject().put("id", id).put("name", o.getString("name"))
                            .put("time", o.getLong("time"))
                    )
                }
            } catch (e: Exception) { }
            return out.toString()
        }

        @JavascriptInterface
        fun openRecent(id: String) {
            val u = uris[id]
            if (u != null) readFile(u) else js("onError", "फाइल नहीं मिली")
        }

        @JavascriptInterface
        fun clearRecent() {
            prefs.edit().remove("recent").apply()
        }
    }

    override fun onDestroy() {
        io.shutdown()
        printWv?.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
