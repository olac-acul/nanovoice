package dev.nanovoice.router

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayMusicMatcherTest {
    private val router = LocalRouter.forLocale(Locale.ITALIAN)!!
    private fun query(text: String) = (router.route(text)?.intent as? LocalIntent.PlayMusic)?.query

    @Test fun songsAndArtists() {
        assertEquals("bohemian rhapsody queen", query("Riproduci Bohemian Rhapsody dei Queen"))
        assertEquals("pink floyd", query("fammi sentire qualcosa dei Pink Floyd"))
        assertEquals("volare", query("metti la canzone Volare"))
        assertEquals("the dark side of the moon", query("mettimi l'album the dark side of the moon su tempo"))
        assertEquals("vasco rossi", query("ehi suona un po' di Vasco Rossi per favore"))
    }

    @Test fun otherUsesOfTheVerbsAreNotMusic() {
        assertNull(query("metti la sveglia alle sette"))
        assertNull(query("metti un timer di cinque minuti"))
        assertNull(query("riproduci il video di ieri"))
        assertNull(query("riproduci"))
        assertNull(query("metti il cappotto"))
    }

    private fun note(text: String) = (router.route(text)?.intent as? LocalIntent.Note)?.text

    @Test fun notesKeepTheOriginalWords() {
        assertEquals("Domani passa il tecnico della caldaia", note("prendi nota che domani passa il tecnico della caldaia"))
        assertEquals("Comprare il latte, il pane e 2 uova", note("Scrivi una nota: comprare il latte, il pane e 2 uova."))
        assertEquals("Codice del garage 4512", note("ehi, annota codice del garage 4512"))
        assertEquals("Chiamare Paolo", note("aggiungi una nota su Notes: chiamare Paolo"))
    }

    @Test fun notNotes() {
        assertNull(note("che note ha la canzone"))
        assertNull(note("prendi nota"))
    }
}
