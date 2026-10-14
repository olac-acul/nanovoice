package dev.nanovoice.linkpreview

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * True for addresses that belong to the open internet. A link preview must never reach into the phone's own
 * network (a router, the NanoClaw server on Tailscale, localhost): the address comes from text the agent wrote.
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

/** Resolves names as usual but refuses a host if any of its addresses is not public. */
object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        if (all.isEmpty() || !all.all(::isPublicAddress)) throw UnknownHostException("$hostname is not a public host")
        return all
    }
}

/** True when [host] is written as an address (OkHttp does not ask the Dns for those) and it is not public. */
internal fun isPrivateLiteral(host: String): Boolean {
    val h = host.removePrefix("[").removeSuffix("]")
    val looksLikeAddress = h.contains(':') || h.all { it.isDigit() || it == '.' }
    if (!looksLikeAddress) return false
    return runCatching { !isPublicAddress(InetAddress.getByName(h)) }.getOrDefault(true)
}
