package com.moyue.reader.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moyue.reader.data.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** 进度停顿多久后自动落盘 */
private const val AUTOSAVE_DEBOUNCE_MS = 800L

/**
 * 自动保存阅读进度，覆盖“直接关闭应用”的场景（最近任务划掉 / 系统杀进程 / 强制停止）。
 * 这些情况下返回键与 onDispose 都不可靠，因此改为：
 * 1. 进度一变化就防抖落盘（IO 线程）——进程随时被杀，最多丢最近一次停顿内的位置；
 * 2. 生命周期 ON_STOP（回桌面、切应用、被划掉前的必经阶段）时同步保存一次。
 *
 * @param page pdf/cbz 的精确页码，不适用时传 -1（保持原值）
 */
@Composable
fun AutoSaveProgress(bookId: String, spineIndex: Int, ratio: Double, page: Int = -1) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    // 观察者/协程只注册一次，通过可刷新的 State 拿最新进度（避免闭包值过期）
    val current = rememberUpdatedState(Triple(spineIndex, ratio, page))
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(bookId) {
        snapshotFlow { current.value }.collectLatest {
            delay(AUTOSAVE_DEBOUNCE_MS) // 停顿后再存；期间又有变化则重起计时
            val (s, r, p) = current.value // 落盘前取最新值，防止防抖期间旧值覆盖新值
            withContext(Dispatchers.IO) {
                runCatching { repo.saveProgress(bookId, s, r, p) }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val (s, r, p) = current.value
                runCatching { repo.saveProgress(bookId, s, r, p) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
