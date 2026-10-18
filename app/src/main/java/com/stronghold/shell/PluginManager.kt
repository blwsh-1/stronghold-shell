package com.stronghold.shell

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 官方插件管理器 —— 对接上游 0.3.0+ 的 plugins/ 体系。
 *
 * 【上游规范（实测自 v0.3.1 的 plugins/README.md）】
 *   plugins/<id>/plugin.json   ← 清单
 *   - id：文件夹名，2~32 位小写字母/数字/`-`
 *   - 以 `_` 开头的文件夹永不加载（模板目录）
 *   - class：lang / cosmetic / content / ui / script / trusted
 *   - manifest 版本号当前为 2，api 为 1
 *
 * 【启动器能做什么】
 *   - 列出 plugins/ 下所有插件及其 class、是否合法
 *   - 从 zip 安装（兼容 HTTP 下载与本地文件选择）
 *   - 删除、导出
 *   - 生成 plugins/config.json（服主策略：允许哪些插件）
 *
 * 【为什么还要生成 config.json】上游 docs 说服主可用 config.json 限定
 *   哪些插件在房间里可用；手机上没命令行，启动器代为生成最省事。
 */
object PluginManager {

    private const val TAG = "StrongholdShell"

    /** 插件类别 → 中文说明 */
    private val CLASS_LABELS = mapOf(
        "lang" to "语言包",
        "cosmetic" to "外观（美术/模型/音频）",
        "content" to "内容（数据/数值/模式）",
        "ui" to "界面组件",
        "script" to "脚本（默认关闭）",
        "trusted" to "原生受信任（默认关闭）"
    )

