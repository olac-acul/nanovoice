package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoskJsonTest {
    @Test fun readsFinalText() = assertEquals("che ore sono", VoskJson.text("""{ "text" : "che ore sono" }"""))
    @Test fun readsPartial() = assertEquals("che ore", VoskJson.partial("""{"partial" : "che ore"}"""))
    @Test fun missingFieldIsEmpty() = assertEquals("", VoskJson.text("""{"partial":"x"}"""))
    @Test fun garbageIsEmpty() { assertEquals("", VoskJson.text("not json")); assertEquals("", VoskJson.partial("[1,2]")) }
    @Test fun joinSkipsBlanks() = assertEquals("uno due tre", VoskJson.join("uno", "", " due ", "tre"))
    @Test fun unicodeText() = assertEquals("è già tardi", VoskJson.text("""{"text":"è già tardi"}"""))
}
