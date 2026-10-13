package com.stronghold.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 前台服务：在进程内启动 Node.js 服务器（nodejs-mobile）。
 *
 * nodejs-mobile 通过 `NodeJS` 单例启动（来自 AAR：com.janeasystems:nodejs-mobile）。
 * 入口脚本：filesDir/bundle/main.js（由 AssetHelper 解压得到）
 */
class NodeService : Service() {

    companion object {
        private const val TAG = "StrongholdShell"
        private const val CHANNEL_ID = "stronghold_server"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.stronghold.shell.action.START"
        const val ACTION_STOP = "com.stronghold.shell.action.STOP"
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNode()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                if (!started) startNode()
            }
        }
        return START_STICKY
    }

    private fun startNode() {
        try {
            val bundleDir = AssetHelper.bundleDir(this)
            // ⭐ 上游是 Node.js 服务器，入口为 package.json 的 main：server/index.js
            //    （不是 main.js / index.js，那是旧壳的误写）
            val candidates = listOf("server/index.js", "index.js", "main.js")
            val entryName = candidates.firstOrNull {
                java.io.File(bundleDir, it).exists()
            } ?: "server/index.js"
            Log.i(TAG, "启动 Node，目录：${bundleDir.absolutePath} 入口：$entryName")

            // ⭐ JNA 直调 libnode.so 的 node::Start
            val logFile = java.io.File(bundleDir, "server.log")
            NodeRunner.start(this, bundleDir.absolutePath, entryName, logFile)

            started = true
            // 注意：node::Start 会阻塞事件循环，这里不能凭它返回判断就绪；
            // 就绪由健康检查轮询判定，这里先广播 true 让 UI 开始轮询。
            notifyServerReady(true)
        } catch (e: Throwable) {
            Log.e(TAG, "Node 启动失败", e)
            FileLogger.log("Node 启动失败: ${e.message}")
            notifyServerReady(false)
        }
    }

    private fun stopNode() {
        // Node 是常驻事件循环，暂无优雅停止；停服务即随进程结束
        started = false
    }

    private fun notifyServerReady(ready: Boolean) {
        val intent = Intent(MainActivity.ACTION_SERVER_STATE)
            .setPackage(packageName)
            .putExtra(MainActivity.EXTRA_SERVER_READY, ready)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "本地服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持本地 Web 服务器运行" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("卫戍协议")
            .setContentText("本地服务运行中")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopNode()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}