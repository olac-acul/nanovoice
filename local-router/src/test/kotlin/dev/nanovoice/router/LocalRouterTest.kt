package dev.nanovoice.router

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LocalRouterTest {
    private val router = LocalRouter.forLocale(Locale.ITALIAN)!!
    private fun intent(s: String) = router.route(s)?.intent

    // --- tell time: positive
    @Test fun timeCheOreSono() = assertEquals(LocalIntent.TellTime, intent("Che ore sono?"))
    @Test fun timeCheOraE() = assertEquals(LocalIntent.TellTime, intent("che ora è"))
    @Test fun timeDimmiLOra() = assertEquals(LocalIntent.TellTime, intent("dimmi l'ora"))
    @Test fun timeMiDiciLOra() = assertEquals(LocalIntent.TellTime, intent("mi dici l'ora?"))
    @Test fun timePolite() = assertEquals(LocalIntent.TellTime, intent("Scusa, che ore sono adesso per favore?"))
    @Test fun timeSai() = assertEquals(LocalIntent.TellTime, intent("sai che ore sono?"))
    @Test fun timePuoiDirmi() = assertEquals(LocalIntent.TellTime, intent("puoi dirmi che ora è"))

    @Test fun timeMiDiciCheOreSono() = assertEquals(LocalIntent.TellTime, intent("mi dici che ore sono?"))

    // --- tell time: negative
    @Test fun timePast() = assertNull(intent("che ore erano ieri?"))
    @Test fun timeOtherCity() = assertNull(intent("che ore sono a Tokyo?"))
    @Test fun timeMeeting() = assertNull(intent("a che ora è la riunione domani"))
    @Test fun timeUnrelated() = assertNull(intent("che tempo fa oggi"))
    @Test fun timeOraAlone() = assertNull(intent("ora"))

    // --- tell date: positive
    @Test fun dateCheGiornoE() = assertEquals(LocalIntent.TellDate, intent("Che giorno è oggi?"))
    @Test fun dateCheGiornoESenzaOggi() = assertEquals(LocalIntent.TellDate, intent("che giorno è"))
    @Test fun dateCheData() = assertEquals(LocalIntent.TellDate, intent("che data è oggi"))
    @Test fun dateDimmiLaData() = assertEquals(LocalIntent.TellDate, intent("dimmi la data di oggi"))
    @Test fun dateQuantiNeAbbiamo() = assertEquals(LocalIntent.TellDate, intent("quanti ne abbiamo oggi?"))
    @Test fun dateInCheGiornoSiamo() = assertEquals(LocalIntent.TellDate, intent("in che giorno siamo"))
    @Test fun datePolite() = assertEquals(LocalIntent.TellDate, intent("scusa, mi dici che giorno è oggi per favore?"))

    // --- tell date: negative
    @Test fun dateTomorrow() = assertNull(intent("che giorno è domani?"))
    @Test fun dateBirthday() = assertNull(intent("che giorno è il mio compleanno"))
    @Test fun dateMeeting() = assertNull(intent("che data ha la riunione"))
    @Test fun dateHoliday() = assertNull(intent("che giorno è Pasqua quest'anno"))

    // --- timer: positive
    private fun timer(s: String) = (intent(s) as? LocalIntent.SetTimer)?.duration.also { assertNotNull(intent(s)) }
    @Test fun timerBasic() = assertEquals(10.minutes, timer("metti un timer di 10 minuti"))
    @Test fun timerImposta() = assertEquals(10.minutes, timer("imposta un timer da dieci minuti"))
    @Test fun timerBare() = assertEquals(30.seconds, timer("timer 30 secondi"))
    @Test fun timerHourTwenty() = assertEquals(80.minutes, timer("un timer di un'ora e venti"))
    @Test fun timerHalfHour() = assertEquals(30.minutes, timer("metti un timer di mezz'ora"))
    @Test fun timerPolite() = assertEquals(5.minutes, timer("puoi mettere un timer di cinque minuti per favore"))
    @Test fun timerMettimi() = assertEquals(2.minutes, timer("mettimi un timer di due minuti"))
    @Test fun timerAvvia() = assertEquals(1.hours, timer("avvia un timer di un'ora"))
    @Test fun timerNoDuration() {
        assertEquals(LocalIntent.SetTimer(null), intent("metti un timer"))
        assertEquals(LocalIntent.SetTimer(null), intent("imposta il timer"))
    }

    // --- timer: negative
    @Test fun timerHowLong() = assertNull(intent("quanto dura un timer?"))
    @Test fun timerWhatIs() = assertNull(intent("cos'è un timer"))
    @Test fun timerStop() = assertEquals(LocalIntent.StopTimer, intent("ferma il timer")) // ClockControlMatcherTest has the rest
    @Test fun timerCancel() = assertNull(intent("annulla il timer di dieci minuti"))
    @Test fun timerPause() = assertNull(intent("metti in pausa il timer"))
    @Test fun timerTwo() = assertNull(intent("metti un timer di dieci minuti e un timer di cinque"))
    @Test fun timerNoUnit() = assertNull(intent("metti un timer di dieci"))
    @Test fun timerTrailingTalk() = assertNull(intent("metti un timer di dieci minuti poi dimmi che tempo fa"))

    // --- alarm: positive
    private fun alarm(text: String) = intent(text) as? LocalIntent.SetAlarm
    @Test fun alarmSvegliami() = assertEquals(LocalIntent.SetAlarm(ClockTime(7, 30)), alarm("svegliami alle sette e mezza"))
    @Test fun alarmMettiUnaSveglia() = assertEquals(LocalIntent.SetAlarm(ClockTime(7, 0)), alarm("metti una sveglia alle 7"))
    @Test fun alarmImpostaPerLe() = assertEquals(LocalIntent.SetAlarm(ClockTime(7, 45)), alarm("imposta la sveglia per le otto meno un quarto"))
    @Test fun alarmColonTime() = assertEquals(LocalIntent.SetAlarm(ClockTime(6, 45)), alarm("sveglia alle 6:45"))
    @Test fun alarmTomorrow() = assertEquals(LocalIntent.SetAlarm(ClockTime(6, 0), tomorrow = true), alarm("svegliami domani alle sei"))
    @Test fun alarmTomorrowAfterTheTime() = assertEquals(LocalIntent.SetAlarm(ClockTime(6, 0), tomorrow = true), alarm("svegliami alle sei domani"))
    @Test fun alarmEvening() = assertEquals(LocalIntent.SetAlarm(ClockTime(19, 0)), alarm("svegliami alle sette di sera"))
    @Test fun alarmNoon() = assertEquals(LocalIntent.SetAlarm(ClockTime(12, 0)), alarm("metti la sveglia a mezzogiorno"))
    @Test fun alarmPolite() = assertEquals(LocalIntent.SetAlarm(ClockTime(7, 0)), alarm("puoi mettere una sveglia alle sette per favore"))
    @Test fun alarmWithoutTimeAsks() {
        assertEquals(LocalIntent.SetAlarm(null), alarm("metti una sveglia"))
        assertEquals(LocalIntent.SetAlarm(null), alarm("svegliami"))
    }
    @Test fun alarmWithoutTimeHasLowerConfidence() = assertEquals(0.9f, router.route("metti una sveglia")?.confidence)

    // --- alarm: negative
    @Test fun alarmCancel() = assertEquals(LocalIntent.StopAlarm(null), intent("cancella la sveglia"))
    @Test fun alarmDisable() = assertEquals(LocalIntent.StopAlarm(ClockTime(7, 0)), intent("disattiva la sveglia delle sette"))
    @Test fun alarmWhenDoesItRing() = assertNull(intent("a che ora suona la sveglia"))
    @Test fun alarmPrice() = assertNull(intent("quanto costa una sveglia"))
    @Test fun alarmDayAfterTomorrow() = assertNull(intent("svegliami dopodomani alle sette"))
    @Test fun alarmWeekday() = assertNull(intent("svegliami lunedì alle sette"))
    @Test fun alarmRepeating() = assertNull(intent("metti una sveglia alle sette tutti i giorni"))
    @Test fun alarmPastTense() = assertNull(intent("ho messo la sveglia alle sette"))
    @Test fun alarmTrailingTalk() = assertNull(intent("svegliami alle sette e poi dimmi che tempo fa"))

    // --- clock replies
    @Test fun clockReplyPlain() = assertEquals(ClockTime(7, 30), router.parseClockReply("alle sette e mezza"))
    @Test fun clockReplyDigits() = assertEquals(ClockTime(7, 30), router.parseClockReply("le 7:30"))
    @Test fun clockReplyEvening() = assertEquals(ClockTime(19, 0), router.parseClockReply("sette di sera"))
    @Test fun clockReplyNotATime() = assertNull(router.parseClockReply("lascia stare"))

    // --- general
    @Test fun unknownGoesToAgent() = assertNull(intent("riassumimi le email di oggi"))
    @Test fun unsupportedLocale() = assertNull(LocalRouter.forLocale(Locale.JAPANESE))
    @Test fun highThresholdFiltersLowConfidence() {
        val strict = LocalRouter.forLocale(Locale.ITALIAN, confidenceThreshold = 0.95f)!!
        assertNull(strict.route("metti un timer"))
        assertNotNull(strict.route("metti un timer di dieci minuti"))
    }

    // --- duration replies
    @Test fun replyPlain() = assertEquals(10.minutes, router.parseDurationReply("dieci minuti"))
    @Test fun replyWithPreposition() = assertEquals(5.minutes, router.parseDurationReply("per cinque minuti"))
    @Test fun replyNotDuration() = assertNull(router.parseDurationReply("lascia stare"))
    @Test fun replyZero() = assertNull(router.parseDurationReply("0 minuti"))
}
