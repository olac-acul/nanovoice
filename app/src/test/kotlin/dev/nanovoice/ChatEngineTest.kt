package dev.nanovoice

import dev.nanovoice.actions.it.ItalianPhrases
import dev.nanovoice.agent.AgentEvent
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.core.ActionExecutor
import dev.nanovoice.core.ActionResult
import dev.nanovoice.core.PhoneAction
import dev.nanovoice.router.LocalRouter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ChatEngineTest {
    private val executed = mutableListOf<PhoneAction>()
    private val executor = object : ActionExecutor {
        override suspend fun execute(action: PhoneAction): ActionResult {
            executed += action
            return ActionResult(true, "fatto: $action")
        }
    }
    private val asked = mutableListOf<String>()
    private val askedImages = mutableListOf<AgentImage?>()
    private var agentReply: Flow<AgentEvent>? = null
    private val agent = AgentGateway { text, image -> asked += text; askedImages += image; agentReply }
    private var threshold = LocalRouter.DEFAULT_THRESHOLD

    private var now = 1_000_000L
    private var localCommands = true
    private val engine = ChatEngine({ LocalRouter.forLocale(Locale.ITALIAN, threshold)!! }, executor, ItalianPhrases, agent, { now }) { localCommands }

    private fun replies(text: String): List<String> = runBlocking { engine.respond(text).toList().map { it.text } }
    private fun say(text: String) = replies(text).last()

    // --- local handling (phase 1)

    @Test fun tellTime() {
        say("che ore sono")
        assertEquals(listOf<PhoneAction>(PhoneAction.TellTime), executed)
        assertEquals(emptyList(), asked)
    }

    @Test fun aPhotoAlwaysGoesToTheAgentEvenWithALocalCommand() {
        agentReply = flowOf(AgentEvent.Delta("Un gatto."), AgentEvent.Done)
        val photo = AgentImage("image/jpeg", byteArrayOf(1))
        assertEquals("Un gatto.", runBlocking { engine.respond("che ore sono", image = photo).toList() }.last().text)
        assertEquals(emptyList(), executed)
        assertEquals(listOf("che ore sono"), asked)
        assertEquals(listOf<AgentImage?>(photo), askedImages)
    }

    @Test fun aPhotoOnTheLockScreenAsksToUnlock() {
        val photo = AgentImage("image/jpeg", byteArrayOf(1))
        assertEquals(ItalianPhrases.unlockNeeded, runBlocking { engine.respond("cos'è?", localOnly = true, image = photo).toList() }.last().text)
        assertEquals(emptyList(), asked)
    }

    @Test fun timerWithDuration() {
        say("metti un timer di due minuti")
        assertEquals(listOf<PhoneAction>(PhoneAction.SetTimer(2.minutes)), executed)
    }

    @Test fun anUnansweredQuestionExpires() {
        assertEquals("Per quanto tempo?", say("metti un timer"))
        now += 5 * 60_000 // five minutes later
        agentReply = flowOf(AgentEvent.Delta("ok"), AgentEvent.Done)
        assertEquals("ok", say("cinque minuti di pubblicità e poi il film"))
        assertEquals(emptyList(), executed)
    }

    @Test fun timerWithoutDurationAsksThenUsesReply() {
        assertEquals("Per quanto tempo?", say("metti un timer"))
        assertEquals(emptyList(), executed)
        say("cinque minuti")
        assertEquals(listOf<PhoneAction>(PhoneAction.SetTimer(5.minutes)), executed)
    }

    @Test fun nonDurationReplyIsTreatedAsNewRequest() {
        say("metti un timer")
        say("che ore sono")
        assertEquals(listOf<PhoneAction>(PhoneAction.TellTime), executed)
    }

    @Test fun waitingStateIsClearedAfterOneReply() {
        say("metti un timer")
        say("boh")
        say("dieci minuti")
        assertEquals(emptyList(), executed)
    }

    // --- NanoClaw (phase 2)

    @Test fun unknownRequestStreamsAgentReply() {
        agentReply = flowOf(AgentEvent.Delta("Oggi hai "), AgentEvent.Delta("tre email."), AgentEvent.Done)
        assertEquals(listOf("Oggi hai ", "Oggi hai tre email."), replies("riassumimi le email"))
        assertEquals(listOf("riassumimi le email"), asked)
        assertEquals(emptyList(), executed)
    }

    @Test fun workingIsSetWhileTheAgentWorksAndClearedByTheAnswer() {
        agentReply = flowOf(
            AgentEvent.Delta("Cerco sul Web..."),
            AgentEvent.Working,
            AgentEvent.Delta(" Ecco il sito."),
            AgentEvent.Done,
        )
        val seen = runBlocking { engine.respond("trova il sito di dicio").toList() }
        // the ack, the same text again once the agent says it is working, then the full answer
        assertEquals(
            listOf(
                ReplySnapshot("Cerco sul Web...", messageEnd = true),
                ReplySnapshot("Cerco sul Web...", working = true),
                ReplySnapshot("Cerco sul Web... Ecco il sito.", messageEnd = true),
            ),
            seen,
        )
    }

    @Test fun eachMessageOfTheAgentIsMarkedWholeAndALocalReplyIsNot() {
        agentReply = flowOf(AgentEvent.Delta("Domani alle 10 hai il dentista."), AgentEvent.Done)
        assertEquals(listOf(ReplySnapshot("Domani alle 10 hai il dentista.", messageEnd = true)),
            runBlocking { engine.respond("che impegni ho domani").toList() })
        assertTrue(runBlocking { engine.respond("che ore sono").toList() }.none { it.messageEnd })
    }

    @Test fun anErrorAfterWorkingEndsTheWorkAndShows() {
        agentReply = flowOf(AgentEvent.Delta("Cerco sul Web..."), AgentEvent.Working, AgentEvent.Error("Il server non risponde."))
        val last = runBlocking { engine.respond("trova qualcosa").toList() }.last()
        assertEquals(ReplySnapshot("Cerco sul Web...\nIl server non risponde."), last)
    }

    @Test fun newConversationForgetsAPendingQuestion() {
        replies("imposta un timer")              // asks for the length
        engine.resetConversation()
        agentReply = flowOf(AgentEvent.Delta("ok"), AgentEvent.Done)
        assertEquals("ok", say("cinque minuti")) // not taken as the timer length any more
        assertEquals(emptyList(), executed)
    }

    private fun lockedReplies(text: String): List<String> = runBlocking { engine.respond(text, localOnly = true).toList().map { it.text } }

    @Test fun lockedPhoneAnswersTimeDateTimersAndAlarms() {
        lockedReplies("che ore sono")
        lockedReplies("che giorno è oggi")
        lockedReplies("imposta un timer di cinque minuti")
        lockedReplies("metti una sveglia alle sette")
        assertEquals(4, executed.size)
        assertEquals(emptyList(), asked)
    }

    @Test fun lockedPhoneDoesNotReachTheAgentAppsOrCalls() {
        agentReply = flowOf(AgentEvent.Delta("segreto"), AgentEvent.Done)
        assertEquals(listOf(ItalianPhrases.unlockNeeded), lockedReplies("riassumimi le email"))
        assertEquals(listOf(ItalianPhrases.unlockNeeded), lockedReplies("apri signal"))
        assertEquals(listOf(ItalianPhrases.unlockNeeded), lockedReplies("chiama mario"))
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), executed)
    }

    @Test fun noServerConfigured() {
        agentReply = null
        assertEquals("Non so ancora rispondere a questo.", say("riassumimi le email"))
    }

    @Test fun serverUnreachableShowsErrorAndLocalStillWorks() {
        agentReply = flowOf(AgentEvent.Error("Server non raggiungibile."))
        assertEquals("Server non raggiungibile.", say("riassumimi le email"))
        say("che ore sono")
        assertEquals(listOf<PhoneAction>(PhoneAction.TellTime), executed)
    }

    @Test fun errorAfterPartialReplyKeepsTheText() {
        agentReply = flowOf(AgentEvent.Delta("Oggi hai"), AgentEvent.Error("connessione persa"))
        assertEquals("Oggi hai\nconnessione persa", say("riassumimi le email"))
    }

    @Test fun localRequestsDoNotReachTheAgent() {
        agentReply = flowOf(AgentEvent.Delta("no"), AgentEvent.Done)
        say("che ore sono")
        say("metti un timer di due minuti")
        assertEquals(emptyList(), asked)
    }

    @Test fun lowConfidenceMatchGoesToTheAgent() {
        agentReply = flowOf(AgentEvent.Delta("ok"), AgentEvent.Done)
        threshold = 0.95f // "metti un timer" (no duration) has confidence 0.9
        say("metti un timer")
        assertEquals(listOf("metti un timer"), asked)
        assertEquals(emptyList(), executed)
    }

    @Test fun withLocalCommandsOffEverythingGoesToTheAgentButTheLockScreenStaysLocal() {
        agentReply = flowOf(AgentEvent.Delta("ok"), AgentEvent.Done)
        localCommands = false
        say("che ore sono")
        say("metti un timer di due minuti")
        assertEquals(listOf("che ore sono", "metti un timer di due minuti"), asked)
        assertEquals(emptyList(), executed)
        runBlocking { engine.respond("che ore sono", localOnly = true).toList() }
        assertEquals(listOf<PhoneAction>(PhoneAction.TellTime), executed)
        assertEquals(2, asked.size)
    }

    @Test fun agentThatOnlyActsProducesNoReplyText() {
        agentReply = flowOf(AgentEvent.Done)
        assertEquals(emptyList(), replies("ricordami di spegnere il forno"))
    }

    // --- alarms

    @Test fun alarmWithTimeIsSetAtOnce() {
        say("svegliami alle sette e mezza")
        assertEquals(listOf<PhoneAction>(PhoneAction.SetAlarm(7, 30)), executed)
        assertEquals(emptyList(), asked)
    }

    @Test fun alarmWithoutTimeAsksThenUsesTheReply() {
        assertEquals("A che ora?", say("metti una sveglia"))
        assertEquals(emptyList(), executed)
        say("alle sei e un quarto")
        assertEquals(listOf<PhoneAction>(PhoneAction.SetAlarm(6, 15)), executed)
    }

    @Test fun tomorrowIsRememberedAcrossTheQuestion() {
        say("svegliami domani")
        say("alle sei")
        assertEquals(listOf<PhoneAction>(PhoneAction.SetAlarm(6, 0, tomorrow = true)), executed)
    }

    @Test fun aReplyThatIsNotATimeIsANewRequest() {
        say("metti una sveglia")
        say("che ore sono")
        assertEquals(listOf<PhoneAction>(PhoneAction.TellTime), executed)
    }

    @Test fun tellDate() {
        say("che giorno è oggi")
        assertEquals(listOf<PhoneAction>(PhoneAction.TellDate), executed)
    }
}
