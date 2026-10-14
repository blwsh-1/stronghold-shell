package com.stronghold.shell

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 创意工坊（mod）管理器 —— 对接 Stronghold-Protocol-Forge 的 workshop 机制。
 *
 * 【机制来源】直接读 Forge 源码（server/workshop.js + shared/enabled.js）确认：
 *
 *   1) 包根目录：`<游戏根>/workshop/`
 *      Forge 源码：
 *        export const WORKSHOP_DIR = path.resolve(dirname, '..', 'workshop');
 *      → 默认就是「游戏根/workshop」；且 `loadWorkshop(dir)` 接受参数可覆盖。
 *      `workshop/` 不存在 = **正常情况**（源码注释原文："no workshop directory: the normal, supported case"）。
 *
 *   2) 一个 mod = 一个目录 + 一个 `pack.json`
 *      `workshop/<包id>/pack.json`（目录名 = 包 id）
 *
 *   3) 装载规则（server/workshop.js L101-150）：
 *      - 只扫 `workshop/` 的**直接子目录**，跳过 `.` 开头的
 *      - 每个目录必须含 `pack.json`，否则报 "pack.json is missing"
 *      - `pack.json.target === "stardust"` → 认识但不装载（属于另一个宿主）
 *      - `pack.json.enabled === false` → 装载期跳过（并**响亮报错** ENABLED_FALSE）
 *      - 改 `enabled` 需要**重启**（不是热更新）
 *
 *   4) ⚠️ 关键设计指导（shared/enabled.js 开头注释，作者**点名**提到 Android APK 壳）：
 *      「另一条产品线（Android APK 壳，见 issue #7）用 filesDir/workshop/enabled.json 这个旁挂文件
 *        表达同一件事。**那个形态在这里不可取**：它把「这个包生不生效」从 pack.json 里拿走，
 *        于是 (a) 包的身份哈希不含它；(b) 编辑器校验时看不到它；(c) 它是第二份真相源。」
 *      → **所以本实现不用旁挂文件，直接改 pack.json 的 `enabled` 字段。**
 *
 *   5) `PACK_FIELDS` 是 35 键**闭集**，未知顶层键会被 `PACK_UNKNOWN_FIELD` 点名拒绝。
 *      → 我们只读不写这些字段；写 `enabled` 是例外（它是闭集内的键）。
 */
object WorkshopHelper {

    private const val TAG = "StrongholdShell"

    /** Forge 约定的包根目录名 */
    private const val WORKSHOP_DIR_NAME = "workshop"
    private const val MANIFEST_NAME = "pack.json"

    /** pack.json 里表示"是否参与装载"的键（shared/enabled.js ENABLED_FIELD） */
    private const val ENABLED_FIELD = "enabled"

    /** 已安装 mod 的元信息 */
    data class ModInfo(
        val id: String,
        val dir: File,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val enabled: Boolean,
        val isForeign: Boolean,        // target == "stardust"：属于别的宿主，本引擎不装载
        val hasAssets: Boolean,
        val manifestError: String? = null,
    )

    // ---------------------------------------------------------------
    // 路径
    // ---------------------------------------------------------------

    /**
     * mod 根目录 = **游戏根**下的 `workshop/`。
     *
     * 注意：游戏根是 `GamePack.activeDir(context)`（含 server/index.js 的那一层）。
     * 若没有导入包，则退回一个占位目录（此时 mod 无处可放）。
     */
    fun workshopDir(context: Context): File {
        val gameRoot = GamePack.activeDir(context)
        return File(gameRoot, WORKSHOP_DIR_NAME)
    }

    /** 确认 mod 根存在（不存在就建） */
    fun ensureWorkshopDir(context: Context): File {
        val d = workshopDir(context)
        if (!d.exists()) d.mkdirs()
        return d
    }

    // ---------------------------------------------------------------
    // 读取
    // ---------------------------------------------------------------

