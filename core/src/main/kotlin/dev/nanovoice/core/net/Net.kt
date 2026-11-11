package dev.nanovoice.core.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * True for addresses of the open internet; false for the phone's own networks: loopback, private and link-local
 * ranges, CGNAT 100.64.0.0/10 (Tailscale), IPv6 unique local fc00::/7 (Tailscale's fd7a:…), multicast, reserved.
 * One rule for everything that must tell a home address from a public one (link previews, the server's address).
 */
fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    val b = address.address
    return when (address) {
        is Inet4Address -> {
            val a = b[0].toInt() and 0xFF
            val c = b[1].toInt() and 0xFF
            val third = b[2].toInt() and 0xFF
            !(a == 0 || (a == 100 && c in 64..127) || (a == 192 && c == 0 && third == 0) || (a == 198 && c in 18..19) || a >= 240)
        }
        is Inet6Address -> (b[0].toInt() and 0xFE) != 0xFC // fc00::/7, unique local addresses
        else -> false
    }
}

/** [host] written as an IP address (brackets allowed), parsed without asking any DNS; null for a name. */
fun literalAddress(host: String): InetAddress? {
    val h = host.removePrefix("[").removeSuffix("]")
    val looksLikeAddress = h.contains(':') || (h.isNotEmpty() && h.all { it.isDigit() || it == '.' })
    if (!looksLikeAddress) return null
    return runCatching { InetAddress.getByName(h) }.getOrNull()
}

/**
 * The whole stream when it holds at most [maxBytes], null when it holds more: only one byte past the limit is read,
 * so a huge answer never fills the memory. (InputStream.readNBytes needs Android 13.)
 */
fun InputStream.readAtMostOrNull(maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() <= maxBytes) {
        val n = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray().takeIf { it.size <= maxBytes }
}
