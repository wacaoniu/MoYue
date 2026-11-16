package com.moyue.reader.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import com.moyue.reader.reader.ComicSupport
import java.io.File
import java.io.InputStream
import java.util.UUID

/** pdf/cbz 的页面流（src 对 pdf 是虚拟路径 pdfpage/N.jpg，对 cbz 是书内相对路径） */
data class PageInfo(val src: String, val w: Int, val h: Int)

data class BookMeta(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    val spineIndex: Int,
    val ratio: Double,
    /** 阅读模式：MODE_TEXT 文本翻页 / MODE_COMIC 漫画长条 */
    val mode: String,
    /** 文件格式：epub / pdf / txt / cbz */
    val format: String,
    /** pdf/cbz 的页面流；epub/txt 为 null */
    val pages: List<PageInfo>?,
    /** 宽页拆分：0 不拆 / 1 左→右 / 2 右→左（跨页大图拆成两个单页） */
    val spread: Int,
    /** 双页合并：0 关 / 1 左起·第1页起 / 2 右起·第1页起 / 3 左起·第2页起 / 4 右起·第2页起（相邻两页拼成跨页） */
    val merge: Int,
    /** 页间距：0 贴合 / 1 细缝 / 2 宽缝 */
    val seam: Int,
    /** pdf/cbz 的精确页码进度（阅读位置），-1 表示不适用/未记录 */
    val page: Int,
    /** 所属系列名（同一部漫画的多卷归组），null 表示未分组 */
    val series: String?,
    val addedAt: Long,
    val lastReadAt: Long,
) {
    val progressPercent: Int
        get() = if (spine.isEmpty()) 0 else (((spineIndex + ratio) / spine.size) * 100).toInt().coerceIn(0, 100)
}

class BookRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val booksDir = File(appContext.filesDir, "books").apply { mkdirs() }

    companion object {
        @Volatile private var instance: BookRepository? = null
        fun get(context: Context): BookRepository =
            instance ?: synchronized(this) {
                instance ?: BookRepository(context).also { instance = it }
            }

        const val MODE_TEXT = "text"
        const val MODE_COMIC = "comic"
    }

    fun bookDir(id: String): File = File(booksDir, id)

    /** 从任意输入流导入一本书（epub / pdf / txt / cbz 自动识别），失败抛出 IllegalArgumentException（带中文原因） */
    fun importBook(input: InputStream, displayName: String?): BookMeta {
        val id = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val dir = bookDir(id)
        val tmp = File(appContext.cacheDir, "import-$id.bin")
        try {
            // 先落临时文件再做格式识别与解析
            input.use { src -> tmp.outputStream().use { out -> src.copyTo(out) } }
            val format = ImportSupport.detectFormat(tmp, displayName)
            val built = when (format) {
                "epub" -> buildEpub(id, dir, tmp)
                "pdf" -> ImportSupport.buildPdf(dir, tmp, ImportSupport.cleanTitle(displayName))
                "cbz" -> ImportSupport.buildCbz(dir, tmp, ImportSupport.cleanTitle(displayName))
                else -> ImportSupport.buildTxt(dir, tmp, ImportSupport.cleanTitle(displayName))
            }
            val meta = BookMeta(
                id = id,
                title = built.title,
                author = built.author,
                coverPath = built.coverHref?.takeIf { File(dir, it).isFile },
                spine = built.spine,
                toc = built.toc,
                spineIndex = 0,
                ratio = 0.0,
                mode = built.mode,
                format = built.format,
                pages = built.pages,
                spread = 0,
                merge = 0,
                seam = 1,
                page = -1,
                series = ImportSupport.detectSeries(built.title),
                addedAt = System.currentTimeMillis(),
                lastReadAt = 0L,
            )
            writeMeta(meta)
            return meta
        } catch (e: IllegalArgumentException) {
            dir.deleteRecursively()
            pdfCacheDir(id).deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            pdfCacheDir(id).deleteRecursively()
            throw IllegalArgumentException("导入失败：${e.message ?: "文件无法解析"}", e)
        } finally {
            tmp.delete()
        }
    }

    private fun buildEpub(id: String, dir: File, tmp: File): ImportSupport.BuiltBook {
        val parsed = openZipAndParse(tmp, dir)
        val mode = if (ComicSupport.detectComicMode(dir, parsed.spine)) MODE_COMIC else MODE_TEXT
        return ImportSupport.BuiltBook(
            title = parsed.title,
            author = parsed.author,
            spine = parsed.spine,
            toc = parsed.toc,
            pages = null,
            coverHref = parsed.coverHref,
            mode = mode,
            format = "epub",
        )
    }

    /** 兼容旧调用名 */
    fun importEpub(input: InputStream): BookMeta = importBook(input, null)

    /** 先按 UTF-8 文件名解包，失败再按 GBK 文件名重试（覆盖国内工具打包、文件名为 GBK 的书） */
    private fun openZipAndParse(zipFile: File, dir: File): ParsedEpub {
        var lastError: Exception? = null
        for (charset in listOf(Charsets.UTF_8, java.nio.charset.Charset.forName("GBK"))) {
            try {
                java.util.zip.ZipFile(zipFile, charset).use { zip ->
                    val hasOpf = zip.entries().asSequence()
                        .any { !it.isDirectory && it.name.endsWith(".opf", true) }
                    if (!hasOpf) {
                        throw IllegalArgumentException("不是有效的 EPUB 文件（压缩包里没有 OPF，可能不是 epub 格式或文件已损坏）")
                    }
                    dir.deleteRecursively()
                    dir.mkdirs()
                    return EpubParser.parseAndExtract(zip, dir)
                }
            } catch (e: Exception) {
                android.util.Log.e("Moyue", "import parse failed (charset=$charset)", e)
                lastError = if (e is IllegalArgumentException) e
                else IllegalArgumentException("导入失败：${e.message ?: "文件无法解析"}", e)
            }
        }
        throw lastError ?: IllegalArgumentException("无法识别的 EPUB 文件")
    }

    fun loadBooks(): List<BookMeta> {
        return booksDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { if (it.lastReadAt > 0) it.lastReadAt else it.addedAt }
            ?: emptyList()
    }

    fun getBook(id: String): BookMeta? = readMeta(bookDir(id))

    fun saveProgress(id: String, spineIndex: Int, ratio: Double) {
        saveProgress(id, spineIndex, ratio, -1)
    }

    /** page >= 0 时记录 pdf/cbz 的精确页码（-1 保持原值） */
    fun saveProgress(id: String, spineIndex: Int, ratio: Double, page: Int) {
        val meta = getBook(id) ?: return
        writeMeta(
            meta.copy(
                spineIndex = spineIndex.coerceIn(0, (meta.spine.size - 1).coerceAtLeast(0)),
                ratio = ratio.coerceIn(0.0, 1.0),
                page = if (page >= 0) page.coerceIn(0, ((meta.pages?.size ?: 1) - 1).coerceAtLeast(0)) else meta.page,
                lastReadAt = System.currentTimeMillis(),
            )
        )
    }

    fun deleteBook(id: String) {
        bookDir(id).deleteRecursively()
        pdfCacheDir(id).deleteRecursively()
    }

    /** PDF 页面渲染缓存目录 */
    fun pdfCacheDir(id: String): File = File(appContext.cacheDir, "pdf-$id")

    /** 切换一本书的阅读模式（文本 ⇄ 漫画长条） */
    fun saveMode(id: String, mode: String) {
        val meta = getBook(id) ?: return
        writeMeta(meta.copy(mode = mode))
    }

    /** 保存宽页拆分设置（0 不拆 / 1 左→右 / 2 右→左） */
    fun saveSpread(id: String, spread: Int) {
        val meta = getBook(id) ?: return
        writeMeta(meta.copy(spread = spread.coerceIn(0, 2)))
    }

    /** 保存双页合并设置（0 关 / 1..4 见 BookMeta.merge） */
    fun saveMerge(id: String, merge: Int) {
        val meta = getBook(id) ?: return
        writeMeta(meta.copy(merge = merge.coerceIn(0, 4)))
    }

    /** 保存页间距设置（0 贴合 / 1 细缝 / 2 宽缝） */
    fun saveSeam(id: String, seam: Int) {
        val meta = getBook(id) ?: return
        writeMeta(meta.copy(seam = seam.coerceIn(0, 2)))
    }

    /** 设置/移除书的系列分组（series 传 null 表示移出分组） */
    fun saveSeries(id: String, series: String?) {
        val meta = getBook(id) ?: return
        writeMeta(meta.copy(series = series?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /** 扫描“投放目录”（app 私有 import 目录与外部私有 Import 目录），导入后删除源文件 */
    fun importDropFolders(): Int {
        var count = 0
        val dirs = buildList {
            add(File(appContext.filesDir, "import"))
            appContext.getExternalFilesDir(null)?.let { add(File(it, "Import")) }
        }
        for (d in dirs) {
            d.mkdirs() // 目录必须由应用自己创建，外部工具建的目录可能无法列出
            val files = d.listFiles { f ->
                f.isFile && f.extension.lowercase() in setOf("epub", "pdf", "txt", "cbz")
            }
            android.util.Log.d("Moyue", "scan dir=$d exists=${d.exists()} files=${files?.size}")
            if (files == null) continue
            for (f in files) {
                try {
                    f.inputStream().use { importBook(it, f.name) }
                    f.delete()
                    count++
                } catch (e: Exception) {
                    android.util.Log.e("Moyue", "drop import failed: ${f.name}", e)
                }
            }
        }
        return count
    }

    private fun metaFile(dir: File): File = File(dir, "meta.json")

    private fun writeMeta(meta: BookMeta) {
        val spineArr = JSONArray().apply {
            meta.spine.forEach { put(JSONObject().put("href", it.href).put("mediaType", it.mediaType)) }
        }
        val tocArr = JSONArray().apply {
            meta.toc.forEach {
                put(
                    JSONObject()
                        .put("title", it.title)
                        .put("href", it.href)
                        .put("anchor", it.anchor ?: JSONObject.NULL)
                        .put("level", it.level)
                        .put("spineIndex", it.spineIndex)
                )
            }
        }
        val json = JSONObject()
            .put("id", meta.id)
            .put("title", meta.title)
            .put("author", meta.author)
            .put("coverPath", meta.coverPath ?: JSONObject.NULL)
            .put("spine", spineArr)
            .put("toc", tocArr)
            .put("spineIndex", meta.spineIndex)
            .put("ratio", meta.ratio)
            .put("mode", meta.mode)
            .put("format", meta.format)
            .put("spread", meta.spread)
            .put("merge", meta.merge)
            .put("seam", meta.seam)
            .put("page", meta.page)
            .put("series", meta.series ?: JSONObject.NULL)
            .put("pages", meta.pages?.let { list ->
                JSONArray().apply { list.forEach { p -> put(JSONObject().put("src", p.src).put("w", p.w).put("h", p.h)) } }
            } ?: JSONObject.NULL)
            .put("addedAt", meta.addedAt)
            .put("lastReadAt", meta.lastReadAt)
        metaFile(bookDir(meta.id)).writeText(json.toString())
    }

    private fun readMeta(dir: File): BookMeta? {
        return try {
            val f = metaFile(dir)
            if (!f.exists()) return null
            val json = JSONObject(f.readText())
            val spine = buildList {
                val arr = json.optJSONArray("spine") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(SpineItem(o.getString("href"), o.optString("mediaType", "application/xhtml+xml")))
                }
            }
            val toc = buildList {
                val arr = json.optJSONArray("toc") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        TocEntry(
                            title = o.getString("title"),
                            href = o.getString("href"),
                            anchor = o.optString("anchor").takeIf { it.isNotEmpty() && it != "null" },
                            level = o.optInt("level", 0),
                            spineIndex = o.optInt("spineIndex", 0),
                        )
                    )
                }
            }
            BookMeta(
                id = json.getString("id"),
                title = json.optString("title", "未命名"),
                author = json.optString("author", "未知作者"),
                coverPath = json.optString("coverPath").takeIf { it.isNotEmpty() && it != "null" },
                spine = spine,
                toc = toc,
                spineIndex = json.optInt("spineIndex", 0),
                ratio = json.optDouble("ratio", 0.0),
                mode = json.optString("mode", MODE_TEXT).ifEmpty { MODE_TEXT },
                format = json.optString("format", "epub").ifEmpty { "epub" },
                pages = run {
                    val arr = json.optJSONArray("pages") ?: return@run null
                    if (arr.length() == 0) null
                    else (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        PageInfo(o.optString("src"), o.optInt("w", 3), o.optInt("h", 4))
                    }
                },
                addedAt = json.optLong("addedAt", 0L),
                lastReadAt = json.optLong("lastReadAt", 0L),
                spread = json.optInt("spread", 0),
                merge = json.optInt("merge", 0),
                seam = json.optInt("seam", 1),
                page = json.optInt("page", -1),
                series = json.optString("series").takeIf { it.isNotEmpty() && it != "null" },
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** 阅读偏好（字号缩放 / 主题 / 字体） */
class ReaderPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    var fontScale: Int
        get() = sp.getInt("fontScale", 100)
        set(v) = sp.edit().putInt("fontScale", v.coerceIn(MIN_SCALE, MAX_SCALE)).apply()

    var themeIdx: Int
        get() = sp.getInt("themeIdx", 0)
        set(v) = sp.edit().putInt("themeIdx", v.coerceIn(0, 3)).apply()

    var fontIdx: Int
        get() = sp.getInt("fontIdx", 0)
        set(v) = sp.edit().putInt("fontIdx", v.coerceIn(0, 1)).apply()

    companion object {
        const val MIN_SCALE = 70
        const val MAX_SCALE = 200
    }
}