    /**
     * 列出已安装的 mod。
     *
     * 完全按 Forge 的装载规则来判定（好让这里显示的状态 = 游戏里实际的状态）：
     *   - 只列**直接子目录**
     *   - 跳过 `.` 开头的
     *   - 没有 pack.json 的，标 manifestError
     */
    fun listMods(context: Context): List<ModInfo> {
        val dir = workshopDir(context)
        if (!dir.isDirectory) return emptyList()

        val children = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return emptyList()

        return children.sortedBy { it.name }.map { packDir ->
            val manifestFile = File(packDir, MANIFEST_NAME)

            if (!manifestFile.isFile) {
                return@map ModInfo(
                    id = packDir.name, dir = packDir, name = packDir.name, version = "",
                    author = "", description = "", enabled = false, isForeign = false,
                    hasAssets = File(packDir, "assets").isDirectory,
                    manifestError = "$MANIFEST_NAME is missing"
                )
            }

            try {
                val json = JSONObject(manifestFile.readText())
                val target = json.optString("target", "")
                ModInfo(
                    id = json.optString("id", packDir.name),
                    dir = packDir,
                    name = json.optString("name", packDir.name),
                    version = json.optString("version", ""),
                    author = json.optString("author", ""),
                    description = json.optString("description", ""),
                    // 缺省 = true（与 Forge 的 parseEnabledDecl 一致：undefined → true）
                    enabled = json.optBoolean(ENABLED_FIELD, true),
                    isForeign = target.equals("stardust", ignoreCase = true),
                    hasAssets = File(packDir, "assets").isDirectory,
                    manifestError = null
                )
            } catch (e: Exception) {
                ModInfo(
                    id = packDir.name, dir = packDir, name = packDir.name, version = "",
                    author = "", description = "", enabled = false, isForeign = false,
                    hasAssets = File(packDir, "assets").isDirectory,
                    manifestError = "$MANIFEST_NAME is unreadable: ${e.message}"
                )
            }
        }
    }

    /** 统计：已启用 / 总数 */
    fun countMods(context: Context): Pair<Int, Int> {
        val mods = listMods(context)
        return mods.count { it.enabled && !it.isForeign && it.manifestError == null } to mods.size
    }

    // ---------------------------------------------------------------
    // 启用 / 禁用
    // ---------------------------------------------------------------