    /** 一个已安装插件 */
    data class PluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val cls: String,
        val app: String,          // 兼容的游戏版本范围，如 ">=0.3.0 <0.4.0"
        val api: Int,
        val dir: File,
        val valid: Boolean,       // plugin.json 是否合法
        val error: String? = null // 不合法时的原因
    ) {
        val classLabel: String get() = CLASS_LABELS[cls] ?: cls
    }

    /** 插件根目录：<游戏根>/plugins */
    fun pluginsDir(gameRoot: File?): File? {
        if (gameRoot == null) return null
        val d = File(gameRoot, "plugins")
        return if (d.isDirectory) d else null
    }

    /**
     * 列出所有插件。`_` 开头的目录（模板）跳过 —— 与上游一致。
     */
    fun list(gameRoot: File?): List<PluginInfo> {
        val dir = pluginsDir(gameRoot) ?: return emptyList()
        val out = ArrayList<PluginInfo>()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            if (d.name.startsWith("_")) return@forEach
            out.add(parsePlugin(d))
        }
        return out.sortedBy { it.id }
    }

    private fun parsePlugin(dir: File): PluginInfo {
        val manifest = File(dir, "plugin.json")
        if (!manifest.isFile) {
            return PluginInfo(
                id = dir.name, name = dir.name, version = "?", cls = "unknown",
                app = "", api = 0, dir = dir, valid = false,
                error = "缺少 plugin.json"
            )
        }
        return try {
            val j = JSONObject(manifest.readText())
            val nameObj = j.opt("name")
            val name = when (nameObj) {
                is JSONObject -> nameObj.optString("zh", nameObj.optString("en", dir.name))
                is String -> nameObj
                else -> dir.name
            }
            val id = j.optString("id", dir.name)
            // id 规则校验（上游要求 2~32 位小写字母数字横线）
            val idOk = id.matches(Regex("[a-z0-9-]{2,32}")) && id == dir.name
            PluginInfo(
                id = dir.name,
                name = name,
                version = j.optString("version", "?"),
                cls = j.optString("class", "unknown"),
                app = j.optString("app", ""),
                api = j.optInt("api", 0),
                dir = dir,
                valid = idOk,
                error = if (!idOk) "id 不符合规范（应为 2~32 位小写字母/数字/-，且与文件夹同名）" else null
            )
        } catch (e: Exception) {
            PluginInfo(
                id = dir.name, name = dir.name, version = "?", cls = "unknown",
                app = "", api = 0, dir = dir, valid = false,
                error = "plugin.json 解析失败：${e.message}"
            )
        }
    }

    /**
     * 从 Uri 安装插件。
     * 兼容两种 zip 结构：
     *   ① zip 根目录直接是 plugin.json
     *   ② zip 里裹了一层 <id>/plugin.json
     *
     * @return 安装成功的插件目录；失败 null
     */
    fun installFromUri(gameRoot: File?, uri: Uri, context: Context): File? {
        val plugins = pluginsDir(gameRoot)
        if (plugins == null) {
            FileLogger.log("安装插件失败：游戏包里没有 plugins/ 目录（需要 0.3.0 及以上版本）")
            return null
        }
        val tmp = File(plugins, "_installing_${System.currentTimeMillis()}")
        tmp.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val name = e.name
                        if (name.contains("..")) {   // 目录穿越防护
                            Log.w(TAG, "跳过可疑条目: $name")
                        } else if (name.endsWith("/")) {
                            File(tmp, name).mkdirs()
                        } else {
                            val outFile = File(tmp, name)
                            if (!outFile.canonicalPath.startsWith(tmp.canonicalPath)) {
                                Log.w(TAG, "跳过穿越条目: $name")
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { fos -> zis.copyTo(fos) }
                            }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            } ?: run {
                tmp.deleteRecursively()
                return null
            }

            // 定位 plugin.json 所在目录
            var root = tmp
            if (!File(root, "plugin.json").isFile) {
                val sub = tmp.listFiles()?.firstOrNull {
                    it.isDirectory && File(it, "plugin.json").isFile
                }
                if (sub != null) root = sub
            }
            if (!File(root, "plugin.json").isFile) {
                FileLogger.log("安装插件失败：zip 里找不到 plugin.json")
                tmp.deleteRecursively()
                return null
            }

            val j = JSONObject(File(root, "plugin.json").readText())
            val id = j.optString("id", "")
            if (!id.matches(Regex("[a-z0-9-]{2,32}"))) {
                FileLogger.log("安装插件失败：id「$id」不符合规范（2~32 位小写字母/数字/-）")
                tmp.deleteRecursively()
                return null
            }

            // 落地到 plugins/<id>/
            val dest = File(plugins, id)
            dest.deleteRecursively()
            if (!root.renameTo(dest)) {
                copyDir(root, dest)
            }
            tmp.deleteRecursively()
            FileLogger.log("插件安装成功：$id")
            return dest
        } catch (e: Throwable) {
            Log.e(TAG, "安装插件失败", e)
            FileLogger.log("安装插件失败：${e.message}")
            tmp.deleteRecursively()
            return null
        }
    }

    /** 删除插件 */
    fun uninstall(gameRoot: File?, id: String): Boolean {
        val dir = pluginsDir(gameRoot) ?: return false
        if (id.startsWith("_")) return false   // 保护模板
        val ok = File(dir, id).deleteRecursively()
        if (ok) FileLogger.log("插件已删除：$id")
        return ok
    }

    /**
     * 写服主策略 plugins/config.json。
     *
     * @param allow 允许的插件 id 列表；null / 空表示不限制
     * @param enableScripts 是否允许脚本类插件（上游对应 SP_PLUGIN_SCRIPTS=sandbox）
     */
    fun writeConfig(gameRoot: File?, allow: List<String>?, enableScripts: Boolean): Boolean {
        val dir = pluginsDir(gameRoot) ?: return false
        return try {
            val j = JSONObject().apply {
                put("manifest", 2)
                if (!allow.isNullOrEmpty()) {
                    put("allow", JSONArray(allow))
                }
                put("scripts", if (enableScripts) "sandbox" else "off")
            }
            File(dir, "config.json").writeText(j.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "写插件配置失败", e)
            false
        }
    }

    /** 读服主策略（不存在则返回默认） */
    fun readConfig(gameRoot: File?): JSONObject {
        val dir = pluginsDir(gameRoot) ?: return JSONObject()
        val f = File(dir, "config.json")
        return try {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) { JSONObject() }
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