package com.stronghold.shell

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * 主界面 —— 启动器首页（PCL2 骨架 × 明日方舟美术）。
 *
 * 页面：主页 / 插件 / 设置（底部导航切换）
 * 设计依据：docs/UI-REDESIGN-PLAN.md
 */
class ImportActivity : AppCompatActivity() {

    companion object {
        private const val NAV_HOME = 0
        private const val NAV_PLUGIN = 1
        private const val NAV_SETTINGS = 2
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var navIndex = NAV_HOME

    private lateinit var contentArea: LinearLayout
    private lateinit var navButtons: List<TextView>

    private var updateInfo: UpdateChecker.ReleaseInfo? = null
    private var checkingUpdate = false
    private var downloading = false
    private var downloadLabel: TextView? = null
    private var downloadBar: View? = null
    /** 最近一次测速排好序的镜像（下载时优先用） */
    private var preferredMirrors: List<Pair<String, String>> = emptyList()
    /** 本次准备下载哪种包（增量 / 完整 / 精简） */
    private var preferredKind: UpdateChecker.PackKind = UpdateChecker.PackKind.FULL
    private var downloadCard: LinearLayout? = null

    private val pickPackZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { doInstallPack(it) } }

    private val pickPluginZipResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { doInstallPlugin(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        buildShell()

        // 首次进入总是停在启动器界面（用户要看新 UI / 版本列表）。
        // 只有用户主动点「启动游戏」才跳转 —— 之前的自动跳转会让启动器一闪而过。
        if (savedInstanceState == null && Settings.load(this).autoCheckPack) {
            checkUpdate(silent = true)
        }
    }

    // ==================== 骨架 ====================

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG_PAGE)
        }
        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            setBackgroundColor(UiKit.BG_PAGE)
        }
        contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(this@ImportActivity, 16f)
            setPadding(p, p, p, p)
        }
        scroll.addView(contentArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll)
        root.addView(buildBottomNav())
        setContentView(root)
        switchNav(NAV_HOME)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiKit.BG_CARD)
            val ph = UiKit.dp(this@ImportActivity, 16f)
            val pv = UiKit.dp(this@ImportActivity, 12f)
            setPadding(ph, pv, ph, pv)
        }
        bar.addView(View(this).apply {
            setBackgroundColor(UiKit.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@ImportActivity, 4f), UiKit.dp(this@ImportActivity, 26f)
            ).apply { rightMargin = UiKit.dp(this@ImportActivity, 10f) }
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(UiKit.label(this, "卫戍协议：盟约", 17f, UiKit.TEXT_MAIN, true))
        box.addView(UiKit.label(this, "STRONGHOLD PROTOCOL · 启动器", 9f, UiKit.TEXT_DIM))
        bar.addView(box)
        bar.addView(UiKit.chip(this, "v" + BuildConfig.VERSION_NAME, UiKit.ACCENT_DIM))
        return bar
    }

    private fun buildBottomNav(): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(View(this).apply {
            setBackgroundColor(UiKit.LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiKit.BG_CARD)
            val pv = UiKit.dp(this@ImportActivity, 6f)
            setPadding(0, pv, 0, pv)
        }
        navButtons = listOf("主页" to NAV_HOME, "插件" to NAV_PLUGIN, "设置" to NAV_SETTINGS).map { (n, i) ->
            val tv = TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val pv = UiKit.dp(this@ImportActivity, 10f)
                setPadding(0, pv, 0, pv)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchNav(i) }
            }
            nav.addView(tv)
            tv
        }
        wrapper.addView(nav)
        return wrapper
    }

    private fun switchNav(idx: Int) {
        navIndex = idx
        navButtons.forEachIndexed { i, tv ->
            val on = i == idx
            tv.setTextColor(if (on) UiKit.ACCENT else UiKit.TEXT_DIM)
            tv.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        }
        contentArea.removeAllViews()
        when (idx) {
            NAV_HOME -> buildHome()
            NAV_PLUGIN -> buildPluginPage()
            NAV_SETTINGS -> buildSettingsPage()
        }
    }

    private fun cardLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiKit.dp(this@ImportActivity, 10f) }

    // ==================== 主页 ====================

    private fun buildHome() {
        val c = contentArea
        c.addView(UiKit.sectionHeader(this, "游戏版本", "01"))
        c.addView(UiKit.spacer(this, 10f))

        val packs = PackManager.listPacks(this)
        val activeId = PackManager.activeId(this)

        if (packs.isEmpty()) {
            val card = UiKit.card(this)
            card.addView(UiKit.label(this, "尚未安装游戏包", 15f, UiKit.TEXT_MAIN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this,
                "可从 GitHub 在线下载最新完整包，或导入本地 zip。\n完整包约 800MB，自带全部素材。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
        } else {
            for (p in packs) {
                c.addView(buildPackCard(p, p.id == activeId), cardLp())
            }
        }

        c.addView(UiKit.sectionHeader(this, "操作", "02"))
        c.addView(UiKit.spacer(this, 10f))

        if (activeId != null) {
            val lb = UiKit.button(this, "▶  启动游戏", true) { startGame() }
            lb.layoutParams = cardLp()
            c.addView(lb)
        }

        val db = UiKit.button(this, "⬇  下载最新完整包", false) { onDownloadLatest() }
        db.layoutParams = cardLp()
        c.addView(db)

        val ib = UiKit.button(this, "📁  从本地导入 zip", false) { pickLocalZip() }
        ib.layoutParams = cardLp()
        c.addView(ib)

        // 多人游戏入口：只在 lab 版显示（主版本不暴露未完成功能）
        if (BuildConfig.ENABLE_MULTIPLAYER) {
            c.addView(UiKit.spacer(this, 6f))
            val mb = UiKit.button(this, "🌐  多人游戏（实验）", false) { openMultiplayer() }
            mb.layoutParams = cardLp()
            c.addView(mb)
        }

        val dlCard = UiKit.card(this)
        dlCard.visibility = View.GONE
        val lbl = UiKit.label(this, "", 12f, UiKit.TEXT_SUB)
        val bar = UiKit.progressBar(this, 6f)
        dlCard.addView(lbl)
        dlCard.addView(UiKit.spacer(this, 8f))
        dlCard.addView(bar)
        c.addView(dlCard, cardLp())
        downloadLabel = lbl
        downloadCard = dlCard
        downloadBar = bar

        val info = updateInfo
        val current = packs.firstOrNull { it.id == activeId }
        if (info != null && current != null && UpdateChecker.isNewer(info.version, current.version)) {
            c.addView(UiKit.divider(this, "UPDATE AVAILABLE"))
            val up = UiKit.card(this, highlight = true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(UiKit.dot(this, UiKit.WARN))
            row.addView(UiKit.label(this, "发现新版本 v${info.version}", 14f, UiKit.WARN, true))
            up.addView(row)
            up.addView(UiKit.spacer(this, 6f))
            up.addView(UiKit.label(
                this, "当前 v${current.version}，最新 v${info.version}。点上方「下载最新完整包」升级。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(up, cardLp())
        }

        c.addView(UiKit.divider(this, "SYSTEM LOG"))
        c.addView(UiKit.label(
            this,
            "共 ${packs.size} 个版本 · 可用空间 ${filesDir.usableSpace / 1048576} MB",
            11f, UiKit.TEXT_DIM
        ))
        c.addView(UiKit.spacer(this, 20f))
    }

    private fun buildPackCard(p: PackManager.PackEntry, active: Boolean): View {
        val card = UiKit.card(this, active)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(UiKit.dot(this, if (active) UiKit.ACCENT else UiKit.TEXT_DIM))
        row.addView(UiKit.label(this, "v${p.version}", 16f, UiKit.TEXT_MAIN, true))
        row.addView(UiKit.hSpacer(this, 8f))
        row.addView(UiKit.chip(this, if (p.kind == "FORGE") "FORGE" else "原版",
            if (p.kind == "FORGE") UiKit.WARN else UiKit.ACCENT_DIM))
        if (active) {
            row.addView(UiKit.hSpacer(this, 6f))
            row.addView(UiKit.chip(this, "使用中", UiKit.ACCENT))
        }
        card.addView(row)
        card.addView(UiKit.spacer(this, 6f))
        card.addView(UiKit.label(this, "${p.sizeMb} MB · ${p.id}", 11f, UiKit.TEXT_DIM))
        card.addView(UiKit.spacer(this, 12f))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!active) {
            val use = UiKit.button(this, "切换到此版本", true) {
                if (PackManager.setActive(this, p.id)) {
                    toast("已切换到 v${p.version}，重启游戏生效")
                    switchNav(NAV_HOME)
                } else toast("切换失败（包不完整）")
            }
            use.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            btnRow.addView(use)
            btnRow.addView(UiKit.hSpacer(this, 8f))
        }
        val del = UiKit.button(this, "删除", false) { confirmDeletePack(p) }
        del.layoutParams = if (active)
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f)
        btnRow.addView(del)
        card.addView(btnRow)
        return card
    }

    private fun confirmDeletePack(p: PackManager.PackEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除 v${p.version}？")
            .setMessage("将删除该版本全部文件（约 ${p.sizeMb} MB）。不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    val ok = PackManager.removePack(this@ImportActivity, p.id)
                    ui.post {
                        toast(if (ok) "已删除 v${p.version}" else "删除失败")
                        switchNav(NAV_HOME)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ==================== 插件页 ====================

    private fun buildPluginPage() {
        val c = contentArea
        val gameRoot = PackManager.activeDir(this)

        c.addView(UiKit.sectionHeader(this, "插件管理", "01"))
        c.addView(UiKit.spacer(this, 10f))

        if (gameRoot == null) {
            val card = UiKit.card(this)
            card.addView(UiKit.label(this, "尚未安装游戏版本", 15f, UiKit.TEXT_MAIN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this, "请先在「主页」安装游戏包，安装后才能管理插件。", 12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
            return
        }

        val kind = PackManager.detectKindAt(gameRoot)
        if (kind == "FORGE") {
            c.addView(UiKit.divider(this, "FORGE MODE"))
            val card = UiKit.card(this, highlight = true)
            card.addView(UiKit.label(this, "当前为 Forge 版本", 14f, UiKit.WARN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this,
                "Forge 使用 workshop/ 目录装 mod，与 0.3.0+ 官方插件体系不兼容。\n" +
                "如需使用官方插件，请切换到原版游戏包。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
            return
        }

        // ---- 已装插件列表 ----
        val plugins = PluginManager.list(gameRoot)
        val curVer = PackManager.readVersionFrom(gameRoot) ?: "?"
        c.addView(UiKit.label(
            this,
            "当前版本 v$curVer · 已装 ${plugins.size} 个",
            11f, UiKit.TEXT_DIM
        ))
        c.addView(UiKit.spacer(this, 10f))

        if (plugins.isEmpty()) {
            val card = UiKit.card(this)
            card.addView(UiKit.label(this, "还没有安装任何插件", 14f, UiKit.TEXT_MAIN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this,
                "插件是 .zip 文件，内含 plugin.json 清单。\n可从社区工坊下载后在这里导入。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
        } else {
            for (p in plugins) {
                c.addView(buildPluginCard(gameRoot, p), cardLp())
            }
        }

        c.addView(UiKit.sectionHeader(this, "操作", "02"))
        c.addView(UiKit.spacer(this, 10f))

        val add = UiKit.button(this, "＋  从 zip 安装插件", true) { pickPluginZip() }
        add.layoutParams = cardLp()
        c.addView(add)

        val openDir = UiKit.button(this, "📂  查看插件目录说明", false) {
            AlertDialog.Builder(this)
                .setTitle("插件目录")
                .setMessage(
                    "${PluginManager.pluginsDir(gameRoot)?.absolutePath}\n\n" +
                    "每个插件是一个子文件夹，内含 plugin.json。\n" +
                    "以 _ 开头的文件夹不会被加载（官方模板即 _template）。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
        openDir.layoutParams = cardLp()
        c.addView(openDir)

        // 脚本开关状态提示
        val prefs = Settings.load(this)
        val scriptsOn = prefs.pluginScripts == "sandbox"
        c.addView(UiKit.divider(this, "SCRIPT POLICY"))
        val pol = UiKit.card(this, scriptsOn)
        val prow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prow.addView(UiKit.dot(this, if (scriptsOn) UiKit.WARN else UiKit.ACCENT))
        prow.addView(UiKit.label(
            this,
            if (scriptsOn) "脚本插件已启用（沙箱）" else "脚本插件已禁用",
            13f, if (scriptsOn) UiKit.WARN else UiKit.TEXT_MAIN, true
        ))
        pol.addView(prow)
        pol.addView(UiKit.spacer(this, 6f))
        pol.addView(UiKit.label(
            this,
            "script / trusted 类插件会执行代码。上游默认关闭，" +
            "可在「设置 → 插件脚本」中开启沙箱模式。",
            11f, UiKit.TEXT_SUB
        ))
        c.addView(pol, cardLp())
        c.addView(UiKit.spacer(this, 20f))
    }

    private fun buildPluginCard(gameRoot: File, p: PluginManager.PluginInfo): View {
        val card = UiKit.card(this, highlight = !p.valid)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(UiKit.dot(this, if (p.valid) UiKit.ACCENT else UiKit.DANGER))
        row.addView(UiKit.label(this, p.name.ifEmpty { p.id }, 15f, UiKit.TEXT_MAIN, true))
        row.addView(UiKit.hSpacer(this, 8f))
        row.addView(UiKit.chip(this, "v${p.version}", UiKit.ACCENT_DIM))
        card.addView(row)

        card.addView(UiKit.spacer(this, 6f))
        card.addView(UiKit.label(this, p.classLabel, 11f, UiKit.TEXT_SUB))
        card.addView(UiKit.label(this, "id: ${p.id}  ·  api${p.api}", 10f, UiKit.TEXT_DIM))
        if (p.app.isNotEmpty()) {
            card.addView(UiKit.label(this, "兼容: ${p.app}", 10f, UiKit.TEXT_DIM))
        }
        if (!p.valid && p.error != null) {
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(this, "⚠ ${p.error}", 11f, UiKit.DANGER))
        }

        card.addView(UiKit.spacer(this, 10f))
        val dispName = p.name.ifEmpty { p.id }
        val del = UiKit.button(this, "卸载", false) {
            AlertDialog.Builder(this)
                .setTitle("卸载 $dispName？")
                .setMessage("将删除插件目录及其配置，不可撤销。")
                .setPositiveButton("卸载") { _, _ ->
                    io.execute {
                        val ok = PluginManager.uninstall(gameRoot, p.id)
                        ui.post {
                            toast(if (ok) "已卸载 ${p.id}" else "卸载失败")
                            if (ok) switchNav(NAV_PLUGIN)
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        del.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        card.addView(del)
        return card
    }

    // ==================== 设置页 ====================

    private fun buildSettingsPage() {
        val c = contentArea
        val cur = Settings.load(this)

        c.addView(UiKit.sectionHeader(this, "服务", "01"))
        c.addView(UiKit.spacer(this, 10f))

        c.addView(settingRow(
            "端口", cur.port.toString(),
            "游戏服务器监听端口，默认 3000"
        ) { editNumber("端口", cur.port, 1024, 65535) { v ->
            val p = Settings.load(this); p.port = v; Settings.save(this, p)
            toast("端口改为 " + v + "（重启游戏生效）"); switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "监听地址", cur.host,
            ":: 为全部网卡（支持局域网联机）"
        ) { chooseString("监听地址", cur.host, listOf("::", "0.0.0.0", "127.0.0.1")) { v ->
            val p = Settings.load(this); p.host = v; Settings.save(this, p)
            toast("监听地址改为 " + v); switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(UiKit.sectionHeader(this, "玩法", "02"))
        c.addView(UiKit.spacer(this, 10f))

        c.addView(settingRow(
            "战斗结算", if (cur.combatMode == "server") "服务端判定" else "客户端判定",
            "服务端判定更严格，用于联机防作弊"
        ) { chooseString("战斗结算", cur.combatMode, listOf("client", "server"),
            listOf("客户端判定", "服务端判定")) { v ->
            val p = Settings.load(this); p.combatMode = v; Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "存档校验", if (cur.verifyMode == "off") "关闭"
                       else if (cur.verifyMode == "all") "全量" else "抽样",
            "SP_VERIFY，校验存档合法性"
        ) { chooseString("存档校验", cur.verifyMode, listOf("off", "sample", "all"),
            listOf("关闭", "抽样", "全量")) { v ->
            val p = Settings.load(this); p.verifyMode = v; Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "插件脚本", if (cur.pluginScripts == "sandbox") "沙箱启用" else "禁用",
            "script / trusted 类插件是否运行代码"
        ) { chooseString("插件脚本", cur.pluginScripts, listOf("off", "sandbox"),
            listOf("禁用", "沙箱启用")) { v ->
            val p = Settings.load(this); p.pluginScripts = v; Settings.save(this, p)
            toast(if (v == "sandbox") "已启用沙箱脚本" else "已禁用脚本插件")
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(UiKit.sectionHeader(this, "其他", "03"))
        c.addView(UiKit.spacer(this, 10f))

        c.addView(settingRow(
            "启动时检查游戏包更新", if (cur.autoCheckPack) "开" else "关",
            "打开启动器时查询上游 GitHub 最新游戏包版本"
        ) { chooseString("游戏包更新", if (cur.autoCheckPack) "on" else "off",
            listOf("on", "off"), listOf("开", "关")) { v ->
            val p = Settings.load(this); p.autoCheckPack = (v == "on"); Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "启动时检查启动器更新", if (cur.autoCheckApp) "开" else "关",
            "打开启动器时查询本项目最新版本（含一键下载安装）"
        ) { chooseString("启动器更新", if (cur.autoCheckApp) "on" else "off",
            listOf("on", "off"), listOf("开", "关")) { v ->
            val p = Settings.load(this); p.autoCheckApp = (v == "on"); Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(UiKit.divider(this, "TOOLS"))
        val tools = UiKit.card(this)
        val dr = UiKit.button(this, "🔧  运行环境诊断", true) { runDoctor() }
        tools.addView(dr)
        tools.addView(UiKit.spacer(this, 8f))
        val lg = UiKit.button(this, "📄  查看运行日志", false) { showLog() }
        tools.addView(lg)
        c.addView(tools, cardLp())

        c.addView(UiKit.divider(this, "ABOUT"))
        val about = UiKit.card(this)
        about.addView(UiKit.label(this, "Stronghold Shell  v" + BuildConfig.VERSION_NAME,
            14f, UiKit.TEXT_MAIN, true))
        about.addView(UiKit.spacer(this, 6f))
        about.addView(UiKit.label(
            this,
            "游戏本体来自上游 sganggs/Stronghold-Protocol (GPL-3.0)。\n" +
            "本壳仅提供安卓运行环境，不包含游戏素材。",
            11f, UiKit.TEXT_SUB
        ))
        c.addView(about, cardLp())
        c.addView(UiKit.spacer(this, 20f))
    }

    /** 一行设置项：标题 + 值 + 说明，点击触发动作 */
    private fun settingRow(
        title: String,
        value: String,
        desc: String,
        onClick: () -> Unit
    ): View {
        val card = UiKit.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(UiKit.label(this, title, 14f, UiKit.TEXT_MAIN, true))
        col.addView(UiKit.spacer(this, 3f))
        col.addView(UiKit.label(this, desc, 10f, UiKit.TEXT_DIM))
        row.addView(col)
        row.addView(UiKit.chip(this, value, UiKit.ACCENT))
        card.addView(row)
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { onClick() }
        return card

    }

    private fun editNumber(title: String, cur: Int, min: Int, max: Int, onOk: (Int) -> Unit) {
        val et = EditText(this).apply {
            setText(cur.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                if (v == null || v < min || v > max) toast("请输入 $min ~ $max 之间的数字")
                else onOk(v)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从固定选项中选择（values 为存储值，labels 为展示值，可省略） */
    private fun chooseString(
        title: String,
        cur: String,
        values: List<String>,
        labels: List<String>? = null,
        onOk: (String) -> Unit
    ) {
        val shown = labels ?: values
        val idx = values.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(shown.toTypedArray(), idx) { d, which ->
                d.dismiss()
                onOk(values[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 动作：安装 / 启动 ====================

    private fun pickLocalZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        pickPackZip.launch(i)
    }
    /** 导入本地游戏包 zip（后台解压 → 注册版本） */
    private fun doInstallPack(uri: Uri) {
        toast("正在导入，请勿退出…")
        io.execute {
            var tmp: File? = null
            try {
                val root = PackManager.packsRoot(this@ImportActivity)
                val stage = File(root, ".incoming")
                stage.deleteRecursively()
                stage.mkdirs()
                tmp = stage

                ZipInstaller.extract(this@ImportActivity, uri, stage)
                // 判断是否有单层顶层目录（GitHub zip 通常含 Stronghold-Protocol/）
                val kids = stage.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
                val gameDir: File = if (kids.size == 1 && kids[0].isDirectory) kids[0] else stage

                val version = PackManager.readVersionFrom(gameDir)
                if (version == null) {
                    ui.post { toast("不是有效的游戏包（缺少 package.json）") }
                    return@execute
                }
                if (!File(gameDir, "server").exists() && !File(gameDir, "index.js").exists()) {
                    ui.post { toast("包结构不完整（缺少 server/）") }
                    return@execute
                }

                val id = PackManager.register(
                    this@ImportActivity, gameDir, version, source = "local", setActive = true
                )
                FileLogger.log("导入游戏包成功：$id (v$version)")
                ui.post {
                    toast("导入成功：v$version")
                    updateInfo = null
                    switchNav(NAV_HOME)
                }
            } catch (e: Exception) {
                FileLogger.log("导入游戏包失败：${e.message}")
                val reason = e.message ?: "未知错误"
                ui.post { toast("导入失败：$reason") }
            } finally {
                tmp?.deleteRecursively()
            }
        }
    }

    /** 从 zip 安装插件（复用 PluginManager 的解压与校验） */
    private fun doInstallPlugin(uri: Uri) {
        val gameRoot = PackManager.activeDir(this)
        if (gameRoot == null) {
            toast("请先安装游戏包")
            return
        }
        toast("正在安装插件…")
        io.execute {
            val dir = try {
                PluginManager.installFromUri(gameRoot, uri, this@ImportActivity)
            } catch (e: Exception) {
                FileLogger.log("安装插件异常：${e.message}")
                null
            }
            ui.post {
                if (dir != null) {
                    toast("插件已安装：${dir.name}")
                    switchNav(NAV_PLUGIN)
                } else {
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("插件安装失败")
                        .setMessage(
                            "可能原因：\n" +
                            "· zip 里没有 plugin.json\n" +
                            "· 文件夹名与插件 id 不一致\n" +
                            "· id 不符合规范（2~32 位小写字母/数字/-）\n" +
                            "· 当前游戏版本太旧（插件需 0.3.0+）"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun pickPluginZip() {
        if (PackManager.activeDir(this) == null) {
            toast("请先安装游戏包")
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        pickPluginZipResult.launch(i)
    }

    private fun startGame() {
        if (PackManager.activeId(this) == null) {
            AlertDialog.Builder(this)
                .setTitle("还没有游戏包")
                .setMessage("需要先下载或导入一个游戏版本，才能启动。")
                .setPositiveButton("去下载") { _, _ -> onDownloadLatest() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val it = Intent(this, MainActivity::class.java)
        startActivity(it)
    }

    /** 打开多人游戏服务器列表 */
    private fun openMultiplayer() {
        startActivity(Intent(this, MultiplayerActivity::class.java))
    }

    // ==================== 动作：下载最新包 ====================

    private fun onDownloadLatest() {
        if (downloading) {
            toast("正在下载中…")
            return
        }
        val info = updateInfo
        if (info == null) {
            toast("正在查询最新版本…")
            checkUpdate(silent = false, thenDownload = true)
            return
        }
        proceedDownload(info)
    }

    /**
     * 决定下哪种包：
     *  - 本地没有这个版本  -> 完整包
     *  - 本地已有这个版本  -> 生成增量升级清单（13MB 级），升级失败可回退完整包
     */
    private fun proceedDownload(info: UpdateChecker.ReleaseInfo) {
        val packs = PackManager.listPacks(this)
        val same = packs.firstOrNull { it.version == info.version }
        if (same == null) {
            val asset = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
            if (asset == null) {
                toast("该版本没有可下载的完整包")
                return
            }
            preferredKind = UpdateChecker.PackKind.FULL
            confirmDownload(info, asset)
            return
        }
        // 已装同版本 -> 走增量更新
        preferredKind = UpdateChecker.PackKind.UPDATE
        confirmIncremental(info, same)
    }

    private fun confirmDownload(info: UpdateChecker.ReleaseInfo, asset: UpdateChecker.Asset) {
        val packs = PackManager.listPacks(this)
        val same = packs.firstOrNull { it.version == info.version }
        val sizeMbText = "%.1f".format(asset.sizeMb)
        val msg = buildString {
            append("版本：v${info.version}\n")
            append("文件：${asset.name}\n")
            append("大小：$sizeMbText MB\n")
            append("下载位置：应用专用目录（不占媒体库）\n\n")
            if (same != null) append("注意：该版本已安装，将覆盖。\n")
            append("完整包较大，建议在 Wi-Fi 下进行。")
        }
        AlertDialog.Builder(this)
            .setTitle("下载 v${info.version}")
            .setMessage(msg)
            .setNeutralButton("改用增量包") { _, _ ->
                if (same == null) {
                    toast("本地还没有这个版本，无法增量升级")
                } else {
                    preferredKind = UpdateChecker.PackKind.UPDATE
                    confirmIncremental(info, same)
                }
            }
            .setPositiveButton("开始下载") { _, _ -> doDownload(info, asset) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 增量更新（十几 MB 级升级） ====================

    /**
     * 增量升级确认框。
     *
     * 【为什么要增量】上游每个版本提供 -update.zip（纯代码补丁）：
     *   v0.3.1 完整包 806MB / 增量包 13MB，相差 61 倍。
     * 【风险】增量包只覆盖「有改动的文件」。若某版把新素材压进完整包，
     *   增量升级后会缺文件 —— 所以解压完必须校验版本号，不符就提示改用完整包。
     */
    private fun confirmIncremental(info: UpdateChecker.ReleaseInfo, same: PackManager.PackEntry) {
        val asset = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.UPDATE)
        if (asset == null) {
            toast("该版本没有增量包，改用完整包")
            val full = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
            preferredKind = UpdateChecker.PackKind.FULL
            if (full != null) confirmDownload(info, full) else toast("也没有完整包")
            return
        }
        val sizeText = if (asset.sizeBytes > 0) {
            "%.1f MB".format(asset.sizeMb)
        } else {
            "约 15 MB（大小未知）"
        }
        val msg = buildString {
            append("本地已有 v${same.version}，可以直接增量升级：")
            append("\n\n")
            append("方式：增量覆盖（只下载改动的代码文件）")
            append("\n")
            append("大小：$sizeText   （完整包约 800MB）")
            append("\n")
            append("目标：${same.dir.name}（原地覆盖）")
            append("\n\n")
            append("升级前会自动备份旧包；若升级后版本号不对，会提示你改用完整包。")
        }
        AlertDialog.Builder(this)
            .setTitle("增量升级到 v${info.version}")
            .setMessage(msg)
            .setPositiveButton("开始增量升级") { _, _ -> doIncremental(info, asset, same) }
            .setNegativeButton("取消", null)
            .setNeutralButton("改用完整包") { _, _ ->
                val full = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
                preferredKind = UpdateChecker.PackKind.FULL
                if (full != null) confirmDownload(info, full) else toast("该版本没有完整包")
            }
            .show()
    }

    private fun doIncremental(
        info: UpdateChecker.ReleaseInfo,
        asset: UpdateChecker.Asset,
        same: PackManager.PackEntry
    ) {
        downloading = true
        downloadCard?.visibility = View.VISIBLE
        downloadLabel?.text = "准备增量升级…"

        io.execute {
            val dir = PackDownloader.downloadDir(this@ImportActivity)
            val dest = File(dir, asset.name)
            var lastUi = 0L

            // 先测速，挑最快的镜像（6 镜像并发，几秒出结果）
            if (preferredMirrors.isEmpty()) {
                ui.post { downloadLabel?.text = "正在测试镜像速度…" }
                preferredMirrors = try {
                    PackDownloader.probeMirrors(asset.downloadUrl)
                } catch (e: Exception) {
                    emptyList()
                }
                FileLogger.log("镜像测速完成：" + preferredMirrors.joinToString { it.second })
            }

            val ok = PackDownloader.download(
                url = asset.downloadUrl,
                dest = dest,
                expectedSize = asset.sizeBytes,
                listener = object : PackDownloader.Listener {
                    override fun onProgress(downloaded: Long, total: Long) {
                        val now = System.currentTimeMillis()
                        if (now - lastUi < 300) return
                        lastUi = now
                        val tot = if (total > 0) total else asset.sizeBytes
                        val pctInt = if (tot > 0) (downloaded * 100.0 / tot).toInt() else 0
                        val dlMb = "%.1f".format(downloaded / 1048576.0)
                        val totMb = "%.1f".format(tot / 1048576.0)
                        ui.post {
                            downloadLabel?.text = "增量包 $dlMb / $totMb MB  ($pctInt%)"
                            (downloadBar as? View)?.let { v ->
                                try {
                                    val m = v.javaClass.getMethod("setProgress", Float::class.java)
                                    m.invoke(v, if (tot > 0) (downloaded.toFloat() / tot) else 0f)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                },
                preferredMirrors = preferredMirrors
            )

            dest.delete()
            if (!ok) {
                downloading = false
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("增量包下载失败，请检查网络后重试")
                }
                return@execute
            }

            // ---- 备份 -> 覆盖解压 -> 版本校验 ----
            val backup = File(same.dir.parentFile, same.dir.name + ".bak")
            var backupMade = false
            try {
                ui.post { downloadLabel?.text = "正在应用增量补丁…" }
                backup.deleteRecursively()
                backupMade = same.dir.renameTo(backup)
                if (!backupMade) {
                    FileLogger.log("警告：旧包备份失败，直接在原目录覆盖")
                }
                val target = same.dir
                if (backupMade) target.mkdirs()
                ZipInstaller.extractFile(dest, target)

                val newVer = PackManager.readVersionFrom(target)
                FileLogger.log("增量升级完成：期望 ${info.version}，实际读取 $newVer")

                if (newVer == null || !newVer.contains(info.version.removePrefix("v"))) {
                    throw IllegalStateException(
                        "增量包应用后版本号不符（读到 " + (newVer ?: "无") + "），请改用完整包"
                    )
                }

                backup.deleteRecursively()
                PackManager.register(
                    this@ImportActivity, target, newVer, source = "github", setActive = true
                )
                downloading = false
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("增量升级完成：v$newVer")
                    updateInfo = null
                    switchNav(NAV_HOME)
                }
            } catch (e: Exception) {
                FileLogger.log("增量升级失败：" + e.message)
                try {
                    if (backupMade) {
                        same.dir.deleteRecursively()
                        backup.renameTo(same.dir)
                    }
                } catch (_: Exception) {
                }
                downloading = false
                val reason = e.message ?: "未知错误"
                ui.post {
                    downloadCard?.visibility = View.GONE
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("增量升级失败")
                        .setMessage(reason + "\n\n" + "已回滚到原版本，可以改用完整包重试。")
                        .setPositiveButton("用完整包") { _, _ ->
                            val full = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
                            preferredKind = UpdateChecker.PackKind.FULL
                            if (full != null) confirmDownload(info, full) else toast("该版本没有完整包")
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    private fun doDownload(info: UpdateChecker.ReleaseInfo, asset: UpdateChecker.Asset) {
        downloading = true
        downloadCard?.visibility = View.VISIBLE
        downloadLabel?.text = "准备中…"

        io.execute {
            val dir = PackDownloader.downloadDir(this@ImportActivity)
            val dest = File(dir, asset.name)
            var lastUi = 0L

            // 镜像并发测速：7 个通道各发一个小请求，几秒内挑出最快的
            if (preferredMirrors.isEmpty()) {
                ui.post {
                    downloadLabel?.text = "正在测试下载通道…（最多 4 秒）"
                    (downloadBar as? View)?.let { v ->
                        try {
                            val m = v.javaClass.getMethod("setProgress", Float::class.java)
                            m.invoke(v, 0f)
                        } catch (_: Exception) {
                        }
                    }
                }
                preferredMirrors = try {
                    PackDownloader.probeMirrors(asset.downloadUrl)
                } catch (e: Exception) {
                    emptyList()
                }
                val best = preferredMirrors.firstOrNull()?.second ?: "无（将逐个尝试）"
                FileLogger.log("镜像测速完成，最快：$best")
            }

            val ok = PackDownloader.download(
                url = asset.downloadUrl,
                dest = dest,
                expectedSize = asset.sizeBytes,
                listener = object : PackDownloader.Listener {
                    override fun onProgress(downloaded: Long, total: Long) {
                        val now = System.currentTimeMillis()
                        if (now - lastUi < 300) return
                        lastUi = now
                        val tot = if (total > 0) total else asset.sizeBytes
                        val pct = if (tot > 0) downloaded * 100.0 / tot else 0.0
                        val dlMb = "%.1f".format(downloaded / 1048576.0)
                        val totMb = "%.1f".format(tot / 1048576.0)
                        val pctInt = pct.toInt()
                        ui.post {
                            downloadLabel?.text = "下载中 $dlMb / $totMb MB  ($pctInt%)"
                            (downloadBar as? View)?.let { v ->
                                try {
                                    val m = v.javaClass.getMethod("setProgress", Float::class.java)
                                    m.invoke(v, (pct / 100.0).toFloat())
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    override fun onMirror(idx: Int, count: Int, name: String) {
                        ui.post { downloadLabel?.text = "镜像 $name（$idx/$count）下载中…" }
                    }
                },
                preferredMirrors = preferredMirrors
            )

            if (!ok) {
                downloading = false
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("下载失败，请检查网络后重试（下次会续传）")
                }
                return@execute
            }

            ui.post {
                downloadLabel?.text = "下载完成，正在解压安装…"
                toast("下载完成，开始安装")
            }

            // ---- 解压安装 ----
            try {
                val root = PackManager.packsRoot(this@ImportActivity)
                val stage = File(root, ".incoming")
                stage.deleteRecursively()
                stage.mkdirs()
                ZipInstaller.extractFile(dest, stage)
                val kids = stage.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
                val gameDir = if (kids.size == 1 && kids[0].isDirectory) kids[0] else stage
                val version = PackManager.readVersionFrom(gameDir) ?: info.version
                PackManager.register(this@ImportActivity, gameDir, version, source = "github", setActive = true)
                stage.deleteRecursively()
                dest.delete()
                FileLogger.log("在线下载并安装完成：v$version")
                downloading = false
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("v$version 安装完成")
                    updateInfo = null
                    switchNav(NAV_HOME)
                }
            } catch (e: Exception) {
                FileLogger.log("在线包解压失败：${e.message}")
                downloading = false
                val reason = e.message ?: "解压出错"
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("安装失败：$reason")
                }
            }
        }
    }

    // ==================== 动作：检查更新 ====================

    private fun checkUpdate(silent: Boolean, thenDownload: Boolean = false) {
        if (checkingUpdate) return
        checkingUpdate = true
        io.execute {
            val info = UpdateChecker.fetchLatest()
            checkingUpdate = false
            ui.post {
                if (info == null) {
                    if (!silent) toast("检查更新失败（网络不通）")
                    return@post
                }
                updateInfo = info

                val prefs = Settings.load(this@ImportActivity)
                prefs.lastKnownLatest = info.version
                Settings.save(this@ImportActivity, prefs)

                val active = PackManager.activeId(this@ImportActivity)
                val curV = active?.let {
                    PackManager.listPacks(this@ImportActivity).firstOrNull { p -> p.id == it }?.version
                }

                if (thenDownload) {
                    // 统一走 proceedDownload：已装同版本会自动转成增量升级
                    proceedDownload(info)
                    return@post
                }

                if (!silent) {
                    if (curV != null && !UpdateChecker.isNewer(info.version, curV)) {
                        toast("已是最新版本 v$curV")
                    } else {
                        toast("发现新版本 v${info.version}")
                    }
                }
                if (navIndex == NAV_HOME) switchNav(NAV_HOME)
            }
        }
    }

    // ==================== 动作：诊断 / 日志 ====================

    private fun runDoctor() {
        toast("正在诊断…")
        io.execute {
            val report = try {
                Doctor.run(this@ImportActivity)
            } catch (e: Exception) {
                "诊断出错：${e.message}"
            }
            ui.post { showTextDialog("环境诊断报告", report) }
        }
    }

    private fun showLog() {
        io.execute {
            val txt = try {
                val f = FileLogger.logFile()
                if (f != null && f.exists()) FileLogger.tail(300) else "（暂无日志）"
            } catch (e: Exception) {
                "读取日志失败：${e.message}"
            }
            ui.post {
                AlertDialog.Builder(this@ImportActivity)
                    .setTitle("最近日志")
                    .setMessage(txt)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("清空") { _, _ ->
                        io.execute { FileLogger.clear() }
                        toast("日志已清空")
                    }
                    .show()
            }
        }
    }

    private fun showTextDialog(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(UiKit.TEXT_MAIN)
            setPadding(UiKit.dp(this@ImportActivity, 16f), UiKit.dp(this@ImportActivity, 8f),
                UiKit.dp(this@ImportActivity, 16f), UiKit.dp(this@ImportActivity, 8f))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}