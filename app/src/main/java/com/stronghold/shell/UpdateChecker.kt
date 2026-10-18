package com.stronghold.shell

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 上游版本检测 —— 查 sganggs/Stronghold-Protocol 的最新 Release。
 *
 * 【为什么需要】启动器要像 HMCL/PCL2 那样「知道有没有新版本」，
 * 而不是靠用户自己去 GitHub 刷。
 *
 * 【接口】GitHub REST API: /repos/{owner}/{repo}/releases/latest
 *        匿名调用限额 60 次/小时，启动时查一次完全够用。
 *
 * 【降级】API 不通时（限流/网络），回退到解析 /releases/latest 的重定向
 *        拿 tag 名，至少能知道版本号。
 */
object UpdateChecker {

    private const val TAG = "StrongholdShell"

    const val OWNER = "sganggs"
    const val REPO = "Stronghold-Protocol"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 一个 Release 资产 */
    data class Asset(val name: String, val sizeBytes: Long, val downloadUrl: String) {
        val sizeMb: Double get() = sizeBytes / 1048576.0
    }

    /** 最新版本信息 */
    data class ReleaseInfo(
        val tag: String,              // 例如 v0.3.1
        val version: String,          // 去掉 v 前缀，例如 0.3.1
        val name: String,             // 例如 卫戍协议：盟约 v0.3.1
        val publishedAt: String,
        val body: String,             // changelog 正文
        val assets: List<Asset>
    ) {
        /** 完整包（不带 -lite / -update 后缀的大 zip） */
        val fullPack: Asset?
            get() = assets.firstOrNull {
                it.name.endsWith(".zip") &&
                    !it.name.contains("-lite") &&
                    !it.name.contains("-update") &&
                    !it.name.contains("-assets") &&
                    !it.name.contains("-win-")
            }

        /** 精简包 */
        val litePack: Asset?
            get() = assets.firstOrNull { it.name.contains("-lite") }

        /** 更新包（增量） */
        val updatePack: Asset?
            get() = assets.firstOrNull { it.name.contains("-update") }
    }

    /**
     * 查询最新版本。必须在 IO 线程调用。
     * @return 成功返回 ReleaseInfo，网络失败返回 null
     */
    fun fetchLatest(): ReleaseInfo? {
        // ---- 通道 1：官方 API ----
        try {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
            val code = conn.responseCode
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return parseApiJson(text)
            }
            conn.disconnect()
            Log.w(TAG, "GitHub API 返回 $code")
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API 请求失败: ${e.message}")
        }

        // ---- 通道 2：走代理的 API（国内更稳） ----
        try {
            val conn = URL("https://ghproxy.net/$API_LATEST").openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return parseApiJson(text)
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "代理 API 请求失败: ${e.message}")
        }

        return null
    }

    private fun parseApiJson(text: String): ReleaseInfo? {
        return try {
            val j = JSONObject(text)
            val tag = j.optString("tag_name", "")
            if (tag.isEmpty()) return null
            val assets = ArrayList<Asset>()
            val arr = j.optJSONArray("assets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    assets.add(
                        Asset(
                            name = a.optString("name", ""),
                            sizeBytes = a.optLong("size", 0),
                            downloadUrl = a.optString("browser_download_url", "")
                        )
                    )
                }
            }
            ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                name = j.optString("name", tag),
                publishedAt = j.optString("published_at", ""),
                body = j.optString("body", ""),
                assets = assets
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 Release JSON 失败", e)
            null
        }
    }

    /**
     * 比较两个版本号，返回 true 表示 remote 比 local 新。
     * 支持 "0.3.1" / "v0.3.1" / "0.3" 这类格式；解析失败时保守返回 false。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parseVersion(v: String): List<Int>? {
        val cleaned = v.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split(".", "-", "+").take(3)
        if (parts.isEmpty()) return null
        return try {
            parts.map { p -> p.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        } catch (e: Exception) {
            null
        }
    }
}
