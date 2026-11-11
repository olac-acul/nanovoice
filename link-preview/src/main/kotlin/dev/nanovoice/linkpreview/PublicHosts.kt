package dev.nanovoice.linkpreview

import dev.nanovoice.core.net.isPublicAddress
import dev.nanovoice.core.net.literalAddress
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

// A link preview must never reach into the phone's own network (a router, the NanoClaw server on Tailscale,
// localhost): the address comes from text the agent wrote. What is public is decided in core (isPublicAddress).

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
    // Written like an address but not one Java can read: refused, as before.
    return literalAddress(h)?.let { !isPublicAddress(it) } ?: true
}
