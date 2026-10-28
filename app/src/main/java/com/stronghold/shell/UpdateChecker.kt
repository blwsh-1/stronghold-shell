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
 * 【接口】按可靠性排序尝试三个通道：
 *   1. releases.atom —— RSS 订阅源，不受 API 限流，最稳
 *   2. GitHub REST API —— 返回信息最全（含资产大小），但匿名限 60 次/小时
 *   3. ghproxy 代理的 API —— API 直连不通时的备选
 *
 * 【为什么先试 atom】API 匿名限额被打满后会一直 403，
 *   而 atom 是公开 RSS，随便取。日常查版本用 atom 足够。
 */
object UpdateChecker {

    private const val TAG = "StrongholdShell"

    const val OWNER = "sganggs"
    const val REPO = "Stronghold-Protocol"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val ATOM_FEED = "https://github.com/$OWNER/$REPO/releases.atom"

    /** 一个 Release 资产 */
    data class Asset(val name: String, val sizeBytes: Long, val downloadUrl: String) {
        val sizeMb: Double get() = sizeBytes / 1048576.0
    }

    /** 包类型：完整 / 精简 / 增量 */
    enum class PackKind(val label: String) {
        FULL("完整包"),
        LITE("精简包"),
        UPDATE("增量包")
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
    /**
     * 不跟随跳转，只读 302 的 Location 头，从中截出 tag。
     * 为什么比调 API 快：API 要返回几十 KB 的 JSON 再解析；这里只发一个请求、
     * 拿到响应头就断，实测比 API 快 2~3 倍。
     */
    private fun fetchTagFromLocation(): String? {
        val u = URL("https://github.com/$OWNER/$REPO/releases/latest")
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
            val code = conn.responseCode
            if (code !in 300..399) return null
            val loc = conn.getHeaderField("Location") ?: return null
            // Location 形如 https://github.com/owner/repo/releases/tag/launcher-v2.1.7
            val idx = loc.indexOf("/releases/tag/")
            if (idx < 0) return null
            val tag = loc.substring(idx + "/releases/tag/".length).trim().trimEnd('/')
            return if (tag.isEmpty()) null else tag
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun fetchLatest(): ReleaseInfo? {
        // ---- 通道 0：不跟随跳转，只读 Location 头拿版本号 ----
        // 实测：调 API 约 811ms，而这一步只发一个请求头就返回，明显更快。
        // GitHub 对 /releases/latest 会回 302，Location 形如 .../releases/tag/launcher-v2.1.7
        try {
            val tag = fetchTagFromLocation()
            if (tag != null) {
                val full = buildAssetUrl(tag, PackKind.FULL)
                Log.i(TAG, "Location 通道成功：$tag")
                return ReleaseInfo(
                    tag = tag,
                    version = tag.removePrefix("v"),
                    name = "卫戍协议：盟约 " + tag,
                    publishedAt = "",
                    body = "",
                    assets = listOf(
                        Asset(
                            name = full.substringAfterLast("/"),
                            sizeBytes = 0L,
                            downloadUrl = full
                        )
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location 通道失败: ${e.message}")
        }
        // ---- 通道 1：releases.atom（RSS，不受 API 限流） ----
        try {
            val info = fetchFromAtom()
            if (info != null) {
                Log.i(TAG, "atom 通道成功：${info.tag}")
                return info
            }
        } catch (e: Exception) {
            Log.w(TAG, "atom 通道失败: ${e.message}")
        }

        // ---- 通道 2：官方 API ----
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

        // ---- 通道 3：走代理的 API（国内更稳） ----
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

    /**
     * 从 releases.atom 拉最新版本。
     *
     * atom 的结构大致是：
     *   <entry>
     *     <updated>2026-10-17T...</updated>
     *     <link rel="alternate" type="text/html" href="https://github.com/.../releases/tag/v0.3.1"/>
     *     <title>v0.3.1</title>
     *     <content type="html">更新日志（转义过的 HTML）</content>
     *   </entry>
     * 只取第一个 entry 即可（atom 按时间倒序）。
     *
     * 注意：atom 不含资产列表，所以 assets 为空；下载时靠 URL 拼接规则
     *       （见 buildAssetUrl）反推，见 PackDownloader。
     */
    private fun fetchFromAtom(): ReleaseInfo? {
        val conn = URL(ATOM_FEED).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
        conn.setRequestProperty("Accept", "application/atom+xml,application/xml,text/xml")
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            Log.w(TAG, "atom 返回 $code")
            return null
        }
        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return parseAtom(xml)
    }

    /** 解析 atom XML，取第一条 release */
    fun parseAtom(xml: String): ReleaseInfo? {
        return try {
            // 只取第一个 <entry>...</entry>
            val start = xml.indexOf("<entry>")
            if (start < 0) return null
            val end = xml.indexOf("</entry>", start)
            if (end < 0) return null
            val entry = xml.substring(start + 7, end)

            // tag：优先从 tag 链接里取（最准），否则用 title
            var tag = ""
            val tagIdx = entry.indexOf("/releases/tag/")
            if (tagIdx > 0) {
                val tail = entry.substring(tagIdx + "/releases/tag/".length)
                tag = tail.substringBefore("\"").substringBefore("<").trim()
            }
            if (tag.isEmpty()) {
                tag = extractTag(entry, "title").trim()
            }
            if (tag.isEmpty()) return null

            val published = extractTag(entry, "updated").trim()
            val body = extractTag(entry, "content").trim()

            ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                name = "卫戍协议：盟约 $tag",
                publishedAt = published,
                body = unescapeHtml(body),
                assets = emptyList()   // atom 不含资产信息
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 atom 失败", e)
            null
        }
    }

    /** 取 <name>...</name> 之间的内容（不处理 CDATA 嵌套的复杂情况） */
    private fun extractTag(xml: String, name: String): String {
        val open = "<" + name
        val i = xml.indexOf(open)
        if (i < 0) return ""
        val gt = xml.indexOf('>', i)
        if (gt < 0) return ""
        val close = "</" + name + ">"
        val j = xml.indexOf(close, gt)
        if (j < 0) return ""
        return xml.substring(gt + 1, j)
    }

    /** atom 的 content 是转义过的 HTML，简单反转义几个常用实体 */
    private fun unescapeHtml(s: String): String {
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /**
     * 已知 tag 时，按上游 Release 的命名规则拼出资产 URL。
     *
     * 【为什么需要】atom 通道拿不到资产列表和文件大小；
     * 但上游资产命名是固定的：
     *   Stronghold-Protocol-<tag>.zip          （完整包）
     *   Stronghold-Protocol-<tag>-lite.zip     （精简包）
     *   Stronghold-Protocol-<tag>-update.zip   （增量包）
     * 所以可以按需拼 URL，size 给 0 表示未知（下载器会自行探测）。
     */
    fun buildAssetUrl(tag: String, kind: PackKind): String {
        val base = "https://github.com/$OWNER/$REPO/releases/download/$tag/"
        val file = when (kind) {
            PackKind.FULL -> "Stronghold-Protocol-$tag.zip"
            PackKind.LITE -> "Stronghold-Protocol-$tag-lite.zip"
            PackKind.UPDATE -> "Stronghold-Protocol-$tag-update.zip"
        }
        return base + file
    }

    /**
     * 取得某类包的资产信息。
     * atom 通道没有资产列表，此时用命名规则拼出 URL、大小记为 0（下载器会探测）；
     * API 通道直接用真实资产（含精确大小，便于校验）。
     */
    fun pickAsset(info: ReleaseInfo, kind: PackKind): Asset? {
        val real = when (kind) {
            PackKind.FULL -> info.fullPack
            PackKind.LITE -> info.litePack
            PackKind.UPDATE -> info.updatePack
        }
        if (real != null) return real
        // atom 通道：靠命名规则反推
        val url = buildAssetUrl(info.tag, kind)
        val name = url.substringAfterLast("/")
        return Asset(name = name, sizeBytes = 0L, downloadUrl = url)
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
