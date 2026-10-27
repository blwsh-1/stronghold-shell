package com.stronghold.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 多人联机服务器地址的本地存储。
 *
 * 设计要点：
 *   - 内存缓存为唯一真相源，避免 SharedPreferences 频繁反序列化
 *   - 所有写操作加锁，防止并发覆盖
 *   - 地址规范化在这里统一处理，UI 层只负责展示错误
 */
object ServerStore {

    private const val PREFS = "stronghold_servers"
    private const val KEY = "servers_json"

    /** 内存缓存，null 表示尚未加载 */
    private var cache: MutableList<ServerEntry>? = null
    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取全部服务器，按最近游玩倒序 */
    fun load(context: Context): List<ServerEntry> = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        list.sortedByDescending { it.lastPlayed }
    }

    private fun readFromDisk(context: Context): MutableList<ServerEntry> {
        val raw = prefs(context).getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<ServerEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val address = o.optString("address", "").trim()
                if (address.isEmpty()) continue
                out.add(
                    ServerEntry(
                        id = o.optString("id", "").ifEmpty { UUID.randomUUID().toString() },
                        name = o.optString("name", ""),
                        address = address,
                        lastPlayed = o.optLong("lastPlayed", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            FileLogger.log("[ServerStore] 解析已保存服务器失败: ${e.message}")
            return mutableListOf()
        }
        return out
    }

    private fun writeToDisk(context: Context, list: List<ServerEntry>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("address", s.address)
                    put("lastPlayed", s.lastPlayed)
                }
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** 新增或更新一条 */
    fun upsert(context: Context, entry: ServerEntry) = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        writeToDisk(context, list)
    }

    /** 删除一条 */
    fun delete(context: Context, id: String) = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        list.removeAll { it.id == id }
        writeToDisk(context, list)
        Unit
    }

    /** 记录最近游玩时间 */
    fun touch(context: Context, id: String) = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastPlayed = System.currentTimeMillis())
            writeToDisk(context, list)
        }
    }

    /** 按 id 取一条 */
    fun find(context: Context, id: String): ServerEntry? = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        list.firstOrNull { it.id == id }
    }

    // ==================== 地址规范化 ====================

    /** 规范化结果：成功时 address 非空，失败时 error 非空 */
    data class Result(val address: String?, val error: String?)

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val DOMAIN = Regex(
        "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
            "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"
    )
    private val SCHEME = Regex("^(https?|wss?)://", RegexOption.IGNORE_CASE)

    /**
     * 把用户输入变成 host:port。
     *
     *   "120.77.178.254:3000"        → 120.77.178.254:3000
     *   "http://192.168.1.5:3000/"   → 192.168.1.5:3000
     *   "ws://example.com"           → example.com:3000
     *   "10.0.0.2:3000/ws?x=1"       → 10.0.0.2:3000
     */
    fun normalizeAddress(input: String): Result {
        var s = input.trim()
        if (s.isEmpty()) return Result(null, "地址不能为空")

        // 剥掉协议前缀
        s = s.replace(SCHEME, "")
        // 去掉路径 / 查询 / 片段
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        s = s.trim()
        if (s.isEmpty()) return Result(null, "地址无效")

        // 用户可能粘了 "host:port" 也可能只给了 host
        val host: String
        val port: String
        val colon = s.lastIndexOf(':')
        if (colon >= 0) {
            host = s.substring(0, colon).trim()
            port = s.substring(colon + 1).trim().ifEmpty { "3000" }
        } else {
            host = s
            port = "3000"
        }

        if (host.isEmpty()) return Result(null, "主机名不能为空")

        val isLocalhost = host.equals("localhost", true)
        val isIpv4 = IPV4.matches(host)
        if (!isLocalhost && !isIpv4 && !DOMAIN.matches(host)) {
            return Result(null, "主机名格式不正确")
        }
        if (isIpv4 && host.split(".").any { (it.toIntOrNull() ?: 999) > 255 }) {
            return Result(null, "IP 地址超出范围")
        }

        val portNum = port.toIntOrNull() ?: return Result(null, "端口号必须是数字")
        if (portNum !in 1..65535) return Result(null, "端口号超出范围（1-65535）")

        return Result("$host:$portNum", null)
    }
}

/** 一条服务器记录 */
data class ServerEntry(
    val id: String,
    val name: String,
    val address: String,
    val lastPlayed: Long
) {
    /** 界面上显示的标题：没起名字就显示地址 */
    val displayName: String get() = if (name.isBlank()) address else name
}
