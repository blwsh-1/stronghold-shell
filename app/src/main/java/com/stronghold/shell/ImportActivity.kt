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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 「游戏包管理」界面 —— 启动器的入口页。
 *
 * 功能：
 *   - 首次启动 / 无包时：显示「导入游戏包」按钮
 *   - 已有包时：显示版本信息 + 「启动游戏」+「重新导入」+「删除包」
 *   - 【阶段6】若当前包是 Forge（带 workshop 装载器）：
 *       显示 mod 管理区（导入 mod.zip / 列出已装 mod / 逐个删除）
 *
 * 导入流程：
 *   游戏包：系统文件选择器选 zip → GamePack.installFromUri → 启动
 *   mod   ：系统文件选择器选 mod.zip → GamePack.installModFromUri → 重启生效
 */
class ImportActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnImport: Button
    private lateinit var btnLaunch: Button
    private lateinit var btnDelete: Button
    private lateinit var logText: TextView

    // ===== mod 管理区（阶段6，仅 Forge 包显示）=====
    private lateinit var modSection: LinearLayout
    private lateinit var modTitle: TextView
    private lateinit var modListBox: LinearLayout
    private lateinit var btnImportMod: Button

    /** 系统文件选择器：游戏包 zip */
    private val pickZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> doInstall(uri) }
        }
    }

    /** 系统文件选择器：mod zip（阶段6） */
    private val pickModZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> doInstallMod(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        buildUi()
        refreshUi()

        // 已导入游戏包时，从桌面图标进入即自动启动游戏（一键开玩）。
        // 仅首次创建时触发（savedInstanceState == null）；
        // 从游戏页按返回键回到本页时不会再次弹回，避免死循环。
        if (savedInstanceState == null && GamePack.hasActivePack(this)) {
            launchGame()
        }
    }

    /** 纯代码构建界面（避免动 res 里其它文件） */
    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
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
        btnDelete = Button(this).apply {
            text = "删除已导入的包"
            setOnClickListener { confirmDelete() }
        }

        // ===== mod 管理区 =====
        modSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = LinearLayout.GONE
            setPadding(0, dp(16), 0, 0)
        }
        modTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#e6edf3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        btnImportMod = Button(this).apply {
            text = "导入 mod (.zip)"
            setOnClickListener { pickModFile() }
        }
        modListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        modSection.addView(modTitle)
        modSection.addView(btnImportMod)
        modSection.addView(modListBox)

        logText = TextView(this).apply {
            setTextColor(Color.parseColor("#7d8590"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(16), 0, 0)
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }

        content.addView(title)
        content.addView(subtitle)
        content.addView(statusText)
        content.addView(progressBar)
        content.addView(btnLaunch, lp)
        content.addView(btnImport, lp)
        content.addView(btnDelete, lp)
        content.addView(modSection)
        content.addView(logText)

        // 内容可能超过一屏（mod 多时），套一层 ScrollView
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0d1117"))
            addView(content)
        }
        setContentView(scroll)
    }

    /** 根据「有没有包」切换按钮/文案 */
    private fun refreshUi() {
        val info = GamePack.readInfo(this)
        val hasPack = GamePack.hasActivePack(this)

        if (hasPack && info != null) {
            statusText.text = "已导入游戏包\n版本：${info.optString("version", "unknown")}\n" +
                "入口：${info.optString("entry", "server/index.js")}"
            btnLaunch.isEnabled = true
            btnImport.text = "重新导入（覆盖）"
            btnDelete.isEnabled = true
        } else if (hasPack) {
            statusText.text = "已导入游戏包（版本信息缺失）"
            btnLaunch.isEnabled = true
            btnImport.text = "重新导入（覆盖）"
            btnDelete.isEnabled = true
        } else {
            statusText.text = "尚未导入游戏包\n\n请选择上游发布的完整包 zip\n" +
                "（Stronghold-Protocol-vX.Y.Z.zip）"
            btnLaunch.isEnabled = false
            btnImport.text = "导入游戏包 (.zip)"
            btnDelete.isEnabled = false
        }

        refreshModSection()
        logText.text = "日志：${FileLogger.readAll().takeLast(300)}"
    }

    /** 【阶段6】刷新 mod 管理区：只有 Forge 包才显示 */
    private fun refreshModSection() {
        val isForge = GamePack.hasActivePack(this) &&
            GamePack.detectKind(this) == GamePack.PackKind.FORGE
        if (!isForge) {
            modSection.visibility = LinearLayout.GONE
            return
        }
        modSection.visibility = LinearLayout.VISIBLE

        val mods = GamePack.listMods(this)
        modTitle.text = "工坊 mod（Forge）· 已装 ${mods.size} 个"

        modListBox.removeAllViews()
        if (mods.isEmpty()) {
            modListBox.addView(TextView(this).apply {
                text = "（暂无 mod，导入后需重启游戏生效）"
                setTextColor(Color.parseColor("#7d8590"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            return
        }

        for (m in mods) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val label = TextView(this).apply {
                text = "${m.name}  v${m.version}\n${m.id}"
                setTextColor(Color.parseColor("#c9d1d9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = Button(this).apply {
                text = "删除"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { confirmDeleteMod(m) }
            }
            row.addView(label)
            row.addView(del)
            modListBox.addView(row)
        }
    }

    private fun pickZipFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        try {
            pickZip.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 【阶段6】选 mod.zip */
    private fun pickModFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        try {
            pickModZip.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doInstall(uri: android.net.Uri) {
        setBusy(true, "正在解压安装，请勿退出…")
        io.execute {
            var lastShown = 0
            val dir = GamePack.installFromUri(this, uri) { count ->
                if (count - lastShown >= 200) {
                    lastShown = count
                    ui.post {
                        progressBar.progress = (count / 500) % 100
                        statusText.text = "正在解压安装…（已处理 $count 个文件）"
                    }
                }
            }
            ui.post {
                setBusy(false, null)
                if (dir != null) {
                    Toast.makeText(this, "导入成功！", Toast.LENGTH_SHORT).show()
                    refreshUi()
                    launchGame()
                } else {
                    val log = FileLogger.readAll().lines().lastOrNull { it.contains("导入失败") }
                        ?: "导入失败"
                    Toast.makeText(this, log, Toast.LENGTH_LONG).show()
                    refreshUi()
                }
            }
        }
    }

    /** 【阶段6】安装 mod */
    private fun doInstallMod(uri: android.net.Uri) {
        setBusy(true, "正在导入 mod…")
        io.execute {
            val dest = GamePack.installModFromUri(this, uri)
            ui.post {
                setBusy(false, null)
                if (dest != null) {
                    // Forge 的 mod 在 Node 启动时装载 → 提示重启生效
                    android.app.AlertDialog.Builder(this)
                        .setTitle("mod 导入成功")
                        .setMessage("mod 已安装：${dest.name}\n\n" +
                            "mod 在游戏启动时装载。请先退出游戏，再重新点开游戏图标生效。")
                        .setPositiveButton("知道了", null)
                        .show()
                    refreshUi()
                } else {
                    val log = FileLogger.readAll().lines().lastOrNull { it.contains("mod") }
                        ?: "mod 导入失败"
                    Toast.makeText(this, log, Toast.LENGTH_LONG).show()
                    refreshUi()
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, msg: String?) {
        progressBar.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        btnImport.isEnabled = !busy
        btnLaunch.isEnabled = !busy && GamePack.hasActivePack(this)
        btnDelete.isEnabled = !busy && GamePack.hasActivePack(this)
        btnImportMod.isEnabled = !busy
        if (msg != null) statusText.text = msg
    }

    private fun launchGame() {
        startActivity(Intent(this, MainActivity::class.java))
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

    /** 【阶段6】删除单个 mod */
    private fun confirmDeleteMod(m: GamePack.ModInfo) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除 mod")
            .setMessage("将彻底移除 mod「${m.name}」。\n\n" +
                "（Forge 规矩：卸载 = 目录彻底不在）\n" +
                "重启游戏后生效。")
            .setPositiveButton("删除") { _, _ ->
                GamePack.uninstallMod(this, m.id)
                Toast.makeText(this, "已删除 ${m.id}", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}