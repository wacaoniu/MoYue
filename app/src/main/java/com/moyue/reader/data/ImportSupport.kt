package com.moyue.reader.data

import com.moyue.reader.reader.ComicSupport
import com.moyue.reader.reader.PdfSupport
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile

/** 非 EPUB 格式（PDF / TXT / CBZ）的导入支持 */
object ImportSupport {

    data class BuiltBook(
        val title: String,
        val author: String,
        val spine: List<SpineItem>,
        val toc: List<TocEntry>,
        val pages: List<PageInfo>?,
        val coverHref: String?,
        val mode: String,
        val format: String,
    )

    const val CHUNK_SIZE = 20 // pdf/cbz 每“章”包含的页数

    /** 依据魔数与扩展名判断格式 */
    fun detectFormat(file: File, displayName: String?): String {
        val head = ByteArray(8)
        runCatching {
            file.inputStream().use { it.read(head) }
        }
        val name = displayName ?: file.name
        return when {
            head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
                head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> "pdf"
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> {
                // zip：有 OPF/mimetype 的是 epub，否则按 CBZ（图片压缩包）
                val isEpub = runCatching {
                    ZipFile(file).use { z ->
                        z.getEntry("META-INF/container.xml") != null ||
                            z.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".opf", true) }
                    }
                }.getOrDefault(false)
                if (isEpub) "epub" else "cbz"
            }
            name.endsWith(".pdf", true) -> "pdf"
            else -> "txt"
        }
    }

    fun cleanTitle(displayName: String?): String {
        val n = displayName?.substringAfterLast('/')?.substringAfterLast('\\') ?: ""
        return n.substringBeforeLast('.').trim().ifEmpty { "未命名" }
    }

    // ── 系列识别（导入时按书名识别“同一部漫画的第 N 卷”）──

    /** 中文数字（第N卷 支持） */
    private const val CN_NUM = "0-9〇零一二三四五六七八九十百千万两"

    private val RE_VOL_CN = Regex("^(.{2,}?)[\\s　._·\\-]*(?:[（(\\[【]\\s*)?第\\s*[$CN_NUM]{1,8}\\s*[卷册部集][\\s　]?(?:[）)\\]】])?$")
    private val RE_VOL_EN = Regex("^(.{2,}?)[\\s　._·\\-]*(?:[（(\\[【]\\s*)?(?:vol\\.?|volume|v)\\s*(\\d{1,4})(?:[）)\\]】])?$", RegexOption.IGNORE_CASE)
    private val RE_TAIL_NUM = Regex("^(.{2,}?)[\\s　._·\\-]*(?:[（(\\[【]\\s*)?(\\d{1,4})(?:[）)\\]】])?$")

    /**
     * 从书名提取系列名，命中才返回（用于把“海贼王 第03卷”这类分卷书自动归入“海贼王”系列）。
     * 保守策略：系列名至少 2 个字符且不能全是数字/空白，避免把《1984》这类书名误拆。
     */
    fun detectSeries(title: String): String? {
        val t = title.trim()
        if (t.isEmpty()) return null
        for (re in listOf(RE_VOL_CN, RE_VOL_EN, RE_TAIL_NUM)) {
            val name = re.find(t)?.groupValues?.get(1)?.trim('　', ' ', '.', '_', '-', '·') ?: continue
            if (name.length >= 2 && name.any { !it.isDigit() }) return name
        }
        return null
    }

    // ── PDF ────────────────────────────────────────────────

    fun buildPdf(dir: File, tmpFile: File, title: String): BuiltBook {
        val pdfFile = File(dir, "source.pdf")
        tmpFile.copyTo(pdfFile, overwrite = true)
        val dims = PdfSupport.pageDims(pdfFile)
        val pages = dims.mapIndexed { i, (w, h) -> PageInfo("pdfpage/$i.jpg", w, h) }
        val (spine, toc) = chunkSpine(pages.size)
        return BuiltBook(title, "PDF 文档", spine, toc, pages, null, BookRepository.MODE_COMIC, "pdf")
    }

    // ── CBZ ────────────────────────────────────────────────

    fun buildCbz(dir: File, tmpFile: File, title: String): BuiltBook {
        ZipFile(tmpFile).use { zip -> unzipAll(zip, dir) }
        val imgExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
        val imgs = ArrayList<Pair<String, Pair<Int, Int>?>>()
        dir.walkTopDown().filter { it.isFile }.forEach {
            if (it.extension.lowercase(Locale.ROOT) in imgExt) {
                imgs.add(it.relativeTo(dir).invariantSeparatorsPath to ComicSupport.ImageSize.read(it))
            }
        }
        if (imgs.isEmpty()) throw IllegalArgumentException("压缩包里没有图片，不是有效的 CBZ 漫画")
        imgs.sortWith { a, b -> naturalOrder.compare(a.first, b.first) }
        val pages = imgs.map { (path, size) ->
            if (size != null) PageInfo(path, size.first, size.second)
            else PageInfo(path, 3, 4) // 读不出尺寸时用兜底比例，加载后自然校正
        }
        val (spine, toc) = chunkSpine(pages.size)
        // 第一张图当封面
        return BuiltBook(title, "CBZ 漫画", spine, toc, pages, pages.firstOrNull()?.src, BookRepository.MODE_COMIC, "cbz")
    }

    private fun unzipAll(zip: ZipFile, root: File) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory) continue
            val out = File(root, e.name.trimStart('/'))
            if (!out.canonicalFile.path.startsWith(root.canonicalPath + File.separator)) continue
            out.parentFile?.mkdirs()
            zip.getInputStream(e).use { ins ->
                out.outputStream().use { fos -> ins.copyTo(fos) }
            }
        }
    }

    /** 自然排序比较器：a2 < a10（系列内按卷号排序、CBZ 图片排序共用） */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ka = naturalKey(a)
        val kb = naturalKey(b)
        val n = minOf(ka.size, kb.size)
        for (i in 0 until n) {
            val x = ka[i]
            val y = kb[i]
            val c = when {
                x is Int && y is Int -> x.compareTo(y)
                x is String && y is String -> x.compareTo(y)
                x is Int -> -1
                else -> 1
            }
            if (c != 0) return@Comparator c
        }
        ka.size - kb.size
    }

    /** 自然排序键：a2 < a10 */
    private fun naturalKey(s: String): List<Any> {
        val out = mutableListOf<Any>()
        var i = 0
        while (i < s.length) {
            if (s[i].isDigit()) {
                var j = i
                while (j < s.length && s[j].isDigit()) j++
                out.add(s.substring(i, j).trimStart('0').ifEmpty { "0" }.toInt())
                i = j
            } else {
                var j = i
                while (j < s.length && !s[j].isDigit()) j++
                out.add(s.substring(i, j).lowercase(Locale.ROOT))
                i = j
            }
        }
        return out
    }

    // ── TXT ────────────────────────────────────────────────

    private val CHAPTER_RE = Regex(
        "^\\s{0,8}(第[0-9零〇一二三四五六七八九十百千万两]{1,14}[章节卷回部集]{1,3}[　\\s]?[^\\n]{0,30}" +
            "|(?:Chapter|CHAPTER)\\s*\\d+[^\\n]{0,30}" +
            "|(?:序章|楔子|序言|前言|后记|尾声|终章|番外[^\\n]{0,12})\\s*?)\\s*$"
    )

    fun buildTxt(dir: File, tmpFile: File, title: String): BuiltBook {
        val bytes = tmpFile.readBytes()
        var text = decodeText(bytes) ?: throw IllegalArgumentException("无法识别的文本编码（既不是 UTF-8 也不是 GBK 系）")
        // 去 BOM
        text = text.removePrefix("\uFEFF")

        val lines = text.split('\n')
        val heads = mutableListOf<Pair<Int, String>>() // (行号, 标题)
        for ((i, line) in lines.withIndex()) {
            val t = line.trim('\r', ' ', '\t', '　')
            if (t.length in 2..40 && CHAPTER_RE.matches(t)) heads.add(i to t)
        }

        // 找不到章节结构就按固定行数分块
        val chunks: List<Pair<Int, Int>> = if (heads.size >= 1) {
            buildList {
                var start = 0
                for (h in heads) {
                    if (h.first > start) add(start to (h.first - 1))
                    start = h.first
                }
                if (start < lines.size) add(start to (lines.size - 1))
            }.filter { it.second >= it.first }
        } else {
            val size = 1500
            (0..lines.size step size).map { s -> s to minOf(s + size - 1, lines.size - 1) }
        }

        if (chunks.isEmpty()) throw IllegalArgumentException("文件内容为空")

        val spine = mutableListOf<SpineItem>()
        val toc = mutableListOf<TocEntry>()
        val textDir = File(dir, "text").apply { mkdirs() }
        chunks.forEachIndexed { idx, (from, to) ->
            val heading = heads.firstOrNull { it.first == from }?.second
            val name = "text/c$idx.xhtml"
            val title2 = heading ?: if (heads.isEmpty()) "第 ${idx + 1} 部分" else "片段 ${idx + 1}"
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
                .append(esc(title2)).append("</title></head><body>")
            if (heading != null) sb.append("<h2>").append(esc(heading)).append("</h2>")
            for (i in from..to) {
                val para = lines[i].trim('\r')
                if (para.isNotBlank()) sb.append("<p>").append(esc(para.trim())).append("</p>")
            }
            sb.append("</body></html>")
            File(dir, name).writeText(sb.toString())
            spine.add(SpineItem(name, "application/xhtml+xml"))
            toc.add(TocEntry(title2, name, null, 0, spine.size - 1))
        }
        return BuiltBook(title, "文本文档", spine, toc, null, null, BookRepository.MODE_TEXT, "txt")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** BOM / UTF-8 严格解码 / GB18030 严格解码 / 兜底 UTF-8 */
    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.b() && bytes[1] == 0xBB.b() && bytes[2] == 0xBF.b()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.b() && bytes[1] == 0xFE.b()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.b() && bytes[1] == 0xFF.b()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        strictDecode(bytes, charset("GB18030"))?.let { return it }
        return String(bytes, Charsets.UTF_8) // 有替换字符也强解
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = runCatching {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun Int.b() = toByte()

    // ── 公共：把页面流切成 20 页一“章” ────────────────────

    fun chunkSpine(pageCount: Int): Pair<List<SpineItem>, List<TocEntry>> {
        val spine = mutableListOf<SpineItem>()
        val toc = mutableListOf<TocEntry>()
        var s = 0
        while (s < pageCount) {
            val e = minOf(s + CHUNK_SIZE - 1, pageCount - 1)
            val href = "chunk/$s-$e"
            spine.add(SpineItem(href, "application/x-pagechunk"))
            toc.add(TocEntry("第 ${s + 1} 页", href, null, 0, spine.size - 1))
            s = e + 1
        }
        if (spine.isEmpty()) {
            spine.add(SpineItem("chunk/0-0", "application/x-pagechunk"))
            toc.add(TocEntry("第 1 页", "chunk/0-0", null, 0, 0))
        }
        return spine to toc
    }
}
