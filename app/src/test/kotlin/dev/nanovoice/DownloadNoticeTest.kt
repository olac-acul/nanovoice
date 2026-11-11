package dev.nanovoice

import dev.nanovoice.voice.ModelState
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadNoticeTest {
    @Test fun `the notice follows the first install's phase and percent`() {
        val names = listOf("Trascrizione accurata (Parakeet)", "Voce Serena")
        assertEquals(DownloadNotice(names, unpacking = false, percent = 42), downloadNotice(names, ModelState.Downloading(0.425f)))
        assertEquals(DownloadNotice(names, unpacking = true, percent = 100), downloadNotice(names, ModelState.Unpacking(1f)))
        // Not started yet, or a progress that is not a fraction: the bar shows activity without a number.
        assertEquals(DownloadNotice(names, unpacking = false, percent = null), downloadNotice(names, ModelState.NotInstalled))
        assertEquals(DownloadNotice(names, unpacking = false, percent = null), downloadNotice(names, ModelState.Downloading(-1f)))
        assertEquals(DownloadNotice(names, unpacking = false, percent = null), downloadNotice(names, null))
    }
}
