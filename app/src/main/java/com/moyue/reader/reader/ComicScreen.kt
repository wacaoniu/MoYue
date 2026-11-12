package com.moyue.reader.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.moyue.reader.MainActivity
import com.moyue.reader.data.BookMeta
import com.moyue.reader.data.BookRepository
import com.moyue.reader.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** 长条里的一个页面单元（pg 为 pdf/cbz 的绝对页码，-1 表示无页级概念） */
private data class StripPage(val src: String, val w: Int, val h: Int, val pg: Int)

/** EPUB 漫画开双页合并时的“全书摊平页序列”：跨章配对、槽位前缀、各章起始页 */
private class FlatEpub(
    val pages: List<StripPage>,
    val slotPrefix: IntArray, // 不含 off 的原始槽位前缀和
    val chStart: IntArray,    // 每个 spine 章节第一张图的全局页号
)

/** 漫画长条模式的 JS 桥（注意：回调属性不能与桥方法同名，否则 Kotlin 解析到方法自身造成自递归） */
private class ComicBridge(
    private val progressCb: (Int, Double) -> Unit,
    private val pageCb: (Int) -> Unit,
    private val nearEndCb: () -> Unit,
    private val reachTopCb: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onProgress(ch: Int, r: Double) {
        handler.post { progressCb(ch, r) }
    }

    @JavascriptInterface
    fun onPage(p: Int) {
        handler.post { pageCb(p) }
    }

    @JavascriptInterface
    fun onNearEnd() {
        handler.post(nearEndCb)
    }

    @JavascriptInterface
    fun onReachTop() {
        handler.post(reachTopCb)
    }
}

