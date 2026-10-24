package dev.nanovoice.router.it

import dev.nanovoice.router.TextNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItalianNumberParserTest {
    private fun tokens(s: String) = TextNormalizer.normalize(s).split(" ").filter { it.isNotEmpty() }
    /** The number [s] says, only if it is all a number. */
    private fun whole(s: String): Int? {
        val t = tokens(s)
        return ItalianNumberParser.parse(t, 0)?.takeIf { it.endIndex == t.size }?.value
    }

    @Test fun digits() = assertEquals(10, whole("10"))
    @Test fun units() { assertEquals(1, whole("un")); assertEquals(1, whole("una")); assertEquals(9, whole("nove")) }
    @Test fun zero() = assertEquals(0, whole("zero"))
    @Test fun teens() { assertEquals(10, whole("dieci")); assertEquals(16, whole("sedici")); assertEquals(19, whole("diciannove")) }
    @Test fun tens() = assertEquals(20, whole("venti"))
    @Test fun accentedCompound() = assertEquals(23, whole("ventitré"))
    @Test fun elidedCompound() { assertEquals(21, whole("ventuno")); assertEquals(38, whole("trentotto")) }
    @Test fun hundreds() { assertEquals(100, whole("cento")); assertEquals(300, whole("trecento")) }
    @Test fun gluedBig() = assertEquals(123, whole("centoventitré"))
    @Test fun thousands() { assertEquals(1000, whole("mille")); assertEquals(2000, whole("duemila")); assertEquals(1500, whole("millecinquecento")) }
    @Test fun splitByRecognizer() { assertEquals(23, whole("venti tre")); assertEquals(3000, whole("tre mila")) }
    @Test fun notNumbers() { assertNull(whole("treno")); assertNull(whole("minuti")); assertNull(whole("ciao")) }
    @Test fun invalidCombinations() { assertNull(whole("tre venti")); assertNull(whole("due tre")) }

    @Test fun stopsAtNonNumber() {
        val r = ItalianNumberParser.parse(tokens("dieci minuti"), 0)
        assertEquals(10, r?.value); assertEquals(1, r?.endIndex)
    }
    @Test fun stopsBeforeInvalidContinuation() {
        val r = ItalianNumberParser.parse(tokens("due tre"), 0)
        assertEquals(2, r?.value); assertEquals(1, r?.endIndex)
    }
    @Test fun startOffset() {
        val r = ItalianNumberParser.parse(tokens("timer di venti secondi"), 2)
        assertEquals(20, r?.value); assertEquals(3, r?.endIndex)
    }
}
