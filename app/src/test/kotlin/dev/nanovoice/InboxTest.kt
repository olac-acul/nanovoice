package dev.nanovoice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxTest {
    private val dir = createTempDirectory("inbox").toFile()
    private val file = File(dir, "alerts.json")
    private var now = 1_000_000_000L
    private fun inbox() = Inbox(file, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { now }

    @AfterTest fun clean() {
        dir.deleteRecursively()
    }

    @Test fun aMessageWithNoRequestOpenIsAnAlertKeptAcrossRestarts() {
        val alert = inbox().receive("## Recap di Reddit\nTre discussioni su …")
        assertNotNull(alert)
        val again = inbox().alerts.value.single()
        assertEquals("Recap di Reddit", again.title)
        assertFalse(again.read)
    }

    @Test fun aLateAnswerGoesToTheChatOnScreen() = runBlocking {
        val box = inbox()
        val got = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) { box.lateAnswers.collect { got += it } }
        yield()
        box.requestUnanswered() // the reply's time was over: the request ended without its answer
        now += 5 * 60_000 // five minutes later the agent finishes
        assertNull(box.receive("Ecco la risposta."))
        assertEquals(listOf("Ecco la risposta."), got)
        // Answered now: a message on its own well after it is an alert.
        now += 3 * 60_000
        assertNotNull(box.receive("Promemoria: chiamare il tecnico."))
        job.cancel()
    }

    @Test fun aLateAnswerInSeveralMessagesStaysTogetherInTheChat() = runBlocking {
        val box = inbox()
        val got = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) { box.lateAnswers.collect { got += it } }
        yield()
        box.requestUnanswered()
        now += 60_000
        assertNull(box.receive("Controllo il calendario."))
        now += 20_000
        assertNull(box.receive(" Domani hai il dentista alle 10."))
        assertEquals(2, got.size)
        assertTrue(box.alerts.value.isEmpty())
        job.cancel()
    }

    @Test fun whileARequestIsOpenAMessageOnItsOwnIsAnAlert() = runBlocking {
        val box = inbox()
        val job = launch(Dispatchers.Unconfined) { box.lateAnswers.collect {} }
        yield()
        box.requestUnanswered() // an earlier request had no answer...
        box.requestAnswered() // ...then a new one was sent: its answer comes on it, never on its own
        now += 60_000
        assertNotNull(box.receive("Recap delle 18."))
        job.cancel()
    }

    @Test fun alertIdsAreNeverUsedTwice() {
        val box = inbox()
        val a = box.receive("Uno")!!
        val b = box.receive("Due")!!
        box.delete(b.id)
        val c = box.receive("Tre")!!
        assertTrue(c.id > b.id)
        box.deleteAll()
        val d = inbox().receive("Quattro")!! // also after a restart with no alert left
        assertTrue(d.id > c.id, "ids ${a.id} ${b.id} ${c.id} ${d.id}")
    }

    @Test fun aLateAnswerWithNoChatOnScreenIsKeptAsAnAlert() {
        val box = inbox()
        box.requestUnanswered()
        assertNotNull(box.receive("Ecco la risposta."))
    }

    @Test fun afterAnAnsweredRequestOrALongWaitItIsAnAlert() = runBlocking {
        val box = inbox()
        val job = launch(Dispatchers.Unconfined) { box.lateAnswers.collect {} }
        yield()
        box.requestUnanswered()
        box.requestAnswered()
        assertNotNull(box.receive("Recap delle 18."))
        box.requestUnanswered()
        now += 20 * 60_000
        assertNotNull(box.receive("Recap delle 18."))
        job.cancel()
    }

    @Test fun readAndDeleted() = runBlocking {
        val box = inbox()
        val a = box.receive("Uno")!!
        val b = box.receive("Due")!!
        box.markRead(a.id)
        assertEquals(listOf(true, false), box.alerts.value.map { it.read })
        box.delete(a.id)
        assertEquals(listOf(b.id), inbox().alerts.first().map { it.id })
        box.markAllRead()
        assertTrue(inbox().alerts.value.all { it.read })
        box.deleteAll()
        assertTrue(inbox().alerts.value.isEmpty())
    }

    @Test fun keptForAMonthAndAtMostAHundred() {
        val day = 24 * 60 * 60_000L
        val old = (1..120).map { Alert(it.toLong(), "n$it", time = now - (120 - it) * 60_000L) }
        assertEquals(100, pruned(old, now).size)
        assertEquals(21L, pruned(old, now).first().id)
        assertEquals(listOf(2L), pruned(listOf(Alert(1, "vecchio", now - 31 * day), Alert(2, "nuovo", now - day)), now).map { it.id })
    }

    @Test fun aDamagedFileIsAnEmptyInbox() {
        file.writeText("{non è json")
        assertTrue(inbox().alerts.value.isEmpty())
    }

    @Test fun titleAndPreviewArePlainText() {
        val text = "**Recap di Reddit** ([r/italy](https://example.com/r))\n\n- prima discussione\n- seconda, [link](https://example.com/2)"
        assertEquals("Recap di Reddit", alertTitle(text))
        assertEquals("prima discussione seconda, link", alertPreview(text))
        assertEquals(81, alertTitle("x".repeat(200)).length)
    }

    @Test fun lateMeansRecentAndUnanswered() {
        assertTrue(isLateAnswer(now - 60_000, now))
        assertFalse(isLateAnswer(now - 16 * 60_000, now))
        assertFalse(isLateAnswer(null, now))
    }

    @Test fun theListSaysTheTimeTodayAndTheDayBefore() {
        val zone = ZoneId.of("Europe/Rome")
        val today = LocalDate.of(2026, 10, 31)
        fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("18:05", alertWhen(at(31, 18), today, zone))
        assertEquals("ieri", alertWhen(at(30, 18), today, zone))
        assertEquals("28 ott", alertWhen(at(28, 18), today, zone))
    }
}
