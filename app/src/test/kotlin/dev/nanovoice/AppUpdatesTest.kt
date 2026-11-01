package dev.nanovoice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdatesTest {
    private fun release(tag: String, draft: Boolean = false, files: Boolean = true): String {
        val v = tag.removePrefix("v")
        val assets = if (!files) "[]" else """[
            {"name":"nanovoice-$v.apk","browser_download_url":"https://github.com/olac-acul/nanovoice/releases/download/$tag/nanovoice-$v.apk"},
            {"name":"nanovoice-$v.apk.sha256","browser_download_url":"https://github.com/olac-acul/nanovoice/releases/download/$tag/nanovoice-$v.apk.sha256"}
        ]"""
        return """{"tag_name":"$tag","draft":$draft,"prerelease":true,"html_url":"https://github.com/olac-acul/nanovoice/releases/tag/$tag",
            "published_at":"2026-11-01T10:00:00Z","assets":$assets}"""
    }

    @Test fun versionsAreComparedAsNumbers() {
        assertTrue(isNewer("0.3.10", "0.3.9"))
        assertTrue(isNewer("v0.4.0", "0.3.3-dev"))
        assertFalse(isNewer("0.3.3", "0.3.3-dev"))
        assertFalse(isNewer("0.3.2", "0.3.3"))
        assertFalse(isNewer("latest", "0.3.3"))
        assertEquals(listOf(1, 2, 3), versionParts("v1.2.3"))
        assertNull(versionParts("1.2"))
    }

    @Test fun theNewestReleaseWithItsFilesIsChosenAndDraftsAreNot() {
        val list = "[${release("v0.3.3")},${release("v0.3.10")},${release("v0.4.0", draft = true)},${release("v0.5.0", files = false)}]"
        val newest = newestRelease(list)!!
        assertEquals("0.3.10", newest.version)
        assertTrue(newest.apkUrl.endsWith("/nanovoice-0.3.10.apk"))
        assertTrue(newest.sumUrl.endsWith("/nanovoice-0.3.10.apk.sha256"))
        assertEquals("https://github.com/olac-acul/nanovoice/releases/tag/v0.3.10", newest.page)
        assertNull(newestRelease("[]"))
        assertNull(newestRelease("{not json"))
    }

    @Test fun theChecksumIsTheFirstWordOfItsFile() {
        val hash = "A".repeat(64)
        assertEquals("a".repeat(64), checksumOf("$hash  nanovoice-0.3.4.apk\n"))
        assertNull(checksumOf("not a hash  nanovoice-0.3.4.apk"))
    }

    @Test fun datesReadAsPartOfASentence() {
        val zone = java.time.ZoneId.of("Europe/Rome")
        val today = java.time.LocalDate.of(2026, 11, 1)
        fun at(day: Int) = java.time.ZonedDateTime.of(2026, 10, 1, 13, 21, 0, 0, zone).plusDays(day.toLong()).toInstant().toEpochMilli()
        assertEquals("oggi alle 13:21", whenSaid(at(31), today, zone))
        assertEquals("ieri", whenSaid(at(30), today, zone))
        assertEquals("il 28 ott", whenSaid(at(27), today, zone))
    }

    @Test fun thePinnedCertificateIsTheReleaseKey() {
        val pinned = File("../tools/release-cert.sha256").readText().replace(Regex("[\\s:]"), "").lowercase()
        assertEquals(pinned, AppUpdates.RELEASE_CERT_SHA256)
    }
}
