package dev.nanovoice.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPolicyTest {
    @Test fun `a web link is handed on in the form whose host was checked`() {
        assertEquals(WebLink("https://example.org/a?b=1", "example.org"), LinkPolicy.webLink("  https://Example.org/a?b=1 "))
        assertEquals("https://example.org/", LinkPolicy.webLink("https://example.org")?.url)
        // Characters java.net.URI refuses and browsers accept: the host is the one that opens, the rest encoded.
        assertEquals(WebLink("https://evil.example/%7Bx%7D", "evil.example"), LinkPolicy.webLink("https://evil.example/{x}"))
        assertEquals("evil.example", LinkPolicy.webLink("https://evil.example\\@bank.example/")?.host)
        assertEquals(java.net.IDN.toASCII("città.it"), LinkPolicy.webLink("https://città.it/")?.host)
    }

    @Test fun `only http and https, with https alone for actions, and never a user name`() {
        assertEquals("http://example.org/", LinkPolicy.webLink("http://example.org")?.url)
        assertNull(LinkPolicy.webLink("http://example.org", httpsOnly = true))
        for (bad in listOf(
            "javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "mailto:a@example.org", "ftp://example.org",
            "https://bank.example.com@other.test/x", "https://user:pw@example.org", "https://exa mple.org", "https://", "example.org",
        )) assertNull(LinkPolicy.webLink(bad), bad)
    }

    @Test fun `an address in a text, the shown host and the domain check`() {
        assertEquals("https://www.example.org/x", LinkPolicy.webLinkInText("www.example.org/x")?.url)
        assertEquals("example.org", LinkPolicy.webLinkInText("www.example.org")?.shownHost)
        val tineye = LinkPolicy.webLink("https://www.tineye.com/search")!!
        assertTrue(tineye.isOn("tineye.com"))
        assertFalse(LinkPolicy.webLink("https://nottineye.com/")!!.isOn("tineye.com"))
        assertFalse(LinkPolicy.webLink("https://tineye.com.evil.test/")!!.isOn("tineye.com"))
    }
}
