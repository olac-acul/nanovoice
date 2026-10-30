package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChatStoreTest {
    @Test fun aConversationComesBackAsItWasWritten() {
        val messages = listOf(
            StoredMessage(1, "che ore sono", fromUser = true, time = 1_000),
            StoredMessage(2, "Sono le 16:05.", fromUser = false, time = 2_000, quote = "che ore", failed = false),
            StoredMessage(3, "Rieccoci.", fromUser = false, time = 3_000, notice = true),
            StoredMessage(4, "cos'è?", fromUser = true, time = 4_000, barcode = "[codice a barre: 123]", preview = byteArrayOf(1, 2, 3)),
            StoredMessage(5, "Risposta interrotta.", fromUser = false, time = 5_000, failed = true),
        )
        val back = decodeChat(encodeChat(messages))
        assertEquals(messages.map { it.copy(preview = null) }, back.map { it.copy(preview = null) })
        assertContentEquals(byteArrayOf(1, 2, 3), back[3].preview)
    }

    @Test fun aDamagedEntryIsSkippedNotTheWholeFile() {
        val text = """{"version":1,"messages":[{"id":1,"text":"ok","fromUser":true,"time":1},{"id":"x"},{"text":"no id","time":2}]}"""
        assertEquals(listOf(1L), decodeChat(text).map { it.id })
        assertEquals(emptyList(), decodeChat("""{"messages":"nope"}"""))
    }
}
