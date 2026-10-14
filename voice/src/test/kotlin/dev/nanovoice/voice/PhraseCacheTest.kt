package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhraseCacheTest {
    private fun audio(n: Int = 4) = PhraseCache.Audio(FloatArray(n), 22050)

    @Test fun returnsWhatWasStoredForTheSameVoiceAndText() {
        val c = PhraseCache()
        val a = audio()
        c.put("v", "ciao", a)
        assertSame(a, c.get("v", "ciao"))
        assertNull(c.get("v", "altro"))
        assertNull(c.get("w", "ciao"))
    }

    @Test fun evictsTheLeastRecentlyUsedEntry() {
        val c = PhraseCache(maxEntries = 2)
        c.put("v", "a", audio()); c.put("v", "b", audio())
        c.get("v", "a") // a is now the most recent
        c.put("v", "c", audio())
        assertTrue(c.get("v", "a") != null)
        assertNull(c.get("v", "b"))
        assertTrue(c.get("v", "c") != null)
    }

    @Test fun pinnedEntriesSurviveEviction() {
        val c = PhraseCache(maxEntries = 2)
        c.pin("v", "fissa", audio())
        for (i in 0 until 10) c.put("v", "x$i", audio())
        assertTrue(c.get("v", "fissa") != null)
        assertTrue(c.isPinned("v", "fissa"))
    }

    @Test fun pinningMovesAnEntryOutOfTheRecentCache() {
        val c = PhraseCache(maxEntries = 1)
        c.put("v", "a", audio())
        c.pin("v", "a", audio())
        c.put("v", "b", audio())
        assertTrue(c.get("v", "a") != null)
    }

    @Test fun puttingAPinnedTextDoesNotReplaceIt() {
        val c = PhraseCache()
        val first = audio()
        c.pin("v", "a", first)
        c.put("v", "a", audio(8))
        assertSame(first, c.get("v", "a"))
    }

    @Test fun clearingAVoiceLeavesTheOthers() {
        val c = PhraseCache()
        c.put("v", "a", audio()); c.pin("v", "b", audio()); c.put("w", "a", audio())
        c.clearVoice("v")
        assertNull(c.get("v", "a")); assertNull(c.get("v", "b"))
        assertTrue(c.get("w", "a") != null)
        assertFalse(c.isPinned("v", "b"))
    }

    @Test fun differentVoicesDoNotCollideOnTheSameText() {
        val c = PhraseCache()
        val a = audio(1); val b = audio(2)
        c.put("v", "t", a); c.put("w", "t", b)
        assertEquals(1, c.get("v", "t")!!.samples.size)
        assertEquals(2, c.get("w", "t")!!.samples.size)
    }
}
