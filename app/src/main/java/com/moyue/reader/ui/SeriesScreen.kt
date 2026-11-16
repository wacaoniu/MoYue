package com.moyue.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.data.BookMeta
import com.moyue.reader.data.ImportSupport

/** 系列详情页：列出同一分组的所有卷（自然排序），支持多选移出分组 / 删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    seriesName: String,
    books: List<BookMeta>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onRenameSeries: (String, String) -> Unit,
    onSetSeries: (List<String>, String?) -> Unit,
    onDeleteBooks: (List<String>) -> Unit,
) {
    val volumes = remember(books, seriesName) {
        books.filter { it.series == seriesName }
            .sortedWith { a, b -> ImportSupport.naturalOrder.compare(a.title, b.title) }
    }

    // 组被解散 / 卷被清空时退回书架
    LaunchedEffect(volumes.isEmpty()) {
        if (volumes.isEmpty()) onBack()
    }

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selected.isNotEmpty()
    fun exitSelection() { selected = emptySet() }

    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (showRename) {
        RenameSeriesDialog(
            initial = seriesName,
            onConfirm = { newName ->
                showRename = false
                if (newName.isNotBlank() && newName != seriesName) onRenameSeries(seriesName, newName.trim())
            },
            onDismiss = { showRename = false },
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
                        TextButton(onClick = {
                            onSetSeries(selected.toList(), null)
                            exitSelection()
                        }) { Text("移出分组") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(seriesName) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "重命名分组")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "共 ${volumes.size} 卷",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(volumes, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        selectionMode = selectionMode,
                        selected = book.id in selected,
                        onOpen = onOpen,
                        onToggleSelect = { id ->
                            selected = if (id in selected) selected - id else selected + id
                        },
                        onLongPress = { id -> selected = setOf(id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}
