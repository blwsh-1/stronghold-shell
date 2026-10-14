package com.stronghold.shell

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 「游戏包管理」界面 —— 启动器的入口页。
 *
 * 功能：
 *   - 首次启动 / 无包时：显示「导入游戏包」按钮
 *   - 已有包时：显示版本信息 + 「启动游戏」+「重新导入」+「删除包」
 *
 * 导入流程：系统文件选择器选 zip → GamePack.installFromUri → 回到主页启动
 */
class ImportActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnImport: Button
    private lateinit var btnLaunch: Button
    private lateinit var btnMods: Button
    private lateinit var btnDelete: Button
    private lateinit var logText: TextView

    /** 系统文件选择器：只挑 zip */
    private val pickZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> doInstall(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        buildUi()
        refreshUi()
    }

    /** 纯代码构建界面（避免动 res 里其它文件） */
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0d1117"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "卫戍协议：盟约"
            setTextColor(Color.parseColor("#e6edf3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "安卓启动器"
            setTextColor(Color.parseColor("#7d8590"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#c9d1d9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        btnImport = Button(this).apply {
            text = "导入游戏包 (.zip)"
            setOnClickListener { pickZipFile() }
        }
        btnLaunch = Button(this).apply {
            text = "启动游戏"
            setOnClickListener { launchGame() }
        }
        btnMods = Button(this).apply {
            text = "创意工坊（Mod）"
            setOnClickListener { openMods() }
        }
        btnDelete = Button(this).apply {
            text = "删除已导入的包"
            setOnClickListener { confirmDelete() }
        }

        logText = TextView(this).apply {
            setTextColor(Color.parseColor("#7d8590"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(16), 0, 0)
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(progressBar)
        root.addView(btnLaunch, lp)
        root.addView(btnImport, lp)
        root.addView(btnMods, lp)
        root.addView(btnDelete, lp)
        root.addView(logText)

        setContentView(root)
    }

    /** 根据「有没有包」切换按钮/文案 */
    private fun refreshUi() {
        val info = GamePack.readInfo(this)
        val hasPack = GamePack.hasActivePack(this)

        if (hasPack && info != null) {
            val modStat = modSummary()
            statusText.text = "已导入游戏包\n版本：${info.optString("version", "unknown")}\n" +
                "入口：${info.optString("entry", "server/index.js")}\n" +
                "Mod：$modStat"
            btnLaunch.isEnabled = true
            btnImport.text = "重新导入（覆盖）"
            btnMods.isEnabled = true
            btnDelete.isEnabled = true
        } else if (hasPack) {
            statusText.text = "已导入游戏包（版本信息缺失）\nMod：${modSummary()}"
            btnLaunch.isEnabled = true
            btnImport.text = "重新导入（覆盖）"
            btnMods.isEnabled = true
            btnDelete.isEnabled = true
        } else {
            statusText.text = "尚未导入游戏包\n\n请选择完整包 zip\n" +
                "（上游 sganggs 或 Forge 版本均可）"
            btnLaunch.isEnabled = false
            btnImport.text = "导入游戏包 (.zip)"
            btnMods.isEnabled = false
            btnDelete.isEnabled = false
        }
        logText.text = "日志：${FileLogger.readAll().takeLast(300)}"
    }

    /** Mod 数量摘要（用于首页状态栏） */
    private fun modSummary(): String {
        return try {
            val (enabled, total) = WorkshopHelper.countMods(this)
            if (total == 0) "未安装" else "已启用 $enabled / 共 $total"
        } catch (e: Exception) {
            "读取失败"
        }
    }

    private fun pickZipFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            // 部分机型 zip 的 MIME 不标准，放宽为 */*
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        try {
            pickZip.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doInstall(uri: android.net.Uri) {
        setBusy(true, "正在解压安装，请勿退出…")
        io.execute {
            val dir = GamePack.installFromUri(this, uri) { count ->
                ui.post { progressBar.progress = (count / 200) % 100 }
            }
            ui.post {
                setBusy(false, null)
                if (dir != null) {
                    Toast.makeText(this, "导入成功！", Toast.LENGTH_SHORT).show()
                    refreshUi()
                    // 可选：直接启动
                    launchGame()
                } else {
                    Toast.makeText(this, "导入失败：包结构不对（需含 node_modules 与 server/index.js）", Toast.LENGTH_LONG).show()
                    refreshUi()
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, msg: String?) {
        progressBar.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        btnImport.isEnabled = !busy
        btnLaunch.isEnabled = !busy && GamePack.hasActivePack(this)
        btnMods.isEnabled = !busy && GamePack.hasActivePack(this)
        btnDelete.isEnabled = !busy && GamePack.hasActivePack(this)
        if (msg != null) statusText.text = msg
    }

    private fun launchGame() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    /** 打开创意工坊（Mod 管理） */
    private fun openMods() {
        if (!GamePack.hasActivePack(this)) {
            Toast.makeText(this, "请先导入游戏包，Mod 需要装在游戏包目录下", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, ModActivity::class.java))
    }

    private fun confirmDelete() {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除游戏包")
            .setMessage("将删除已导入的包以释放空间。确定吗？")
            .setPositiveButton("删除") { _, _ ->
                GamePack.uninstall(this)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}