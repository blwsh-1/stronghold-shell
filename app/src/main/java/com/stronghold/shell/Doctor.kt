package com.stronghold.shell

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * 诊断工具 —— 对应上游 `node tools/doctor.mjs` 的移动版。
 *
 * 检查项：
 *   1. 游戏包完整性（node_modules / 入口 / 素材）
 *   2. Node 是否就绪（端口 3000 /healthz）
 *   3. 端口占用情况
 *   4. 本地服务地址（给联机用）
 *   5. 磁盘空间
 *   6. 版本信息
 *
 * 输出一串可读文本，直接显示在「诊断」页里，方便用户复制给我看。
 */
object Doctor {

    private const val TAG = "StrongholdShell"

    data class Check(val title: String, val ok: Boolean, val detail: String)

    private data class Report(val checks: List<Check>, val localIp: String, val version: String, val packVersion: String)

    /** 跑一遍全部检查（IO 线程） */
    fun run(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 卫戍协议启动器 · 诊断报告 ═══")
        sb.appendLine()

        // ---- 1. 环境 ----
        sb.appendLine("【环境】")
        sb.appendLine("  启动器版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("  安卓版本：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("  机型：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("  ABI：${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()

        // ---- 2. 游戏包 ----
        sb.appendLine("【游戏包】")
        val packs = PackManager.listPacks(context)
        if (packs.isEmpty()) {
            sb.appendLine("  ✗ 未安装任何游戏包")
        } else {
            val activeId = PackManager.activeId(context)
            for (p in packs) {
                val mark = if (p.id == activeId) "★激活" else "     "
                sb.appendLine("  $mark ${p.id}  v${p.version}  ${p.kind}  ${p.sizeMb}MB")
                // 完整性细查（只查激活的，省时间）
                if (p.id == activeId) {
                    val nm = File(p.dir, "node_modules")
                    sb.appendLine("       node_modules：${if (nm.isDirectory) "✓ 存在（${nm.list()?.size ?: 0} 个包）" else "✗ 缺失"}")
                    val entry = GamePack.findEntry(p.dir)
                    sb.appendLine("       入口脚本：${if (entry != null) "✓ $entry" else "✗ 未找到"}")
                    val assets = File(p.dir, "public/assets")
                    if (assets.isDirectory) {
                        sb.appendLine("       素材：✓ 存在")
                    } else {
                        sb.appendLine("       素材：✗ 缺失（可能是 lite 包，需联网下载）")
                    }
                    val plugins = File(p.dir, "plugins")
                    sb.appendLine("       插件目录：${if (plugins.isDirectory) "✓ 支持（0.3.0+）" else "✗ 无（0.3.0 之前）"}")
                }
            }
        }
        sb.appendLine()

        // ---- 3. 端口与服务 ----
        sb.appendLine("【本地服务】")
        val health = checkHealth()
        sb.appendLine("  /healthz：${if (health.first) "✓ 正常" else "✗ 无响应"}")
        if (health.second.isNotEmpty()) sb.appendLine("    ${health.second}")
        sb.appendLine("  Node 线程：${if (NodeRunner.isRunning()) "✓ 运行中" else "✗ 未运行"}")
        sb.appendLine()

        // ---- 4. 局域网地址 ----
        sb.appendLine("【联机地址】")
        val ip = localIp()
        if (ip != null) {
            sb.appendLine("  ✓ http://$ip:3000")
            sb.appendLine("    把上面地址发给同一个 Wi-Fi 下的朋友即可联机")
        } else {
            sb.appendLine("  ✗ 未检测到局域网 IPv4 地址（可能未连 Wi-Fi）")
        }
        sb.appendLine()

        // ---- 5. 插件 ----
        sb.appendLine("【插件】")
        val activeDir = PackManager.activeDir(context)
        val plugins = PluginManager.list(activeDir)
        if (plugins.isEmpty()) {
            sb.appendLine("  已装插件：0 个")
        } else {
            sb.appendLine("  已装插件：${plugins.size} 个")
            for (p in plugins) {
                val v = if (p.valid) "✓" else "✗"
                sb.appendLine("    $v ${p.id} v${p.version} [${p.classLabel}]")
                if (!p.valid && p.error != null) sb.appendLine("        ${p.error}")
            }
        }
        val cfg = PluginManager.readConfig(activeDir)
        if (cfg.length() > 0) {
            sb.appendLine("  服主策略：${cfg.toString()}")
        }
        sb.appendLine()

        // ---- 6. 存储 ----
        sb.appendLine("【存储】")
        val files = context.filesDir
        val usableMb = files.usableSpace / 1048576
        sb.appendLine("  可用空间：${usableMb} MB")
        val packsMb = packs.sumOf { it.sizeMb }
        sb.appendLine("  游戏包占用：${packsMb} MB")
        val dl = PackDownloader.downloadDir(context)
        val dlMb = (dl.walkTopDown().filter { it.isFile }.sumOf { it.length() }) / 1048576
        sb.appendLine("  下载缓存：${dlMb} MB${if (dlMb > 100) "（可清理）" else ""}")
        sb.appendLine()

        // ---- 7. 最近日志 ----
        sb.appendLine("【最近日志】")
        sb.appendLine(FileLogger.tail(15))
        sb.appendLine()
        sb.appendLine("═══ 报告结束 ═══")
        return sb.toString()
    }

    /** 检查 /healthz，返回 (是否健康, 详情) */
    private fun checkHealth(): Pair<Boolean, String> {
        return try {
            val conn = URL("http://127.0.0.1:3000/healthz").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            val body = try {
                if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText().take(300) }
                else ""
            } catch (_: Exception) { "" }
            conn.disconnect()
            Pair(code in 200..299, if (body.isNotEmpty()) body else "HTTP $code")
        } catch (e: Exception) {
            Pair(false, e.message ?: "连接失败")
        }
    }

    /** 取局域网 IPv4（优先 wlan0） */
    fun localIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            var fallback: String? = null
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan")) return ip
                        if (fallback == null) fallback = ip
                    }
                }
            }
            return fallback
        } catch (e: Exception) {
            Log.w(TAG, "取 IP 失败: ${e.message}")
            return null
        }
    }

    /** 静默获取（给设置页显示用） */
    fun quickStatus(context: Context): JSONObject {
        val j = JSONObject()
        try {
            j.put("hasPack", PackManager.activeId(context) != null)
            j.put("activeId", PackManager.activeId(context) ?: "")
            j.put("serverAlive", checkHealth().first)
            j.put("localIp", localIp() ?: "")
            j.put("usableMb", context.filesDir.usableSpace / 1048576)
        } catch (_: Exception) {}
        return j
    }
}