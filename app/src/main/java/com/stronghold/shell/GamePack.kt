package com.stronghold.shell

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 游戏包（Content Pack）管理器 —— 启动器的核心。
 *
 * 【背景】上游 sganggs/Stronghold-Protocol 的 Release 完整包结构
 *   （经 dsh 实测 + sha256 校验，2026-10-13）：
 *
 *   Stronghold-Protocol-v0.2.5.zip   （579MB，共 21142 个条目）
 *     └── Stronghold-Protocol/       ← 【严格单层，唯一顶层目录】
 *           ├── MANIFEST.json        ← 【注意：在文件夹里，不在 zip 根】
 *           ├── package.json         ← scripts.start = "node server/index.js"
 *           │                          engines.node = ">=22"
 *           ├── server/index.js      ← 入口
 *           ├── node_modules/        ← 6367 条，已自带（无需 npm install）
 *           ├── public/vendor/  data/  packs/
 *           └── ...
 *
 * 【坑】不同打包方式可能不同，所以仍用「探测式」定位游戏根（找含
 *       node_modules + 入口的目录），但**校验必须严格**：没有
 *       node_modules 就视为不完整包，安装时直接拒绝并提示用户。
 *
 * 【与本壳原逻辑的关系】
 *   原壳：assets/app_bundle.zip → filesDir/bundle/
 *   本启动器：用户导入 zip → filesDir/packs/active/  （不动 assets，纯导入）
 */
object GamePack {

    private const val TAG = "StrongholdShell"

    /** 导入包存放根目录（每个包一个子目录） */
    fun packsRoot(context: Context): File {
        val dir = File(context.filesDir, "packs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 当前激活包目录（解压后的实际游戏根，即含 server/ 的那一层） */
    fun activeDir(context: Context): File = File(packsRoot(context), "active")

    /** 已安装包的标记文件（记录版本、来源等） */
    private fun markerFile(context: Context): File = File(packsRoot(context), "installed.json")

    /** 是否已有可用游戏包 */
    fun hasActivePack(context: Context): Boolean {
        val dir = activeDir(context)
        return findEntry(dir) != null && File(dir, "node_modules").isDirectory
    }

    /** 读取已安装包元信息 */
    fun readInfo(context: Context): JSONObject? {
        val f = markerFile(context)
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 在给定目录里找 Node 入口。
     * 依次尝试：server/index.js、index.js、main.js、app.js
     */
    fun findEntry(dir: File): String? {
        val candidates = listOf("server/index.js", "index.js", "main.js", "app.js")
        return candidates.firstOrNull { File(dir, it).isFile }
    }

    /**
     * 从用户选择的 zip（content:// Uri）安装游戏包。
     *
     * @param onProgress 进度回调 (已解压文件数) —— 在 IO 线程调用
     * @return 安装成功后的包目录，失败返回 null
     */
    fun installFromUri(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): File? {
        val dest = activeDir(context)
        val tmp = File(packsRoot(context), "active.installing")

        try {
            // 1) 清临时目录
            tmp.deleteRecursively()
            tmp.mkdirs()

            // ⭐ 空间预检（解压前！）：先用 zip 的条目未压缩大小估算所需空间
            //    上游完整包（v0.2.5）dsh 实测解压后约 0.76GB，手机存储紧张时必须提前拦。
            //    ⚠️ 注意：用 ZipInputStream 流式读取时 entry.size 常为 -1（未知），
            //       若直接累加会得到 0 → 预检形同虚设。故采用「双通道」：
            //       ① 能拿到有效 size 的条目就累加；
            //       ② 若有效条目过少（size 大量缺失），改用「压缩包大小 × 3」保守估算
            //          （dsh 实测上游包压缩率约 141.5%，×3 已是安全余量）。
            val pre = try {
                val compressed = try {
                    context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                } catch (_: Exception) { 0L }
                var sum = 0L; var known = 0; var total = 0
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    java.util.zip.ZipInputStream(ins).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            total++
                            if (e.size >= 0) { sum += e.size; known++ }
                            z.closeEntry()
                            e = z.nextEntry
                        }
                    }
                }
                longArrayOf(sum, known.toLong(), total.toLong(), compressed)
            } catch (e: Exception) { longArrayOf(0L, 0L, 0L, 0L) }
            val sumKnown = pre[0]; val knownCount = pre[1]; val totalCount = pre[2]; val zipFileSize = pre[3]
            // 判定：有效条目占比 < 50% 视为 size 不可靠，退化估算
            val needBytes: Long = if (knownCount > 0 && knownCount * 2 >= totalCount) {
                sumKnown
            } else if (zipFileSize > 0) {
                zipFileSize * 3L
            } else {
                sumKnown
            }

            if (needBytes > 0) {
                val usable = packsRoot(context).usableSpace
                // 留 300MB 余量（解压过程中还会写日志/缓存）
                val margin = 300L * 1024 * 1024
                if (usable < needBytes + margin) {
                    Log.e(TAG, "空间不足：需要约 ${needBytes / 1024 / 1024}MB，可用 ${usable / 1024 / 1024}MB")
                    FileLogger.log("导入失败：存储空间不足（需约 ${needBytes / 1024 / 1024}MB，可用 ${usable / 1024 / 1024}MB）")
                    return null
                }
                Log.i(TAG, "空间预检通过：需要 ${needBytes / 1024 / 1024}MB，可用 ${usable / 1024 / 1024}MB")
            }

            // 2) 流式解压到临时目录（防目录穿越）
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    var count = 0
                    while (entry != null) {
                        val name = entry.name
                        // 跳过根目录条目
                        if (!name.endsWith("/")) {
                            val outFile = File(tmp, name)
                            if (!outFile.canonicalPath.startsWith(tmp.canonicalPath)) {
                                Log.w(TAG, "跳过可疑条目: $name")
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { fos -> zis.copyTo(fos) }
                                count++
                                if (count % 200 == 0) onProgress(count)
                            }
                        } else {
                            File(tmp, name).mkdirs()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    onProgress(count)
                }
            } ?: return null

            // 3) 定位真正的游戏根（处理「单层 Stronghold-Protocol/」包裹）
            val gameRoot = locateGameRoot(tmp)
            if (gameRoot == null) {
                Log.e(TAG, "包的目录结构不对：找不到含 node_modules 与 server/index.js 的目录")
                FileLogger.log("导入失败：包结构不对（缺 node_modules 或 server/index.js）")
                tmp.deleteRecursively()
                return null
            }

            // 3.5) 【严格校验】明确检查 node_modules 与入口，给出精准错误
            //      —— 依 dsh 第 10 封建议：宁可在导入时报错，不要装完启动失败。
            if (!File(gameRoot, "node_modules").isDirectory) {
                Log.e(TAG, "包不完整：缺 node_modules（可能导入了 lite/源码包）")
                FileLogger.log("导入失败：缺 node_modules，请使用【完整包】而非 lite/源码包")
                tmp.deleteRecursively()
                return null
            }
            if (findEntry(gameRoot) == null) {
                Log.e(TAG, "包不完整：找不到入口 server/index.js")
                FileLogger.log("导入失败：找不到入口 server/index.js")
                tmp.deleteRecursively()
                return null
            }

            // 4) 如果游戏根 != tmp（说明外面裹了一层或多层），需要搬迁内容
            val finalTmp = if (gameRoot.absolutePath == tmp.absolutePath) {
                tmp
            } else {
                // 把 gameRoot 内容移到 tmp 下（用重命名省时间）
                val moved = File(packsRoot(context), "active.normalized")
                moved.deleteRecursively()
                if (gameRoot.renameTo(moved)) moved else {
                    // 重命名失败（跨挂载点），退化为拷贝
                    copyDir(gameRoot, moved)
                    moved
                }
            }

            // 5) 原子替换：旧 active 删掉，新目录改名 active
            dest.deleteRecursively()
            if (finalTmp.renameTo(dest)) {
                // 清理可能剩下的中间目录
                tmp.deleteRecursively()
            } else {
                copyDir(finalTmp, dest)
                finalTmp.deleteRecursively()
                tmp.deleteRecursively()
            }

            // 6) 写元信息
            val version = detectVersion(dest) ?: "unknown"
            markerFile(context).writeText(
                JSONObject().apply {
                    put("version", version)
                    put("installedAt", System.currentTimeMillis())
                    put("entry", findEntry(dest) ?: "server/index.js")
                }.toString(2)
            )
            Log.i(TAG, "游戏包安装完成，版本=$version，目录=${dest.absolutePath}")
            return dest
        } catch (e: Throwable) {
            Log.e(TAG, "安装游戏包失败", e)
            FileLogger.log("安装游戏包失败: ${e.message}")
            tmp.deleteRecursively()
            return null
        }
    }