@Composable
fun ComicScreen(bookId: String, onExit: () -> Unit, onSwitchMode: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val repo = remember { BookRepository.get(context) }
    val prefs = remember { ReaderPrefs(context) }
    val scope = rememberCoroutineScope()

    var book by remember { mutableStateOf<BookMeta?>(null) }
    LaunchedEffect(bookId) {
        book = withContext(Dispatchers.IO) { repo.getBook(bookId) }
    }
    val b = book
    if (b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (b.spine.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("书籍数据不完整") }
        return
    }

    var spineIndex by remember(b.id) { mutableIntStateOf(b.spineIndex.coerceIn(0, b.spine.lastIndex)) }
    var ratio by remember(b.id) { mutableDoubleStateOf(b.ratio.coerceIn(0.0, 1.0)) }
    var curCh by remember(b.id) { mutableIntStateOf(spineIndexInitial(b)) }
    var curPage by remember(b.id) { mutableIntStateOf(if (b.page >= 0) b.page else -1) }
    var appendedFrom by remember(b.id) { mutableIntStateOf(-1) }
    var appendedTo by remember(b.id) { mutableIntStateOf(-1) }
    var flat by remember(b.id) { mutableStateOf<FlatEpub?>(null) }
    var tailAdded by remember(b.id) { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var themeIdx by remember { mutableIntStateOf(prefs.themeIdx) }
    var spread by remember(b.id) { mutableIntStateOf(b.spread) }
    var mergeCfg by remember(b.id) { mutableIntStateOf(b.merge) }
    var seam by remember(b.id) { mutableIntStateOf(b.seam) }
    var web by remember { mutableStateOf<ReaderWebView?>(null) }

    // 给 JS 桥用的可刷新回调（避免工厂闭包过期）
    val nearEndAction = remember { mutableStateOf<(() -> Unit)?>(null) }
    val reachTopAction = remember { mutableStateOf<(() -> Unit)?>(null) }
    // PDF 页面按需渲染（shouldInterceptRequest 在后台线程调用）
    val pdfPageAction = remember { mutableStateOf<((Int) -> ByteArray?)?>(null) }

    val palette = ReaderPalettes.themes[themeIdx]
    val pageCount = b.pages?.size ?: (flat?.pages?.size ?: 0)
    val isPageBook = pageCount > 0
    val chapterLabel = if (isPageBook && curPage >= 0) "" else b.toc.lastOrNull { it.spineIndex == spineIndex }?.title.orEmpty()
    val progressText = if (isPageBook && curPage >= 0) {
        "${((curPage + 1) * 100 / pageCount).coerceIn(0, 100)}%"
    } else {
        "${(((spineIndex + ratio) / b.spine.size) * 100).toInt().coerceIn(0, 100)}%"
    }

    /** 左右半页常有 1~2px 的扫描差，配对尺寸判定带容差 */
    fun sameSize(w1: Int, h1: Int, w2: Int, h2: Int): Boolean {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false
        val tolW = maxOf(4, w1 / 100)
        val tolH = maxOf(4, h1 / 100)
        return kotlin.math.abs(w1 - w2) <= tolW && kotlin.math.abs(h1 - h2) <= tolH
    }

    /** 普通单页 img（带宽高占位与页码标记） */
    fun singleImg(p: StripPage): String {
        val ar = if (p.w > 0 && p.h > 0) " style=\"aspect-ratio:${p.w}/${p.h}\"" else ""
        val pg = if (p.pg >= 0) " data-pg=\"${p.pg}\"" else ""
        return "<img src=\"${p.src}\"$ar$pg loading=\"lazy\" decoding=\"async\"/>"
    }

    /** 生成一页的 HTML：宽页且开了拆分时，拆成左右两个单页（spread=2 时右半先显示） */
    fun imgTag(p: StripPage): String {
        if (p.w <= 0 || p.h <= 0) {
            val pg = if (p.pg >= 0) " data-pg=\"${p.pg}\"" else ""
            return "<img src=\"${p.src}\"$pg loading=\"lazy\" decoding=\"async\"/>"
        }
        val body = singleImg(p)
        if (spread > 0 && p.w.toFloat() / p.h.toFloat() >= 1.1f) {
            return if (spread == 2) {
                "<div class=\"split-half second\"${if (p.pg >= 0) " data-pg=\"${p.pg}\"" else ""}>$body</div><div class=\"split-half\"${if (p.pg >= 0) " data-pg=\"${p.pg}\"" else ""}>$body</div>"
            } else {
                "<div class=\"split-half\"${if (p.pg >= 0) " data-pg=\"${p.pg}\"" else ""}>$body</div><div class=\"split-half second\"${if (p.pg >= 0) " data-pg=\"${p.pg}\"" else ""}>$body</div>"
            }
        }
        return body
    }

    /**
     * 配对渲染：开启双页合并后，把相邻的两张同尺寸竖版页拼成一行跨页
     * （源文件里被拆成两个半页的跨页大图由此合回整页）。
     * 用“槽位”模型对齐：已连好的跨页大图占 2 槽（它本身就是一整个跨页），
     * 竖版单页占 1 槽；槽位为偶的竖页与其后同尺寸竖页配对。
     */
    fun renderPages(list: List<StripPage>, baseSlot: Int): String {
        val mergeOn = mergeCfg > 0
        val rtl = mergeCfg == 2 || mergeCfg == 4
        val sb = StringBuilder()
        var slot = baseSlot
        var i = 0
        while (i < list.size) {
            val p = list[i]
            if (!mergeOn) {
                sb.append(imgTag(p))
                i++
                continue
            }
            if (p.w.toFloat() / p.h.toFloat() >= 1.1f) {
                // 合并模式下已连好的跨页大图整张显示，占 2 个槽位
                sb.append(singleImg(p))
                slot += 2
                i++
                continue
            }
            if (slot % 2 == 0 && i + 1 < list.size) {
                val q = list[i + 1]
                if (q.w.toFloat() / q.h.toFloat() < 1.1f && sameSize(p.w, p.h, q.w, q.h)) {
                    val a = singleImg(p)
                    val b = singleImg(q)
                    val pgAttr = if (p.pg >= 0) " data-pg=\"${p.pg}\"" else ""
                    sb.append(if (rtl) "<div class=\"pair\"$pgAttr>$b$a</div>" else "<div class=\"pair\"$pgAttr>$a$b</div>")
                    slot += 2
                    i += 2
                    continue
                }
            }
            sb.append(singleImg(p))
            slot += 1
            i++
        }
        return sb.toString()
    }

    /** 把整本 EPUB 漫画的图片摊平成全局页序列（跨章配对的基础） */
    fun buildFlat(dir: File): FlatEpub {
        val pages = mutableListOf<StripPage>()
        val chStart = IntArray(b.spine.size)
        for (i in b.spine.indices) {
            chStart[i] = pages.size
            for (img in ComicSupport.chapterImages(dir, b.spine[i])) {
                val sz = ComicSupport.ImageSize.read(File(dir, img))
                pages.add(StripPage(img, sz?.first ?: 3, sz?.second ?: 4, pages.size))
            }
        }
        val prefix = IntArray(pages.size + 1)
        for (g in pages.indices) {
            prefix[g + 1] = prefix[g] + (if (pages[g].w.toFloat() / pages[g].h.toFloat() >= 1.1f) 2 else 1)
        }
        return FlatEpub(pages, prefix, chStart)
    }

    /** EPUB 漫画 + 双页合并：按全局页序渲染本章窗口（跨章借页，保证章界的跨页也拼完整） */
    fun chapterInnerFlat(idx: Int): String {
        val f = flat ?: return "<div class=\"txtpage\"></div>"
        var s = f.chStart.getOrElse(idx) { 0 }
        var e = (f.chStart.getOrNull(idx + 1) ?: f.pages.size) - 1
        if (e < s) return "" // 无图章节
        val off = if (mergeCfg >= 3) 1 else 0
        fun slotAt(g: Int) = off + f.slotPrefix[g]
        fun isWideG(g: Int) = f.pages[g].w.toFloat() / f.pages[g].h.toFloat() >= 1.1f
        fun samePairG(x: Int, y: Int) =
            !isWideG(x) && !isWideG(y) && sameSize(f.pages[x].w, f.pages[x].h, f.pages[y].w, f.pages[y].h)
        while (s <= e && s > 0 && slotAt(s) % 2 == 1 && samePairG(s - 1, s)) s++
        if (e < f.pages.lastIndex && slotAt(e + 1) % 2 == 1 && samePairG(e, e + 1)) e++
        if (s > e) return "<div class=\"txtpage\"></div>"
        return renderPages(f.pages.subList(s, e + 1), slotAt(s))
    }

    /** 一个章节在长条里的内容：pdf/cbz 页面流、epub 图片串（零间距，预填宽高比）或纯文字页 */
    fun chapterInner(dir: File, idx: Int): String {
        val item = b.spine[idx]

        // pdf / cbz：虚拟章节 chunk/START-END，src 为页面流（带绝对页码，供页级导航）
        val pageStream = b.pages
        if (pageStream != null) {
            val m = Regex("chunk/(\\d+)-(\\d+)").find(item.href)
            if (m != null) {
                var s = m.groupValues[1].toIntOrNull() ?: 0
                val rawE = (m.groupValues[2].toIntOrNull() ?: 0).coerceAtMost(pageStream.size - 1)
                if (s !in pageStream.indices || s > rawE) {
                    return "<div class=\"txtpage\">（本章没有可显示的内容）</div>"
                }
                var e = rawE
                // 双页合并时跨章节边界“借页”，保证被 20 页分章切断的跨页也能拼完整（按槽位对齐）
                if (mergeCfg > 0) {
                    val off = if (mergeCfg >= 3) 1 else 0
                    fun isWideP(p: com.moyue.reader.data.PageInfo) =
                        p.w > 0 && p.h > 0 && p.w.toFloat() / p.h >= 1.1f
                    fun samePair(a: com.moyue.reader.data.PageInfo, b: com.moyue.reader.data.PageInfo) =
                        !isWideP(a) && !isWideP(b) && sameSize(a.w, a.h, b.w, b.h)
                    fun slotOf(x: Int): Int {
                        var t = off
                        for (j in 0 until x) t += if (isWideP(pageStream[j])) 2 else 1
                        return t
                    }
                    while (s <= e && s > 0 && slotOf(s) % 2 == 1 && samePair(pageStream[s - 1], pageStream[s])) s++
                    if (e < pageStream.lastIndex && slotOf(e + 1) % 2 == 1 && samePair(pageStream[e], pageStream[e + 1])) e++
                    if (s > e) return "<div class=\"txtpage\"></div>"
                    val list = (s..e).map { StripPage(pageStream[it].src, pageStream[it].w, pageStream[it].h, it) }
                    return renderPages(list, slotOf(s))
                }
                val list = (s..e).map { StripPage(pageStream[it].src, pageStream[it].w, pageStream[it].h, it) }
                val sb = StringBuilder()
                for (t in list) sb.append(imgTag(t))
                return sb.toString()
            }
            return "<div class=\"txtpage\">（本章没有可显示的内容）</div>"
        }

        if (mergeCfg > 0 && b.mode == com.moyue.reader.data.BookRepository.MODE_COMIC) {
            // EPUB 漫画合并：全书摊平后跨章配对（每章一图的书只有这样才能拼上）
            return chapterInnerFlat(idx)
        }
        val imgs = ComicSupport.chapterImages(dir, item)
        if (imgs.isNotEmpty()) {
            // 读取图片头部宽高，加载前就占好位：滚动不因出图重排而卡顿
            val list = imgs.map { img ->
                val size = ComicSupport.ImageSize.read(File(dir, img))
                StripPage(img, size?.first ?: 3, size?.second ?: 4, -1)
            }
            val sb = StringBuilder()
            for (t in list) sb.append(imgTag(t))
            return sb.toString()
        }
        val f = File(dir, item.href)
        if (f.isFile) {
            val body = ComicSupport.chapterBodyText(f)
            if (body != null) return "<div class=\"txtpage\">$body</div>"
        }
        return "<div class=\"txtpage\">（本章没有可显示的内容）</div>"
    }

    /** 以 idx 为锚点重建长条（跳章/进入时用）；targetPage>=0 时精确滚到该页（页级导航） */
    fun loadAnchored(idx: Int, restoreRatio: Double, targetPage: Int = -1) {
        val clamped = idx.coerceIn(0, b.spine.lastIndex)
        val rr = restoreRatio.coerceIn(0.0, 0.995) // 1.0 会落空，钳到章底附近
        spineIndex = clamped
        ratio = rr
        curCh = clamped
        if (targetPage >= 0) curPage = targetPage
        appendedFrom = clamped
        appendedTo = clamped
        tailAdded = false
        val dir = repo.bookDir(b.id)
        scope.launch(Dispatchers.IO) {
            if (flat == null && mergeCfg > 0 && b.format == "epub" &&
                b.mode == com.moyue.reader.data.BookRepository.MODE_COMIC
            ) {
                flat = buildFlat(dir)
            }
            val inner = chapterInner(dir, clamped)
            val html = ComicSupport.shellHtml(ReaderPalettes.themes[themeIdx], rr, targetPage, seam)
                .replace("<!--INIT-->", "<div data-ch=\"$clamped\">$inner</div>")
            withContext(Dispatchers.Main) {
                web?.loadDataWithBaseURL("file://${dir.absolutePath}/", html, "text/html", "utf-8", null)
            }
        }
    }

    /** 页级跳转（pdf/cbz/合并中的 epub 漫画）：落在哪一段虚拟章就锚定哪段，再滚到精确页 */
    fun jumpToPage(pg: Int) {
        val stream = b.pages
        if (stream != null) {
            val p = pg.coerceIn(0, stream.size - 1)
            val chunk = p / com.moyue.reader.data.ImportSupport.CHUNK_SIZE
            repo.saveProgress(b.id, spineIndex, ratio, p)
            loadAnchored(chunk, 0.0, p)
            return
        }
        val f = flat ?: return
        val p = pg.coerceIn(0, f.pages.size - 1)
        var chIdx = 0
        for (i in f.chStart.indices) if (f.chStart[i] <= p) chIdx = i
        repo.saveProgress(b.id, spineIndex, ratio, p)
        loadAnchored(chIdx, 0.0, p)
    }

    fun appendNext() {
        if (appendedTo - curCh >= 6) return   // 向下最多预挂 6 章
        val next = appendedTo + 1
        if (next > b.spine.lastIndex) {
            if (!tailAdded && appendedTo == b.spine.lastIndex) {
                tailAdded = true
                val payload = JSONObject.quote("<div class=\"tail\">· 全书完 ·</div>")
                web?.eval("window.__comic&&window.__comic.append(-1, $payload)")
            }
            return
        }
        appendedTo = next
        val dir = repo.bookDir(b.id)
        scope.launch(Dispatchers.IO) {
            val payload = JSONObject.quote(chapterInner(dir, next))
            withContext(Dispatchers.Main) {
                web?.eval("window.__comic&&window.__comic.append($next, $payload)")
            }
        }
    }

    fun prependPrev() {
        if (curCh - appendedFrom >= 3) return // 向上最多回填 3 章
        val prev = appendedFrom - 1
        if (prev < 0) return
        appendedFrom = prev
        val dir = repo.bookDir(b.id)
        scope.launch(Dispatchers.IO) {
            val payload = JSONObject.quote(chapterInner(dir, prev))
            withContext(Dispatchers.Main) {
                web?.eval("window.__comic&&window.__comic.prepend($prev, $payload)")
            }
        }
    }

    fun page(dir: Int) {
        web?.eval("window.__comic&&window.__comic.page(${if (dir > 0) 1 else -1})")
    }

    /** 切换双页合并设置并重建长条 */
    fun setMerge(v: Int) {
        if (mergeCfg != v) {
            mergeCfg = v
            scope.launch {
                withContext(Dispatchers.IO) { repo.saveMerge(b.id, v) }
            }
            loadAnchored(spineIndex, ratio)
        }
    }

    /** 切换页间距并重建长条 */
    fun setSeam(v: Int) {
        if (seam != v) {
            seam = v
            scope.launch {
                withContext(Dispatchers.IO) { repo.saveSeam(b.id, v) }
            }
            loadAnchored(spineIndex, ratio)
        }
    }

    SideEffect {
        // 漫画模式：点击不再翻页，点任何位置只呼出/收起菜单
        web?.onZoneTap = {
            overlay = !overlay
            if (!overlay) settingsOpen = false
        }
        nearEndAction.value = { appendNext() }
        reachTopAction.value = { prependPrev() }
        pdfPageAction.value = { n ->
            if (b.format == "pdf") {
                PdfSupport.renderPage(
                    bookId = b.id,
                    pdfFile = File(repo.bookDir(b.id), "source.pdf"),
                    page = n,
                    cacheDir = repo.pdfCacheDir(b.id),
                )
            } else null
        }
    }

    LaunchedEffect(b.id) {
        if (b.pages != null && b.page >= 0) jumpToPage(b.page)
        else loadAnchored(spineIndex, b.ratio)
    }

    // 进度变化即防抖落盘 + 切后台即存：直接关闭应用（划掉/被杀）也不丢进度
    AutoSaveProgress(b.id, spineIndex, ratio, if (isPageBook) curPage else -1)

    LaunchedEffect(themeIdx) {
        prefs.themeIdx = themeIdx
        val p = ReaderPalettes.themes[themeIdx]
        web?.eval("window.__comic&&window.__comic.theme('${p.bgCss}','${p.fgCss}')")
    }

    LaunchedEffect(overlay, tocOpen, settingsOpen) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (overlay || tocOpen || settingsOpen) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    DisposableEffect(b.id) {
        MainActivity.readerKeyHandler = { code ->
            when (code) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { page(1); true }
                KeyEvent.KEYCODE_VOLUME_UP -> { page(-1); true }
                else -> false
            }
        }
        (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            MainActivity.readerKeyHandler = null
            repo.saveProgress(b.id, spineIndex, ratio, if (isPageBook) curPage else -1)
            if (b.format == "pdf") PdfSupport.close()
            val window = (context as? Activity)?.window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let {
                runCatching {
                    WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    BackHandler {
        when {
            settingsOpen -> settingsOpen = false
            tocOpen -> tocOpen = false
            overlay -> overlay = false
            else -> {
                repo.saveProgress(b.id, spineIndex, ratio, if (isPageBook) curPage else -1)
                onExit()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(android.graphics.Color.parseColor(palette.bgCss)))
    ) {
        AndroidView(
            factory = { ctx ->
                ReaderWebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // 漫画核心：自由双指缩放（无需点开图片）
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val u = request.url
                            if (u.scheme == "http" || u.scheme == "https") {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            // PDF 页面按需渲染：拦下 pdfpage/N.jpg，用 PdfRenderer 现做一张 JPEG
                            val path = request.url.path ?: return null
                            val m = Regex("/pdfpage/(\\d+)\\.jpg").find(path) ?: return null
                            val n = m.groupValues[1].toIntOrNull() ?: return null
                            val bytes = pdfPageAction.value?.invoke(n) ?: return null
                            return WebResourceResponse("image/jpeg", "binary", java.io.ByteArrayInputStream(bytes))
                        }
                    }
                    addJavascriptInterface(
                        ComicBridge(
                            progressCb = { ch, r ->
                                if (ch >= 0) {
                                    curCh = ch
                                    spineIndex = ch
                                    ratio = r
                                }
                            },
                            pageCb = { p ->
                                if (p >= 0) curPage = p
                            },
                            nearEndCb = { nearEndAction.value?.invoke() },
                            reachTopCb = { reachTopAction.value?.invoke() },
                        ),
                        "__AndroidComic",
                    )
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── 顶部栏 ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = overlay,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shadowElevation = 6.dp,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        repo.saveProgress(b.id, spineIndex, ratio, if (isPageBook) curPage else -1)
                        onExit()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            b.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (isPageBook) "第 ${(curPage.coerceAtLeast(0)) + 1}/$pageCount 页"
                            else if (chapterLabel.isEmpty()) "第 ${spineIndex + 1}/${b.spine.size} 节"
                            else "$chapterLabel（${spineIndex + 1}/${b.spine.size}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { tocOpen = true; overlay = false }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录")
                    }
                    TextButton(onClick = { settingsOpen = !settingsOpen }) {
                        Text("Aa", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        // ── 底部栏 + 设置 ──────────────────────────────────────
        AnimatedVisibility(
            visible = overlay,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shadowElevation = 12.dp,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (settingsOpen) {
                        Text("主题", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            ReaderPalettes.themes.forEachIndexed { i, t ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(Color(android.graphics.Color.parseColor(t.bgCss)))
                                            .border(
                                                width = if (themeIdx == i) 3.dp else 1.dp,
                                                color = if (themeIdx == i) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline,
                                                shape = CircleShape,
                                            )
                                            .clickable { themeIdx = i }
                                    )
                                    Text(
                                        t.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Text("页间距", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            listOf("贴合", "细缝", "宽缝").forEachIndexed { i, name ->
                                FilterChip(
                                    selected = seam == i,
                                    onClick = { setSeam(i) },
                                    label = { Text(name) },
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Text("双页合并（两个半页拼成跨页）", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            val mergeOn = mergeCfg > 0
                            val mergeRtl = mergeCfg == 2 || mergeCfg == 4
                            val mergeOff = mergeCfg == 3 || mergeCfg == 4
                            FilterChip(
                                selected = !mergeOn,
                                onClick = { setMerge(0) },
                                label = { Text("关闭") },
                            )
                            FilterChip(
                                selected = mergeOn && !mergeRtl,
                                onClick = { setMerge(if (mergeOff) 3 else 1) },
                                label = { Text("合并·左起") },
                            )
                            FilterChip(
                                selected = mergeOn && mergeRtl,
                                onClick = { setMerge(if (mergeOff) 4 else 2) },
                                label = { Text("合并·右起") },
                            )
                        }
                        if (mergeCfg > 0) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                Text("配对起点", style = MaterialTheme.typography.labelMedium)
                                FilterChip(
                                    selected = mergeCfg == 1 || mergeCfg == 2,
                                    onClick = { setMerge(if (mergeCfg == 2) 2 else 1) },
                                    label = { Text("第1页起") },
                                )
                                FilterChip(
                                    selected = mergeCfg == 3 || mergeCfg == 4,
                                    onClick = { setMerge(if (mergeCfg == 4) 4 else 3) },
                                    label = { Text("第2页起") },
                                )
                            }
                        }
                        if (mergeCfg == 0) {
                            Spacer(Modifier.size(8.dp))
                            Text("宽页拆分（跨页大图 → 两个单页）", style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                listOf("不拆分", "拆分·左起", "拆分·右起").forEachIndexed { i, name ->
                                    FilterChip(
                                        selected = spread == i,
                                        onClick = {
                                            if (spread != i) {
                                                spread = i
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { repo.saveSpread(b.id, i) }
                                                }
                                                loadAnchored(spineIndex, ratio)
                                            }
                                        },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {
                                web?.eval("window.__comic&&window.__comic.resetZoom()")
                            }) { Text("复位缩放") }
                            OutlinedButton(onClick = onSwitchMode) { Text("切换为文本模式") }
                        }
                        Spacer(Modifier.size(4.dp))
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }

                    // 导航锚点：跳转/换章时变化，滑块随之复位（滚动中的实时页码不干扰拖动）
                    val navAnchor = spineIndex * 100000 + (if (isPageBook) curPage.coerceAtLeast(0) else 0)
                    var sliderValue by remember(navAnchor) {
                        mutableFloatStateOf(
                            (if (isPageBook) curPage.coerceAtLeast(0) else spineIndex).toFloat()
                        )
                    }
                    // 漫画模式不放上一章/下一章按钮：连续滚动 + 页级滑块 + 目录已覆盖导航
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            if (isPageBook) jumpToPage(sliderValue.roundToInt())
                            else loadAnchored(sliderValue.roundToInt(), 0.0)
                        },
                        valueRange = if (isPageBook) 0f..(pageCount - 1).toFloat()
                        else 0f..b.spine.lastIndex.toFloat(),
                        steps = if (!isPageBook && b.spine.size > 2) b.spine.size - 2 else 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (isPageBook) "第 ${(curPage.coerceAtLeast(0)) + 1}/$pageCount 页 · 全书 $progressText"
                        else "全书进度 $progressText · 第 ${spineIndex + 1}/${b.spine.size} 节（下滑自动衔接下一章）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }

        // ── 目录抽屉 ───────────────────────────────────────────
        AnimatedVisibility(
            visible = tocOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { tocOpen = false }
                )
                Surface(
                    modifier = Modifier.fillMaxHeight().widthIn(max = 340.dp),
                    shadowElevation = 16.dp,
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(
                                "目录",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { tocOpen = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭目录")
                            }
                        }
                        HorizontalDivider()
                        val listState = rememberLazyListState()
                        LaunchedEffect(tocOpen) {
                            if (tocOpen) {
                                val i = b.toc.indexOfFirst { it.spineIndex == spineIndex }
                                if (i > 1) listState.scrollToItem(i - 1)
                            }
                        }
                        LazyColumn(state = listState) {
                            itemsIndexed(b.toc) { _, entry ->
                                Text(
                                    entry.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            tocOpen = false
                                            if (entry.spineIndex != spineIndex) {
                                                loadAnchored(entry.spineIndex, 0.0)
                                            }
                                        }
                                        .padding(
                                            start = (14 + entry.level * 18).dp,
                                            top = 11.dp,
                                            bottom = 11.dp,
                                            end = 12.dp,
                                        ),
                                    color = if (entry.spineIndex == spineIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun spineIndexInitial(b: BookMeta): Int = b.spineIndex.coerceIn(0, (b.spine.size - 1).coerceAtLeast(0))
