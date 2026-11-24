package com.codetext.pro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfTools {

    /** टेक्स्ट से PDF बनाता है और पेज की संख्या लौटाता है */
    fun make(raw: String, o: JSONObject, out: File): Int {
        val text = raw.replace("\r\n", "\n").replace("\t", "    ")

        val base = when (o.optString("size")) {
            "A5" -> intArrayOf(420, 595)
            "Letter" -> intArrayOf(612, 792)
            else -> intArrayOf(595, 842)
        }
        val land = o.optString("orient") == "landscape"
        val pageW = if (land) base[1] else base[0]
        val pageH = if (land) base[0] else base[1]

        val mm = 72f / 25.4f
        val margin = o.optDouble("margin", 20.0).toFloat().coerceIn(5f, 50f) * mm
        val fs = o.optDouble("fs", 12.0).toFloat().coerceIn(6f, 36f)
        val tf = when (o.optString("font")) {
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        val header = o.optString("header")
        val footer = o.optString("footer")
        val showNum = o.optBoolean("pn", true)

        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = fs; typeface = tf
        }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 9f; typeface = Typeface.SANS_SERIF
        }

        val band = 24f
        val top = margin + (if (header.isNotEmpty()) band else 0f)
        val bottom = pageH - margin - (if (footer.isNotEmpty() || showNum) band else 0f)
        val contentW = (pageW - 2 * margin).toInt()
        val contentH = bottom - top
        if (contentW < 50 || contentH < fs * 2) throw Exception("मार्जिन बहुत बड़ा है")

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, body, contentW)
            .setLineSpacing(0f, 1.15f)
            .build()
        val lines = layout.lineCount

        // पहला पास: हर पेज की पहली लाइन
        val starts = ArrayList<Int>()
        var s = 0
        while (s < lines) {
            starts.add(s)
            val t0 = layout.getLineTop(s)
            var e = s + 1
            while (e < lines && layout.getLineBottom(e) - t0 <= contentH) e++
            s = e
        }
        if (starts.isEmpty()) starts.add(0)
        val total = starts.size

        // दूसरा पास: ड्रॉ करना
        val doc = PdfDocument()
        try {
            for (p in 0 until total) {
                val first = starts[p]
                val last = (if (p + 1 < total) starts[p + 1] else lines) - 1
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, p + 1).create())
                val c = page.canvas

                if (header.isNotEmpty()) {
                    val h = TextUtils.ellipsize(header, small, contentW.toFloat(), TextUtils.TruncateAt.END).toString()
                    c.drawText(h, margin, margin + 12f, small)
                }

                val t0 = layout.getLineTop(first)
                val t1 = layout.getLineBottom(last)
                c.save()
                c.translate(margin, top - t0)
                c.clipRect(0f, t0.toFloat(), contentW.toFloat(), t1.toFloat())
                layout.draw(c)
                c.restore()

                val fy = pageH - margin - 8f
                if (footer.isNotEmpty()) {
                    val f = TextUtils.ellipsize(footer, small, contentW * 0.7f, TextUtils.TruncateAt.END).toString()
                    c.drawText(f, margin, fy, small)
                }
                if (showNum) {
                    val label = "Page " + (p + 1) + " / " + total
                    c.drawText(label, margin + contentW - small.measureText(label), fy, small)
                }
                doc.finishPage(page)
            }
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return total
    }

    /** PDF के पेज JPEG इमेज बनाकर dir में रखता है। [कुल पेज, दिखाए गए पेज] लौटाता है */
    fun render(pdf: File, dir: File, token: String, max: Int): IntArray {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try {
            val total = r.pageCount
            val n = minOf(total, max)
            for (i in 0 until n) {
                val pg = r.openPage(i)
                try {
                    val w = 900
                    val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(File(dir, "pv_" + token + "_" + (i + 1) + ".jpg")).use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
                    }
                    bmp.recycle()
                } finally {
                    pg.close()
                }
            }
            return intArrayOf(total, n)
        } finally {
            r.close()
            pfd.close()
        }
    }

    /** Print के लिए PDF फाइल देने वाला adapter */
    class FileAdapter(private val f: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
            cb: LayoutResultCallback, extras: android.os.Bundle?
        ) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            cb.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, dest: ParcelFileDescriptor,
            cancel: CancellationSignal?, cb: WriteResultCallback
        ) {
            try {
                FileInputStream(f).use { i ->
                    FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) }
                }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.message)
            }
        }
    }
}
