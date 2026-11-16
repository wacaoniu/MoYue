package com.moyue.reader.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.moyue.reader.data.UpdateChecker
import com.moyue.reader.data.UpdateChecker.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Latest(val version: String) : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val read: Long, val total: Long) : UpdateState
    data class Ready(val file: File, val info: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** 检查更新对话框：当前版本 → 检查（GitHub Releases）→ 下载（带进度）→ 调起系统安装 */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    val currentVersion = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    fun apkFile(info: ReleaseInfo): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "updates/MoYue-${info.version}.apk")

    fun canInstallApk(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun launchInstall(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { state = UpdateState.Failed("无法启动安装：${it.message ?: "未知错误"}") }
    }

    fun openInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        }
    }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateChecker.check() } }
            result.fold(
                onSuccess = { info ->
                    state = when {
                        info == null -> UpdateState.Failed("发布信息里没有找到安卓安装包")
                        UpdateChecker.isNewer(info.version, currentVersion) -> UpdateState.Available(info)
                        else -> UpdateState.Latest(currentVersion)
                    }
                },
                onFailure = { state = UpdateState.Failed(it.message ?: "网络请求失败，请稍后重试") },
            )
        }
    }

    fun download(info: ReleaseInfo) {
        state = UpdateState.Downloading(0, info.apkSize)
        scope.launch {
            val dest = apkFile(info)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateChecker.download(info.apkUrl, dest) { read, total ->
                        state = UpdateState.Downloading(read, total)
                    }
                    dest
                }
            }
            result.fold(
                onSuccess = { file ->
                    state = UpdateState.Ready(file, info)
                    // 已有安装权限则直接调起安装；否则等用户点「安装」时引导开启
                    if (canInstallApk()) launchInstall(file)
                },
                onFailure = { state = UpdateState.Failed(it.message ?: "下载失败，请稍后重试") },
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新") },
        text = {
            Column {
                Text(
                    "当前版本 v$currentVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                when (val s = state) {
                    is UpdateState.Idle -> Text(
                        "更新发布在 GitHub（wacaoniu/MoYue），\n只在点击「检查更新」时联网。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在检查…")
                    }

                    is UpdateState.Latest -> Text("已是最新版本 v${s.version} ✓")

                    is UpdateState.Available -> {
                        Text(
                            "发现新版本 ${s.info.tag}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (s.info.notes.isNotBlank()) {
                            Text(
                                s.info.notes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        if (s.info.apkSize > 0) {
                            Text(
                                "安装包大小 ${"%.1f".format(Locale.CHINA, s.info.apkSize / 1024.0 / 1024.0)} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }

                    is UpdateState.Downloading -> {
                        val pct = if (s.total > 0) (s.read * 100 / s.total).toInt() else -1
                        LinearProgressIndicator(
                            progress = { if (pct >= 0) pct / 100f else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (s.total > 0) "${mb(s.read)} / ${mb(s.total)} MB（$pct%）"
                            else "已下载 ${mb(s.read)} MB…",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    is UpdateState.Ready -> Text(
                        if (canInstallApk()) "下载完成，如系统安装器未弹出，请点「安装」。"
                        else "下载完成。系统尚未允许墨阅安装应用，请点「安装」并按提示开启权限。"
                    )

                    is UpdateState.Failed -> Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.Idle, is UpdateState.Failed -> TextButton(onClick = ::check) { Text("检查更新") }
                is UpdateState.Available -> TextButton(onClick = { download(s.info) }) { Text("下载并安装") }
                is UpdateState.Downloading -> TextButton(onClick = {}, enabled = false) { Text("下载中…") }
                is UpdateState.Ready -> TextButton(onClick = {
                    if (canInstallApk()) launchInstall(s.file) else openInstallPermission()
                }) { Text("安装") }
                is UpdateState.Checking, is UpdateState.Latest -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun mb(bytes: Long): String = "%.1f".format(Locale.CHINA, bytes / 1024.0 / 1024.0)
