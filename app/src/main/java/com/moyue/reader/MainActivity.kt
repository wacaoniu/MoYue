package com.moyue.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.moyue.reader.data.BookMeta
import com.moyue.reader.data.BookRepository
import com.moyue.reader.reader.ComicScreen
import com.moyue.reader.reader.ReaderScreen
import com.moyue.reader.ui.LibraryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        /** 阅读器注册的音量键处理器 */
        @Volatile
        var readerKeyHandler: ((Int) -> Boolean)? = null
    }

    private var startUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startUri = takeViewIntent(intent)
        setContent {
            MoyueTheme {
                MoyueApp(startUri = startUri, onConsumedStartUri = { startUri = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeViewIntent(intent)?.let { startUri = it }
    }

    private fun takeViewIntent(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) intent.data else null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (readerKeyHandler?.invoke(keyCode) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}

private sealed class Screen {
    data object Library : Screen()
    data class Reader(val bookId: String) : Screen()
}

@Composable
fun MoyueTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(
            primary = Color(0xFF6D4C2F),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFF5E4D3),
            onPrimaryContainer = Color(0xFF2C1B0C),
        ),
        content = content,
    )
}

@Composable
fun MoyueApp(startUri: Uri?, onConsumedStartUri: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var books by remember { mutableStateOf<List<BookMeta>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var importErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    // 每次进入/回到书架都扫描投放目录并刷新列表
    LaunchedEffect(screen) {
        if (screen is Screen.Library) {
            withContext(Dispatchers.IO) { runCatching { repo.importDropFolders() } }
            books = withContext(Dispatchers.IO) { repo.loadBooks() }
        }
    }

    fun importUris(uris: List<Uri>) {
        importing = true
        scope.launch {
            val fails = mutableListOf<String>()
            for (uri in uris) {
                val name = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)
                        ?.use { c ->
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                        }
                }.getOrNull() ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "所选文件")
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { repo.importBook(it, name) }
                    }
                }
                val ex = result.exceptionOrNull()
                when {
                    ex != null -> {
                        android.util.Log.e("Moyue", "import failed: $name", ex)
                        fails.add("$name\n${ex.message ?: "未知错误"}")
                    }
                    result.getOrNull() == null -> fails.add("$name\n无法读取文件（权限被拒绝或文件不存在）")
                }
            }
            books = withContext(Dispatchers.IO) { repo.loadBooks() }
            importing = false
            if (fails.isNotEmpty()) importErrors = fails
        }
    }

    // 从文件管理器「打开方式」进入：导入并直接跳到阅读
    LaunchedEffect(startUri) {
        val uri = startUri ?: return@LaunchedEffect
        importing = true
        val imported = withContext(Dispatchers.IO) {
            runCatching {
                val name = context.contentResolver.query(uri, null, null, null, null)
                    ?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                context.contentResolver.openInputStream(uri)?.use { repo.importBook(it, name) }
            }
        }
        importing = false
        onConsumedStartUri()
        val meta = imported.getOrNull()
        if (meta != null) {
            books = withContext(Dispatchers.IO) { repo.loadBooks() }
            screen = Screen.Reader(meta.id)
        } else {
            imported.exceptionOrNull()?.let {
                android.util.Log.e("Moyue", "open-with import failed", it)
                importErrors = listOf("打开失败：${it.message ?: "无法读取该文件"}")
            } ?: run { importErrors = listOf("打开失败：无法读取该文件") }
        }
    }

    when (val s = screen) {
        is Screen.Library -> LibraryScreen(
            books = books,
            importing = importing,
            importErrors = importErrors,
            onDismissErrors = { importErrors = emptyList() },
            onOpen = {
                android.util.Log.d("Moyue", "onOpen bookId=$it")
                screen = Screen.Reader(it)
            },
            onDelete = { id ->
                scope.launch {
                    withContext(Dispatchers.IO) { repo.deleteBook(id) }
                    books = withContext(Dispatchers.IO) { repo.loadBooks() }
                }
            },
            onImport = { importUris(it) },
        )

        is Screen.Reader -> {
            // 根据书籍模式（文本/漫画长条）选择阅读器，可随时互切
            var comic by remember(s.bookId) { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(s.bookId) {
                comic = withContext(Dispatchers.IO) { repo.getBook(s.bookId)?.mode } ==
                    BookRepository.MODE_COMIC
            }
            val exitReader: () -> Unit = {
                scope.launch {
                    books = withContext(Dispatchers.IO) { repo.loadBooks() }
                }
                screen = Screen.Library
            }
            when (comic) {
                null -> Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                true -> ComicScreen(
                    bookId = s.bookId,
                    onExit = exitReader,
                    onSwitchMode = {
                        scope.launch {
                            withContext(Dispatchers.IO) { repo.saveMode(s.bookId, BookRepository.MODE_TEXT) }
                        }
                        comic = false
                    },
                )

                else -> ReaderScreen(
                    bookId = s.bookId,
                    onExit = exitReader,
                    onSwitchMode = {
                        scope.launch {
                            withContext(Dispatchers.IO) { repo.saveMode(s.bookId, BookRepository.MODE_COMIC) }
                        }
                        comic = true
                    },
                )
            }
        }
    }
}
