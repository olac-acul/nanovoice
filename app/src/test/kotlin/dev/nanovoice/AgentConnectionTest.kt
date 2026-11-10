package dev.nanovoice

import dev.nanovoice.agent.AgentClient
import dev.nanovoice.agent.AgentConfig
import dev.nanovoice.agent.AgentEvent
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.agent.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentConnectionTest {
    private class FakeClient(val config: AgentConfig) : AgentClient {
        override val state = MutableStateFlow(ConnectionState.Disconnected)
        override val notifications = MutableSharedFlow<String>(extraBufferCapacity = 4)
        var started = false
        val endpoints = mutableListOf<String?>()
        override fun setPushEndpoint(url: String?) { endpoints += url }
        var stopped = false
        /** Sent by the server as soon as the client connects (what it kept while the phone was off). */
        var onStart: String? = null
        override fun start() {
            started = true
            onStart?.let { notifications.tryEmit(it) }
        }
        override fun stop() { stopped = true }
        override fun ask(text: String, image: AgentImage?): Flow<AgentEvent> = emptyFlow()
    }

    private val created = mutableListOf<FakeClient>()
    private val settings = MutableStateFlow(AppSettings())

    private var network = NetworkNow(vpn = true, wifi = true)

    private var kept: String? = null

    private fun TestScope.connection() = AgentConnection(
        scope = backgroundScope,
        settings = settings,
        capabilities = { setOf("tell_time") },
        clientFactory = { FakeClient(it).also { c -> c.onStart = kept; created.add(c) } },
        network = { network },
    ).also { runCurrent() }

    @Test fun aServerThatDoesNotAnswerWithTheVpnOffNeedsTheVpn() = runTest {
        val c = connection()
        network = NetworkNow(vpn = false, wifi = true)
        settings.value = AppSettings("ws://100.101.102.3:8767", "tok")
        runCurrent()
        created.single().state.value = ConnectionState.Connecting
        runCurrent()
        created.single().state.value = ConnectionState.Disconnected
        runCurrent()
        assertEquals(AgentStatus.NeedsVpn, c.status.value)
        network = NetworkNow(vpn = true, wifi = true)
        created.single().state.value = ConnectionState.Connecting
        runCurrent()
        created.single().state.value = ConnectionState.Disconnected
        runCurrent()
        assertEquals(AgentStatus.Disconnected, c.status.value) // the VPN is on: the server itself does not answer
    }

    @Test fun retriesAfterAFailureKeepSayingWhatIsWrong() = runTest {
        val c = connection()
        settings.value = AppSettings("ws://myhost:8767", "tok")
        runCurrent()
        val client = created.single()
        client.state.value = ConnectionState.Connecting
        runCurrent()
        assertEquals(AgentStatus.Connecting, c.status.value)
        client.state.value = ConnectionState.Disconnected
        runCurrent()
        client.state.value = ConnectionState.Connecting
        runCurrent()
        assertEquals(AgentStatus.Disconnected, c.status.value)
        client.state.value = ConnectionState.Connected
        runCurrent()
        client.state.value = ConnectionState.Disconnected
        runCurrent()
        client.state.value = ConnectionState.Connecting
        runCurrent()
        assertEquals(AgentStatus.Disconnected, c.status.value)
    }

    @Test fun aHomeServerNeedsTheVpnOnlyAwayFromTheWifi() {
        assertTrue(waitsForVpn(dev.nanovoice.agent.ServerReach.HomeNetwork, NetworkNow(vpn = false, wifi = false)))
        assertTrue(!waitsForVpn(dev.nanovoice.agent.ServerReach.HomeNetwork, NetworkNow(vpn = false, wifi = true)))
        assertTrue(waitsForVpn(dev.nanovoice.agent.ServerReach.VpnOnly, NetworkNow(vpn = false, wifi = true)))
        assertTrue(!waitsForVpn(dev.nanovoice.agent.ServerReach.VpnOnly, NetworkNow(vpn = true, wifi = false)))
        assertTrue(!waitsForVpn(dev.nanovoice.agent.ServerReach.Anywhere, NetworkNow(vpn = false, wifi = false)))
    }

    @Test fun notConfiguredByDefault() = runTest {
        val c = connection()
        assertEquals(AgentStatus.NotConfigured, c.status.value)
        assertNull(c.ask("ciao", null))
        assertTrue(created.isEmpty())
    }

    @Test fun configuredSettingsStartAClientAndMirrorItsState() = runTest {
        val c = connection()
        settings.value = AppSettings("ws://myhost:8767", "tok")
        runCurrent()
        val client = created.single()
        assertTrue(client.started)
        assertEquals(Triple("ws://myhost:8767", "tok", setOf("tell_time")), Triple(client.config.url, client.config.token, client.config.capabilities))
        assertNotNull(c.ask("ciao", null))
        client.state.value = ConnectionState.Connected
        runCurrent()
        assertEquals(AgentStatus.Connected, c.status.value)
        client.state.value = ConnectionState.Disconnected
        runCurrent()
        assertEquals(AgentStatus.Disconnected, c.status.value)
    }

    @Test fun changingSettingsReplacesTheClient() = runTest {
        val c = connection()
        settings.value = AppSettings("ws://myhost:8767", "tok")
        runCurrent()
        settings.value = AppSettings("ws://myhost:8767", "other")
        runCurrent()
        assertEquals(2, created.size)
        assertTrue(created[0].stopped)
        assertEquals("other", created[1].config.token)
        assertEquals(AgentStatus.Connecting, c.status.value) // the new client has not tried yet
    }

    @Test fun changingOnlyALocalSettingKeepsTheClient() = runTest {
        connection()
        settings.value = AppSettings("ws://myhost:8767", "tok")
        runCurrent()
        settings.value = settings.value.copy(localCommands = false)
        runCurrent()
        assertEquals(1, created.size)
        assertEquals(false, created[0].stopped)
    }

    @Test fun clearingSettingsStopsTheClient() = runTest {
        val c = connection()
        settings.value = AppSettings("ws://myhost:8767", "tok")
        runCurrent()
        settings.value = AppSettings()
        runCurrent()
        assertTrue(created.single().stopped)
        assertEquals(AgentStatus.NotConfigured, c.status.value)
        assertNull(c.ask("ciao", null))
    }

    @Test fun insecureUrlIsNotStarted() = runTest {
        val c = connection()
        settings.value = AppSettings("ws://example.com:8767", "tok")
        runCurrent()
        assertTrue(created.isEmpty())
        assertEquals(AgentStatus.NotConfigured, c.status.value)
    }

    @Test fun notificationsAreForwarded() = runTest {
        val c = connection()
        settings.value = AppSettings("ws://myhost:8767", "tok")
        runCurrent()
        val got = mutableListOf<String>()
        backgroundScope.launch { c.notifications.collect { got += it } }
        runCurrent()
        created.single().notifications.emit("Promemoria")
        runCurrent()
        assertEquals(listOf("Promemoria"), got)
    }

    @Test fun whatTheServerSendsRightAfterConnectingWaitsForItsReader() = runTest {
        // Woken by a push: the client connects and the server hands over a reminder before anything reads it.
        kept = "Promemoria delle 18"
        val c = connection()
        settings.value = AppSettings("ws://myhost:8767", "tok")
        runCurrent()
        assertTrue(created.single().started)
        val got = mutableListOf<String>()
        backgroundScope.launch { c.notifications.collect { got += it } }
        runCurrent()
        assertEquals(listOf("Promemoria delle 18"), got)
    }

    @Test fun thePushEndpointIsPassedToTheClientAndUpdatedWithoutReconnecting() = runTest {
        connection()
        settings.value = AppSettings("ws://myhost:8767", "tok", pushEndpoint = "https://push.example/a")
        runCurrent()
        settings.value = settings.value.copy(pushEndpoint = "https://push.example/b")
        runCurrent()
        settings.value = settings.value.copy(pushEndpoint = "")
        runCurrent()
        assertEquals(1, created.size)
        assertEquals(listOf("https://push.example/a", "https://push.example/b", null), created.single().endpoints)
    }
}
