package com.moyue.reader.data

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 检查更新：以 GitHub Releases 为更新源（wacaoniu/MoYue），手动触发，不做任何静默联网 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/wacaoniu/MoYue/releases/latest"
    private const val UA = "MoYue-Android"

    data class ReleaseInfo(
        val tag: String,        // 如 "v2.5"
        val version: String,    // 如 "2.5"
        val notes: String,      // 更新说明（release body）
        val apkUrl: String,     // APK 下载地址（browser_download_url）
        val apkSize: Long,      // APK 字节数（无 Content-Length 时为 -1）
    )

    /** 拉取最新 Release；失败抛异常（中文提示），无 APK 资产返回 null */
    fun check(): ReleaseInfo? {
        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) {
                throw RuntimeException("GitHub 返回 ${conn.responseCode}${if (conn.responseCode == 403) "（接口限流，请稍后再试）" else ""}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) return null
            val asset = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).asSequence()
                    .map { arr.optJSONObject(it) }
                    .firstOrNull { it?.optString("name")?.endsWith(".apk", true) == true }
            }
            ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v").trim(),
                notes = json.optString("body").trim(),
                apkUrl = asset?.optString("browser_download_url") ?: return null,
                apkSize = asset.optLong("size", -1L),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 版本号比较：latest 每段数字都大于等于 current 且至少一段更大才算更新（"2.5" > "2.4"，"2.4" 不 > "2.4"） */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(a.size, b.size)
        var strictlyGreater = false
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) {
                if (x < y) return false
                strictlyGreater = true
            }
        }
        return strictlyGreater
    }

    /** 流式下载到 dest，onProgress(已读字节, 总字节) 回调进度（总字节无 Content-Length 时为 -1） */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) throw RuntimeException("下载失败（HTTP ${conn.responseCode}）")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
