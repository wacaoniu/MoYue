package com.moyue.reader.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
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
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** 支持点击分区（左/中/右）与滚动模式的阅读 WebView */
class ReaderWebView(context: Context) : WebView(context) {
    var onZoneTap: ((Int) -> Unit)? = null      // 0 左侧 1 中间 2 右侧
    var onNavigate: ((Uri) -> Unit)? = null

    /** 点击内文链接导航时，抑制同一次点击触发的分区动作 */
    var suppressZoneTap = false

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis()
                suppressZoneTap = false
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx < slop && dy < slop && SystemClock.uptimeMillis() - downT < 400) {
                    if (suppressZoneTap) {
                        suppressZoneTap = false
                    } else {
                        val zone = when {
                            ev.x < width * 0.3f -> 0
                            ev.x > width * 0.7f -> 2
                            else -> 1
                        }
                        android.util.Log.d("Moyue", "zone tap $zone at ${ev.x},${ev.y}")
                        onZoneTap?.invoke(zone)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun eval(js: String) = evaluateJavascript(js, null)
}

private class ScrollBridge(private val scrollCb: (Double) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onScroll(r: Double) {
        handler.post { scrollCb(r) }
    }
}

@Composable
fun ReaderScreen(bookId: String, onExit: () -> Unit, onSwitchMode: () -> Unit) {
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("书籍数据不完整")
        }
        return
    }

    var spineIndex by remember(b.id) { mutableIntStateOf(b.spineIndex.coerceIn(0, b.spine.lastIndex)) }
    var ratio by remember(b.id) { mutableDoubleStateOf(b.ratio.coerceIn(0.0, 1.0)) }
    var overlay by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fontScale by remember { mutableIntStateOf(prefs.fontScale) }
    var themeIdx by remember { mutableIntStateOf(prefs.themeIdx) }
    var fontIdx by remember { mutableIntStateOf(prefs.fontIdx) }
    var web by remember { mutableStateOf<ReaderWebView?>(null) }

    val palette = ReaderPalettes.themes[themeIdx]
    val chapterLabel = b.toc.lastOrNull { it.spineIndex == spineIndex }?.title.orEmpty()
    val progressText = "${(((spineIndex + ratio) / b.spine.size) * 100).toInt().coerceIn(0, 100)}%"

    /** 把某个文件渲染进 WebView（IO 线程读取，主线程加载） */
    fun render(hrefRel: String, anchor: String?, targetRatio: Double) {
        val dir = repo.bookDir(b.id)
        val file = File(dir, hrefRel)
        val imgExt = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
        val isImage = hrefRel.substringAfterLast('.', "").lowercase() in imgExt
        scope.launch(Dispatchers.IO) {
            val html: String
            var baseUrl: String
            if (!file.exists()) {
                html = "<html><body style='text-align:center;color:#999;padding:3em'>本章内容缺失</body></html>"
                baseUrl = "file://${dir.absolutePath}/"
            } else if (isImage) {
                html = ("<html><head><meta name='viewport' content='width=device-width'/></head>" +
                    "<body style='margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;background:#1c1c1e'>" +
                    "<img src='${file.name}' style='max-width:100%;max-height:100vh;object-fit:contain'/></body></html>")
                baseUrl = "file://${file.parentFile?.absolutePath}/"
            } else {
                html = ReaderHtml.decode(file.readBytes())
                baseUrl = "file://${file.absolutePath}"
            }
            val injected = ReaderHtml.inject(
                html, anchor, targetRatio,
                ReaderPalettes.themes[themeIdx],
                ReaderPalettes.fontStacks[fontIdx],
            )
            withContext(Dispatchers.Main) {
                web?.loadDataWithBaseURL(baseUrl, injected, "text/html", "utf-8", null)
            }
        }
    }

    fun loadChapter(idx: Int, anchor: String?, targetRatio: Double) {
        val clamped = idx.coerceIn(0, b.spine.lastIndex)
        android.util.Log.d("Moyue", "loadChapter book=${b.title} idx=$clamped href=${b.spine[clamped].href}")
        if (clamped != spineIndex) {
            // 顺序翻到下一章时，把上一章记为读完
            if (clamped == spineIndex + 1) repo.saveProgress(b.id, spineIndex, 1.0)
            else repo.saveProgress(b.id, spineIndex, ratio)
            spineIndex = clamped
        }
        ratio = targetRatio
        render(b.spine[clamped].href, anchor, targetRatio)
    }

    fun page(dir: Int) {
        val w = web ?: return
        if (dir > 0) {
            if (ratio > 0.965 && spineIndex < b.spine.lastIndex) loadChapter(spineIndex + 1, null, 0.0)
            else w.eval("window.__rd&&window.__rd.page(1)")
        } else {
            if (ratio < 0.035 && spineIndex > 0) loadChapter(spineIndex - 1, null, 1.0)
            else w.eval("window.__rd&&window.__rd.page(-1)")
        }
    }

    fun handleNavigate(uri: Uri) {
        val scheme = uri.scheme ?: return
        if (scheme == "http" || scheme == "https") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            return
        }
        if (scheme != "file") return
        val root = repo.bookDir(b.id).absolutePath
        val path = uri.path ?: return
        if (!path.startsWith(root)) return
        val rel = path.removePrefix(root).trimStart('/')
        val anchor = uri.fragment
        web?.suppressZoneTap = true
        val idx = b.spine.indexOfFirst { it.href == rel }
        when {
            idx >= 0 && idx == spineIndex && !anchor.isNullOrEmpty() ->
                web?.eval("window.__rd&&window.__rd.anchor('${anchor.replace("'", "\\'")}')")
            idx >= 0 -> loadChapter(idx, anchor, 0.0)
            else -> render(rel, anchor, 0.0)
        }
    }

    // 把最新的回调查到 WebView 上（避免工厂里的闭包过期）
    SideEffect {
        web?.onZoneTap = { zone ->
            when (zone) {
                0 -> page(-1)
                2 -> page(1)
                else -> {
                    overlay = !overlay
                    if (!overlay) settingsOpen = false
                }
            }
        }
        web?.onNavigate = { uri -> handleNavigate(uri) }
        web?.settings?.textZoom = fontScale
    }

    LaunchedEffect(b.id) {
        loadChapter(spineIndex, null, b.ratio)
    }

    LaunchedEffect(themeIdx) {
        prefs.themeIdx = themeIdx
        applyThemeJs(web, themeIdx, fontIdx)
    }
    LaunchedEffect(fontIdx) {
        prefs.fontIdx = fontIdx
        applyThemeJs(web, themeIdx, fontIdx)
    }
    LaunchedEffect(fontScale) {
        prefs.fontScale = fontScale
        web?.settings?.textZoom = fontScale
    }

    // 进度变化即防抖落盘 + 切后台即存：直接关闭应用（划掉/被杀）也不丢进度
    AutoSaveProgress(b.id, spineIndex, ratio)

    // 阅读时隐藏系统栏，呼出菜单时恢复
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
            repo.saveProgress(b.id, spineIndex, ratio)
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
                repo.saveProgress(b.id, spineIndex, ratio)
                onExit()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(AndroidColor.parseColor(palette.bgCss)))
    ) {
        AndroidView(
            factory = { ctx ->
                ReaderWebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            onNavigate?.invoke(request.url)
                            return true
                        }
                    }
                    addJavascriptInterface(ScrollBridge { r -> ratio = r }, "__AndroidReader")
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
                        repo.saveProgress(b.id, spineIndex, ratio)
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
                            if (chapterLabel.isEmpty()) "第 ${spineIndex + 1}/${b.spine.size} 节"
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

        // ── 底部栏 + 设置面板 ──────────────────────────────────
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("字号", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = { if (fontScale > ReaderPrefs.MIN_SCALE) fontScale -= 5 },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) { Text("A-") }
                            Text(
                                "${fontScale}%",
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            OutlinedButton(
                                onClick = { if (fontScale < ReaderPrefs.MAX_SCALE) fontScale += 5 },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) { Text("A+") }
                        }
                        Spacer(Modifier.size(10.dp))
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
                                            .background(Color(AndroidColor.parseColor(t.bgCss)))
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("字体", style = MaterialTheme.typography.bodyMedium)
                            ReaderPalettes.fontNames.forEachIndexed { i, name ->
                                FilterChip(
                                    selected = fontIdx == i,
                                    onClick = { fontIdx = i },
                                    label = { Text(name) },
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = onSwitchMode, modifier = Modifier.fillMaxWidth()) {
                            Text("切换为漫画长条模式（适合漫画 / 图多书）")
                        }
                        Spacer(Modifier.size(4.dp))
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }

                    var sliderValue by remember(spineIndex) { mutableFloatStateOf(spineIndex.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (spineIndex > 0) loadChapter(spineIndex - 1, null, 0.0) },
                            enabled = spineIndex > 0,
                        ) { Text("上一章") }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                loadChapter(sliderValue.roundToInt(), null, 0.0)
                            },
                            valueRange = 0f..b.spine.lastIndex.toFloat(),
                            steps = if (b.spine.size > 2) b.spine.size - 2 else 0,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { if (spineIndex < b.spine.lastIndex) loadChapter(spineIndex + 1, null, 0.0) },
                            enabled = spineIndex < b.spine.lastIndex,
                        ) { Text("下一章") }
                    }
                    Text(
                        "全书进度 $progressText · 第 ${spineIndex + 1}/${b.spine.size} 节",
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
                                                loadChapter(entry.spineIndex, entry.anchor, 0.0)
                                            } else if (!entry.anchor.isNullOrEmpty()) {
                                                web?.eval(
                                                    "window.__rd&&window.__rd.anchor('" +
                                                        entry.anchor.replace("'", "\\'") + "')"
                                                )
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

private fun applyThemeJs(web: ReaderWebView?, themeIdx: Int, fontIdx: Int) {
    val p = ReaderPalettes.themes[themeIdx]
    val font = ReaderPalettes.fontStacks[fontIdx]
    web?.eval("window.__rd&&window.__rd.theme('${p.bgCss}','${p.fgCss}','${font}')")
}
