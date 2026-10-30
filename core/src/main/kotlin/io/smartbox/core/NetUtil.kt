package io.smartbox.core

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /**
     * 이 기기의 로컬 네트워크 IPv4 주소. Wi-Fi·핫스팟 인터페이스를 우선한다.
     * (스마트폰 핫스팟을 켜면 보통 192.168.x.1 이 된다.)
     */
    fun localIpv4(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress } }
        val preferred = listOf("wlan", "swlan", "ap", "eth")
        return candidates.sortedBy { (name, _) ->
            preferred.indexOfFirst { name.startsWith(it) }.let { if (it < 0) 99 else it }
        }.firstOrNull()?.second
    }
}
