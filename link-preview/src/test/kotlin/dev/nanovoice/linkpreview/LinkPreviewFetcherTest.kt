package dev.nanovoice.linkpreview

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkPreviewFetcherTest {
    private val server = MockWebServer().apply { start() }
    private val fetcher = LinkPreviewFetcher(strict = false, maxHtmlBytes = 2_000, maxImageBytes = 100)
    private fun url(path: String) = server.url(path).toString()

    @AfterTest fun stop() = server.close()

    private fun html(body: String, type: String = "text/html; charset=utf-8") =
        MockResponse.Builder().addHeader("Content-Type", type).body(body).build()

    @Test fun readsTheMetadataOfAPage() {
        server.enqueue(html("""<head><meta property="og:title" content="Dicio"><meta property="og:description" content="Assistente"></head>"""))
        val m = runBlocking { fetcher.fetch(url("/a")) }!!
        assertEquals("Dicio", m.title)
        assertEquals("Assistente", m.description)
    }

    @Test fun followsAShortRedirectChain() {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/b").build())
        server.enqueue(html("<title>Arrivato</title>"))
        assertEquals("Arrivato", runBlocking { fetcher.fetch(url("/a")) }?.title)
    }

    @Test fun givesUpAfterTooManyRedirects() {
        repeat(6) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/loop").build()) }
        assertNull(runBlocking { fetcher.fetch(url("/a")) })
    }

    @Test fun ignoresNonHtmlAndErrors() {
        server.enqueue(html("{}", "application/json"))
        assertNull(runBlocking { fetcher.fetch(url("/a")) })
        server.enqueue(MockResponse.Builder().code(404).body("<title>x</title>").build())
        assertNull(runBlocking { fetcher.fetch(url("/b")) })
    }

    @Test fun readsOnlyTheStartOfALargePage() {
        server.enqueue(html("<title>Presto</title>" + "x".repeat(50_000)))
        assertEquals("Presto", runBlocking { fetcher.fetch(url("/a")) }?.title)
    }

    @Test fun imagesAreBoundedAndMustBeImages() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("12345").build())
        assertEquals(5, runBlocking { fetcher.fetchImage(url("/i")) }?.size)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("x".repeat(500)).build())
        assertNull(runBlocking { fetcher.fetchImage(url("/big")) })
        server.enqueue(html("not an image"))
        assertNull(runBlocking { fetcher.fetchImage(url("/html")) })
    }

    @Test fun strictModeRefusesPlainHttpAndPrivateHosts() {
        val strict = LinkPreviewFetcher()
        for (u in listOf("http://example.org/", "https://127.0.0.1/", "https://[::1]/", "https://100.101.102.105/", "https://192.168.1.1/", "ftp://example.org/", "not a url")) { // privacy-scan: allow (an example address the code must classify)
            assertNull(runBlocking { strict.fetch(u) }, u)
        }
        assertEquals(0, server.requestCount)
    }
}
