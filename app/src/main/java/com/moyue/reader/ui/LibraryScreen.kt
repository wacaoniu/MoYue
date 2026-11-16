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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.moyue.reader.data.ImportSupport
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

/** 书架条目：单本书 或 一个系列组（series 相同的书聚合而来） */
sealed class LibraryEntry {
    data class Single(val book: BookMeta) : LibraryEntry()
    data class Group(val name: String, val volumes: List<BookMeta>) : LibraryEntry()
}

private fun activityTime(book: BookMeta): Long = if (book.lastReadAt > 0) book.lastReadAt else book.addedAt

/** 把书列表聚合成「单本 + 系列组」混排的书架条目（按活跃时间降序，组内按卷号自然排序） */
fun buildLibraryEntries(books: List<BookMeta>): List<LibraryEntry> {
    val singles = books.filter { it.series == null }.map { LibraryEntry.Single(it) }
    val groups = books.filter { it.series != null }
        .groupBy { it.series!! }
        .map { (name, vols) -> LibraryEntry.Group(name, vols.sortedWith { a, b -> ImportSupport.naturalOrder.compare(a.title, b.title) }) }
    return (singles + groups).sortedByDescending { entry ->
        when (entry) {
            is LibraryEntry.Single -> activityTime(entry.book)
            is LibraryEntry.Group -> entry.volumes.maxOf(::activityTime)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<BookMeta>,
    importing: Boolean,
    importErrors: List<String>,
    onDismissErrors: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onSetSeries: (List<String>, String?) -> Unit,
    onRenameSeries: (String, String) -> Unit,
    onDissolveSeries: (String) -> Unit,
    onDeleteBooks: (List<String>) -> Unit,
    onImport: (List<Uri>) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }

    val entries = remember(books) { buildLibraryEntries(books) }
    val existingSeries = remember(books) { books.mapNotNull { it.series }.distinct().sortedBy { it } }

    // 多选模式：selected 非空即处于选择态
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selected.isNotEmpty()
    fun exitSelection() { selected = emptySet() }

    var showSeriesPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var groupMenuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }

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

    if (showSeriesPicker) {
        SeriesPickerDialog(
            existing = existingSeries,
            onPick = { name ->
                showSeriesPicker = false
                onSetSeries(selected.toList(), name)
                exitSelection()
            },
            onCreate = { name ->
                showSeriesPicker = false
                onSetSeries(selected.toList(), name.trim())
                exitSelection()
            },
            onDismiss = { showSeriesPicker = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selected.size} 本书？") },
            text = { Text("所选图书将从书架移除，阅读进度一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteBooks(selected.toList())
                    confirmDelete = false
                    exitSelection()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    groupMenuFor?.let { name ->
        AlertDialog(
            onDismissRequest = { groupMenuFor = null },
            title = { Text(name) },
            text = {
                Column {
                    TextButton(onClick = { groupMenuFor = null; renameFor = name }, modifier = Modifier.fillMaxWidth()) {
                        Text("重命名分组", modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = {
                        groupMenuFor = null
                        onDissolveSeries(name)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("解散分组", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { groupMenuFor = null }) { Text("取消") }
            },
        )
    }

    renameFor?.let { old ->
        RenameSeriesDialog(
            initial = old,
            onConfirm = { newName ->
                if (newName.isNotBlank() && newName != old) onRenameSeries(old, newName.trim())
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("已选 ${selected.size} 本") },
                    navigationIcon = {
                        IconButton(onClick = ::exitSelection) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出选择")
                        }
                    },
                    actions = {
                        TextButton(onClick = { showSeriesPicker = true }) { Text("分组") }
                        if (books.filter { it.id in selected }.any { it.series != null }) {
                            TextButton(onClick = {
                                onSetSeries(selected.toList(), null)
                                exitSelection()
                            }) { Text("移出") }
                        }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("墨阅 · 书架") },
                    actions = {
                        IconButton(onClick = { picker.launch(epubMimes) }) {
                            Icon(Icons.Filled.Add, contentDescription = "导入 EPUB")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(epubMimes) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("导入图书") },
                )
            }
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
                    items(entries, key = {
                        when (val e = it) {
                            is LibraryEntry.Single -> "b-${e.book.id}"
                            is LibraryEntry.Group -> "g-${e.name}"
                        }
                    }) { entry ->
                        when (val e = entry) {
                            is LibraryEntry.Single -> BookRow(
                                book = e.book,
                                selectionMode = selectionMode,
                                selected = e.book.id in selected,
                                onOpen = onOpen,
                                onToggleSelect = { id ->
                                    selected = if (id in selected) selected - id else selected + id
                                },
                                onLongPress = { id -> selected = setOf(id) },
                            )

                            is LibraryEntry.Group -> GroupRow(
                                name = e.name,
                                volumes = e.volumes,
                                selectionMode = selectionMode,
                                onOpen = onOpenSeries,
                                onLongPress = { groupMenuFor = e.name },
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

/** 「移入分组」对话框：选已有分组或新建 */
@Composable
private fun SeriesPickerDialog(
    existing: List<String>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移入分组") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (existing.isNotEmpty()) {
                    Text(
                        "已有分组",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    existing.forEach { name ->
                        TextButton(onClick = { onPick(name) }) { Text(name) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "新建分组",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("输入分组名，如：海贼王") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(newName) },
                enabled = newName.isNotBlank(),
            ) { Text("创建并移入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun RenameSeriesDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名分组") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BookRow(
    book: BookMeta,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bookDir = remember(book.id) { com.moyue.reader.data.BookRepository.get(context).bookDir(book.id) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect(book.id) else onOpen(book.id) },
                onLongClick = { if (!selectionMode) onLongPress(book.id) },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect(book.id) })
            Spacer(Modifier.width(10.dp))
        }
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
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    name: String,
    volumes: List<BookMeta>,
    selectionMode: Boolean,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val first = volumes.firstOrNull() ?: return
    val bookDir = remember(first.id) { com.moyue.reader.data.BookRepository.get(context).bookDir(first.id) }
    val avgProgress = volumes.map { it.progressPercent }.average().toInt().coerceIn(0, 100)
    val doneCount = volumes.count { it.progressPercent >= 100 }
    val lastTs = volumes.maxOf { if (it.lastReadAt > 0) it.lastReadAt else it.addedAt }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (!selectionMode) onOpen(name) },
                onLongClick = { if (!selectionMode) onLongPress(name) },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 叠层封面：后移两片色块 + 第一卷封面
        Box(modifier = Modifier.size(width = 80.dp, height = 100.dp)) {
            Box(
                Modifier
                    .size(width = 72.dp, height = 100.dp)
                    .offset(x = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Box(
                Modifier
                    .size(width = 72.dp, height = 100.dp)
                    .offset(x = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            )
            val cover = remember(first.id) { loadCover(first.coverPath?.let { p -> File(bookDir, p) }) }
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
                        .background(Brush.linearGradient(listOf(Color(0xFF6D5B4E), Color(0xFF3E342C)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(1),
                        color = Color(0xFFF3E9DC),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (doneCount > 0) "共 ${volumes.size} 卷 · 读完 $doneCount 卷"
                else "共 ${volumes.size} 卷 · 尚未开读",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { avgProgress / 100f },
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "$avgProgress%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                if (volumes.any { it.lastReadAt > 0 }) "上次阅读 ${formatTime(lastTs)}"
                else "收录于 ${formatTime(volumes.minOf { it.addedAt })}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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

internal fun formatTime(ts: Long): String {
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
