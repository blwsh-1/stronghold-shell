package com.stronghold.shell

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 增量包（-update.zip）覆盖器。
 *
 * ============================================================================
 * 【实测上游 update.zip 结构】v0.3.3，41 条目，876,557 字节
 *
 *   Stronghold-Protocol/                  ← 有顶层目录，直接解压会多套一层
 *     UPDATE.json                         ← 增量清单
 *     MANIFEST.json                       ← 整包清单
 *     package.json                        ← 版本号 0.3.3
 *     server/ public/ shared/ docs/ ...
 *     node_modules/.package-lock.json     ← 只有一个文件，不是完整 node_modules
 *
 * 【实测 UPDATE.json】三个版本的真实内容（字段含义已确认）
 *
 *   v0.3.3: app=0.3.3 from=["0.3.2"] count=21   removed=[]
 *   v0.3.2: app=0.3.2 from=["0.3.1"] count=1097 removed=[{path,sha256:[...]}, ×3]
 *   v0.3.1: app=0.3.1 from=["0.3.0"] count=388  removed=[{path,sha256:[...]}, ×N]
 *
 *   {"format":1, "app":"0.3.3", "from":["0.3.2"], "count":21, "bytes":1513567,
 *    "removed":[{"path":"server/sim/damage.js","sha256":["ae64cbdc..."]}],
 *    "files":{"CHANGELOG.md":{"size":222597,"sha256":"c15643a1..."}, ...}}
 *
 *   · from     —— 允许的基线版本。**只对紧邻的上一版有效**，所以跨版本必须逐级升
 *                 （见 SteppedUpdater），不能拿 0.3.3 的补丁直接打 0.3.1。
 *   · removed  —— 要删除的旧文件，**是对象数组不是字符串数组**，
 *                 每个带 sha256：只有当前文件确实是这些哈希之一才删，
 *                 用户改过的文件 / mod 覆盖过的文件不会被误删。
 *   · files    —— 覆盖后逐个校验 size + sha256。
 *
 * ============================================================================
 * 【桌面启动器（updater.cjs）的可借鉴点】
 *   1. findProjectRoot  —— 剥包裹目录找真正的项目根（本类 stripWrapper）
 *   2. blackList        —— 覆盖时保护外壳自己的文件（本类 PROTECTED_PREFIXES）
 *   3. 强制同步版本号    —— localPkg.version = remoteVersion（本类 forceVersion）
 *   4. 阶梯式递增        —— 见 SteppedUpdater.kt
 *
 * ============================================================================
 * 【安全顺序】任何一步失败都能回到原样
 *   解压 → 剥包裹 → 校验 from → rename 备份 → 逐文件覆盖 → 按 removed 删
 *   → 校验 sha256 → 目录改名 v<新版本> → 写索引；失败全部回滚
 */
object PackUpdater {

    private const val TAG = "StrongholdShell"

    enum class ZipKind { FULL, UPDATE, UNKNOWN }

    private const val MAX_SCAN_ENTRIES = 800
    private const val MAX_SCAN_BYTES = 80L * 1024 * 1024

    /**
     * 覆盖时**永远不碰**的目录。
     * 对应桌面版 updater.cjs 的 blackList（那边保护 main.cjs/preload.cjs 等壳文件），
     * 安卓壳这边没有壳文件在游戏目录里，真正要保护的是玩家的 mod 与 mod 状态：
     *   workshop/  Forge 工坊 mod
     *   var/       mod-registry.json 等运行时状态
     * 上游目前不会往这两个目录塞文件，但补丁里若出现同名路径，跳过比覆盖安全。
     */
    private val PROTECTED_PREFIXES = listOf("workshop/", "var/")

    // ==================== 包类型判定 ====================

