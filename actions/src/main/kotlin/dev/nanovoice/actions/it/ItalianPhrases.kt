package dev.nanovoice.actions.it

import dev.nanovoice.actions.SpokenPhrases
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration

object ItalianPhrases : SpokenPhrases {
    /**
     * The time as people say it: hour, then "e" and the minutes ("sono le dodici e quarantanove"). Never the
     * "meno" form ("l'una meno undici"): it is correct Italian but easy to misread when spoken by a TTS.
     */
    override fun time(time: LocalTime): String {
        val h = time.hour
        val m = time.minute
        if (m == 0) return hourPhrase(h)
        val minutes = when (m) {
            1 -> "un minuto"
            15 -> "un quarto"
            30 -> "mezza"
            else -> ItalianNumberWords.of(m)
        }
        // Noon with minutes is "le dodici e ..."; midnight keeps its own word, as "le dodici" would be ambiguous.
        val hour = if (h == 12) "sono le dodici" else hourPhrase(h)
        return "$hour e $minutes"
    }

    private val days = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
    private val months = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
    )

    /** "oggi è lunedì dodici ottobre"; the first of the month is "primo". */
    override fun date(date: LocalDate): String {
        val day = if (date.dayOfMonth == 1) "primo" else ItalianNumberWords.of(date.dayOfMonth)
        return "oggi è ${days[date.dayOfWeek.value - 1]} $day ${months[date.monthValue - 1]}"
    }

    private fun hourPhrase(h24: Int): String = when (val h = h24 % 12) {
        0 -> if (h24 == 0) "è mezzanotte" else "è mezzogiorno"
        1 -> "è l'una"
        else -> "sono le ${ItalianNumberWords.of(h)}"
    }

    override fun timerSet(duration: Duration) = "Ok, timer impostato per ${spokenDuration(duration)}."

    private fun spokenDuration(d: Duration): String {
        val total = d.inWholeSeconds
        val parts = buildList {
            val h = (total / 3600).toInt()
            val m = (total % 3600 / 60).toInt()
            val s = (total % 60).toInt()
            if (h > 0) add(if (h == 1) "un'ora" else "${ItalianNumberWords.of(h)} ore")
            if (m > 0) add(if (m == 1) "un minuto" else "${ItalianNumberWords.of(m)} minuti")
            if (s > 0) add(if (s == 1) "un secondo" else "${ItalianNumberWords.of(s)} secondi")
        }
        return if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
    }

    /** "alle sette e mezza", "all'una", "a mezzogiorno", "alle diciannove e trenta". */
    private fun alarmTimeWords(hour: Int, minute: Int): String {
        val base = when (hour) {
            0 -> "a mezzanotte"
            12 -> "a mezzogiorno"
            1 -> "all'una"
            else -> "alle ${ItalianNumberWords.of(hour)}"
        }
        val minutes = when (minute) {
            0 -> return base
            1 -> "un minuto"
            15 -> "un quarto"
            30 -> "mezza"
            else -> ItalianNumberWords.of(minute)
        }
        return "$base e $minutes"
    }

    override fun alarmSet(hour: Int, minute: Int, ringsToday: Boolean): String {
        val ok = "Ok, sveglia ${alarmTimeWords(hour, minute)}."
        return if (ringsToday) "$ok Attenzione: l'orario è ancora a venire, quindi suonerà oggi e non domani." else ok
    }

    override val askAlarmTime = "A che ora?"
    override fun openingApp(label: String) = "Apro $label."
    override fun appNotFound(query: String) = "Non ho trovato nessuna app che si chiami $query."
    override fun calling(name: String) = "Chiamo $name."
    override val callingNumber = "Compongo il numero."
    override fun contactNotFound(query: String) = "Non ho trovato nessun contatto che si chiami $query."
    override fun contactAmbiguous(names: List<String>) =
        "Ho trovato più contatti: ${names.take(3).joinToString(", ")}. Dimmi il nome completo."
    override val contactsPermissionNeeded = "Per chiamare devo poter leggere i contatti: consenti l'accesso dalle impostazioni."
    override val dialerNotFound = "Non ho trovato nessuna app per telefonare."
    override val alarmNoApp = "Non ho trovato nessuna app che possa impostare la sveglia."
    override val alarmDenied = "Non ho il permesso di impostare la sveglia."

    override val timerTooLong = "Posso impostare timer lunghi al massimo ventiquattro ore."
    override val timerNoApp = "Non ho trovato nessuna app che possa impostare il timer."
    override val timerDenied = "Non ho il permesso di impostare il timer."
    override val timerStopped = "Timer fermato."
    override val timersOpened =
        "Non sta suonando nessun timer, e un timer in corso si ferma solo dall'Orologio: ho aperto i timer, tocca quello da fermare."
    override val ringingIsAlarm = "Sta suonando una sveglia, non un timer. Per spegnerla dimmi «spegni la sveglia»."
    override val alarmStopped = "Sveglia spenta."
    override val nothingRinging =
        "Non sta suonando nessuna sveglia. Per saltarne una dimmi l'orario, per esempio «disattiva la sveglia delle 7»."
    override fun alarmSkipped(hour: Int, minute: Int) =
        "Ho chiesto all'Orologio di saltare la prossima sveglia ${alarmTimeWords(hour, minute)}. Lo fa solo se suona entro ventiquattro ore."
    override fun alarmSnoozed(minutes: Int?) =
        if (minutes == null) "Sveglia posticipata." else "Sveglia posticipata di $minutes ${if (minutes == 1) "minuto" else "minuti"}."
    override val askTimerDuration = "Per quanto tempo?"
    override val notUnderstood = "Non so ancora rispondere a questo."
    override fun media(command: dev.nanovoice.core.PhoneAction.MediaCommand) = when (command) {
        dev.nanovoice.core.PhoneAction.MediaCommand.Play -> "Faccio partire la musica."
        dev.nanovoice.core.PhoneAction.MediaCommand.Pause -> "Metto in pausa."
        dev.nanovoice.core.PhoneAction.MediaCommand.Toggle -> "Fatto."
        dev.nanovoice.core.PhoneAction.MediaCommand.Next -> "Brano successivo."
        dev.nanovoice.core.PhoneAction.MediaCommand.Previous -> "Brano precedente."
    }
    override fun volumeSet(percent: Int) = "Volume al $percent per cento."
    override val volumeMuted = "Audio disattivato."
    override fun flashlight(on: Boolean) = if (on) "Torcia accesa." else "Torcia spenta."
    override val noFlashlight = "Questo telefono non ha una torcia che posso usare."
    override fun navigating(destination: String) = "Apro le indicazioni per $destination."
    override val noMapsApp = "Non trovo un'app di mappe per le indicazioni."
    override val openingLink = "Apro il link."
    override val badLink = "Il link non è valido: apro solo indirizzi https."
    override val noBrowser = "Non trovo un browser per aprire il link."
    override fun smsDraft(name: String?) = if (name != null) "Ho preparato il messaggio per $name: controllalo e premi invia." else "Ho preparato il messaggio: scegli a chi mandarlo e premi invia."
    override val noMessagingApp = "Non trovo un'app per i messaggi."
    override fun calendarDraft(title: String) = "Ho preparato l'evento $title: controllalo e salvalo."
    override val badEventTime = "L'orario dell'evento non è valido."
    override val noCalendarApp = "Non trovo un'app di calendario."
    override fun battery(percent: Int, charging: Boolean) = "La batteria è al $percent per cento" + if (charging) ", in carica." else "."
    override val calendarPermissionNeeded = "Per leggere il calendario serve il permesso: attivalo nelle impostazioni di NanoVoice, in Permessi."
    override fun noEvents(days: Int) = if (days == 1) "Nessun impegno in calendario per oggi." else "Nessun impegno in calendario nei prossimi $days giorni."
    override val locationPermissionNeeded = "Per la posizione serve il permesso: attivalo nelle impostazioni di NanoVoice, in Permessi."
    override val locationUnavailable = "Non riesco a ottenere la posizione adesso."
    override val notificationAccessNeeded = "Per leggere le notifiche serve l'accesso alle notifiche: attivalo nelle impostazioni di NanoVoice, in Permessi."
    override val noNotifications = "Non ci sono notifiche."
    override fun playingMusic(query: String) = "Cerco $query su Tempo e la faccio partire."
    override fun playingSong(title: String, artist: String) = if (artist.isNotBlank()) "Metto $title di $artist su Tempo." else "Metto $title su Tempo."
    override fun playingAlbum(album: String) = "Metto l'album $album su Tempo."
    override fun playingArtist(artist: String) = "Metto un po' di $artist su Tempo."
    override fun musicNotFound(query: String) = "Non trovo $query nella tua libreria."
    override val musicNotConfigured = "Per la musica collega Navidrome nelle impostazioni di NanoVoice, in Musica."
    override val musicServerError = "Non riesco a raggiungere Navidrome: controlla indirizzo e password nelle impostazioni."
    override val noMusicApp = "Non riesco a far partire la musica su Tempo: controlla che sia installato."
    override val nothingToResume = "Non è partito niente: nessun lettore aveva musica da riprendere. Serve un brano, un album o un artista."
    override fun musicQueued(label: String) = "Ho passato $label a Tempo, ma non sento ancora la musica: se non parte, toccalo in Tempo."
    override val musicAppUntrusted = "Il Tempo installato non ha la firma di F-Droid che conosco, quindi non gli passo l'accesso a Navidrome."
    override fun signalDraft(name: String?) = if (name != null) "Ho aperto Signal con il messaggio pronto: scegli $name e premi invia." else "Ho aperto Signal con il messaggio pronto: scegli la chat e premi invia."
    override val noSignal = "Signal non è installato."
    override val noteWritten = "Ho scritto la nota in Notes."
    override val noNotesApp = "Nextcloud Notes non è installato."
    override val requestInterrupted = "Risposta interrotta."
    override val contextRestarted = "I messaggi qui sopra non li ricordo più."

    // Neither masculine nor feminine: the app does not know who is using it.
    private val freshStarts = listOf(
        "Pagina nuova.",
        "Si riparte da qui.",
        "Foglio bianco, idee fresche.",
        "Rieccoci.",
        "Nuovo giro, nuova corsa.",
        "Che bello risentirti.",
        "Ripartiamo con calma.",
    )

    override fun freshStart(hour: Int, random: kotlin.random.Random): String {
        val greeting = when (hour) {
            in 5..11 -> "Buongiorno, si riparte."
            in 12..17 -> "Buon pomeriggio, si riparte."
            in 18..22 -> "Buonasera, si riparte."
            else -> "Si fa tardi: ripartiamo da qui."
        }
        return (freshStarts + greeting).random(random)
    }
    override val unlockNeeded = "Per questo sblocca il telefono. Da bloccato posso dirti l'ora e la data, e impostare timer e sveglie."
    override val newConversationFailed = "Non sono riuscito ad avviare una nuova conversazione sul server: la chat è vuota, ma l'agente ricorda ancora la precedente. Riprova quando sei connesso."

    override val fixedPhrases get() = listOf(askTimerDuration, askAlarmTime, notUnderstood, timerTooLong, timerNoApp, timerDenied, alarmNoApp, alarmDenied, callingNumber, contactsPermissionNeeded, dialerNotFound)
}
