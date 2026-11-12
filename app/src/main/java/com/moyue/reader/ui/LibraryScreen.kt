package com.moyue.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moyue.reader.data.BookMeta
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val epubMimes = arrayOf(
    "application/epub+zip", "application/epub",
    "application/pdf", "application/x-pdf",
    "text/plain",
    "application/vnd.comicbook+zip", "application/x-cbz",
    "*/*"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<BookMeta>,
    importing: Boolean,
    importErrors: List<String>,
    onDismissErrors: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: (List<Uri>) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }

    if (importErrors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissErrors,
            title = { Text("导入失败") },
            text = {
                Text(importErrors.joinToString("\n\n") { "· $it" })
            },
            confirmButton = {
                TextButton(onClick = onDismissErrors) { Text("知道了") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("墨阅 · 书架") },
                actions = {
                    IconButton(onClick = { picker.launch(epubMimes) }) {
                        Icon(Icons.Filled.Add, contentDescription = "导入 EPUB")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(epubMimes) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入图书") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (importing) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        "正在导入，请稍候…",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📚", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点击「导入图书」选择 .epub 文件\n也可以在文件管理器里用「墨阅」打开 EPUB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookRow(book, onOpen, onDelete)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: BookMeta, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bookDir = remember(book.id) { com.moyue.reader.data.BookRepository.get(context).bookDir(book.id) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onOpen(book.id) }, onLongClick = { confirmDelete = true })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = remember(book.id) { loadCover(book.coverPath?.let { p -> File(bookDir, p) }) }
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(width = 72.dp, height = 100.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 100.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF6D5B4E), Color(0xFF3E342C)))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.title.take(1),
                    color = Color(0xFFF3E9DC),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { book.progressPercent / 100f },
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${book.progressPercent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                if (book.lastReadAt > 0) "上次阅读 ${formatTime(book.lastReadAt)}"
                else "尚未阅读 · 收录于 ${formatTime(book.addedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这本书？") },
            text = { Text("「${book.title}」将从书架移除，阅读进度一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(book.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun loadCover(file: File?): Bitmap? {
    if (file == null || !file.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 400) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

private fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diffMin = (now - ts) / 60000
    return when {
        diffMin < 1 -> "刚刚"
        diffMin < 60 -> "$diffMin 分钟前"
        diffMin < 60 * 24 -> "${diffMin / 60} 小时前"
        diffMin < 60 * 24 * 30 -> "${diffMin / 60 / 24} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))
    }
}
