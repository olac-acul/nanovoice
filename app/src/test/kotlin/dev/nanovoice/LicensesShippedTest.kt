package dev.nanovoice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The licence texts shipped in the APK (assets/licenses, shown under Impostazioni, Licenze) are copies of the
 * repository's NOTICE, LICENSE and docs/licenses: one changed without the other fails here.
 */
class LicensesShippedTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "NOTICE").isFile }
    private val shipped = File(repo, "app/src/main/assets/licenses")

    @Test fun theNoticeAndTheAppsOwnLicenceAreTheRepositorysOnes() {
        assertEquals(File(repo, "NOTICE").readText(), File(shipped, "NOTICE.txt").readText())
        assertEquals(File(repo, "LICENSE").readText(), File(shipped, "Apache-2.0.txt").readText())
    }

    @Test fun everyLicenceTextOfTheRepositoryIsShipped() {
        val docs = File(repo, "docs/licenses").listFiles()!!.filter { it.isFile }
        assertTrue(docs.isNotEmpty())
        for (doc in docs) assertEquals(doc.readText(), File(shipped, doc.name).readText(), doc.name)
        val expected = docs.map { it.name }.toSet() + setOf("NOTICE.txt", "Apache-2.0.txt")
        assertEquals(expected, shipped.list()!!.toSet())
    }
}
