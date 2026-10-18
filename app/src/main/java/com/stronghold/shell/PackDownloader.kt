package com.stronghold.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 游戏包下载器 —— 从 GitHub Release 下载 zip，支持断点续传和多镜像自动切换。
 *
 * 【为什么不用第三方库】零依赖，避免 APK 变大；HttpURLConnection 足够。
 *
 * 【断点续传原理】用 Range: bytes=N- 请求剩余部分，配合 RandomAccessFile 追加写。
 *   服务器不支持 Range（返回 200 而非 206）时，从头重下。
 *
 * 【镜像策略】GitHub 直连在国内经常 000/超时，按以下顺序尝试：
 *   1. ghproxy.net  （实测最快，约 2.4s/文件）
 *   2. gh-proxy.com
 *   3. 直连 objects.githubusercontent.com
 *   失败一个自动换下一个，已下的字节数保留。
 */
object PackDownloader {

    private const val TAG = "StrongholdShell"

    /** 下载进度回调（在调用线程执行，通常是后台线程） */
    interface Listener {
        /** @param downloaded 已下载字节 @param total 总字节（未知时 <=0） */
        fun onProgress(downloaded: Long, total: Long)
        /** 切换镜像时通知（第 idx 个，共 count 个） */
        fun onMirror(idx: Int, count: Int, name: String) {}
    }

    /** 代理前缀列表（按优先级） */
    private val MIRRORS = listOf(
        "" to "GitHub 直连",
        "https://ghproxy.net/" to "ghproxy.net",
        "https://gh-proxy.com/" to "gh-proxy.com"
    )

    /**
     * 下载到指定文件（支持续传：文件已存在则接着下）。
     *
     * @param dest 目标文件
     * @param onCancel 返回 true 时中止下载
     * @return 成功返回 true（文件完整，大小与 expectedSize 一致时更可信）
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        listener: Listener?,
        onCancel: () -> Boolean = { false }
    ): Boolean {
        dest.parentFile?.mkdirs()

        for ((idx, m) in MIRRORS.withIndex()) {
            if (onCancel()) return false
            val fullUrl = if (m.first.isEmpty()) url else m.first + url
            listener?.onMirror(idx + 1, MIRRORS.size, m.second)
            Log.i(TAG, "尝试下载 [${m.second}]: ${maskUrl(fullUrl)}")
            val ok = tryDownloadOnce(fullUrl, dest, expectedSize, listener, onCancel)
            if (ok) {
                Log.i(TAG, "下载成功（${m.second}）")
                return true
            }
            // 直连失败就换代理；代理失败换下一个
            if (onCancel()) return false
        }
        return false
    }

    private fun tryDownloadOnce(
        url: String,
        dest: File,
        expectedSize: Long,
        listener: Listener?,
        onCancel: () -> Boolean
    ): Boolean {
        var raf: RandomAccessFile? = null
        var conn: HttpURLConnection? = null
        try {
            var startPos = if (dest.exists()) dest.length() else 0L
            // 已经下完了（大小对得上）直接成功
            if (expectedSize > 0 && startPos == expectedSize) return true
            // 文件比预期还大 → 数据坏了，删了重下
            if (expectedSize > 0 && startPos > expectedSize) {
                dest.delete()
                startPos = 0
            }

            val u = URL(url)
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
            if (startPos > 0) {
                conn.setRequestProperty("Range", "bytes=$startPos-")
            }

            val code = conn.responseCode
            Log.i(TAG, "HTTP $code (startPos=$startPos)")

            if (code !in 200..299) {
                conn.disconnect()
                return false
            }

            // 服务器不支持 Range：返回 200 而不是 206 → 只能从头下
            val actualStart = if (code == 206) startPos else 0L
            if (code == 200 && startPos > 0) {
                Log.w(TAG, "服务器不支持断点续传，从头下载")
            }

            // 总大小：206 时是 Content-Range 剩余 + 已下载
            var total = -1L
            val cr = conn.getHeaderField("Content-Range")
            val cl = conn.contentLengthLong
            total = when {
                cr != null && cr.contains("/") -> {
                    cr.substringAfterLast("/").trim().toLongOrNull() ?: (cl + actualStart)
                }
                cl > 0 -> cl + actualStart
                expectedSize > 0 -> expectedSize
                else -> -1
            }

            raf = RandomAccessFile(dest, "rw")
            raf.seek(actualStart)

            val buf = ByteArray(64 * 1024)
            var downloaded = actualStart
            var lastNotify = 0L
            conn.inputStream.use { ins ->
                while (true) {
                    if (onCancel()) return false
                    val n = ins.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 300) {   // 限流到 ~3 次/秒，避免 UI 卡
                        listener?.onProgress(downloaded, total)
                        lastNotify = now
                    }
                }
            }
            listener?.onProgress(downloaded, total)

            // 校验大小
            if (total > 0 && downloaded != total) {
                Log.w(TAG, "下载不完整：$downloaded / $total")
                return false
            }
            if (expectedSize > 0 && dest.length() != expectedSize) {
                Log.w(TAG, "文件大小不符：${dest.length()} / $expectedSize")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "下载异常: ${e.message}")
            return false
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 日志里隐藏完整 URL 太长，只留文件名 */
    private fun maskUrl(url: String): String {
        val name = url.substringAfterLast("/")
        return ".../$name"
    }

    /** 下载目录：优先外部私有目录（不进 /sdcard 媒体库，卸载可清） */
    fun downloadDir(context: Context): File {
        val d = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 清掉下载缓存（装完后调用，省空间） */
    fun clearDownloads(context: Context) {
        try {
            downloadDir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}