package dev.nanovoice

import dev.nanovoice.actions.it.ItalianPhrases
import dev.nanovoice.core.ActionExecutor
import dev.nanovoice.core.ActionResult
import dev.nanovoice.core.PhoneAction
import dev.nanovoice.router.LocalRouter
import dev.nanovoice.voice.SpeechToText
import dev.nanovoice.voice.SpokenOutput
import dev.nanovoice.voice.SttEvent
import dev.nanovoice.voice.TtsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.util.Locale
import java.util.concurrent.CyclicBarrier
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The assistant sheet has a conversation of its own: it opens over the lock screen, so the app's chat must not show
 * there, and handing it over to the app adds only what was said in it. And the chat's file stays whole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetConversationTest {
    private val dir = createTempDirectory("sheet").toFile()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun clean() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private val executor = object : ActionExecutor {
        override suspend fun execute(action: PhoneAction) = ActionResult(true, "ok")
    }
    private val speech = object : SpokenOutput {
        override val status = MutableStateFlow(TtsStatus.Ready)
        override val speaking = MutableStateFlow(false)
        override fun speak(text: String) {}
        override fun stop() {}
        override fun shutdown() {}
    }
    private val stt = object : SpeechToText {
        override val id = "fake"
        override fun isAvailable() = false
        override fun start(endOnSilence: Boolean): Flow<SttEvent> = emptyFlow()
        override fun stopListening() {}
    }

    private fun vm(store: ChatStore?, sheet: Boolean = false, files: FileInbox? = null): ChatViewModel {
        val settings = MutableStateFlow(AppSettings())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connection = AgentConnection(scope, settings, { emptySet() }, { error("no client") })
        val engine = ChatEngine({ LocalRouter.forLocale(Locale.ITALIAN)!! }, executor, ItalianPhrases, connection, { 0L })
        return ChatViewModel(
            engine, connection, speech, ItalianPhrases, settings, stt, LinkPreviews(), ActionConsent(), {},
            chatStore = store, fileInbox = files, sheet = sheet, elapsedRealtime = { 0L },
        )
    }

    private val saved = listOf(
        ChatMessage(0, "Sposta la visita a giovedì", fromUser = true, time = 1000),
        ChatMessage(1, "Fatto: spostata a giovedì alle 10.", fromUser = false, time = 2000),
        ChatMessage(2, "Nuova conversazione", fromUser = false, time = 3000, notice = true),
        ChatMessage(3, "Che tempo fa?", fromUser = true, time = 4000),
        ChatMessage(4, "Il server non risponde.", fromUser = false, time = 5000, failed = true),
    )

    @Test fun `the sheet starts empty, whatever the app's chat holds`() {
        val store = ChatStore(File(dir, "chat/conversation.json"))
        store.save(saved)
        // The factory gives the sheet no store; even given one, the sheet neither shows nor writes the chat.
        val sheet = vm(store = store, sheet = true)
        assertEquals(emptyList(), sheet.messages.value)
        sheet.adopt(listOf(ChatMessage(0, "detto nel foglio", fromUser = true)))
        Thread.sleep(700) // past the chat's save delay
        assertEquals(saved.map { it.text }, store.load().map { it.text })
    }

    @Test fun `handing the sheet over adds only what was said in it, as it was`() {
        val store = ChatStore(File(dir, "chat/conversation.json"))
        store.save(saved)
        val main = vm(store)
        val fromSheet = listOf(
            ChatMessage(0, "Che ore sono?", fromUser = true, time = 9000, barcode = null),
            ChatMessage(1, "Sono le 10.", fromUser = false, time = 9100),
            ChatMessage(2, "Interrotta.", fromUser = false, time = 9200, failed = true),
            ChatMessage(3, "Nuova conversazione", fromUser = false, time = 9300, notice = true),
            ChatMessage(4, "Avviso", fromUser = false, time = 9400, alertId = 7),
        )
        main.adopt(fromSheet)
        val after = main.messages.value
        assertEquals(saved.size + 3, after.size)
        val added = after.takeLast(3)
        assertEquals(listOf("Che ore sono?", "Sono le 10.", "Interrotta."), added.map { it.text })
        assertEquals(listOf(9000L, 9100L, 9200L), added.map { it.time })
        assertTrue(added.last().failed)
        assertEquals(after.size, after.map { it.id }.distinct().size) // ids of this chat, no clash
    }

    @Test fun `two saves at the same time leave a whole conversation`() {
        val file = File(dir, "chat/conversation.json")
        val a = ChatStore(file)
        val b = ChatStore(file)
        val listA = (0L until 60L).map { ChatMessage(it, "A$it ${"x".repeat(5_000)}", fromUser = it % 2 == 0L, time = it) }
        val listB = (0L until 60L).map { ChatMessage(it, "B$it ${"y".repeat(4_000)}", fromUser = it % 2 == 1L, time = it) }
        repeat(150) {
            val barrier = CyclicBarrier(2)
            val t1 = Thread { barrier.await(); a.save(listA) }
            val t2 = Thread { barrier.await(); b.save(listB) }
            t1.start(); t2.start(); t1.join(); t2.join()
            val texts = a.load().map { it.text }
            assertTrue(texts == listA.map { it.text } || texts == listB.map { it.text }, "a mixed or empty file")
        }
        assertEquals(listOf("conversation.json"), file.parentFile.list()!!.sorted()) // no temporary left behind
    }

    @Test fun `a damaged file is put aside, not overwritten, and cleared with the chat`() {
        val file = File(dir, "chat/conversation.json")
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        val store = ChatStore(file)
        assertEquals(emptyList(), store.load())
        val bad = File(file.parentFile, "conversation.json.bad")
        assertEquals("{ not json", bad.readText())
        store.save(saved)
        assertEquals("{ not json", bad.readText())
        store.save(emptyList())
        assertFalse(bad.exists())
        assertFalse(file.exists())
    }

    @Test fun `a file sent while the app was closed shows in the app's chat, never in the sheet`() {
        val files = FileInbox(File(dir, "chat/files"), io = Dispatchers.Unconfined)
        kotlinx.coroutines.runBlocking { files.receive(dev.nanovoice.agent.AgentFile("riepilogo.pdf", null)) }
        val sheet = vm(store = null, sheet = true, files = files)
        assertEquals(emptyList(), sheet.messages.value)
        assertEquals(1, files.pending.value.size)
        val main = vm(ChatStore(File(dir, "chat/conversation.json")), files = files)
        assertTrue(main.messages.value.single().text.contains("riepilogo.pdf"))
        assertEquals(emptyList(), files.pending.value)
        assertEquals(emptyList(), File(dir, "chat/files").listFiles()!!.toList())
    }
}
