package dev.nanovoice

import dev.nanovoice.agent.AgentFile
import dev.nanovoice.agent.AgentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The files the agent sends on its own wait on disk for the app's chat, even across an end of the process. */
class FileInboxTest {
    private val dir = File(createTempDirectory("files").toFile(), "chat/files")

    @AfterTest fun clean() {
        dir.parentFile.parentFile.deleteRecursively()
    }

    private fun inbox() = FileInbox(dir, io = Dispatchers.Unconfined)

    @Test fun `a file waits for the chat across a restart, and goes once shown`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        inbox().receive(AgentFile("grafico.png", AgentImage("image/png", png)))
        inbox().receive(AgentFile("dati.csv", null))
        val again = inbox() // a new process
        val waiting = again.pending.value
        assertEquals(listOf("grafico.png", "dati.csv"), waiting.map { it.file.name })
        assertContentEquals(png, waiting[0].file.image?.bytes)
        assertNull(waiting[1].file.image)
        again.remove(waiting[0].id)
        assertEquals(listOf("dati.csv"), inbox().pending.value.map { it.file.name })
        // A new one after a restart comes after the ones waiting.
        val third = inbox().receive(AgentFile("altro.png", null, tooBig = true))
        assertTrue(third.id > waiting[1].id)
        assertEquals(listOf("dati.csv", "altro.png"), inbox().pending.value.map { it.file.name })
        assertTrue(inbox().pending.value.last().file.tooBig)
    }

    @Test fun `only the newest few are kept, and a damaged one is dropped`() = runBlocking {
        val box = inbox()
        repeat(10) { box.receive(AgentFile("f$it", null)) }
        assertEquals((2..9).map { "f$it" }, box.pending.value.map { it.file.name })
        assertEquals(8, dir.listFiles()!!.size)
        File(dir, "${box.pending.value.first().id}.json").writeText("{not json")
        File(dir, "99.json.tmp").writeText("half")
        assertEquals((3..9).map { "f$it" }, inbox().pending.value.map { it.file.name })
        assertEquals(7, dir.listFiles()!!.size)
    }
}
