package com.stronghold.shell

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 「创意工坊 / Mod 管理」界面。
 *
 * 功能：
 *   - 列出已安装的 mod（读 workshop/<id>/pack.json）
 *   - 每个 mod 可「启用 / 禁用」（直接改 pack.json 的 enabled 字段）
 *   - 「导入 mod (.zip)」—— 兼容标准包（ZIP 根有 pack.json）与包裹式
 *   - 删除 mod
 *
 * 对接的是 Stronghold-Protocol-Forge 的 workshop 机制，
 * 具体规则见 WorkshopHelper 的注释（从 Forge 源码抄下来的）。
 */
class ModActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var listContainer: LinearLayout
    private lateinit var summary: TextView

    /** 系统文件选择器：只挑 zip */
    private val pickZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> doImport(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d1117"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = if (isZh()) "创意工坊" else "Workshop"
            setTextColor(Color.parseColor("#e6edf3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }

        summary = TextView(this).apply {
            setTextColor(Color.parseColor("#7d8590"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }

        val btnImport = Button(this).apply {
            text = if (isZh()) "导入 Mod (.zip)" else "Import Mod (.zip)"
            setOnClickListener { pickZipFile() }
        }

        val btnBack = Button(this).apply {
            text = if (isZh()) "返回" else "Back"
            setOnClickListener { finish() }
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            addView(listContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnImport, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(title)
        root.addView(summary)
        root.addView(scroll)
        root.addView(bottomBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        setContentView(root)
    }

    /** 重新读取并渲染 mod 列表 */
    private fun refresh() {
        io.execute {
            val hasPack = GamePack.hasActivePack(this)
            val mods = if (hasPack) WorkshopHelper.listMods(this) else emptyList()
            val (enabledCount, total) = if (hasPack) WorkshopHelper.countMods(this) else (0 to 0)

            runOnUiThread {
                listContainer.removeAllViews()

                if (!hasPack) {
                    summary.text = if (isZh())
                        "尚未导入游戏包 —— Mod 需要装在游戏包的 workshop/ 目录下。"
                    else "No game pack imported yet."
                    return@runOnUiThread
                }

                summary.text = if (isZh())
                    "已启用 $enabledCount / 共 $total 个 Mod\n（改动需重启游戏生效）"
                else "$enabledCount enabled / $total total"

                if (mods.isEmpty()) {
                    listContainer.addView(hintText(
                        if (isZh()) "还没有安装任何 Mod。\n点下方「导入 Mod (.zip)」添加。"
                        else "No mods installed yet."
                    ))
                    return@runOnUiThread
                }

                for (m in mods) {
                    listContainer.addView(buildModRow(m))
                }
            }
        }
    }

    /** 一行显示一个 mod */
    private fun buildModRow(m: WorkshopHelper.ModInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161b22"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val nameColor = when {
            m.manifestError != null -> "#f85149"     // 红：坏包
            m.isForeign -> "#8b949e"                   // 灰：别的宿主的包
            m.enabled -> "#3fb950"                     // 绿：启用
            else -> "#d29922"                          // 黄：已禁用
        }

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val nameView = TextView(this).apply {
            text = m.name
            setTextColor(Color.parseColor(nameColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val statusView = TextView(this).apply {
            text = when {
                m.manifestError != null -> "×"
                m.isForeign -> "foreign"
                m.enabled -> (if (isZh()) "已启用" else "ON")
                else -> (if (isZh()) "已禁用" else "OFF")
            }
            setTextColor(Color.parseColor(nameColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        titleRow.addView(nameView)
        titleRow.addView(statusView)
        card.addView(titleRow)

        // 副标题：id + 版本 + 作者
        val sub = buildString {
            append("id: ").append(m.id)
            if (m.version.isNotEmpty()) append("  ·  v").append(m.version)
            if (m.author.isNotEmpty()) append("  ·  ").append(m.author)
        }
        card.addView(smallText(sub))

        if (m.description.isNotEmpty()) {
            card.addView(smallText(m.description))
        }

        // 错误信息
        if (m.manifestError != null) {
            card.addView(smallText("⚠ ${m.manifestError}", "#f85149"))
        }
        if (m.isForeign) {
            card.addView(smallText(
                if (isZh()) "此包 target 为 stardust（另一个宿主的包），本引擎不会装载它。"
                else "target: stardust — not loaded by this engine.",
                "#8b949e"
            ))
        }
        if (m.hasAssets) {
            card.addView(smallText(
                if (isZh()) "含素材（assets/）" else "ships assets/",
                "#7d8590"
            ))
        }

        // 操作按钮
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btnToggle = Button(this).apply {
            text = if (m.enabled)
                (if (isZh()) "禁用" else "Disable")
            else
                (if (isZh()) "启用" else "Enable")
            isEnabled = m.manifestError == null && !m.isForeign
            setOnClickListener {
                io.execute {
                    val ok = WorkshopHelper.setEnabled(this, m.id, !m.enabled)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) (if (isZh()) "已${if (m.enabled) "禁用" else "启用"}（重启生效）" else "Changed")
                            else (if (isZh()) "操作失败" else "Failed"),
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnDelete = Button(this).apply {
            text = if (isZh()) "删除" else "Delete"
            setOnClickListener { confirmDelete(m) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(btnToggle)
        btnRow.addView(btnDelete)
        card.addView(btnRow)

        return card
    }

    private fun confirmDelete(m: WorkshopHelper.ModInfo) {
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isZh()) "删除 Mod" else "Delete Mod")
            .setMessage(if (isZh()) "确定删除「${m.name}」？此操作不可撤销。" else "Delete \"${m.name}\"?")
            .setPositiveButton(if (isZh()) "删除" else "Delete") { _, _ ->
                io.execute {
                    val ok = WorkshopHelper.delete(this, m.id)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) (if (isZh()) "已删除" else "Deleted") else (if (isZh()) "删除失败" else "Failed"),
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton(if (isZh()) "取消" else "Cancel", null)
            .show()
    }

    private fun pickZipFile() {
        if (!GamePack.hasActivePack(this)) {
            Toast.makeText(this, if (isZh()) "请先导入游戏包" else "Import a game pack first", Toast.LENGTH_LONG).show()
            return
        }
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

    private fun doImport(uri: android.net.Uri) {
        Toast.makeText(this, if (isZh()) "正在导入…" else "Importing…", Toast.LENGTH_SHORT).show()
        io.execute {
            val r = WorkshopHelper.importFromUri(this, uri)
            runOnUiThread {
                val msg = when (r) {
                    is WorkshopHelper.ImportResult.Ok ->
                        if (r.target == "stardust")
                            (if (isZh()) "导入成功，但它是 stardust 包（本引擎不装载）" else "Imported (stardust target)")
                        else
                            (if (isZh()) "导入成功：${r.name}" else "Imported: ${r.name}")
                    is WorkshopHelper.ImportResult.Fail -> (if (isZh()) "导入失败：${r.reason}" else "Failed: ${r.reason}")
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    // ---- 小工具 ----

    private fun isZh(): Boolean = true

    private fun smallText(s: String, color: String = "#7d8590"): TextView =
        TextView(this).apply {
            text = s
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        }

    private fun hintText(s: String): TextView =
        TextView(this).apply {
            text = s
            setTextColor(Color.parseColor("#7d8590"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
