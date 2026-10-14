package dev.nanovoice

import dev.nanovoice.agent.ActionOutcome
import dev.nanovoice.agent.ActionRequest
import dev.nanovoice.core.CalendarEditor
import dev.nanovoice.core.CalendarEvent
import dev.nanovoice.core.EventChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An approval belongs to one event snapshot and one change. A separate `confirm` never authorizes a write. */
class CalendarActions(
    private val editor: CalendarEditor,
    private val consent: suspend (String) -> Consent,
    private val enabled: () -> Boolean,
    private val locked: () -> Boolean,
) {
    private val turn = Mutex()

    suspend fun handle(name: String, args: JsonObject, request: ActionRequest): ActionOutcome = turn.withLock {
        try {
            perform(name, args, request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            refused("Serve il permesso di lettura e modifica del calendario nelle impostazioni.")
        } catch (_: Exception) {
            // A provider exception can contain event data; it must not reach the action log.
            refused("Non riesco ad aggiornare il calendario. Controlla l'evento nell'app Calendario.")
        }
    }

    private suspend fun perform(name: String, args: JsonObject, request: ActionRequest): ActionOutcome {
        fun allowed() = request.isOpen() && enabled() && !locked()
        if (!allowed()) return refused("Richiesta interrotta, telefono bloccato o modifiche disattivate.")
        if (!editor.permitted()) return refused("Serve il permesso di lettura e modifica del calendario nelle impostazioni.")
        val keys = if (name == "calendar_update") setOf("event_id", "title", "start", "end", "location")
        else if (name == "calendar_delete") setOf("event_id") else return refused("Azione calendario sconosciuta.")
        if (args.keys.any { it !in keys }) return refused("Parametri calendario non supportati.")
        val idValue = args["event_id"] as? JsonPrimitive
        val id = idValue?.takeUnless { it.isString }?.longOrNull
        if (id == null || id !in 1..9_007_199_254_740_991L) return refused("Serve un event_id valido restituito da calendar_read.")
        val event = editor.find(id) ?: return refused("Evento non trovato: rileggi il calendario.")
        if (!event.writable) return refused("Questo calendario non permette di modificare l'evento.")
        if (event.recurring) return refused("Per modificare o cancellare eventi ricorrenti usa l'app Calendario.")
        if (event.title.length > 500 || (event.location?.length ?: 0) > 500 || event.calendarName.length > 200) {
            return refused("Questo evento ha testi troppo lunghi: usa l'app Calendario.")
        }
        val change = if (name == "calendar_update") parseChange(args) ?: return refused("Modifica non valida: controlla titolo, luogo e date (yyyy-MM-ddTHH:mm).") else null
        if (change != null) {
            if (change.isEmpty) return refused("Indica almeno un campo da modificare.")
            if (!validTimes(event, change)) return refused("Orari non validi o ambigui: la fine deve seguire l'inizio; per tutto il giorno usa mezzanotte e la data di fine esclusa.")
            val times = change.newTimes(event)
            if ((change.title ?: event.title) == event.title && times.first == event.start && times.second == event.end &&
                (change.location ?: event.location) == event.location) return refused("L'evento ha già questi dati.")
        }
        if (!allowed()) return refused("Richiesta non più attiva.")
        val question = question(event, change)
        when (consent(question)) {
            Consent.No -> return refused("Modifica non approvata: calendario invariato.")
            Consent.NoAnswer -> return refused("Nessuna risposta: calendario invariato.")
            Consent.Yes -> Unit
        }
        // AndroidCalendarEditor repeats this guard on its IO thread immediately before the conditional write.
        if (!allowed()) return refused("Richiesta interrotta, telefono bloccato o modifiche disattivate.")
        if (!editor.permitted()) return refused("Permesso del calendario revocato: modifica non eseguita.")
        if (editor.find(id) != event) return refused("L'evento è cambiato durante l'approvazione: rileggi il calendario e chiedi una nuova modifica.")
        val written = if (change == null) editor.delete(event, ::allowed) else editor.update(event, change, ::allowed)
        return if (written) ActionOutcome(true, if (change == null) "Evento cancellato dal calendario del telefono." else "Evento aggiornato nel calendario del telefono.")
        else refused("Evento cambiato o richiesta non più autorizzata: modifica non eseguita. Rileggi il calendario.")
    }

    private fun parseChange(args: JsonObject): EventChange? {
        fun text(key: String, max: Int, empty: Boolean = false): String? {
            if (key !in args) return null
            val value = args[key] as? JsonPrimitive ?: throw IllegalArgumentException()
            require(value.isString)
            val s = value.content.trim()
            require(s.length <= max && (empty || s.isNotEmpty()) && s.none { it.isISOControl() || it.category == CharCategory.FORMAT })
            return s
        }
        fun time(key: String): LocalDateTime? = text(key, 16)?.let {
            require(DATE_TIME.matches(it))
            LocalDateTime.parse(it).also { date -> require(date.year in 1..9999) }
        }
        return try { EventChange(text("title", 200), time("start"), time("end"), text("location", 500, empty = true)) }
        catch (_: IllegalArgumentException) { null }
        catch (_: java.time.DateTimeException) { null }
    }

    private fun validTimes(event: CalendarEvent, change: EventChange): Boolean {
        if (change.start == null && change.end == null) return true
        val (start, end) = change.newTimes(event)
        if (end != null && !end.isAfter(start)) return false
        val times = listOfNotNull(event.start, event.end, start, end)
        return if (event.allDay) times.all { it.toLocalTime() == java.time.LocalTime.MIDNIGHT }
        else times.all { event.zone.rules.getValidOffsets(it).size == 1 }
    }

    private fun question(event: CalendarEvent, change: EventChange?): String {
        fun visible(s: String) = s.map { if (it.isISOControl() || it.category == CharCategory.FORMAT) ' ' else it }.joinToString("")
        fun details(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?): String {
            fun stamp(time: LocalDateTime) = time.format(DAY) + if (event.allDay) "" else " alle ${time.toLocalTime()}"
            return buildString {
                append("«${visible(title)}»")
                if (event.calendarName.isNotBlank()) append("\nCalendario: ${visible(event.calendarName)}")
                append("\nInizio: ${stamp(start)}")
                end?.let { append("\nFine: ${stamp(it)}") }
                if (event.allDay) append("\nTutto il giorno; la data di fine è esclusa.")
                append("\nLuogo: ${location?.takeIf { it.isNotBlank() }?.let(::visible) ?: "nessuno"}")
            }
        }
        // The first line is the question said aloud and shown in large type; the details below are only shown.
        fun spoken(time: LocalDateTime) = time.format(SPOKEN_DAY) + if (event.allDay) "" else " alle ${time.toLocalTime()}"
        val name = "«${visible(event.title)}»"
        val before = details(event.title, event.start, event.end, event.location)
        if (change == null) return "Cancello $name di ${spoken(event.start)}?\n$before"
        val (start, end) = change.newTimes(event)
        val onlyMoved = start != event.start && (change.title ?: event.title) == event.title &&
            (change.location ?: event.location) == event.location
        val headline = if (onlyMoved) "Sposto $name da ${spoken(event.start)} a ${spoken(start)}?" else "Modifico $name di ${spoken(event.start)}?"
        return "$headline\n$before\n\nNuovi dati:\n" + details(change.title ?: event.title, start, end, change.location ?: event.location)
    }

    private fun refused(message: String) = ActionOutcome(false, message)

    companion object {
        val NAMES = setOf("calendar_update", "calendar_delete")
        private val DATE_TIME = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ITALIAN)
        /** Whole words: the voice would read "mar 20 ott" letter by letter. */
        private val SPOKEN_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
    }
}
