package com.moyue.reader.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedHashMap

/**
 * PDF 页面渲染：基于系统 PdfRenderer（无第三方依赖）。
 * - 页面按需渲染成 JPEG（目标宽 1440px），写入书目录的磁盘缓存，重复滚动零开销
 * - 一本书同一时刻只开一个 PdfRenderer（PdfRenderer 非线程安全），全局串行渲染
 * - 通过 WebView 的 shouldInterceptRequest 拦截 pdfpage/N.jpg 按需供给
 */
object PdfSupport {

    private const val TARGET_WIDTH = 1440
    private const val MEM_CACHE_PAGES = 16

    private val lock = Any()
    private var currentBookId: String? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val memCache = LinkedHashMap<String, ByteArray>(MEM_CACHE_PAGES, 0.75f, true)

    /** 读取每页的宽高（point），导入时调用，只读元数据不渲染，速度很快 */
    fun pageDims(pdfFile: File): List<Pair<Int, Int>> {
        val fd = try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的 PDF 文件（无法打开）", e)
        }
        try {
            val r = PdfRenderer(fd)
            try {
                val out = ArrayList<Pair<Int, Int>>(r.pageCount)
                for (i in 0 until r.pageCount) {
                    val p = r.openPage(i)
                    try {
                        out.add(p.width to p.height)
                    } finally {
                        p.close()
                    }
                }
                if (out.isEmpty()) throw IllegalArgumentException("PDF 里没有任何页面")
                return out
            } finally {
                r.close()
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的 PDF 文件（${e.message ?: "解析失败"}）", e)
        } finally {
            runCatching { fd.close() }
        }
    }

    /** 渲染某一页为 JPEG 字节；cacheDir 为该书专属缓存目录 */
    fun renderPage(bookId: String, pdfFile: File, page: Int, cacheDir: File): ByteArray? {
        val key = "$bookId/$page"
        synchronized(lock) {
            memCache[key]?.let { return it }
        }
        val cacheFile = File(cacheDir, "p$page.jpg")
        if (cacheFile.isFile) {
            val bytes = runCatching { cacheFile.readBytes() }.getOrNull()
            if (bytes != null) {
                synchronized(lock) {
                    memCache[key] = bytes
                    trim()
                }
                return bytes
            }
        }
        return synchronized(lock) {
            try {
                if (currentBookId != bookId || renderer == null) {
                    runCatching { renderer?.close() }
                    runCatching { pfd?.close() }
                    pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(pfd!!)
                    currentBookId = bookId
                }
                val r = renderer ?: return null
                if (page < 0 || page >= r.pageCount) return null
                val p = r.openPage(page)
                try {
                    val scale = TARGET_WIDTH.toFloat() / p.width
                    val w = (p.width * scale).toInt().coerceAtLeast(320)
                    val h = (p.height * scale).toInt().coerceAtLeast(320)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 87, bos)
                    bmp.recycle()
                    val bytes = bos.toByteArray()
                    runCatching {
                        cacheDir.mkdirs()
                        cacheFile.writeBytes(bytes)
                    }
                    memCache[key] = bytes
                    trim()
                    bytes
                } finally {
                    p.close()
                }
            } catch (e: Exception) {
                android.util.Log.e("Moyue", "pdf render page $page failed", e)
                null
            }
        }
    }

    private fun trim() {
        val it = memCache.entries.iterator()
        while (memCache.size > MEM_CACHE_PAGES && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null
            pfd = null
            currentBookId = null
            memCache.clear()
        }
    }
}
