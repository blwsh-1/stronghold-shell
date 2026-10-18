package com.stronghold.shell

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多版本管理 —— 像 HMCL 的「版本隔离」那样，允许 packs/ 下并存多个游戏版本。
 *
 * 【目录布局】
 *   filesDir/
 *     packs/
 *       installed.json          ← 索引（哪些版本存在、哪个是当前激活）
 *       v0.2.5/                 ← 各版本目录（游戏根，含 node_modules 与 server/）
 *       v0.3.1/
 *       active -> v0.3.1        ← 符号链接不行（Android 限制），改用索引里的 activeId
 *     packs-active/             ← 当前激活版本的「入口」目录
 *
 * 【为什么不用 symlink】Android 的 filesDir 在部分 ROM 上不支持创建软链接；
 *   且 Node 进程的 cwd 需要真实路径。故改为：NodeService 启动时从索引读取
 *   activeId，直接用 packs/<activeId>/ 作为工作目录。
 *
 * 【向后兼容】旧版有 packs/active/ 目录（无索引文件）。首次读取时自动
 *   迁移成 packs/v<版本号>/ 并写索引，保证老用户升级不丢包。
 */
object PackManager {

    private const val TAG = "StrongholdShell"
    private const val INDEX_FILE = "installed.json"
    private const val LEGACY_ACTIVE = "active"

    /** 一个已安装版本 */
    data class PackEntry(
        val id: String,            // 目录名，例如 v0.3.1
        val version: String,       // 从 package.json 读的版本号
        val kind: String,          // UPSTREAM / FORGE
        val dir: File,
        val installedAt: Long,
        val source: String         // "local" / "github" / "legacy"
    ) {
        val sizeBytes: Long by lazy { dirSize(dir) }
        val sizeMb: Int get() = (sizeBytes / 1048576L).toInt()
    }

    // ==================== 目录 ====================

