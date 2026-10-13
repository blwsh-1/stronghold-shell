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

    companion object {
        private const val TAG = "StrongholdShell"
        const val LOCAL_URL = "http://127.0.0.1:3000"
        const val HEALTH_URL = "$LOCAL_URL/healthz"
        const val ACTION_SERVER_STATE = "com.stronghold.shell.SERVER_STATE"
        const val EXTRA_SERVER_READY = "server_ready"
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

    /** 轮询健康检查，健康则加载游戏；最多尝试 ~30 秒 */
    private fun pollHealthAndLoad() {
        io.execute {
            var ok = false
            for (i in 1..60) {
                if (isServerHealthy()) { ok = true; break }
                Thread.sleep(500)
            }
            Log.i(TAG, "健康检查结果: $ok")
            if (ok) mainHandler.post { loadGame() }
            else mainHandler.post { showErrorAndRetry() }
        }
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
        //   ① 有导入包但 Node 没起来 → 多半是 libnode 运行时版本问题（已知卡点）
        //   ② 压根没导入包 → 提示先去导入
        val hasPack = GamePack.hasActivePack(this)
        val hint = if (hasPack)
            "<p>Node 运行时未就绪。</p>" +
            "<p style=\"color:#f0883e\">若使用上游 v0.2.2+ 完整包，需要 Node&nbsp;≥&nbsp;22；" +
            "当前内置运行时版本可能不足（等待 libnode 升级）。</p>" +
            "<p style=\"color:#888\">游戏包已成功导入，导入流程本身正常。</p>"
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

    override fun onDestroy() {
        try { unregisterReceiver(serverReceiver) } catch (_: Exception) {}
        io.shutdown()
        super.onDestroy()
    }
}