    /**
     * 判定 zip 是完整包还是增量包。
     *   1. 文件名含 "-update"           → 增量包（上游命名固定）
     *   2. 包内有 UPDATE.json           → 增量包（决定性证据，只有 update 包带）
     *   3. 包内有 node_modules/<包>/... → 完整包（update 包只有 .package-lock.json）
     *   4. 都没有                       → 未知（lite / 源码包）
     *
     * ZipInputStream 必须解压完条目才能读下一个名字，所以扫描有上限；
     * 完整包默认就走 FULL，扫不到证据也不影响判断。
     */
    fun detect(context: Context, uri: Uri, fileName: String? = null): ZipKind {
        if (fileName != null && guessKindByName(fileName) == ZipKind.UPDATE) {
            return ZipKind.UPDATE
        }

        var hasUpdateJson = false
        var realNodeModules = 0
        var entries = 0
        var bytes = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val n = e.name
                        if (!n.endsWith("/")) {
                            entries++
                            if (n.endsWith("UPDATE.json")) hasUpdateJson = true
                            if (n.contains("node_modules/") && !n.endsWith(".package-lock.json")) {
                                realNodeModules++
                            }
                            if (e.size > 0) bytes += e.size
                        }
                        if (hasUpdateJson || realNodeModules >= 20) break
                        if (entries >= MAX_SCAN_ENTRIES || bytes >= MAX_SCAN_BYTES) break
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "探测包类型失败：${ex.message}")
            return ZipKind.UNKNOWN
        }

        return when {
            hasUpdateJson -> ZipKind.UPDATE
            realNodeModules >= 20 -> ZipKind.FULL
            else -> ZipKind.UNKNOWN
        }
    }

    /** 只按文件名判断，不解压。给「本地包列表」用。 */
    fun guessKindByName(name: String): ZipKind = when {
        name.contains("-update", true) -> ZipKind.UPDATE
        name.contains("-lite", true) -> ZipKind.UNKNOWN
        else -> ZipKind.FULL
    }

    // ==================== 增量清单 ====================

    data class FileSig(val size: Long, val sha256: String)

    /** removed 里的一项：path + 允许被删除的哈希列表（防止误删用户改过的文件） */
    data class RemovedFile(val path: String, val sha256: List<String>)

    data class UpdateMeta(
        val app: String,
        val from: List<String>,
        val removed: List<RemovedFile>,
        val files: Map<String, FileSig>
    )

    fun readUpdateMeta(gameRoot: File): UpdateMeta? {
        val f = File(gameRoot, "UPDATE.json")
        if (!f.isFile) return null
        return try {
            val j = JSONObject(f.readText())

            val files = LinkedHashMap<String, FileSig>()
            j.optJSONObject("files")?.let { fo ->
                for (key in fo.keys()) {
                    val o = fo.optJSONObject(key) ?: continue
                    files[key] = FileSig(o.optLong("size", -1L), o.optString("sha256", ""))
                }
            }

            val from = ArrayList<String>()
            j.optJSONArray("from")?.let { fa ->
                for (i in 0 until fa.length()) from.add(fa.optString(i, ""))
            }

            // ⚠️ removed 是对象数组：[{"path":"...","sha256":["..."]}, ...]
            //    不是字符串数组。按字符串解析会全部读成空串，等于没删。
            val removed = ArrayList<RemovedFile>()
            j.optJSONArray("removed")?.let { ra ->
                for (i in 0 until ra.length()) {
                    when (val item = ra.opt(i)) {
                        is JSONObject -> {
                            val p = item.optString("path", "")
                            if (p.isBlank()) continue
                            val hashes = ArrayList<String>()
                            item.optJSONArray("sha256")?.let { ha ->
                                for (k in 0 until ha.length()) hashes.add(ha.optString(k, ""))
                            }
                            removed.add(RemovedFile(p, hashes))
                        }
                        is String -> if (item.isNotBlank()) removed.add(RemovedFile(item, emptyList()))
                    }
                }
            }

            UpdateMeta(
                app = j.optString("app", ""),
                from = from,
                removed = removed,
                files = files
            )
        } catch (e: Exception) {
            Log.w(TAG, "UPDATE.json 解析失败：${e.message}")
            null
        }
    }

    // ==================== 覆盖升级 ====================

    data class Result(
        val ok: Boolean,
        val fromVersion: String?,
        val toVersion: String?,
        val message: String
    )

    fun applyOverlay(
        context: Context,
        uri: Uri,
        targetId: String? = null,
        onStage: (String) -> Unit = {}
    ): Result = apply(context, targetId, onStage) { stage ->
        ZipInstaller.extract(context, uri, stage)
    }

    fun applyOverlayFile(
        context: Context,
        zip: File,
        targetId: String? = null,
        onStage: (String) -> Unit = {}
    ): Result = apply(context, targetId, onStage) { stage ->
        ZipInstaller.extractFile(zip, stage)
    }

    private fun apply(
        context: Context,
        targetId: String?,
        onStage: (String) -> Unit,
        extract: (File) -> Unit
    ): Result {
        val root = PackManager.packsRoot(context)
        val activeId = targetId ?: PackManager.activeId(context)
            ?: return Result(false, null, null, "本地还没有完整安装，增量包不能单独使用")

        val target = File(root, activeId)
        if (!File(target, "node_modules").isDirectory) {
            return Result(
                false, null, null,
                "当前版本 $activeId 缺少 node_modules，不是完整安装；请先导入完整包"
            )
        }
        val fromVer = PackManager.readVersionFrom(target)

        val stage = File(root, ".overlay")
        val backup = File(root, "$activeId.bak")
        var workDir: File = target
        var renamedTo: File? = null

        try {
            // ---- 1) 解压 ----
            stage.deleteRecursively()
            stage.mkdirs()
            onStage("正在解压增量包…")
            extract(stage)

            // ---- 2) 剥包裹目录 ----
            val base = stripWrapper(stage)

            // ---- 3) 先判断适不适用，不适用就一个文件都不动 ----
            val meta = readUpdateMeta(base)
            if (meta != null && meta.from.isNotEmpty() && fromVer != null &&
                !meta.from.contains(fromVer)
            ) {
                return Result(
                    false, fromVer, null,
                    "这个增量包只适用于 v${meta.from.joinToString(" / v")}，本地是 v$fromVer。\n" +
                        "跨版本升级需要逐级进行（见 SteppedUpdater），或直接用完整包。"
                )
            }
            val toVer = meta?.app?.takeIf { it.isNotBlank() }
                ?: PackManager.readVersionFrom(base)
                ?: fromVer

            // ---- 4) 备份 ----
            onStage("正在备份旧版本…")
            backup.deleteRecursively()
            if (!target.renameTo(backup)) {
                return Result(false, fromVer, null, "无法备份旧版本（目录被占用或存储异常），已中止")
            }
            workDir.mkdirs()

            // ---- 5) 逐文件覆盖（跳过 workshop/ var/）----
            onStage("正在覆盖文件…")
            var moved = 0
            var copied = 0
            var skipped = 0
            base.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(base).path.replace('\\', '/')
                    if (PROTECTED_PREFIXES.any { rel.startsWith(it) }) {
                        skipped++
                    } else {
                        val dest = File(workDir, rel)
                        dest.parentFile?.mkdirs()
                        if (dest.exists()) dest.delete()
                        if (f.renameTo(dest)) {
                            moved++
                        } else {
                            f.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                            copied++
                        }
                    }
                }
            }
            if (skipped > 0) FileLogger.log("覆盖时跳过 $skipped 个 mod/状态目录内的文件")

            // ---- 6) 按 removed 清单删旧文件（带哈希校验，不误删用户改过的文件）----
            if (meta != null && meta.removed.isNotEmpty()) {
                var del = 0
                var keep = 0
                for (r in meta.removed) {
                    val f = File(workDir, r.path)
                    if (!f.isFile || !f.canonicalPath.startsWith(workDir.canonicalPath)) continue
                    val okToDelete = if (r.sha256.isEmpty()) {
                        true
                    } else {
                        val actual = sha256(f)
                        r.sha256.any { it.equals(actual, true) }
                    }
                    if (okToDelete) {
                        if (f.delete()) del++
                    } else {
                        keep++
                    }
                }
                FileLogger.log("按清单删除 $del 个旧文件" + if (keep > 0) "，$keep 个哈希不符已保留" else "")
            }

            // ---- 7) 校验 ----
            onStage("正在校验文件…")
            if (meta != null && meta.files.isNotEmpty()) {
                val bad = ArrayList<String>()
                for ((rel, sig) in meta.files) {
                    val f = File(workDir, rel)
                    if (!f.isFile) {
                        bad.add("$rel（缺失）")
                    } else if (sig.size >= 0 && f.length() != sig.size) {
                        bad.add("$rel（大小 ${f.length()} ≠ ${sig.size}）")
                    } else if (sig.sha256.isNotEmpty() && !sha256(f).equals(sig.sha256, true)) {
                        bad.add("$rel（sha256 不符）")
                    }
                    if (bad.size >= 5) break
                }
                if (bad.isNotEmpty()) {
                    throw IllegalStateException(
                        "文件校验失败，增量包可能下坏了：\n" + bad.joinToString("\n")
                    )
                }
            }
            if (GamePack.findEntry(workDir) == null) {
                throw IllegalStateException("覆盖后找不到入口 server/index.js")
            }
            if (!File(workDir, "node_modules").isDirectory) {
                throw IllegalStateException("覆盖后 node_modules 丢了")
            }

            // ---- 8) 版本号对齐（照抄桌面版：localPkg.version = remoteVersion）----
            if (!toVer.isNullOrBlank()) forceVersion(workDir, toVer)
            val finalVer = PackManager.readVersionFrom(workDir) ?: toVer
                ?: throw IllegalStateException("覆盖后读不到版本号")

            // ---- 9) 目录名跟着版本走 + 写索引 ----
            val newId = PackManager.safeId("v$finalVer")
            if (newId != activeId) {
                val finalDir = File(root, newId)
                finalDir.deleteRecursively()
                if (workDir.renameTo(finalDir)) {
                    renamedTo = finalDir
                    workDir = finalDir
                }
            }
            PackManager.register(context, workDir, finalVer, source = "local-update", setActive = true)

            backup.deleteRecursively()
            stage.deleteRecursively()
            FileLogger.log(
                "增量覆盖完成：$fromVer -> $finalVer（rename $moved，copy $copied，" +
                    "校验 ${meta?.files?.size ?: 0} 个文件）"
            )
            return Result(true, fromVer, finalVer, "已升级到 v$finalVer")
        } catch (e: Exception) {
            try {
                renamedTo?.deleteRecursively()
                if (workDir != target) workDir.deleteRecursively()
                target.deleteRecursively()
                if (backup.isDirectory) backup.renameTo(target)
            } catch (re: Exception) {
                Log.e(TAG, "回滚失败", re)
            }
            stage.deleteRecursively()
            FileLogger.log("增量覆盖失败：" + (e.message ?: "未知错误"))
            return Result(false, fromVer, null, e.message ?: "未知错误")
        }
    }

    /**
     * 剥掉单层包裹目录（对应桌面版 findProjectRoot）。
     * 上游 update.zip 所有条目都在 Stronghold-Protocol/ 下面，不剥就会变成
     * packs/v0.3.2/Stronghold-Protocol/package.json —— 版本号读不到、游戏跑不起来。
     * 只在那层里确实有 package.json / server / UPDATE.json 时才剥，
     * 避免把「只改了 public/ 一个子目录」的补丁误判成包裹层。
     */
    private fun stripWrapper(stage: File): File {
        val kids = stage.listFiles()?.filter { it.name != "__MACOSX" && !it.name.startsWith(".") }
            ?: return stage
        if (kids.size != 1 || !kids[0].isDirectory) return stage
        val only = kids[0]
        val looksLikeRoot = File(only, "package.json").isFile ||
            File(only, "server").isDirectory ||
            File(only, "UPDATE.json").isFile
        return if (looksLikeRoot) only else stage
    }

    /**
     * 把 package.json 的 version 强制写成目标版本。
     * 桌面版 updater.cjs 就是这么做的（还顺便合并 dependencies）。
     * 目的：即使上游某个版本的 update.zip 忘了 bump package.json，
     *      壳里的版本号、目录名也不会和实际内容对不上。
     */
    private fun forceVersion(gameRoot: File, version: String) {
        val pkg = File(gameRoot, "package.json")
        if (!pkg.isFile) return
        try {
            val j = JSONObject(pkg.readText())
            if (j.optString("version", "") == version) return
            j.put("version", version)
            pkg.writeText(j.toString(2))
            FileLogger.log("package.json 版本号已强制对齐：$version")
        } catch (e: Exception) {
            Log.w(TAG, "写 package.json 版本号失败：${e.message}")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
