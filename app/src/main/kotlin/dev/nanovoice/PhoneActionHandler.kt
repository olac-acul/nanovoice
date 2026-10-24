package dev.nanovoice

import dev.nanovoice.agent.ActionHandler
import dev.nanovoice.agent.ActionOutcome
import dev.nanovoice.agent.ActionRequest
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.core.ActionExecutor
import dev.nanovoice.core.PhoneAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Runs actions requested by NanoClaw through the same executor the local router uses. [enabled] gives the actions the
 * settings currently allow: anything else is refused even if the server asks (it should not: they are not declared).
 */
class PhoneActionHandler(
    private val executor: ActionExecutor,
    private val enabled: () -> Set<String> = { ALL },
    /** The photo tools (PhotoActions), given the photo of the request the action belongs to, if it had one. */
    private val photos: (suspend (String, JsonObject, AgentImage?) -> ActionOutcome)? = null,
    /** The phone is locked: then only the actions a locked phone answers by itself (time, date, timers) run. */
    private val locked: () -> Boolean = { false },
    /** Asks the person a question of the agent ("Sposto il dentista a mercoledì?") for `confirm`; no answer is a no. */
    private val consent: suspend (String) -> Consent = { Consent.NoAnswer },
    /** Asks the person to choose one of a few options for `choose`: the index, ActionConsent.REFUSED, or null. */
    private val choose: suspend (String, List<String>) -> Int? = { _, _ -> null },
    private val calendar: CalendarActions? = null,
) : ActionHandler {
    override suspend fun handle(name: String, args: JsonObject, request: ActionRequest): ActionOutcome {
        if (name !in enabled()) return ActionOutcome(false, "azione disattivata nelle impostazioni del telefono: $name")
        if (name !in WHEN_LOCKED && locked()) return ActionOutcome(false, "il telefono è bloccato: azione non eseguita")
        if (name in CALENDAR_EDITS) return calendar?.handle(name, args, request) ?: ActionOutcome(false, "modifiche al calendario non disponibili")
        if (name in PHOTOS) return photos?.invoke(name, args, request.image) ?: ActionOutcome(false, "strumenti per le foto non disponibili")
        if (name == "confirm") return confirm(args.text("question") ?: return ActionOutcome(false, "manca il parametro question"), request)
        if (name == "choose") return choose(args, request)
        val action = when (name) {
            "tell_time" -> PhoneAction.TellTime
            "tell_date" -> PhoneAction.TellDate
            "set_alarm" -> {
                val hour = (args["hour"] as? JsonPrimitive)?.longOrNull?.toInt()
                val minute = (args["minute"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                if (hour == null || hour !in 0..23 || minute !in 0..59) return ActionOutcome(false, "ora non valida")
                val tomorrow = (args["tomorrow"] as? JsonPrimitive)?.booleanOrNull ?: false
                PhoneAction.SetAlarm(hour, minute, tomorrow)
            }
            "stop_timer" -> PhoneAction.StopTimer
            "stop_alarm" -> {
                val hour = (args["hour"] as? JsonPrimitive)?.longOrNull?.toInt()
                val minute = (args["minute"] as? JsonPrimitive)?.longOrNull?.toInt()
                if (hour == null && minute != null) return ActionOutcome(false, "minute senza hour")
                if ((hour != null && hour !in 0..23) || (minute != null && minute !in 0..59)) return ActionOutcome(false, "ora non valida")
                PhoneAction.StopAlarm(hour, minute)
            }
            "snooze_alarm" -> {
                val minutes = (args["minutes"] as? JsonPrimitive)?.longOrNull?.toInt()
                if (minutes != null && minutes !in 1..60) return ActionOutcome(false, "minutes va da 1 a 60")
                PhoneAction.SnoozeAlarm(minutes)
            }
            "open_app" -> PhoneAction.OpenApp(args.text("name") ?: return ActionOutcome(false, "manca il parametro name"))
            // Only prepares the call: the executor opens the dialer and never dials by itself.
            "call" -> PhoneAction.Call(args.text("target") ?: return ActionOutcome(false, "manca il parametro target"))
            "set_timer" -> {
                val seconds = (args["seconds"] as? JsonPrimitive)?.longOrNull
                    ?: return ActionOutcome(false, "manca il parametro seconds")
                PhoneAction.SetTimer(seconds.seconds)
            }
            "media" -> PhoneAction.Media(
                when (args.text("command")) {
                    "play" -> PhoneAction.MediaCommand.Play
                    "pause" -> PhoneAction.MediaCommand.Pause
                    "toggle" -> PhoneAction.MediaCommand.Toggle
                    "next" -> PhoneAction.MediaCommand.Next
                    "previous" -> PhoneAction.MediaCommand.Previous
                    else -> return ActionOutcome(false, "command deve essere play, pause, toggle, next o previous")
                },
            )
            "volume" -> {
                val percent = (args["percent"] as? JsonPrimitive)?.longOrNull?.toInt()
                when {
                    percent != null -> if (percent in 0..100) PhoneAction.Volume(percent = percent) else return ActionOutcome(false, "percent va da 0 a 100")
                    args.text("change") == "up" -> PhoneAction.Volume(up = true)
                    args.text("change") == "down" -> PhoneAction.Volume(up = false)
                    args.text("change") == "mute" -> PhoneAction.Volume(mute = true)
                    else -> return ActionOutcome(false, "serve percent oppure change (up, down, mute)")
                }
            }
            "flashlight" -> PhoneAction.Flashlight((args["on"] as? JsonPrimitive)?.booleanOrNull ?: return ActionOutcome(false, "manca il parametro on"))
            "navigate" -> PhoneAction.Navigate(args.text("destination") ?: return ActionOutcome(false, "manca il parametro destination"))
            "open_url" -> {
                val url = safeLink(args.text("url") ?: return ActionOutcome(false, "manca il parametro url"))
                    ?: return ActionOutcome(false, "il link deve essere https, con un host e senza nome utente")
                // The address the person is shown is the one that opens, not the text the agent sent.
                PhoneAction.OpenUrl(url.toASCIIString())
            }
            "play_music" -> PhoneAction.PlayMusic(args.text("query") ?: return ActionOutcome(false, "manca il parametro query"))
            "note" -> PhoneAction.Note(args.text("text") ?: return ActionOutcome(false, "manca il parametro text"), args.text("title"))
            "signal_draft" -> PhoneAction.SignalDraft(args.text("to"), args.text("text") ?: return ActionOutcome(false, "manca il parametro text"))
            "sms_draft" -> PhoneAction.SmsDraft(args.text("to"), args.text("text") ?: return ActionOutcome(false, "manca il parametro text"))
            "calendar_draft" -> PhoneAction.CalendarDraft(
                title = args.text("title") ?: return ActionOutcome(false, "manca il parametro title"),
                start = args.text("start") ?: return ActionOutcome(false, "manca il parametro start"),
                end = args.text("end"),
                location = args.text("location"),
            )
            "battery" -> PhoneAction.Battery
            "calendar_read" -> PhoneAction.CalendarRead(((args["days"] as? JsonPrimitive)?.longOrNull ?: 1L).toInt().coerceIn(1, 14))
            "location" -> PhoneAction.Location
            "notifications" -> PhoneAction.Notifications(((args["limit"] as? JsonPrimitive)?.longOrNull ?: 5L).toInt().coerceIn(1, 10))
            else -> return ActionOutcome(false, "azione sconosciuta: $name")
        }
        val result = executor.execute(action)
        // The person sees and hears the contact on the phone; the agent only learns whether it worked. Names from the
        // address book would otherwise flow to it one query at a time ("Ma", "Mar"...).
        if (name in CONTACT_LOOKUPS) {
            return ActionOutcome(
                result.ok,
                if (result.ok) "fatto: il telefono mostra il contatto trovato, la persona lo vede"
                else "nessun contatto univoco o permesso mancante: chiedi alla persona il nome completo",
            )
        }
        return ActionOutcome(result.ok, result.message)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The agent asks the person to approve a change (a calendar event moved or deleted) before making it. The answer
     * comes from a tap, the voice or the keyboard; it counts only if the request is still open when it arrives.
     */
    private suspend fun confirm(question: String, request: ActionRequest): ActionOutcome {
        if (question.length > MAX_QUESTION) return ActionOutcome(false, "la domanda deve stare in $MAX_QUESTION caratteri")
        return when (consent(question)) {
            Consent.Yes -> when {
                !request.isOpen() -> ActionOutcome(false, "richiesta interrotta: non fare la modifica")
                locked() -> ActionOutcome(false, "il telefono è bloccato: non fare la modifica")
                else -> ActionOutcome(true, "la persona ha approvato: procedi")
            }
            Consent.No -> ActionOutcome(false, "la persona non ha approvato: non fare la modifica")
            Consent.NoAnswer -> ActionOutcome(false, "nessuna risposta dalla persona: non fare la modifica")
        }
    }

    /** The option chosen is the outcome's detail, word for word: the server gives it back to the agent's question. */
    private suspend fun choose(args: JsonObject, request: ActionRequest): ActionOutcome {
        val question = args.text("question") ?: return ActionOutcome(false, "manca il parametro question")
        if (question.length > MAX_QUESTION) return ActionOutcome(false, "la domanda deve stare in $MAX_QUESTION caratteri")
        val options = (args["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { o -> o.isNotEmpty() } }
        if (options.size !in 2..MAX_OPTIONS || options.any { it.length > MAX_OPTION } || options.toSet().size != options.size) {
            return ActionOutcome(false, "servono da 2 a $MAX_OPTIONS opzioni diverse, ognuna di al massimo $MAX_OPTION caratteri")
        }
        return when (val index = choose(question, options)) {
            null -> ActionOutcome(false, "nessuna risposta dalla persona")
            ActionConsent.REFUSED -> ActionOutcome(false, "la persona non ha scelto nessuna opzione")
            else -> when {
                !request.isOpen() -> ActionOutcome(false, "richiesta interrotta")
                locked() -> ActionOutcome(false, "il telefono è bloccato")
                else -> ActionOutcome(true, options[index])
            }
        }
    }

    companion object {
        private const val MAX_QUESTION = 200
        private const val MAX_OPTIONS = 5
        private const val MAX_OPTION = 60

        /**
         * An https link with a host and no user name ("https://bank.example.com@other.test" opens other.test), or null.
         * java.net.URI is strict: what it accepts, Android's Uri reads with the same host.
         */
        internal fun safeLink(raw: String): java.net.URI? = runCatching { java.net.URI(raw) }.getOrNull()?.takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.rawUserInfo == null
        }

        /** Always available: the agent asks the person to approve a change, or to choose (see [ActionConsent]). */
        val CONFIRM = setOf("confirm", "choose")

        /** Always available: the actions the local router can do as well. */
        val BASIC = setOf("set_timer", "tell_time", "tell_date", "set_alarm", "open_app", "call", "stop_timer", "stop_alarm", "snooze_alarm")
        val CONTROLS = setOf("media", "volume", "flashlight", "play_music")
        val LINKS = setOf("navigate", "open_url")
        val DRAFTS = setOf("sms_draft", "signal_draft", "calendar_draft", "note")
        val STATUS = setOf("battery")
        val CALENDAR = setOf("calendar_read")
        val CALENDAR_EDITS = CalendarActions.NAMES
        val LOCATION = setOf("location")
        val NOTIFICATIONS = setOf("notifications")

        /** On the photo of the request they answer (none without one); off with "Servizi esterni per le foto". */
        val PHOTOS = PhotoActions.NAMES

        /** Actions that look up the address book: their outcome reaches the agent without names. */
        private val CONTACT_LOOKUPS = setOf("call", "sms_draft")

        /** What runs with the phone locked: the same as a locked phone answers by itself. */
        val WHEN_LOCKED = setOf("set_timer", "set_alarm", "tell_time", "tell_date", "stop_timer", "stop_alarm", "snooze_alarm")

        /** Every action the `when` above handles, and the photo tools. */
        val ALL = BASIC + CONFIRM + CONTROLS + LINKS + DRAFTS + STATUS + CALENDAR + CALENDAR_EDITS + LOCATION + NOTIFICATIONS + PHOTOS

        /** What to declare in `hello`, from the switches in the settings. */
        fun capabilities(settings: AppSettings): Set<String> = buildSet {
            addAll(BASIC)
            addAll(CONFIRM)
            if (settings.photoServices) addAll(PHOTOS)
            if (settings.phoneControls) addAll(CONTROLS)
            if (settings.phoneLinks) addAll(LINKS)
            if (settings.phoneDrafts) addAll(DRAFTS)
            if (settings.phoneStatus) addAll(STATUS)
            if (settings.readCalendar) addAll(CALENDAR)
            if (settings.readCalendar && settings.editCalendar) addAll(CALENDAR_EDITS)
            if (settings.readLocation) addAll(LOCATION)
            if (settings.readNotifications) addAll(NOTIFICATIONS)
        }
    }
}
