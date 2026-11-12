package com.moyue.reader.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import com.moyue.reader.reader.ReaderHtml
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** spine 中的一项（一本书的线性阅读顺序） */
data class SpineItem(val href: String, val mediaType: String)

/** 目录条目，href 为解包后的相对路径，anchor 为文件内锚点 */
data class TocEntry(
    val title: String,
    val href: String,
    val anchor: String?,
    val level: Int,
    val spineIndex: Int,
)

data class ParsedEpub(
    val title: String,
    val author: String,
    val coverHref: String?,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
)

/**
 * 自带的 EPUB 解析器：不依赖第三方库，直接解包 ZIP 并解析
 * container.xml / OPF / NCX(EPUB2) / nav(EPUB3)，尽量兼容各种制作粗糙的电子书。
 */
object EpubParser {

    /** 解包并解析。用 ZipFile 而非 ZipInputStream，方便上层做 GBK 文件名重试。
     *  容错：个别条目数据损坏（网上下载的书常见）时跳过并保留已解出的部分，不拖垮整本书。 */
    fun parseAndExtract(zip: ZipFile, targetDir: File): ParsedEpub {
        val root = targetDir.canonicalFile
        root.mkdirs()
        val entries = zip.entries()
        var broken = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.trimStart('/')
            val out = File(root, name)
            if (!out.canonicalFile.path.startsWith(root.path + File.separator)) continue
            out.parentFile?.mkdirs()
            try {
                zip.getInputStream(entry).use { ins ->
                    out.outputStream().use { fos -> ins.copyTo(fos) }
                }
                if (entry.size > 0 && out.length() == 0L) out.delete()
            } catch (e: Exception) {
                // 该条目数据损坏：能解多少留多少，继续其它条目
                broken++
                if (out.length() == 0L) out.delete()
                android.util.Log.w("Moyue", "损坏的压缩条目已跳过: $name (${e.message})")
            }
        }
        return try {
            parseExtractedDir(root)
        } catch (e: IllegalArgumentException) {
            if (broken > 0) throw IllegalArgumentException("${e.message}（另有 $broken 个损坏条目已跳过，可能包含关键文件）", e)
            throw e
        }
    }

    /** 解析已解包的书籍目录（对 META-INF/OEBPS 大小写混乱的书做不敏感匹配） */
    fun parseExtractedDir(root: File): ParsedEpub {
        // 建立 小写路径 -> 实际路径 的索引：有些书容器内大小写与声明不一致
        val lowerIndex = HashMap<String, String>()
        root.walkTopDown().filter { it.isFile }.forEach {
            val rel = it.relativeTo(root).invariantSeparatorsPath
            lowerIndex[rel.lowercase()] = rel
        }
        fun fixCase(p: String): String = lowerIndex[p.lowercase()] ?: p

        val container = File(root, fixCase("META-INF/container.xml"))
        var opfRel: String? = null
        if (container.exists()) {
            opfRel = parseContainerPath(ReaderHtml.decode(container.readBytes()))?.let { fixCase(it) }
        }
        if (opfRel == null || !File(root, opfRel).exists()) opfRel = findFirstOpf(root)
        requireNotNull(opfRel) { "不是有效的 EPUB 文件（缺少 OPF 包描述）" }
        val opfFile = File(root, opfRel)
        require(opfFile.exists()) { "不是有效的 EPUB 文件（OPF 缺失）" }
        val opf = parseOpf(ReaderHtml.decode(opfFile.readBytes()))
        val opfDir = opfRel.substringBeforeLast('/', "")

        val spine = opf.spineIdrefs.mapNotNull { opf.manifest[it] }
            .map { SpineItem(fixCase(resolvePath(opfDir, it.href)), it.mediaType) }
        require(spine.isNotEmpty()) { "不是有效的 EPUB 文件（阅读顺序为空）" }

        val hrefToIndex = HashMap<String, Int>()
        spine.forEachIndexed { i, s -> hrefToIndex.putIfAbsent(s.href, i) }

        // 目录：EPUB3 nav 优先，其次 EPUB2 NCX
        var toc: List<TocEntry> = emptyList()
        val navItem = opf.manifest.values.firstOrNull { it.properties?.contains("nav") == true }
        if (navItem != null) {
            val f = File(root, fixCase(resolvePath(opfDir, navItem.href)))
            if (f.exists()) toc = parseNavToc(ReaderHtml.decode(f.readBytes()), opfDir)
        }
        if (toc.isEmpty()) {
            val ncxItem = opf.manifest[opf.spineTocIdref]
                ?: opf.manifest.values.firstOrNull { it.mediaType.contains("dtbncx+xml", true) }
            if (ncxItem != null) {
                val f = File(root, fixCase(resolvePath(opfDir, ncxItem.href)))
                if (f.exists()) toc = parseNcxToc(ReaderHtml.decode(f.readBytes()), opfDir)
            }
        }
        toc = if (toc.isEmpty()) {
            // 没有目录的书，用 spine 生成一个最简目录
            spine.mapIndexed { i, s -> TocEntry("第 ${i + 1} 节", s.href, null, 0, i) }
        } else {
            toc.map { entry ->
                val fixed = fixCase(entry.href)
                entry.copy(href = fixed, spineIndex = hrefToIndex[fixed] ?: -1)
            }.filter { it.spineIndex >= 0 }
        }

        // 封面：EPUB3 properties -> EPUB2 meta[name=cover] -> 文件名带 cover 的图片
        val coverHref = opf.manifest.values
            .firstOrNull { it.properties?.contains("cover-image") == true }
            ?.let { fixCase(resolvePath(opfDir, it.href)) }
            ?: opf.coverMetaId?.let { opf.manifest[it] }?.let { fixCase(resolvePath(opfDir, it.href)) }
            ?: opf.manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") && it.href.contains("cover", ignoreCase = true)
            }?.let { fixCase(resolvePath(opfDir, it.href)) }

        return ParsedEpub(opf.title, opf.author, coverHref, spine, toc)
    }

    /** 把书内相对链接解析成解包目录中的规范相对路径 */
    fun resolvePath(baseDir: String, href: String): String {
        val noFrag = href.substringBefore('#')
        val decoded = runCatching {
            URLDecoder.decode(noFrag.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(noFrag)
        val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        val stack = ArrayDeque<String>()
        for (part in combined.split('/')) {
            when {
                part.isEmpty() || part == "." -> {}
                part == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun findFirstOpf(root: File): String? {
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("opf", true) }
            .forEach { return it.relativeTo(root).invariantSeparatorsPath }
        return null
    }

    private fun newParser(text: String): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(text.reader())
        return p
    }

    private fun parseContainerPath(xml: String): String? {
        return try {
            val p = newParser(xml)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                    return p.getAttributeValue(null, "full-path")?.takeIf { it.isNotEmpty() }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String?,
    )

    class OpfData {
        var title: String = "未命名"
        var author: String = "未知作者"
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spineIdrefs = mutableListOf<String>()
        var spineTocIdref: String? = null
        var coverMetaId: String? = null
    }

    private fun parseOpf(xml: String): OpfData {
        val data = OpfData()
        val p = newParser(xml)
        var inMetadata = false
        var inManifest = false
        var inSpine = false
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "metadata" -> inMetadata = true
                        "manifest" -> inManifest = true
                        "spine" -> {
                            inSpine = true
                            data.spineTocIdref = p.getAttributeValue(null, "toc")
                        }
                        "item" -> if (inManifest) {
                            val id = p.getAttributeValue(null, "id") ?: continue
                            data.manifest[id] = ManifestItem(
                                id = id,
                                href = p.getAttributeValue(null, "href") ?: "",
                                mediaType = p.getAttributeValue(null, "media-type") ?: "",
                                properties = p.getAttributeValue(null, "properties"),
                            )
                        }
                        "itemref" -> if (inSpine) {
                            p.getAttributeValue(null, "idref")?.let { data.spineIdrefs.add(it) }
                        }
                        "title" -> if (inMetadata) {
                            readText(p)?.let { data.title = it }
                        }
                        "creator" -> if (inMetadata) {
                            readText(p)?.let { data.author = it }
                        }
                        "meta" -> if (inMetadata && p.getAttributeValue(null, "name") == "cover") {
                            data.coverMetaId = p.getAttributeValue(null, "content")
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "metadata" -> inMetadata = false
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
            }
        } catch (_: Exception) {
            // 截断或畸形的 OPF：保留已解析到的部分
        }
        return data
    }

    private fun readText(p: XmlPullParser): String? = try {
        p.nextText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** EPUB2 的 toc.ncx */
    private fun parseNcxToc(xml: String, baseDir: String): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        try {
            val p = newParser(xml)
            var navDepth = 0
            var inLabel = false
            var label: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "navPoint" -> navDepth++
                        "navLabel" -> { inLabel = true; label = null }
                        "text" -> if (inLabel) {
                            label = readText(p) ?: label
                        }
                        "content" -> {
                            val src = p.getAttributeValue(null, "src")
                            val text = label
                            if (src != null && !text.isNullOrEmpty()) {
                                out.add(
                                    TocEntry(
                                        title = text,
                                        href = resolvePath(baseDir, src),
                                        anchor = src.substringAfter('#', "").takeIf { it.isNotEmpty() },
                                        level = (navDepth - 1).coerceAtLeast(0),
                                        spineIndex = -1,
                                    )
                                )
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "navPoint" -> navDepth--
                        "navLabel" -> inLabel = false
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** EPUB3 的 nav.xhtml */
    private fun parseNavToc(xml: String, baseDir: String): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        try {
            val p = newParser(xml)
            var navDepth = 0
            var targetNavDepth = -1   // -1 未找到，>=1 目标，-2 已结束
            var olDepth = 0
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "nav" -> {
                            navDepth++
                            if (targetNavDepth == -1) {
                                val type = p.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                                    ?: p.getAttributeValue(null, "type")
                                if (type != null && type.split(' ', '\t').any { it == "toc" }) {
                                    targetNavDepth = navDepth
                                }
                            }
                        }
                        "ol" -> if (targetNavDepth >= 1 && navDepth >= targetNavDepth) olDepth++
                        "a" -> if (targetNavDepth >= 1 && navDepth == targetNavDepth && olDepth > 0) {
                            val href = p.getAttributeValue(null, "href")
                            val text = readText(p)
                            if (href != null && !text.isNullOrEmpty()) {
                                out.add(
                                    TocEntry(
                                        title = text,
                                        href = resolvePath(baseDir, href),
                                        anchor = href.substringAfter('#', "").takeIf { it.isNotEmpty() },
                                        level = (olDepth - 1).coerceAtLeast(0),
                                        spineIndex = -1,
                                    )
                                )
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "nav" -> {
                            if (targetNavDepth == navDepth) targetNavDepth = -2
                            navDepth--
                        }
                        "ol" -> if (targetNavDepth >= 1 && navDepth >= targetNavDepth && olDepth > 0) olDepth--
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