    /**
     * 在解压出来的目录里定位「真正的游戏根」。
     * 规则（按优先级）：
     *   1. 当前目录本身含 node_modules + entry → 就是它
     *   2. 直接子目录中名为 Stronghold-Protocol / stronghold-protocol 的
     *   3. 任意直接子目录里含 node_modules 且含 entry
     *   4. 再往下递归一层（兼容多级包裹）
     *
     * 【严格】最终必须满足 isGameRoot（即同时有 node_modules 和入口），
     *        否则返回 null，由上层拒绝安装并提示「包不完整」。
     *        —— 依 dsh 第 10 封建议：宁可在导入时报错，也不要装完启动失败。
     */
    private fun locateGameRoot(dir: File): File? {
        if (isGameRoot(dir)) return dir

        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null

        // 优先找名字匹配的
        children.firstOrNull {
            it.name.equals("Stronghold-Protocol", ignoreCase = true)
        }?.let { named ->
            if (isGameRoot(named)) return named
            // 名字对上但还不是游戏根 → 递归往里找（可能又包了一层）
            locateGameRoot(named)?.let { return it }
        }

        // 其次找含 node_modules 的
        children.firstOrNull { isGameRoot(it) }?.let { return it }

        // 最后递归
        for (c in children) {
            locateGameRoot(c)?.let { return it }
        }
        return null
    }

    private fun isGameRoot(dir: File): Boolean {
        return File(dir, "node_modules").isDirectory && findEntry(dir) != null
    }

    /** 从 package.json 或 MANIFEST.json 读版本号 */
    private fun detectVersion(dir: File): String? {
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

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyDir(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it) } }
        }
    }

    /** 卸载当前游戏包（释放空间） */
    fun uninstall(context: Context) {
        activeDir(context).deleteRecursively()
        markerFile(context).delete()
    }
}
