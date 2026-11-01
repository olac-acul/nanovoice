package dev.nanovoice

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.nanovoice.agent.AgentImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatExportTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(2026, 11, 1, 23, 10, 0, 0, zone)
    private fun at(day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Only whether a message has a picture matters here: no Android bitmap behind it. */
    private val picture = object : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
    }

    private val chat = listOf(
        ChatMessage(1, "Che tempo fa?", fromUser = true, time = at(31, 9, 5), earlier = true, image = picture),
        ChatMessage(2, "Buongiorno!", fromUser = false, time = at(31, 20, 0), notice = true),
        ChatMessage(3, "Recap di Reddit", fromUser = false, time = at(31, 20, 1), alertId = 7),
        ChatMessage(4, "fammi un grafico", fromUser = true, time = at(31, 20, 2), quote = "Recap di Reddit"),
        ChatMessage(5, "", fromUser = false, time = at(31, 20, 3), image = picture, photo = AgentImage("image/png", byteArrayOf(1, 2))),
    )

    @Test fun theChatIsJsonWithItsTimesAndWhoSaidIt() {
        assertEquals(
            """
            {
                "app": "NanoVoice",
                "versione": "0.3.5",
                "esportato": "2026-11-01T23:10:00+01:00",
                "messaggi": [
                    {
                        "ora": "2026-10-31T09:05:00+01:00",
                        "da": "utente",
                        "testo": "Che tempo fa?",
                        "precedente": true,
                        "immagine": "foto"
                    },
                    {
                        "ora": "2026-10-31T20:00:00+01:00",
                        "tipo": "nuova_conversazione",
                        "testo": "Buongiorno!"
                    },
                    {
                        "ora": "2026-10-31T20:01:00+01:00",
                        "tipo": "avviso",
                        "testo": "Recap di Reddit",
                        "precedente": false
                    },
                    {
                        "ora": "2026-10-31T20:02:00+01:00",
                        "da": "utente",
                        "testo": "fammi un grafico",
                        "precedente": false,
                        "risposta_a": "Recap di Reddit"
                    },
                    {
                        "ora": "2026-10-31T20:03:00+01:00",
                        "da": "assistente",
                        "testo": "",
                        "precedente": false,
                        "immagine": "immagini/002.png"
                    }
                ]
            }
            """.trimIndent() + "\n",
            chatJson(chat, now, "0.3.5", mapOf(5L to "immagini/002.png")),
        )
        assertEquals("nanovoice-chat-2026-11-01.json", exportName(now, zip = false))
        assertEquals("nanovoice-chat-2026-11-01.zip", exportName(now, zip = true))
    }

    @Test fun theZipHasTheMessagesAndThePicturesTheyName() {
        // the agent's picture as it came; the old photo only as its thumbnail
        val pictures = picturesOf(chat) { byteArrayOf(9) }
        assertEquals(listOf("immagini/001.jpg", "immagini/002.png"), pictures.map { it.name })
        assertEquals(listOf(1L, 5L), pictures.map { it.messageId })
        assertTrue(hasOnlyThumbnails(chat))
        assertFalse(hasOnlyThumbnails(chat.drop(1)))

        val out = ByteArrayOutputStream()
        writeChatZip(out, "{}\n", pictures)
        val entries = buildMap {
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toList())
                }
            }
        }
        assertEquals(
            mapOf("messaggi.json" to "{}\n".toByteArray().toList(), "immagini/001.jpg" to listOf<Byte>(9), "immagini/002.png" to listOf<Byte>(1, 2)),
            entries,
        )
    }
}