    /**
     * 设置某个 mod 的启用状态。
     *
     * ⚠️ 按 Forge 作者的明确指导：**改 `pack.json` 里的 `enabled` 字段**，
     *    不能另建旁挂文件（那会造成"第二份真相源"）。
     *
     * 返回是否成功。注意：**改完需要重启 Node 才生效**（Forge 明确不是热更新）。
     */
    fun setEnabled(context: Context, modId: String, enabled: Boolean): Boolean {
        val packDir = File(workshopDir(context), modId)
        val manifestFile = File(packDir, MANIFEST_NAME)
        if (!manifestFile.isFile) {
            Log.w(TAG, "setEnabled 失败：找不到 $manifestFile")
            return false
        }
        return try {
            val json = JSONObject(manifestFile.readText())
            // 按 Forge 语义：写真正的布尔（不接受字符串 "false" 之类）
            json.put(ENABLED_FIELD, enabled)
            manifestFile.writeText(json.toString(2))
            Log.i(TAG, "mod $modId enabled=$enabled（需重启 Node 生效）")
            FileLogger.log("mod $modId 已${if (enabled) "启用" else "禁用"}（重启后生效）")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setEnabled 异常", e)
            FileLogger.log("切换 mod 状态失败: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------
    // 导入
    // ---------------------------------------------------------------

    /** 导入结果 */
    sealed class ImportResult {
        data class Ok(val id: String, val name: String, val target: String?) : ImportResult()
        data class Fail(val reason: String) : ImportResult()
    }

    /**
     * 从用户选择的 zip 导入一个 mod。
     *
     * 兼容 Forge 的**两种布局**（docs/STANDARD-PACK.md）：
     *   ① 标准工坊包：**ZIP 根**有 `pack.json`
     *   ② 包裹式：所有文件套在**一个顶层目录**下（`<任意名>/pack.json`）
     *      （引擎两种都收；站点只认 ①，会被 HTTP 400 挡下，但引擎接受）
     *
     * 流程：解压到临时目录 → 定位含 pack.json 的那一层 → 校验 → 移动到 workshop/<id>/
     */
    fun importFromUri(context: Context, uri: Uri): ImportResult {
        val root = ensureWorkshopDir(context)
        val tmp = File(root, ".importing")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()

            // 1) 流式解压（防目录穿越）
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    var count = 0
                    while (entry != null) {
                        val name = entry.name
                        if (!name.endsWith("/")) {
                            val outFile = File(tmp, name)
                            if (!outFile.canonicalPath.startsWith(tmp.canonicalPath)) {
                                Log.w(TAG, "跳过可疑条目: $name")
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { fos -> zis.copyTo(fos) }
                                count++
                            }
                        } else {
                            File(tmp, name).mkdirs()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    Log.i(TAG, "mod 解压完成，$count 个文件")
                }
            } ?: return ImportResult.Fail("无法读取所选的 zip")

            // 2) 定位 pack.json（兼容 ZIP 根 / 包裹式）
            val packRoot = locatePackRoot(tmp)
                ?: return ImportResult.Fail("这不是一个工坊包：找不到 $MANIFEST_NAME").also {
                    tmp.deleteRecursively()
                }

            // 3) 读清单
            val manifestFile = File(packRoot, MANIFEST_NAME)
            val json = try {
                JSONObject(manifestFile.readText())
            } catch (e: Exception) {
                tmp.deleteRecursively()
                return ImportResult.Fail("$MANIFEST_NAME 不是合法的 JSON：${e.message}")
            }

            // 4) 校验 id（Forge 的 PACK_ID_RE: ^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$）
            val declaredId = json.optString("id", "").trim()
            val dirName = packRoot.name
            val id = if (declaredId.isNotEmpty()) declaredId else dirName

            if (!isValidPackId(id)) {
                tmp.deleteRecursively()
                return ImportResult.Fail("包 id 不合法（只允许字母数字开头，后接字母数字/下划线/连字符，最长 32）：$id")
            }
            // 若 id 与目录名不一致，Forge 会 warn（不拒）；我们这里按 id 落盘，避免不一致
            if (declaredId.isNotEmpty() && declaredId != dirName) {
                Log.w(TAG, "包的 id($declaredId) 与目录名($dirName) 不一致，按 id 落盘")
            }

            // 5) 目标目录
            val dest = File(root, id)
            if (dest.exists()) {
                // 覆盖安装
                dest.deleteRecursively()
            }

            // 6) 移动（重命名优先）
            val ok = if (packRoot.absolutePath == tmp.absolutePath) {
                tmp.renameTo(dest)
            } else {
                // 包裹式：把 packRoot 移出来
                if (!packRoot.renameTo(dest)) {
                    copyDir(packRoot, dest)
                    packRoot.deleteRecursively()
                    true
                } else true
            }

            tmp.deleteRecursively()

            if (!ok || !dest.isDirectory) {
                return ImportResult.Fail("写入 workshop 目录失败（可能是存储空间不足）")
            }

            val target = json.optString("target", "").ifEmpty { null }
            Log.i(TAG, "mod 导入成功：id=$id name=${json.optString("name")} target=$target")
            FileLogger.log("mod 导入成功：$id（${json.optString("name", "")}）")
            return ImportResult.Ok(id, json.optString("name", id), target)

        } catch (e: Throwable) {
            Log.e(TAG, "mod 导入失败", e)
            FileLogger.log("mod 导入失败: ${e.message}")
            tmp.deleteRecursively()
            return ImportResult.Fail("导入失败：${e.message}")
        }
    }

    /**
     * 在解压出来的目录里定位含 `pack.json` 的那一层。
     * 规则：① 根目录本身有 → 用它；② 只有一个子目录且有 → 用它（包裹式）；③ 否则递归一层。
     */
    private fun locatePackRoot(dir: File, depth: Int = 0): File? {
        if (File(dir, MANIFEST_NAME).isFile) return dir
        if (depth > 2) return null
        val children = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return null
        for (c in children) {
            locatePackRoot(c, depth + 1)?.let { return it }
        }
        return null
    }

    /** Forge 的 PACK_ID_RE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/ */
    private fun isValidPackId(id: String): Boolean =
        Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$").matches(id)

    // ---------------------------------------------------------------
    // 删除
    // ---------------------------------------------------------------

    fun delete(context: Context, modId: String): Boolean {
        val packDir = File(workshopDir(context), modId)
        if (!packDir.exists()) return false
        val ok = packDir.deleteRecursively()
        if (ok) {
            Log.i(TAG, "mod $modId 已删除")
            FileLogger.log("mod $modId 已删除")
        }
        return ok
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyDir(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it) } }
        }
    }
}
