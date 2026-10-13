package com.stronghold.shell

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * JS ↔ Kotlin 桥。注入名：AndroidNative
 * 前端通过 window.AndroidNative.xxx() 调用。
 *
 * 接口保持与常见「打包版」前端兼容，避免前端需要改动：
 *   isNativeApp / reportClientState / getClientState / reloadClient /
 *   restartLocalServer / getLogs / getLocalIp / exitApp
 */
class AndroidBridge(
    private val context: Context,
    private val webView: WebView
) {

    companion object {
        private const val TAG = "StrongholdShell"

        /** 前端上报的客户端状态（booted 等） */
        @Volatile
        var clientState: String = "{}"
            private set
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    /**
     * 前端启动完成后上报状态。收到 {booted:true} 即视为「画面已出来」。
     * （对应原壳的黑屏看门狗复位）
     */
    @JavascriptInterface
    fun reportClientState(json: String) {
        Log.i(TAG, "reportClientState: $json")
        clientState = json
        try {
            val obj = JSONObject(json)
            if (obj.optBoolean("booted", false)) {
                FileLogger.log("前端已 booted")
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun getClientState(): String = clientState

    /** 重新加载页面 */
    @JavascriptInterface
    fun reloadClient() {
        webView.post { webView.reload() }
    }

    /** 重启本地服务 */
    @JavascriptInterface
    fun restartLocalServer() {
        Log.i(TAG, "restartLocalServer 请求")
        val intent = android.content.Intent(context, NodeService::class.java).apply {
            action = NodeService.ACTION_STOP
        }
        context.startService(intent)
        context.startService(
            android.content.Intent(context, NodeService::class.java).apply {
                action = NodeService.ACTION_START
            }
        )
    }

    @JavascriptInterface
    fun getLogs(): String = FileLogger.readAll()

    @JavascriptInterface
    fun getLocalIp(): String = NetworkUtils.localIpv4() ?: "127.0.0.1"

    @JavascriptInterface
    fun exitApp() {
        webView.post {
            (context as? android.app.Activity)?.finish()
        }
    }
}