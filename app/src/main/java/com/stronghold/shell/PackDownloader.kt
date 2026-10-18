package com.stronghold.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 游戏包下载器 —— 从 GitHub Release 下载 zip，支持断点续传、多镜像测速与多轮重试。
 *
 * 【为什么不用第三方库】零依赖，避免 APK 变大；HttpURLConnection 足够。
 *
 * 【断点续传原理】用 Range: bytes=N- 请求剩余部分，配合 RandomAccessFile 追加写。
 *   服务器不支持 Range（返回 200 而非 206）时，从头重下。
 *
 * 【镜像策略】GitHub 直连在国内经常 000/超时，
 *   且实测所有通道都可能静默截断（镜像只下 36%、直连只下 83%），
 *   所以必须「断点续传 + 大小强校验 + 多轮重试」。
 *
 *   镜像列表（参考同类项目实测，按国内可用性排序）：
 *     gh-proxy.com / ghfast.top / ghproxy.net / gh.monlor.com / slink.ltd / gh.llkk.cc
 *   先用 [probeMirrors] 并发测速，挑最快的几个开始下载。
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

    /** 一轮下载内最多尝试的镜像数 */
    private const val MIRRORS_PER_ROUND = 4

    /** 完整下载最多重试轮数（每轮都会续传，不会从头重下） */
    private const val MAX_ROUNDS = 4

    /** 代理前缀列表（按国内可用性排序，第一个是直连） */
    val MIRRORS = listOf(
        "" to "GitHub 直连",
        "https://gh-proxy.com/" to "gh-proxy.com",
        "https://ghfast.top/" to "ghfast.top",
        "https://ghproxy.net/" to "ghproxy.net",
        "https://gh.monlor.com/" to "gh.monlor.com",
        "https://slink.ltd/" to "slink.ltd",
        "https://gh.llkk.cc/" to "gh.llkk.cc"
    )

    /**
     * 并发测速：对每个镜像做一次 Range 小请求，测出响应速度。
     *
     * @return 可用的镜像，按速度从快到慢排序
     */
    fun probeMirrors(url: String, threads: Int = 7, timeoutMs: Int = 6000): List<Pair<String, String>> {
        val results = Collections.synchronizedList(ArrayList<Triple<Long, String, String>>())
        val pool = Executors.newFixedThreadPool(threads)
        for ((prefix, name) in MIRRORS) {
            pool.execute {
                val full = if (prefix.isEmpty()) url else prefix + url
                val t0 = System.currentTimeMillis()
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(full).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
                    conn.setRequestProperty("Range", "bytes=0-65535")
                    val code = conn.responseCode
                    if (code in 200..399) {
                        // 读一点数据，确认真的能出流量
                        val buf = ByteArray(16384)
                        val n = conn.inputStream.use { it.read(buf) }
                        if (n > 0) {
                            val cost = System.currentTimeMillis() - t0
                            results.add(Triple(cost, prefix, name))
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "测速失败 $name: ${e.message}")
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(15, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        val sorted = results.sortedBy { it.first }
        return sorted.map { it.second to it.third }
    }

    /**
     * 下载到指定文件（支持续传：文件已存在则接着下）。
     *
     * 分多轮进行：每轮最多试 MIRRORS_PER_ROUND 个镜像，失败后进入下一轮，
     * 但**已下载的字节会保留**，所以总耗时远小于重下。
     *
     * @param dest 目标文件
     * @param expectedSize 期望大小（<=0 表示未知，只做能下完的判断）
     * @param preferredMirrors 优先尝试的镜像（来自 probeMirrors），可为空
     * @param onCancel 返回 true 时中止下载
     * @return 成功返回 true
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        listener: Listener? = null,
        preferredMirrors: List<Pair<String, String>>? = null,
        onCancel: () -> Boolean = { false }
    ): Boolean {
        dest.parentFile?.mkdirs()

        // 组装尝试顺序：测速结果在前，其余补齐
        val order = ArrayList<Pair<String, String>>()
        preferredMirrors?.let { order.addAll(it) }
        for (m in MIRRORS) {
            if (order.none { it.first == m.first }) order.add(m)
        }

        var cursor = 0
        for (round in 1..MAX_ROUNDS) {
            if (onCancel()) return false
            if (expectedSize > 0 && dest.exists() && dest.length() == expectedSize) return true

            var tried = 0
            while (tried < MIRRORS_PER_ROUND && cursor < order.size) {
                if (onCancel()) return false
                val m = order[cursor]
                cursor++
                tried++
                val fullUrl = if (m.first.isEmpty()) url else m.first + url
                listener?.onMirror(cursor, order.size, m.second)
                Log.i(TAG, "第 $round 轮下载尝试 [${m.second}]: ${maskUrl(fullUrl)}")
                val ok = tryDownloadOnce(fullUrl, dest, expectedSize, listener, onCancel)
                if (ok) {
                    Log.i(TAG, "下载成功（${m.second}，共 $round 轮）")
                    return true
                }
                if (onCancel()) return false
            }
            // 本轮镜像用完，回到队首继续下一轮（这时已下的字节还在）
            cursor = 0
            Log.w(TAG, "第 $round 轮全部失败，准备重试（已下 ${dest.length()} 字节）")
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
            try {
                raf?.close()
            } catch (_: Exception) {
            }
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 日志里隐藏完整 URL，只留文件名 */
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

    /** 磁盘剩余空间（字节） */
    fun freeSpace(dir: File): Long {
        return try {
            dir.usableSpace
        } catch (e: Exception) {
            -1L
        }
    }

    /** 清掉下载缓存（装完后调用，省空间） */
    fun clearDownloads(context: Context) {
        try {
            downloadDir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
