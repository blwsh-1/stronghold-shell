package com.stronghold.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 简单落盘日志，便于装到手机后排查。 */
object FileLogger {

    private const val TAG = "StrongholdShell"
    private const val MAX_BYTES = 256 * 1024L

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "shell.log")
    }

    @Synchronized
    fun log(msg: String) {
        Log.i(TAG, msg)
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
    }

    fun readAll(): String {
        val f = logFile ?: return ""
        return try {
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /** 只取最后 n 行（诊断报告用） */
    fun tail(n: Int): String {
        val all = readAll()
        if (all.isEmpty()) return "  （暂无日志）"
        val lines = all.trimEnd().split('\n')
        val take = if (lines.size <= n) lines else lines.subList(lines.size - n, lines.size)
        return take.joinToString("\n") { "  $it" }
    }

    /** 日志文件路径（分享用） */
    fun logFile(): File? = logFile

    /** 清空日志 */
    fun clear() {
        try { logFile?.delete() } catch (_: Exception) {}
    }
}