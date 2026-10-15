package dev.nanovoice.agent

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingLinkTest {
    private val code = "7H2KQ9M4XB5TD3WE"
    private fun link(server: String = "ws%3A%2F%2F100.101.102.109%3A8767", v: String = "1", c: String = code) = "nanovoice://pair?v=$v&server=$server&code=$c"

    @Test fun readsAValidCode() {
        val r = assertIs<PairingLinkResult.Valid>(PairingLink.parse(link()))
        assertEquals("ws://100.101.102.109:8767", r.server)
        assertEquals(code, r.code)
    }

    @Test fun acceptsTheCodeInLowerCaseAndWithHyphens() {
        assertEquals(code, assertIs<PairingLinkResult.Valid>(PairingLink.parse(link(c = "7h2k-q9m4-xb5t-d3we"))).code)
    }

    @Test fun acceptsWssOnAnyHost() {
        assertIs<PairingLinkResult.Valid>(PairingLink.parse(link(server = "wss%3A%2F%2Fvoice.example.org%3A443")))
    }

    @Test fun refusesAnAddressTheSettingsWouldRefuse() {
        assertIs<PairingLinkResult.BadServer>(PairingLink.parse(link(server = "ws%3A%2F%2Fexample.org%3A8767"))) // privacy-scan: allow
        assertIs<PairingLinkResult.BadServer>(PairingLink.parse(link(server = "http%3A%2F%2F100.101.102.109")))
        assertIs<PairingLinkResult.BadServer>(PairingLink.parse(link(server = "ws%3A%2F%2F100.101.102.109%3A99999")))
    }

    @Test fun refusesAnotherVersionAndSaysSo() {
        assertEquals(PairingLinkResult.UnsupportedVersion, PairingLink.parse(link(v = "2")))
        assertEquals(PairingLinkResult.UnsupportedVersion, PairingLink.parse("nanovoice://pair?server=x&code=$code"))
    }

    @Test fun refusesAnythingElse() {
        for (text in listOf("", "hello", "https://example.org/", "nanovoice://other?v=1", "nanovoice://pair?v=1&server=ws%3A%2F%2F100.101.102.109&code=SHORT",
            "nanovoice://pair?v=1&code=$code", "nanovoice://pair?v=1&server=ws%3A%2F%2F100.101.102.109&code=IIIIIIIIIIIIIIII", "5449000000996")) {
            assertEquals(PairingLinkResult.NotPairing, PairingLink.parse(text), text)
        }
    }
}

class PairingClientTest {
    private val server = MockWebServer()
    private val client = PairingClient()
    private val code = "7H2KQ9M4XB5TD3WE"
    private val token = "t".repeat(48)

    @AfterTest fun stop() = server.close()

    private fun base(): String {
        server.start()
        return "ws://${server.hostName}:${server.port}"
    }

    @Test fun tradesTheCodeForTheToken() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"$token"}""").build())
        val result = client.exchange(base(), code, "Pixel 8")
        assertEquals(PairingResult.Paired(token), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pair", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"code":"$code","device":"Pixel 8"}""", request.body!!.utf8())
        assertEquals(null, request.headers["Authorization"])
    }

    @Test fun cutsTheDeviceNameAndKeepsTheServerPath() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"$token"}""").build())
        client.exchange(base() + "/", code, "x".repeat(100))
        assertEquals("x".repeat(40), kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body!!.utf8()).let { (it as kotlinx.serialization.json.JsonObject)["device"]!!.toString().trim('"') })
    }

    @Test fun mapsTheRefusals() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"invalid_code"}""").build())
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(MockResponse.Builder().code(500).build())
        val url = base()
        assertEquals(PairingResult.InvalidCode, client.exchange(url, code, "d"))
        assertEquals(PairingResult.TooManyAttempts, client.exchange(url, code, "d"))
        assertEquals(PairingResult.Unreachable, client.exchange(url, code, "d"))
    }

    @Test fun doesNotTrustAnOddAnswer() {
        val url = base()
        for (body in listOf("not json", """{"token":42}""", """{"nope":"x"}""", """{"token":"has space"}""", """{"token":""}""", "x".repeat(5000))) {
            server.enqueue(MockResponse.Builder().code(200).body(body).build())
            assertEquals(PairingResult.Unreachable, client.exchange(url, code, "d"), body.take(20))
        }
    }

    @Test fun doesNotFollowARedirect() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.1:1/").build())
        assertEquals(PairingResult.Unreachable, client.exchange(base(), code, "d"))
    }

    @Test fun saysUnreachableWhenNobodyAnswers() {
        server.start()
        val dead = "ws://${server.hostName}:${server.port}"
        server.close()
        assertEquals(PairingResult.Unreachable, client.exchange(dead, code, "d"))
    }
}