    fun packsRoot(context: Context): File {
        val d = File(context.filesDir, "packs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(context: Context) = File(packsRoot(context), INDEX_FILE)

    // ==================== 索引读写 ====================

    private fun readIndex(context: Context): JSONObject {
        val f = indexFile(context)
        if (f.isFile) {
            try {
                return JSONObject(f.readText())
            } catch (e: Exception) {
                Log.w(TAG, "索引损坏，重建")
            }
        }
        return JSONObject()
    }

    private fun writeIndex(context: Context, index: JSONObject) {
        try {
            indexFile(context).writeText(index.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "写索引失败", e)
        }
    }

    /** 当前激活版本 id（无则 null） */
    fun activeId(context: Context): String? {
        migrateLegacyIfNeeded(context)
        val id = readIndex(context).optString("activeId", "")
        if (id.isEmpty()) return null
        // 目录被别人删了 → 视为无
        if (!File(packsRoot(context), id).isDirectory) return null
        return id
    }

    /** 当前激活版本的游戏根目录 */
    fun activeDir(context: Context): File? {
        val id = activeId(context) ?: return null
        return File(packsRoot(context), id)
    }

    // ==================== 迁移旧版 ====================

    /**
     * 旧版只有 packs/active/ 一个目录，没有索引。
     * 检测到这种状态时，读出版本号，重命名为 packs/v<版本>/，写入索引。
     */
    private fun migrateLegacyIfNeeded(context: Context) {
        val root = packsRoot(context)
        val legacy = File(root, LEGACY_ACTIVE)
        if (!legacy.isDirectory) return
        // 已经有索引且指向别的 → 说明不是首次，跳过
        val idx = readIndex(context)
        if (idx.optString("activeId", "").isNotEmpty()) return

        Log.i(TAG, "检测到旧版 active 目录，开始迁移")
        val version = readVersionFrom(legacy) ?: "unknown"
        val newId = safeId("v$version")
        val dest = File(root, newId)

        var target = legacy
        if (dest.exists()) {
            // 极端情况：目标已存在，删掉旧的 active
            legacy.deleteRecursively()
            target = dest
        } else {
            // 同分区 rename 是原子的，很快
            val ok = legacy.renameTo(dest)
            if (!ok) {
                // 跨分区退化为拷贝（很慢，但保证不丢）
                copyDir(legacy, dest)
                legacy.deleteRecursively()
            }
            target = dest
        }

        val entries = JSONArray().apply {
            put(JSONObject().apply {
                put("id", newId)
                put("version", version)
                put("kind", detectKindAt(target))
                put("installedAt", System.currentTimeMillis())
                put("source", "legacy")
            })
        }
        writeIndex(context, JSONObject().apply {
            put("activeId", newId)
            put("packs", entries)
        })
        Log.i(TAG, "迁移完成：active → $newId")
    }

    // ==================== 列出 / 增删 ====================

    /** 列出所有已安装版本（按安装时间倒序） */
    fun listPacks(context: Context): List<PackEntry> {
        migrateLegacyIfNeeded(context)
        val root = packsRoot(context)
        val idx = readIndex(context)
        val arr = idx.optJSONArray("packs") ?: JSONArray()
        val out = ArrayList<PackEntry>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val dir = File(root, id)
            if (!dir.isDirectory) continue      // 被手工删了
            out.add(
                PackEntry(
                    id = id,
                    version = o.optString("version", "unknown"),
                    kind = o.optString("kind", detectKindAt(dir)),
                    dir = dir,
                    installedAt = o.optLong("installedAt", 0),
                    source = o.optString("source", "local")
                )
            )
        }
        return out.sortedByDescending { it.installedAt }
    }

    /** 注册一个新装好的版本（dir 已在 packs/ 下） */
    fun register(
        context: Context,
        dir: File,
        version: String,
        source: String,
        setActive: Boolean
    ): String {
        val root = packsRoot(context)
        var id = dir.name
        // 确保 id 落在 packs 根下
        if (dir.parentFile?.absolutePath != root.absolutePath) {
            id = safeId("v$version")
            val dest = File(root, id)
            dest.deleteRecursively()
            if (!dir.renameTo(dest)) {
                copyDir(dir, dest)
                dir.deleteRecursively()
            }
        }

        val idx = readIndex(context)
        val arr = idx.optJSONArray("packs") ?: JSONArray()
        // 同 id 覆盖：先移除旧的
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != id) newArr.put(o)
        }
        newArr.put(JSONObject().apply {
            put("id", id)
            put("version", version)
            put("kind", detectKindAt(File(root, id)))
            put("installedAt", System.currentTimeMillis())
            put("source", source)
        })
        idx.put("packs", newArr)
        if (setActive || idx.optString("activeId", "").isEmpty()) {
            idx.put("activeId", id)
        }
        writeIndex(context, idx)
        return id
    }

    /** 切换激活版本 */
    fun setActive(context: Context, id: String): Boolean {
        val dir = File(packsRoot(context), id)
        if (!dir.isDirectory) return false
        if (!File(dir, "node_modules").isDirectory) return false
        val idx = readIndex(context)
        idx.put("activeId", id)
        writeIndex(context, idx)
        Log.i(TAG, "激活版本切换为：$id")
        return true
    }

    /** 删除一个版本（不能删当前激活的，除非先切走） */
    fun removePack(context: Context, id: String): Boolean {
        val root = packsRoot(context)
        val dir = File(root, id)
        val ok = dir.deleteRecursively()

        val idx = readIndex(context)
        val arr = idx.optJSONArray("packs") ?: JSONArray()
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != id) newArr.put(o)
        }
        idx.put("packs", newArr)
        if (idx.optString("activeId", "") == id) {
            // 激活的被删了，自动指向第一个剩下的
            val first = if (newArr.length() > 0) newArr.getJSONObject(0).optString("id", "") else ""
            idx.put("activeId", first)
        }
        writeIndex(context, idx)
        return ok
    }

    // ==================== 工具 ====================

    /** 目录名安全化：只留字母数字点横线 */
    fun safeId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isEmpty()) "pack_" + System.currentTimeMillis() else cleaned
    }

    fun detectKindAt(dir: File): String =
        if (File(dir, "workshop").isDirectory) "FORGE" else "UPSTREAM"

    fun readVersionFrom(dir: File): String? {
        try {
            val pkg = File(dir, "package.json")
            if (pkg.isFile) {
                val v = JSONObject(pkg.readText()).optString("version", "")
                if (v.isNotEmpty()) return v
            }
        } catch (_: Exception) {}
        try {
            val man = File(dir, "MANIFEST.json")
            if (man.isFile) {
                val v = JSONObject(man.readText()).optString("version", "")
                if (v.isNotEmpty()) return v
            }
        } catch (_: Exception) {}
        return null
    }

    private fun dirSize(dir: File): Long {
        var sum = 0L
        try {
            dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        } catch (_: Exception) {}
        return sum
    }

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