package dev.nanovoice

import dev.nanovoice.ui.Block
import dev.nanovoice.ui.Inline
import dev.nanovoice.ui.Inline.Bold
import dev.nanovoice.ui.Inline.CodeSpan
import dev.nanovoice.ui.Inline.Italic
import dev.nanovoice.ui.Inline.LinkTo
import dev.nanovoice.ui.Inline.Plain
import dev.nanovoice.ui.Item
import dev.nanovoice.ui.parseBlocks
import dev.nanovoice.ui.plainText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    private fun inlines(text: String): List<Inline> = (parseBlocks(text).single() as Block.Para).inlines

    @Test fun boldItalicCodeAndLinks() {
        assertEquals(
            listOf(
                Plain("Il "), Bold(listOf(Plain("treno"))), Plain(" parte "), Italic(listOf(Plain("presto"))),
                Plain(", vedi "), LinkTo("https://transit.example.org", listOf(Plain("orari"))), Plain(" e "), CodeSpan("ncl"),
            ),
            inlines("Il **treno** parte *presto*, vedi [orari](https://transit.example.org) e `ncl`"),
        )
    }

    @Test fun linksOnlyToWebOrMailAndTheRealHostShows() {
        assertEquals(listOf(Plain("apri")), inlines("[apri](file:///sdcard/x)"))
        assertEquals(listOf(Plain("clic")), inlines("[clic](javascript:alert(1))"))
        assertEquals(listOf(LinkTo("https://example.org/a", listOf(Plain("sito")))), inlines("[sito](https://example.org/a)"))
        assertEquals(
            listOf(LinkTo("https://evil.example/login", listOf(Plain("banca.it"), Plain(" (evil.example)")))),
            inlines("[banca.it](https://evil.example/login)"),
        )
        assertEquals(listOf(LinkTo("https://example.org/", listOf(Plain("www.example.org")))), inlines("[www.example.org](https://example.org/)"))
        assertEquals(listOf(LinkTo("mailto:a@example.com", listOf(Plain("scrivimi")))), inlines("[scrivimi](mailto:a@example.com)"))
    }

    @Test fun aPictureIsItsDescriptionAndRawHtmlStaysText() {
        assertEquals(listOf(LinkTo("https://x.org/a.png", listOf(Plain("[gatto]")))), inlines("![gatto](https://x.org/a.png)"))
        assertEquals(listOf(Plain("[gatto]")), (inlines("![gatto](file:///a.png)").single() as LinkTo).children)
        assertEquals(listOf(Plain("a "), Plain("<b>").let { it }, Plain("x"), Plain("</b>")).joinToString("") { (it as Plain).text },
            inlines("a <b>x</b>").joinToString("") { (it as Plain).text })
    }

    @Test fun manyUnclosedMarkersParseInLinearTime() {
        for (text in listOf("[".repeat(20_000), "*a ".repeat(6_000), "`" + "x".repeat(20_000), "[a](".repeat(5_000))) {
            val started = System.nanoTime()
            parseBlocks(text)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 1_500, "took $ms ms")
        }
    }

    @Test fun loneMarkersStayAsWritten() {
        assertEquals(listOf(Plain("2 * 3 = 6 e un ** solo")), inlines("2 * 3 = 6 e un ** solo"))
        assertEquals(listOf(Plain("la variabile snake_case_name")), inlines("la variabile snake_case_name"))
        assertEquals(listOf(Plain("[non un link] (davvero)")), inlines("[non un link] (davvero)"))
    }

    @Test fun underscoresAroundWordsAreItalicAndTildesStrike() {
        assertEquals(listOf(Plain("una "), Italic(listOf(Plain("parola"))), Plain(" sola")), inlines("una _parola_ sola"))
        assertEquals(listOf(Plain("non "), Inline.Strike(listOf(Plain("domani"))), Plain(" oggi")), inlines("non ~~domani~~ oggi"))
    }

    @Test fun blocks() {
        val md = """
            ## Domani
            Sole al mattino,
            nuvole dopo.

            - pranzo alle 13
              - con Marco

            3. primo
            4. secondo

            > nota bene

            ```kotlin
            ncl groups list
            ```

            ---
        """.trimIndent()
        assertEquals(
            listOf(
                Block.Title(2, listOf(Plain("Domani"))),
                Block.Para(listOf(Plain("Sole al mattino, nuvole dopo."))),
                Block.Items(
                    null,
                    listOf(Item(null, listOf(Block.Para(listOf(Plain("pranzo alle 13"))), Block.Items(null, listOf(Item(null, listOf(Block.Para(listOf(Plain("con Marco")))))))))),
                ),
                Block.Items(3, listOf(Item(null, listOf(Block.Para(listOf(Plain("primo"))))), Item(null, listOf(Block.Para(listOf(Plain("secondo"))))))),
                Block.Quote(listOf(Block.Para(listOf(Plain("nota bene"))))),
                Block.CodeBlock("kotlin", "ncl groups list"),
                Block.Rule,
            ),
            parseBlocks(md),
        )
    }

    @Test fun taskListsAndTables() {
        assertEquals(
            listOf(Block.Items(null, listOf(Item(true, listOf(Block.Para(listOf(Plain("pane"))))), Item(false, listOf(Block.Para(listOf(Plain("latte")))))))),
            parseBlocks("- [x] pane\n- [ ] latte"),
        )
        assertEquals(
            listOf(Block.Grid(listOf(listOf(Plain("Ora")), listOf(Plain("Cosa"))), listOf(listOf(listOf(Plain("10:00")), listOf(Bold(listOf(Plain("Dentista")))))))),
            parseBlocks("| Ora | Cosa |\n|---|---|\n| 10:00 | **Dentista** |"),
        )
    }

    @Test fun plainTextIsOneParagraphAndAnUnclosedFenceTakesTheRest() {
        assertEquals(listOf(Block.Para(listOf(Plain("Sono le 12:49.")))), parseBlocks("Sono le 12:49."))
        assertEquals(listOf(Block.Para(listOf(Plain("Ecco:"))), Block.CodeBlock("", "a\nb")), parseBlocks("Ecco:\n```\na\nb"))
    }

    @Test fun copiedTextHasNoSymbolsButKeepsTheAddresses() {
        assertEquals(
            "Domani\nIl treno parte presto, vedi orari (https://transit.example.org).\n• pranzo\n• cena\n> nota",
            plainText("## Domani\nIl **treno** parte *presto*, vedi [orari](https://transit.example.org).\n\n- pranzo\n- cena\n\n> nota"),
        )
        assertEquals("vai su https://x.org", plainText("vai su [https://x.org](https://x.org)"))
    }

    @Test fun theVoiceSkipsTheSymbols() {
        assertEquals("Domani", withoutMarkdown("## Domani"))
        assertEquals("pranzo alle 13", withoutMarkdown("- pranzo alle 13"))
        assertEquals("", withoutMarkdown("1."))
        assertEquals("Il treno parte presto.", withoutMarkdown("Il **treno** parte *presto*."))
        assertEquals("usa ncl groups list", withoutMarkdown("usa `ncl groups list`"))
        assertEquals("la variabile snake_case_name", withoutMarkdown("la variabile snake_case_name"))
        assertEquals("una parola sola", withoutMarkdown("una _parola_ sola"))
    }
}
