package dev.nanovoice

import dev.nanovoice.actions.SpokenPhrases
import dev.nanovoice.agent.AgentEvent
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.core.ActionExecutor
import dev.nanovoice.core.PhoneAction
import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalRouter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * The reply so far: [text] is everything said, [working] true while the agent said it is busy (a web search, a call to
 * an outside service) and the answer is still to come.
 */
/**
 * The reply so far. [working]: the agent announced a long task. [messageEnd]: the text ends with a whole message of the
 * agent (the server delivers whole messages, never half of one), so what is still unsaid can be said now instead of
 * when the reply is over, about 1.5 s later.
 */
data class ReplySnapshot(
    val text: String,
    val working: Boolean = false,
    val messageEnd: Boolean = false,
    /** The server started the conversation over before this request: nothing to show as reply text. */
    val contextRestarted: Boolean = false,
)

/**
 * Turns a user utterance into a reply: local router -> action executor, otherwise NanoClaw.
 * [respond] emits the full reply so far, so the UI can just show the latest value while it streams. Each reply carries
 * its own working state: nothing is shared between requests.
 * Holds one piece of conversational state: a timer waiting for its duration.
 */
class ChatEngine(
    private val routerProvider: () -> LocalRouter,
    private val executor: ActionExecutor,
    private val phrases: SpokenPhrases,
    private val agent: AgentGateway,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** "Comandi semplici sul telefono" in the settings; off sends every request to the agent while unlocked. */
    private val localCommands: () -> Boolean = { true },
) {
    private var awaitingTimerDuration = false
    private var awaitingAlarmTime: Boolean = false
    private var alarmTomorrow = false
    /** When "Per quanto tempo?" or "A che ora?" was asked: an answer much later is a new request, not the reply. */
    private var askedAt = 0L

    /** Forgets the questions still waiting for an answer (timer length, alarm time). */
    fun resetConversation() {
        awaitingTimerDuration = false
        awaitingAlarmTime = false
        alarmTomorrow = false
    }

    /**
     * [localOnly] is for the locked phone: only the time, the date, timers and alarms are answered; anything else
     * (the agent, opening apps, calls) would reach private data or the unlocked phone, so it asks to unlock.
     * A message with an [image] is always for the agent: "cos'è questo?" is about the photo, not a local command.
     * [forAgent] is what the agent gets when it answers: [text] with the app's own lines (the message it replies to,
     * a barcode). The local commands read only [text], the person's words.
     */
    fun respond(text: String, localOnly: Boolean = false, image: AgentImage? = null, forAgent: String = text): Flow<ReplySnapshot> = flow {
        if (clock() - askedAt > QUESTION_TTL_MS) resetConversation()
        if (image != null) {
            if (localOnly) emit(ReplySnapshot(phrases.unlockNeeded)) else askAgent(forAgent, image)
            return@flow
        }
        // On the lock screen the agent is out of reach: the simple commands stay on the phone even when switched off.
        if (!localOnly && !localCommands()) {
            resetConversation()
            askAgent(forAgent)
            return@flow
        }
        val router = routerProvider()
        if (awaitingTimerDuration) {
            awaitingTimerDuration = false
            val duration = router.parseDurationReply(text)
            if (duration != null) {
                emit(ReplySnapshot(executor.execute(PhoneAction.SetTimer(duration)).message))
                return@flow
            }
            // not a duration: treat it as a new request
        }
        if (awaitingAlarmTime) {
            awaitingAlarmTime = false
            val time = router.parseClockReply(text)
            if (time != null) {
                emit(ReplySnapshot(executor.execute(PhoneAction.SetAlarm(time.hour, time.minute, alarmTomorrow)).message))
                return@flow
            }
            // not a time: treat it as a new request
        }
        when (val intent = router.route(text)?.intent) {
            LocalIntent.TellTime -> emit(ReplySnapshot(executor.execute(PhoneAction.TellTime).message))
            LocalIntent.TellDate -> emit(ReplySnapshot(executor.execute(PhoneAction.TellDate).message))
            is LocalIntent.OpenApp -> if (localOnly) emit(ReplySnapshot(phrases.unlockNeeded)) else emit(ReplySnapshot(executor.execute(PhoneAction.OpenApp(intent.name)).message))
            is LocalIntent.Note -> if (localOnly) emit(ReplySnapshot(phrases.unlockNeeded)) else emit(ReplySnapshot(executor.execute(PhoneAction.Note(intent.text)).message))
            is LocalIntent.PlayMusic -> if (localOnly) emit(ReplySnapshot(phrases.unlockNeeded)) else emit(ReplySnapshot(executor.execute(PhoneAction.PlayMusic(intent.query)).message))
            is LocalIntent.Call -> if (localOnly) emit(ReplySnapshot(phrases.unlockNeeded)) else emit(ReplySnapshot(executor.execute(PhoneAction.Call(intent.target)).message))
            is LocalIntent.SetAlarm -> {
                val time = intent.time
                if (time == null) {
                    awaitingAlarmTime = true
                    alarmTomorrow = intent.tomorrow
                    askedAt = clock()
                    emit(ReplySnapshot(phrases.askAlarmTime))
                } else {
                    emit(ReplySnapshot(executor.execute(PhoneAction.SetAlarm(time.hour, time.minute, intent.tomorrow)).message))
                }
            }
            is LocalIntent.SetTimer -> {
                val duration = intent.duration
                if (duration == null) {
                    awaitingTimerDuration = true
                    askedAt = clock()
                    emit(ReplySnapshot(phrases.askTimerDuration))
                } else {
                    emit(ReplySnapshot(executor.execute(PhoneAction.SetTimer(duration)).message))
                }
            }
            // Allowed with the phone locked too: an alarm ringing on a locked phone is exactly when this is wanted.
            LocalIntent.StopTimer -> emit(ReplySnapshot(executor.execute(PhoneAction.StopTimer).message))
            is LocalIntent.StopAlarm -> emit(ReplySnapshot(executor.execute(PhoneAction.StopAlarm(intent.time?.hour, intent.time?.minute)).message))
            is LocalIntent.SnoozeAlarm -> emit(ReplySnapshot(executor.execute(PhoneAction.SnoozeAlarm(intent.minutes)).message))
            null -> if (localOnly) emit(ReplySnapshot(phrases.unlockNeeded)) else askAgent(forAgent)
        }
    }

    private suspend fun FlowCollector<ReplySnapshot>.askAgent(text: String, image: AgentImage? = null) {
        val events = agent.ask(text, image)
        if (events == null) {
            emit(ReplySnapshot(phrases.notUnderstood))
            return
        }
        val reply = StringBuilder()
        events.collect { event ->
            when (event) {
                is AgentEvent.Delta -> {
                    reply.append(event.text)
                    emit(ReplySnapshot(reply.toString(), messageEnd = true))
                }
                // Same text again, now marked working: what was said so far can be read out while the work goes on.
                AgentEvent.Working -> emit(ReplySnapshot(reply.toString(), working = true))
                AgentEvent.Done -> Unit
                AgentEvent.ContextRestarted -> emit(ReplySnapshot(reply.toString(), contextRestarted = true))
                // An error ends the work too: it must show, not stay hidden behind the status line.
                is AgentEvent.Error -> emit(ReplySnapshot(if (reply.isEmpty()) event.message else "$reply\n${event.message}"))
            }
        }
    }
}

private const val QUESTION_TTL_MS = 60_000L
