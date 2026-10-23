package utils

import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
fun activeIpv4Addresses(): List<String> = memScoped {
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
