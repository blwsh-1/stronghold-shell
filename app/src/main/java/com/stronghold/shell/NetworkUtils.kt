package com.stronghold.shell

import java.net.Inet4Address
import java.net.NetworkInterface

/** 网络工具：获取本机局域网 IPv4（供「局域网共享」用）。 */
object NetworkUtils {

    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}