package dev.nanovoice.linkpreview

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkMetadataTest {
    private val page = "https://www.example.org/post/1"

    @Test fun hostileMarkupIsReadInLinearTime() {
        val pages = listOf(
            "<meta " + "a".repeat(200_000),
            "<meta " + "a-".repeat(100_000) + ">",
            "<title>" + "x".repeat(200_000),
            "<title>".repeat(30_000),
        )
        for (html in pages) {
            val started = System.nanoTime()
            parseLinkMetadata(html, "https://example.org/")
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 2_000, "took $ms ms")
        }
    }

    @Test fun prefersOpenGraphOverThePlainTags() {
        val html = """
            <html><head><title>Titolo semplice</title>
            <meta name="description" content="Descrizione semplice">
            <meta property="og:title" content="Titolo OG &amp; amici">
            <meta property="og:description" content='Descrizione "OG"'>
            <meta property="og:image" content="/img/cover.png">
            <meta property="og:site_name" content="Esempio">
            </head></html>
        """.trimIndent()
        val m = parseLinkMetadata(html, page)!!
        assertEquals("Titolo OG & amici", m.title)
        assertEquals("Descrizione \"OG\"", m.description)
        assertEquals("https://www.example.org/img/cover.png", m.imageUrl)
        assertEquals("Esempio", m.siteName)
    }

    @Test fun fallsBackToTitleAndDescriptionAndTheHost() {
        val html = "<head><title>\n  Solo   titolo </title><meta name=description content=breve></head>"
        val m = parseLinkMetadata(html, page)!!
        assertEquals("Solo titolo", m.title)
        assertEquals("breve", m.description)
        assertNull(m.imageUrl)
        assertEquals("example.org", m.siteName)
    }

    @Test fun readsContentBeforeAndAfterThePropertyAttribute() {
        val m = parseLinkMetadata("""<meta content="Titolo" property="og:title">""", page)!!
        assertEquals("Titolo", m.title)
    }

    @Test fun noTitleMeansNoPreview() {
        assertNull(parseLinkMetadata("<head><meta name=description content=x></head>", page))
    }

    @Test fun onlyHttpsImagesAreKept() {
        val m = parseLinkMetadata("""<meta property="og:title" content="T"><meta property="og:image" content="http://example.org/a.png">""", page)!!
        assertNull(m.imageUrl)
    }

    @Test fun decodesNumericEntities() {
        assertEquals("L'amico — è qui", decodeEntities("L&#39;amico &mdash; &#xE8; qui"))
    }

    @Test fun publicAndPrivateAddresses() {
        fun ip(s: String) = InetAddress.getByName(s)
        for (ok in listOf("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111")) assertTrue(isPublicAddress(ip(ok)), ok)
        for (bad in listOf("127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "169.254.1.1", "100.101.102.105", "100.127.255.1", "0.0.0.0", "198.18.0.1", "255.255.255.255", "::1", "fe80::1", "fc00::1", "fd12:3456::1")) { // privacy-scan: allow (an example address the code must classify)
            assertFalse(isPublicAddress(ip(bad)), bad)
        }
        assertTrue(isPublicAddress(ip("100.63.255.255")))
    }

    @Test fun literalHosts() {
        assertTrue(isPrivateLiteral("127.0.0.1"))
        assertTrue(isPrivateLiteral("[::1]"))
        assertTrue(isPrivateLiteral("100.101.102.105"))
        assertFalse(isPrivateLiteral("8.8.8.8"))
        assertFalse(isPrivateLiteral("example.org"))
    }
}
