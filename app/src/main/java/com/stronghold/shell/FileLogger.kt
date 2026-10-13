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
}