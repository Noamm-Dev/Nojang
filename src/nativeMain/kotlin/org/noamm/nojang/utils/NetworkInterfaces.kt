@file:OptIn(ExperimentalForeignApi::class)

package org.noamm.nojang.utils

import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.*

fun publicIpv4Addresses() = activeIpv4Addresses().filterNot {
    isLoopback(it) || isSiteLocal(it) || isLinkLocal(it) || isTailscale(it)
}.distinct()

private fun activeIpv4Addresses(): List<String> = memScoped {
    val head = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(head.ptr) != 0) error("getifaddrs() failed with errno $errno")

    try {
        generateSequence(head.value) { it.pointed.ifa_next }
            .map { it.pointed }
            .filter { it.ifa_flags.toInt() and IFF_UP != 0 && it.ifa_flags.toInt() and IFF_LOOPBACK == 0 }
            .mapNotNull { it.ifa_addr?.takeIf { addr -> addr.pointed.sa_family.toInt() == AF_INET } }
            .map { addr ->
                val buffer = allocArray<ByteVar>(INET_ADDRSTRLEN)
                inet_ntop(AF_INET, addr.reinterpret<sockaddr_in>().pointed.sin_addr.ptr, buffer, INET_ADDRSTRLEN.convert())
                buffer.toKString()
            }
            .toList()
    }
    finally {
        freeifaddrs(head.value)
    }
}

private fun octets(ip: String) = ip.split('.').map(String::toInt)
private fun isLoopback(ip: String) = octets(ip)[0] == 127

private fun isSiteLocal(ip: String): Boolean {
    val (a, b) = octets(ip)
    return a == 10 || (a == 172 && b in 16 .. 31) || (a == 192 && b == 168)
}

private fun isLinkLocal(ip: String): Boolean {
    val (a, b) = octets(ip)
    return a == 169 && b == 254
}

private fun isTailscale(ip: String): Boolean {
    val (a, b) = octets(ip)
    return a == 100 && b >= 64 && b <= 127
}
