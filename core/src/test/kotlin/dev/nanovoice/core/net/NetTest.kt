package dev.nanovoice.core.net

import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun publicAndHomeAddresses() {
        for (ok in listOf("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111", "100.63.255.255")) assertTrue(isPublicAddress(ip(ok)), ok)
        for (home in listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.5", "0.0.0.0", "::1", // privacy-scan: allow (example addresses the code must classify)
            "fe80::1", "fd7a:115c:a1e0::1", "224.0.0.1", "240.0.0.1",
        )) assertFalse(isPublicAddress(ip(home)), home)
    }

    @Test fun literalsAreParsedWithoutDns() {
        assertEquals(ip("192.168.1.5"), literalAddress("192.168.1.5")) // privacy-scan: allow (example address)
        assertEquals(ip("fd7a:115c:a1e0::1"), literalAddress("[fd7a:115c:a1e0::1]"))
        assertNull(literalAddress("myhost"))
        assertNull(literalAddress("example.test"))
        assertNull(literalAddress(""))
    }

    @Test fun readsAtMostTheLimit() {
        assertContentEquals(ByteArray(10) { 1 }, ByteArrayInputStream(ByteArray(10) { 1 }).readAtMostOrNull(10))
        assertNull(ByteArrayInputStream(ByteArray(11)).readAtMostOrNull(10))
        assertContentEquals(ByteArray(0), ByteArrayInputStream(ByteArray(0)).readAtMostOrNull(10))
        val big = ByteArray(100_000)
        assertNull(ByteArrayInputStream(big).readAtMostOrNull(40_000))
    }
}
