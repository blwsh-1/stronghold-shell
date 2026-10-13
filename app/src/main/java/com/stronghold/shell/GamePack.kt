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
 * 【背景】上游 sganggs/Stronghold-Protocol 的 Release 完整包结构：
 *   Stronghold-Protocol-v0.2.5.zip
 *     ├── MANIFEST.json                ← 根部
 *     └── Stronghold-Protocol/         ← 单层顶层文件夹
 *           ├── server/index.js        ← 入口
 *           ├── shared/ data/ public/ packs/
 *           ├── node_modules/          ← 已装好（npm ci --omit=dev）
 *           └── package.json
 *
 * 【坑】不能假设「根下只有一个文件夹」，要**找名为 Stronghold-Protocol 的顶层目录**；
 *       但若用户导入的是「已经进入该目录」的包，则顶层就是 server/ 等，需要兼容。
 *
 * 【与本壳原逻辑的关系】
 *   原壳：assets/app_bundle.zip → filesDir/bundle/
 *   本启动器：用户导入 zip → filesDir/packs/<版本>/  （不动 assets，纯导入）
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

            // 3) 定位真正的游戏根（处理「单层 Stronghold-Protocol/」这个坑）
            val gameRoot = locateGameRoot(tmp)
            if (gameRoot == null) {
                Log.e(TAG, "包的目录结构不对：找不到 server/index.js 或 node_modules")
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
     *   4. 再往下找一层（兼容多级）
     */
    private fun locateGameRoot(dir: File): File? {
        if (isGameRoot(dir)) return dir

        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null

        // 优先找名字匹配的
        children.firstOrNull {
            it.name.equals("Stronghold-Protocol", ignoreCase = true)
        }?.let { named ->
            if (isGameRoot(named)) return named
            // 名字对上但没有 node_modules，也优先返回它（可能用户手动装依赖）
            locateGameRoot(named)?.let { return it }
        }

        // 其次找含 node_modules 的
        children.firstOrNull { isGameRoot(it) }?.let { return it }

        // 最后一层递归
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
