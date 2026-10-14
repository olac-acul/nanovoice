package dev.nanovoice.router

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppAndCallMatchersTest {
    private val router = LocalRouter.forLocale(Locale.ITALIAN)!!
    private fun intent(s: String) = router.route(s)?.intent

    // --- open app: positive
    @Test fun openSimple() = assertEquals(LocalIntent.OpenApp("signal"), intent("apri Signal"))
    @Test fun openWithArticle() = assertEquals(LocalIntent.OpenApp("fotocamera"), intent("apri la fotocamera"))
    @Test fun openWithApp() = assertEquals(LocalIntent.OpenApp("calcolatrice"), intent("apri l'app calcolatrice"))
    @Test fun openAppDi() = assertEquals(LocalIntent.OpenApp("mappe"), intent("apri l'applicazione di mappe"))
    @Test fun openAvvia() = assertEquals(LocalIntent.OpenApp("calendario"), intent("avvia il calendario"))
    @Test fun openLancia() = assertEquals(LocalIntent.OpenApp("orologio"), intent("lancia orologio"))
    @Test fun openPolite() = assertEquals(LocalIntent.OpenApp("signal"), intent("puoi aprire Signal per favore"))
    @Test fun openTwoWords() = assertEquals(LocalIntent.OpenApp("delta chat"), intent("apri Delta Chat"))

    // --- open app: negative
    @Test fun openDoor() = assertNull(intent("apri la porta del garage"))
    @Test fun openFile() = assertNull(intent("apri il file delle spese"))
    @Test fun openLink() = assertNull(intent("apri il link che ti ho mandato"))
    @Test fun openNothing() = assertNull(intent("apri"))
    @Test fun openLongSentence() = assertNull(intent("apri quella cosa che abbiamo visto ieri sera in tv"))
    @Test fun appearsInsideOtherSentence() = assertNull(intent("ieri ho aperto Signal ma non funzionava"))

    // --- call: positive
    @Test fun callSimple() = assertEquals(LocalIntent.Call("mario rossi"), intent("chiama Mario Rossi"))
    @Test fun callTelefonaA() = assertEquals(LocalIntent.Call("mario"), intent("telefona a Mario"))
    @Test fun callChiamata() = assertEquals(LocalIntent.Call("mario"), intent("fai una chiamata a Mario"))
    @Test fun callPolite() = assertEquals(LocalIntent.Call("mario"), intent("puoi chiamare Mario per favore"))
    @Test fun callNumber() = assertEquals(LocalIntent.Call("112"), intent("chiama il 112"))
    @Test fun callNumberWithSpaces() = assertEquals(LocalIntent.Call("3331234567"), intent("chiama il 333 1234567"))

    // --- call: negative
    @Test fun callMe() = assertNull(intent("chiamami domani"))
    @Test fun callNobody() = assertNull(intent("chiama"))
    @Test fun callHowTo() = assertNull(intent("come si chiama il presidente"))
    @Test fun callSomeone() = assertNull(intent("chiama qualcuno"))
    @Test fun callPastTense() = assertNull(intent("ieri ho chiamato Mario"))
}
