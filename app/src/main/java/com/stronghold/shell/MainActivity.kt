package com.stronghold.shell

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 主界面：内嵌一个 WebView，指向本机 Node 服务器（127.0.0.1:3000）。
 *
 * 启动流程：
 *   1. onCreate → AssetHelper.ensureAssetsExtracted()（把 assets/app_bundle.zip 解到私有目录）
 *   2. 启动 NodeService（前台服务，进程内跑 Node）
 *   3. 轮询 /healthz，健康后 WebView 加载 index.html
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** 联机模式：远端服务器地址；null 表示单机模式 */
    private var serverAddress: String? = null
    /** 联机模式：服务器显示名，仅用于提示 */
    private var serverName: String? = null
    /** 已注入过 hook 的页面 URL，避免重复插入 */
    private val hookedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    companion object {
        private const val TAG = "StrongholdShell"
        const val LOCAL_URL = "http://127.0.0.1:3000"
        const val HEALTH_URL = "$LOCAL_URL/healthz"
        const val ACTION_SERVER_STATE = "com.stronghold.shell.SERVER_STATE"
        const val EXTRA_SERVER_READY = "server_ready"

        /**
         * 联机模式：远端服务器地址（"host:port"）。
         * 有值时本页会 hook WebSocket，把 /ws 连接改写到远端；
         * 页面与素材仍然由手机内的 Node 在 127.0.0.1:3000 提供。
         */
        const val EXTRA_SERVER_ADDRESS = "server_address"
        /** 联机模式下用于界面提示的服务器名 */
        const val EXTRA_SERVER_NAME = "server_name"

        /** 需要注入 hook 的入口页面（唯一一个会插入脚本的路径） */
        private const val INDEX_PATH = "/index.html"
    }

    /** 服务状态广播接收器：服务就绪后加载页面 */
    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ready = intent?.getBooleanExtra(EXTRA_SERVER_READY, false) ?: false
            Log.i(TAG, "收到服务状态广播: ready=$ready")
            if (ready) loadGame()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 0) 读取联机参数。有地址 = 联机模式，无地址 = 单机（行为完全不变）
        serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)?.takeIf { it.isNotBlank() }
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
        Log.i(TAG, "启动模式: " + if (serverAddress == null) "单机" else "联机 -> $serverAddress")

        // 1) 仅在「没有导入游戏包」时，才回退解压 assets 内置资源
        //    （启动器模式：用户导入的包在 GamePack.activeDir，不需要这步）
        if (!GamePack.hasActivePack(this)) {
            AssetHelper.ensureAssetsExtracted(this)
        }
        FileLogger.init(this)

        // 2) 创建 WebView
        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        setContentView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setupWebView()

        // 2.5) 沉浸式全屏：隐藏状态栏 + 导航栏，滑动才临时唤出（需在 setContentView 之后）
        setupImmersiveMode()
        // 2.6) 联机模式：挂 JS 桥（单机不挂，行为与旧版完全一致）
        installBridgeIfNeeded()

        // 3) 注册服务状态广播
        registerReceiverCompat()

        // 4) 启动 Node 服务
        startNodeService()

        // 5) 轮询健康检查（服务可能比 UI 慢）
        pollHealthAndLoad()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // 本地资源加载器：game.local 域名映射到私有 assets 目录
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // 1) 联机模式下拦截入口页面，把 WebSocket hook 插到 <head> 最前面
                if (serverAddress != null && isIndexRequest(request.url)) {
                    injectIntoIndex(request.url)?.let { return it }
                }
                // 2) 其余交给 assetLoader（game.local 等自定义域名）
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.webChromeClient = WebChromeClient()

        // 注入 JS 桥（名字必须与前端约定一致）
        webView.addJavascriptInterface(AndroidBridge(this, webView), "AndroidNative")

        // 长按禁用（游戏内不需要）
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
    }

    /**
     * 判断这是不是需要注入的入口页面请求。
     * 只认本机 Node 上的 /index.html（含根路径），其它一律不碰。
     */
    private fun isIndexRequest(url: android.net.Uri): Boolean {
        val host = url.host ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        val path = url.path ?: return false
        return path == INDEX_PATH || path == "/" || path.isEmpty()
    }

    /**
     * 从本机 Node 取回 index.html，把 WebSocket hook 插到 <head> 之后返回。
     *
     * 为什么不用 onPageStarted：那是异步投递的，页面里的内联脚本可能先跑，
     * 导致上游在 hook 生效前就把 WebSocket 取了引用。在 HTML 里插入 <script>
     * 能保证 hook 严格先于页面上任何脚本执行。
     *
     * 任何异常都返回 null → 退回原生加载，最坏情况是联机不生效但游戏能开。
     */
    private fun injectIntoIndex(url: android.net.Uri): WebResourceResponse? {
        val addr = serverAddress ?: return null
        val key = url.toString()
        if (!hookedUrls.add(key)) {
            // 已经注入过（页面刷新/重进），仍要注入，只是记一笔日志
            Log.i(TAG, "重复注入 hook: $key")
        }
        return try {
            val conn = URL(url.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                Log.w(TAG, "取 index.html 失败: HTTP $code")
                return null
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val script = "<script>" + WsHook.build(addr) + "</script>"
            val patched = insertIntoHead(html, script)
            if (patched == null) {
                Log.w(TAG, "index.html 里找不到 <head>，改用 onPageStarted 兜底")
                return null
            }
            Log.i(TAG, "已注入 WebSocket hook -> $addr")
            WebResourceResponse(
                "text/html",
                "UTF-8",
                200,
                "OK",
                mapOf(
                    "Cache-Control" to "no-store",
                    "Pragma" to "no-cache"
                ),
                patched.toByteArray(Charsets.UTF_8).inputStream()
            )
        } catch (e: Exception) {
            Log.w(TAG, "注入失败: ${e.message}")
            null
        }
    }

    /**
     * 把脚本插到 <head> 标签之后（大小写不敏感）。
     * 找不到 <head> 时退而插入到 <html> 之后；都没有就返回 null。
     */
    private fun insertIntoHead(html: String, script: String): String? {
        val headRegex = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        val m = headRegex.find(html)
        if (m != null) {
            val at = m.range.last + 1
            return html.substring(0, at) + script + html.substring(at)
        }
        val htmlRegex = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
        val m2 = htmlRegex.find(html)
        if (m2 != null) {
            val at = m2.range.last + 1
            return html.substring(0, at) + script + html.substring(at)
        }
        // 连 <html> 都没有（极少数情况）：直接放最前面
        return script + html
    }

    /**
     * 联机模式下挂一个 JS 桥，用于连接失败时给用户明确提示。
     * 只暴露一个无参回调，且只在联机模式挂载；单机完全不挂。
     */
    @SuppressLint("JavascriptInterface")
    private fun installBridgeIfNeeded() {
        if (serverAddress == null) return
        webView.addJavascriptInterface(
            object {
                @android.webkit.JavascriptInterface
                fun onConnectionFailed(reason: String?) {
                    Log.w(TAG, "WebSocket 连接失败: $reason")
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        val target = serverName ?: serverAddress ?: ""
                        showToastLike("连不上 $target\n请确认对方已开服，或地址填错了")
                    }
                }
            },
            WsHook.BRIDGE_NAME
        )
    }

    /** 用一个小浮层提示，不打断游戏（游戏是横屏全屏，Toast 也能用） */
    private fun showToastLike(message: String) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    /**
     * 沉浸式全屏：内容延伸到状态栏/导航栏区域，并隐藏两者。
     * 用户从边缘滑动时临时唤出，几秒后自动收起（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）。
     */
    private fun setupImmersiveMode() {
        // 让内容延伸到系统栏区域（配合 hide 才是真全屏）
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // 系统栏隐藏后，滑动才临时显示
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 隐藏状态栏 + 导航栏
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 窗口重新获得焦点时再隐藏一次（有些系统/场景会把系统栏弹回来） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun registerReceiverCompat() {
        val filter = IntentFilter(ACTION_SERVER_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serverReceiver, filter)
        }
    }

    private fun startNodeService() {
        val intent = Intent(this, NodeService::class.java).apply {
            action = NodeService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i(TAG, "已请求启动 NodeService")
    }

    /**
     * 轮询健康检查，健康则加载游戏。
     *
     * 上游 Node 服务器首次启动需要较长时间（实测几十秒 ~ 1 分钟，
     * 因为要加载 21165 个文件的站点资源）。故：
     *   - 轮询上限放宽到 ~90 秒（180 × 500ms）
     *   - 轮询期间显示「正在启动游戏…」加载页（而非黑屏）
     *   - 每 2 秒更新一次已等待时间，让用户知道在推进
     */
    private fun pollHealthAndLoad() {
        showLoadingPage()
        io.execute {
            var ok = false
            val maxTries = 180          // 180 × 500ms = 90 秒
            for (i in 1..maxTries) {
                if (isServerHealthy()) { ok = true; break }
                if (i % 4 == 0) {       // 每 2 秒刷新一次提示
                    val sec = i / 2
                    mainHandler.post { updateLoadingPage(sec) }
                }
                Thread.sleep(500)
            }
            Log.i(TAG, "健康检查结果: $ok")
            if (ok) mainHandler.post { loadGame() }
            else mainHandler.post { showErrorAndRetry() }
        }
    }

    /** 启动中：显示加载页（替代黑屏） */
    private fun showLoadingPage() {
        if (isFinishing || isDestroyed) return
        webView.loadDataWithBaseURL(
            null,
            """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
                   flex-direction:column;background:#0d1117;color:#e6edf3;
                   font-family:sans-serif;text-align:center}
              .spin{width:44px;height:44px;border:4px solid #30363d;border-top-color:#58a6ff;
                    border-radius:50%;animation:r 1s linear infinite;margin-bottom:22px}
              @keyframes r{to{transform:rotate(360deg)}}
              .t{font-size:17px;margin-bottom:8px}
              .s{font-size:13px;color:#7d8590}
            </style></head>
            <body>
              <div class="spin"></div>
              <div class="t">正在启动游戏…</div>
              <div class="s" id="tip">首次启动需要约 30~60 秒，请稍候</div>
            </body></html>
            """.trimIndent(),
            "text/html", "UTF-8", null
        )
    }

    /** 更新加载页上的等待秒数 */
    private fun updateLoadingPage(sec: Int) {
        if (isFinishing || isDestroyed) return
        // 只在还停留在加载页时更新
        webView.evaluateJavascript(
            "document.getElementById('tip') && " +
            "(document.getElementById('tip').innerText='已等待 ${'$'}{sec} 秒，仍在启动…')",
            null
        )
    }

    private fun isServerHealthy(): Boolean {
        return try {
            val conn = URL(HEALTH_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun loadGame() {
        if (webView.url?.startsWith(LOCAL_URL) == true) return
        Log.i(TAG, "加载游戏页面: $LOCAL_URL/index.html")
        webView.loadUrl("$LOCAL_URL/index.html")
    }

    /** 服务没起来时的兜底提示（黑屏看门狗思路：不崩，只提示 + 重试） */
    private fun showErrorAndRetry() {
        // 区分两种情形，给用户明确指引：
        //   ① 有导入包但 Node 没起来 → 多半是首次启动还没加载完 / 被系统回收
        //   ② 压根没导入包 → 提示先去导入
        val hasPack = GamePack.hasActivePack(this)
        val hint = if (hasPack)
            "<p>游戏服务还没起来（首次启动加载较慢）。</p>" +
            "<p style=\"color:#888\">游戏包已成功导入，导入流程本身正常；" +
            "稍等片刻会自动重试。</p>"
        else
            "<p>尚未导入游戏包。</p>" +
            "<p style=\"color:#888\">请返回上一页点击「导入游戏包 (.zip)」。</p>"

        webView.loadDataWithBaseURL(
            null,
            """
            <html><body style="background:#111;color:#eee;font-family:sans-serif;
                 display:flex;align-items:center;justify-content:center;flex-direction:column;height:100%;padding:0 24px;text-align:center">
            <h2>本地服务未就绪</h2>
            $hint
            <p style="color:#888">即将自动重试…</p>
            </body></html>
            """.trimIndent(),
            "text/html", "UTF-8", null
        )
        mainHandler.postDelayed({ pollHealthAndLoad() }, 3000)
    }

    override fun onResume() {
        super.onResume()
        // 自愈：回到前台时若服务健康但页面没加载好，则重新导航
        io.execute {
            if (isServerHealthy()) mainHandler.post { loadGame() }
        }
    }

        /**
     * 返回键：退出游戏页，回到启动器（而不是直接退出整个 App）。
     * Node 服务是前台服务，会继续在后台跑，再次点「启动游戏」可秒回。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(serverReceiver) } catch (_: Exception) {}
        io.shutdown()
        super.onDestroy()
    }
}