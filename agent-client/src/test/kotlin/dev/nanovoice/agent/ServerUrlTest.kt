package dev.nanovoice.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerUrlTest {
    private fun valid(s: String) = assertEquals(ServerUrlCheck.Valid(s), ServerUrl.check(s), s)
    private fun invalid(s: String) = assertTrue(ServerUrl.check(s) is ServerUrlCheck.Invalid, s)

    @Test fun wssAnywhere() = valid("wss://nanoclaw.example.com/voice")
    @Test fun tailscaleIp() = valid("ws://100.101.102.107:8767")
    @Test fun tailscaleMagicDns() { valid("ws://myhost:8767"); valid("ws://myhost.tail1234.ts.net:8767") }
    @Test fun privateRanges() { valid("ws://192.168.1.10:8767"); valid("ws://10.0.2.2:8767"); valid("ws://172.20.0.5:8767") } // privacy-scan: allow (an example address the code must classify)
    @Test fun loopback() { valid("ws://localhost:8767"); valid("ws://127.0.0.1:8767"); valid("ws://[::1]:8767") }
    @Test fun publicPlainWsRejected() { invalid("ws://example.com:8767"); invalid("ws://8.8.8.8:8767"); invalid("ws://172.32.0.1:8767") }
    @Test fun cgnatBoundary() { invalid("ws://100.128.0.1:8767"); invalid("ws://100.63.0.1:8767") }
    @Test fun wrongScheme() { invalid("http://192.168.1.10:8767"); invalid("192.168.1.10:8767"); invalid("") } // privacy-scan: allow (an example address the code must classify)
    @Test fun trimsWhitespace() = assertEquals(ServerUrlCheck.Valid("ws://myhost:8767"), ServerUrl.check("  ws://myhost:8767 "))
}
