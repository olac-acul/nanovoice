package dev.nanovoice.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerUrlExtraTest {
    @Test fun urlsOkHttpCannotBuildAreRefused() {
        assertIs<ServerUrlCheck.Invalid>(ServerUrl.check("ws://192.168.50.20:99999")) // privacy-scan: allow (an example address the code must classify)
        assertIs<ServerUrlCheck.Valid>(ServerUrl.check("ws://192.168.50.20:8767")) // privacy-scan: allow (an example address the code must classify)
    }

    @Test fun tokensMustBePrintableAscii() {
        assertTrue(ServerUrl.tokenIsValid("abc123-_.~XYZ"))
        assertFalse(ServerUrl.tokenIsValid(""))
        assertFalse(ServerUrl.tokenIsValid("con spazio"))
        assertFalse(ServerUrl.tokenIsValid("città"))
    }

    @Test fun onlyWsIsCleartext() {
        assertTrue(ServerUrl.isCleartext("ws://192.168.50.20:8767")) // privacy-scan: allow (an example address the code must classify)
        assertTrue(ServerUrl.isCleartext(" WS://host:1"))
        assertFalse(ServerUrl.isCleartext("wss://example.org"))
    }

    @Test fun aTailscaleServerIsReachedOnlyThroughTheVpn() {
        kotlin.test.assertEquals(ServerReach.VpnOnly, ServerUrl.reach("ws://100.101.102.3:8767"))
        kotlin.test.assertEquals(ServerReach.VpnOnly, ServerUrl.reach("wss://myhost.example.ts.net"))
        kotlin.test.assertEquals(ServerReach.HomeNetwork, ServerUrl.reach("ws://192.168.50.20:8767")) // privacy-scan: allow (an example address the code must classify)
        kotlin.test.assertEquals(ServerReach.HomeNetwork, ServerUrl.reach("ws://myhost:8767"))
        kotlin.test.assertEquals(ServerReach.Anywhere, ServerUrl.reach("wss://example.test/voice"))
        kotlin.test.assertEquals(ServerReach.Anywhere, ServerUrl.reach("not a url"))
    }
}
