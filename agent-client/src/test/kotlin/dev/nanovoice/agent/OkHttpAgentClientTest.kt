package dev.nanovoice.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OkHttpAgentClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fromClient = LinkedBlockingQueue<JsonObject>()
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val actions = mutableListOf<Pair<String, JsonObject>>()
    private val requests = mutableListOf<ActionRequest>()

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets.add(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            fromClient.add(Json.parseToJsonElement(text).jsonObject)
        }
    }

    private fun wsUrl() = server.url("/").toString().replaceFirst("http", "ws")

    private fun client(
        capabilities: Set<String> = setOf("set_timer", "tell_time"),
        replyTimeoutMs: Long = 5_000,
        cleartextAllowed: () -> Boolean = { true },
    ): OkHttpAgentClient {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(serverListener).build())
        server.start()
        return OkHttpAgentClient(
            AgentConfig(
                wsUrl(), "tok", capabilities,
                initialBackoffMs = 50, maxBackoffMs = 100, replyTimeoutMs = replyTimeoutMs, cleartextAllowed = cleartextAllowed,
            ),
            scope,
            ActionHandler { name, args, request -> actions += name to args; requests += request; ActionOutcome(true, "fatto $name") },
        ).also { it.start() }
    }

    private fun nextRaw(): JsonObject = fromClient.poll(5, TimeUnit.SECONDS) ?: error("no message from client")

    /** The next frame, skipping the "no push endpoint" the client sends on every connection. */
    private fun next(): JsonObject {
        while (true) {
            val frame = nextRaw()
            val noEndpoint = frame["type"]?.jsonPrimitive?.content == "push_endpoint" && frame["url"] is kotlinx.serialization.json.JsonNull
            if (!noEndpoint) return frame
        }
    }
    private fun serverSocket(): WebSocket = serverSockets.poll(5, TimeUnit.SECONDS) ?: error("client did not connect")
    private fun waitConnected(c: OkHttpAgentClient) = runBlocking { withTimeout(5_000) { c.state.first { it == ConnectionState.Connected } } }

    @AfterTest fun tearDown() {
        scope.cancel()
        runCatching { server.close() }
    }

    @Test fun sendsHelloWithTokenAndCapabilities() {
        val c = client()
        val request = server.takeRequest()
        assertEquals("Bearer tok", request.headers["Authorization"])
        val hello = next()
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals("1", hello["protocol"]!!.jsonPrimitive.content)
        assertEquals(listOf("set_timer", "tell_time"), hello["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        waitConnected(c)
    }

    @Test fun streamsDeltasUntilDone() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val events = scope.async { c.ask("riassumimi le email").toList() }
        val msg = next()
        assertEquals("message", msg["type"]!!.jsonPrimitive.content)
        assertEquals("riassumimi le email", msg["text"]!!.jsonPrimitive.content)
        val id = msg["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"delta","reply_to":"$id","text":"Oggi hai "}""")
        ws.send("""{"type":"delta","reply_to":"$id","text":"tre email."}""")
        ws.send("""{"type":"done","reply_to":"$id"}""")
        assertEquals(
            listOf(AgentEvent.Delta("Oggi hai "), AgentEvent.Delta("tre email."), AgentEvent.Done),
            runBlocking { events.await() },
        )
    }

    @Test fun sendsAPhotoWithTheMessage() {
        val c = client()
        serverSocket()
        next() // hello
        waitConnected(c)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2)
        scope.async { c.ask("cosa vedi?", AgentImage("image/jpeg", jpeg)).toList() }
        val msg = next()
        assertEquals("cosa vedi?", msg["text"]!!.jsonPrimitive.content)
        val image = msg["image"]!!.jsonObject
        assertEquals("image/jpeg", image["mime"]!!.jsonPrimitive.content)
        assertEquals(jpeg.toList(), java.util.Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content).toList())
    }

    @Test fun aMessageWithoutPhotoHasNoImageField() {
        val c = client()
        serverSocket()
        next() // hello
        waitConnected(c)
        scope.async { c.ask("ciao").toList() }
        assertEquals(null, next()["image"])
    }

    @Test fun workingKeepsTheReplyOpenUntilTheAnswer() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val events = scope.async { c.ask("cerca dicio").toList() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"delta","reply_to":"$id","text":"Cerco sul Web..."}""")
        ws.send("""{"type":"working","reply_to":"$id"}""")
        ws.send("""{"type":"delta","reply_to":"$id","text":" Ecco il sito."}""")
        ws.send("""{"type":"done","reply_to":"$id"}""")
        assertEquals(
            listOf(AgentEvent.Delta("Cerco sul Web..."), AgentEvent.Working, AgentEvent.Delta(" Ecco il sito."), AgentEvent.Done),
            runBlocking { events.await() },
        )
    }

    @Test fun aRestartAfterAPauseReachesTheRequestItCameWith() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val events = scope.async { c.ask("dopo la pausa").toList() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"context_restarted","reply_to":"$id"}""")
        ws.send("""{"type":"delta","reply_to":"$id","text":"Eccomi."}""")
        ws.send("""{"type":"done","reply_to":"$id"}""")
        assertEquals(
            listOf(AgentEvent.ContextRestarted, AgentEvent.Delta("Eccomi."), AgentEvent.Done),
            runBlocking { events.await() },
        )
    }

    @Test fun newConversationWaitsForTheServer() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val result = scope.async { c.newConversation() }
        assertEquals("new_conversation", next()["type"]!!.jsonPrimitive.content)
        ws.send("""{"type":"conversation_reset","ok":true}""")
        assertEquals(true, runBlocking { result.await() })
    }

    @Test fun newConversationReportsARefusal() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val result = scope.async { c.newConversation() }
        next()
        ws.send("""{"type":"conversation_reset","ok":false}""")
        assertEquals(false, runBlocking { result.await() })
    }

    @Test fun warmUpTellsTheServerARequestIsComing() {
        val c = client()
        c.warmUp() // not connected yet: nothing to send, and no error
        serverSocket()
        next() // hello
        waitConnected(c)
        c.warmUp()
        assertEquals("warm", next()["type"]!!.jsonPrimitive.content)
    }

    @Test fun newConversationNeedsAConnection() {
        val c = client()
        assertEquals(false, runBlocking { c.newConversation() })
    }

    @Test fun anInterruptedReplyIsDroppedAndItsActionsRefused() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val reply = scope.async { c.ask("cerca qualcosa").first() } // takes the first event, then stops listening
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"delta","reply_to":"$id","text":"Cerco sul Web..."}""")
        assertEquals(AgentEvent.Delta("Cerco sul Web..."), runBlocking { reply.await() })
        assertEquals("cancel", next()["type"]!!.jsonPrimitive.content) // the server is told it was given up

        val notified = scope.async { withTimeoutOrNull(1_000) { c.notifications.first() } }
        runBlocking { delay(100) } // let the collector subscribe before the late text arrives
        ws.send("""{"type":"delta","reply_to":"$id","text":" Ecco la risposta."}""")
        ws.send("""{"type":"action","reply_to":"$id","action_id":"a1","name":"set_timer","args":{"seconds":60}}""")
        val result = next()
        assertEquals("action_result", result["type"]!!.jsonPrimitive.content)
        assertEquals(false, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(emptyList(), actions) // the timer was not started
        ws.send("""{"type":"done","reply_to":"$id"}""")
        assertEquals(null, runBlocking { notified.await() }) // the late answer did not become a notification
    }

    @Test fun runsDeclaredActionAndReportsResult() {
        val c = client()
        val ws = serverSocket()
        next()
        waitConnected(c)
        scope.async { c.ask("metti un timer").toList() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"action","reply_to":"$id","action_id":"a1","name":"set_timer","args":{"seconds":600}}""")
        val result = next()
        assertEquals("action_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("a1", result["action_id"]!!.jsonPrimitive.content)
        assertEquals("true", result["ok"]!!.jsonPrimitive.content)
        assertEquals("set_timer", actions.single().first)
        assertEquals("600", actions.single().second["seconds"]!!.jsonPrimitive.content)
    }

    @Test fun refusesActionsThatBelongToNoOpenRequest() {
        val c = client()
        val ws = serverSocket()
        next()
        waitConnected(c)
        // Sent on the agent's own: no reply_to at all, or one the phone never asked.
        ws.send("""{"type":"action","action_id":"a1","name":"set_timer","args":{"seconds":60}}""")
        assertEquals("false", next()["ok"]!!.jsonPrimitive.content)
        ws.send("""{"type":"action","reply_to":"m99","action_id":"a2","name":"set_timer","args":{"seconds":60}}""")
        assertEquals("false", next()["ok"]!!.jsonPrimitive.content)
        // A request already answered: its reply ended with done.
        val events = scope.async { c.ask("ciao").toList() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"done","reply_to":"$id"}""")
        runBlocking { events.await() }
        ws.send("""{"type":"action","reply_to":"$id","action_id":"a3","name":"set_timer","args":{"seconds":60}}""")
        assertEquals("false", next()["ok"]!!.jsonPrimitive.content)
        assertTrue(actions.isEmpty())
    }

    @Test fun anActionRightBeforeDoneStillRuns() {
        val c = client()
        val ws = serverSocket()
        next()
        waitConnected(c)
        scope.async { c.ask("metti un timer").toList() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"action","reply_to":"$id","action_id":"a1","name":"set_timer","args":{"seconds":60}}""")
        ws.send("""{"type":"done","reply_to":"$id"}""")
        assertEquals("true", next()["ok"]!!.jsonPrimitive.content)
    }

    @Test fun anActionGetsThePhotoOfItsOwnRequestOnly() {
        val c = client()
        val ws = serverSocket()
        next()
        waitConnected(c)
        val photo = AgentImage("image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 9))
        scope.async { c.ask("che pianta è?", photo).toList() }
        val withPhoto = next()["id"]!!.jsonPrimitive.content
        scope.async { c.ask("che ore sono?").toList() }
        val without = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"action","reply_to":"$without","action_id":"a1","name":"tell_time","args":{}}""")
        next()
        ws.send("""{"type":"action","reply_to":"$withPhoto","action_id":"a2","name":"set_timer","args":{}}""")
        next()
        assertEquals(listOf(without, withPhoto), requests.map { it.replyTo })
        assertEquals(null, requests[0].image)
        assertTrue(requests[1].image === photo)
    }

    @Test fun anInterruptedReplyIsCancelledOnTheServer() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val reply = scope.async { c.ask("cerca qualcosa").first() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"delta","reply_to":"$id","text":"Cerco..."}""")
        runBlocking { reply.await() } // stops listening after the first event
        val cancel = next()
        assertEquals("cancel", cancel["type"]!!.jsonPrimitive.content)
        assertEquals(id, cancel["reply_to"]!!.jsonPrimitive.content)
    }

    @Test fun anErrorAboutAnotherFrameDoesNotFailTheOpenReplies() {
        val c = client()
        val ws = serverSocket()
        next()
        waitConnected(c)
        val events = scope.async { c.ask("ciao").toList() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"error","message":"push_endpoint needs a public https url"}""")
        ws.send("""{"type":"error","reply_to":"m999","message":"unknown"}""")
        ws.send("""{"type":"delta","reply_to":"$id","text":"Ciao!"}""")
        ws.send("""{"type":"done","reply_to":"$id"}""")
        assertEquals(listOf(AgentEvent.Delta("Ciao!"), AgentEvent.Done), runBlocking { events.await() })
    }

    @Test fun rejectsUndeclaredAction() {
        val c = client(capabilities = setOf("tell_time"))
        val ws = serverSocket()
        next()
        waitConnected(c)
        ws.send("""{"type":"action","reply_to":"m1","action_id":"a1","name":"set_timer","args":{}}""")
        val result = next()
        assertEquals("false", result["ok"]!!.jsonPrimitive.content)
        assertTrue(actions.isEmpty())
    }

    @Test fun notifyGoesToNotifications() {
        val c = client()
        val ws = serverSocket()
        next()
        waitConnected(c)
        val got = scope.async { c.notifications.first() }
        Thread.sleep(100)
        ws.send("""{"type":"notify","text":"Promemoria: riunione"}""")
        assertEquals("Promemoria: riunione", runBlocking { withTimeout(5_000) { got.await() } })
    }

    @Test fun serverErrorEndsTheReply() {
        val c = client()
        val ws = serverSocket()
        next()
        waitConnected(c)
        val events = scope.async { c.ask("x").toList() }
        val id = next()["id"]!!.jsonPrimitive.content
        ws.send("""{"type":"error","reply_to":"$id","message":"boom"}""")
        assertEquals(listOf<AgentEvent>(AgentEvent.Error("boom")), runBlocking { events.await() })
    }

    @Test fun askWhileDisconnectedFailsFast() {
        val c = OkHttpAgentClient(
            AgentConfig("ws://127.0.0.1:1", "tok", emptySet(), initialBackoffMs = 50, maxBackoffMs = 100),
            scope,
            ActionHandler { _, _, _ -> ActionOutcome(true, "") },
        )
        c.start()
        val events = runBlocking { c.ask("ciao").toList() }
        assertEquals(listOf<AgentEvent>(AgentEvent.Error("Server non raggiungibile.")), events)
    }

    @Test fun lostConnectionFailsPendingAndReconnects() {
        val c = client()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(serverListener).build())
        val ws = serverSocket()
        next()
        waitConnected(c)
        val events = scope.async { c.ask("x").toList() }
        next() // message
        ws.close(1011, "server crash")
        val result = runBlocking { events.await() }
        assertTrue(result.single() is AgentEvent.Error)
        serverSocket() // second connection: reconnected
        assertEquals("hello", next()["type"]!!.jsonPrimitive.content)
    }

    @Test fun silentServerTimesOut() {
        val c = client(replyTimeoutMs = 200)
        serverSocket()
        next()
        waitConnected(c)
        val events = runBlocking { c.ask("x").toList() }
        assertEquals(listOf<AgentEvent>(AgentEvent.Error("Il server non risponde.")), events)
    }

    @Test fun sendsThePushEndpointRightAfterHello() {
        val c = client()
        c.setPushEndpoint("https://push.example/up123")
        serverSocket()
        assertEquals("hello", next()["type"]!!.jsonPrimitive.content)
        val frame = next()
        assertEquals("push_endpoint", frame["type"]!!.jsonPrimitive.content)
        assertEquals("https://push.example/up123", frame["url"]!!.jsonPrimitive.content)
        waitConnected(c)
    }

    @Test fun anEndpointSetWhileConnectedIsSentAtOnce() {
        val c = client()
        serverSocket()
        next() // hello
        waitConnected(c)
        c.setPushEndpoint("https://push.example/up999")
        assertEquals("https://push.example/up999", next()["url"]!!.jsonPrimitive.content)
    }

    @Test fun clearingTheEndpointSendsNull() {
        val c = client()
        serverSocket()
        next()
        waitConnected(c)
        c.setPushEndpoint("https://push.example/up1"); next()
        c.setPushEndpoint(null)
        val frame = nextRaw()
        assertEquals("push_endpoint", frame["type"]!!.jsonPrimitive.content)
        assertTrue(frame["url"] is kotlinx.serialization.json.JsonNull)
    }

    @Test fun anEndpointTurnedOffWhileOfflineReachesTheServerOnConnection() {
        val c = client()
        serverSocket()
        nextRaw() // hello
        waitConnected(c)
        val frame = nextRaw()
        assertEquals("push_endpoint", frame["type"]!!.jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, frame["url"])
    }

    @Test fun aCleartextServerWaitsForAVpn() {
        val c = client(cleartextAllowed = { false })
        runBlocking { withTimeout(5_000) { c.state.first { it == ConnectionState.Blocked } } }
        Thread.sleep(200)
        assertEquals(0, server.requestCount) // nothing sent, not even the token
    }

    @Test fun aRefusedProtocolVersionIsExplainedUntilTheNextAttempt() {
        val c = client()
        val ws = serverSocket()
        next() // hello
        waitConnected(c)
        val open = scope.async { c.ask("prima").toList() }
        next() // that message
        ws.send("""{"type":"error","message":"unsupported protocol; expected 2"}""")
        ws.close(1002, "unsupported protocol")
        val newer = protocolRefusalText("unsupported protocol; expected 2")
        assertEquals(listOf<AgentEvent>(AgentEvent.Error(newer)), runBlocking { open.await() })
        runBlocking { withTimeout(5_000) { c.state.first { it != ConnectionState.Connected } } }
        // The server closed the session; the reconnections fail here. Asking again says why, not "unreachable".
        assertEquals(listOf<AgentEvent>(AgentEvent.Error(newer)), runBlocking { c.ask("ancora").toList() })
    }

    @Test fun theRefusalSaysWhichSideToUpdate() {
        assertTrue(protocolRefusalText("unsupported protocol; expected ${Protocol.VERSION + 1}").contains("aggiorna NanoVoice"))
        assertTrue(protocolRefusalText("unsupported protocol; expected 0").contains("add-voice"))
        assertTrue(protocolRefusalText("unsupported protocol").contains("versioni diverse"))
    }
}
