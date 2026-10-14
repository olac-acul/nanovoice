package dev.nanovoice.voice

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceLibraryTest {
    private val base = Files.createTempDirectory("voices-test").toFile()

    @AfterTest fun cleanup() { base.deleteRecursively() }

    private fun voice(id: String) = VoiceSpec(
        id = id, displayName = id, quality = "q", licenseNote = "l",
        onnx = RemoteFile("https://example.test/$id.onnx", "a".repeat(64), 10),
        config = RemoteFile("https://example.test/$id.json", "b".repeat(64), 1),
        metadata = emptyMap(), dirName = "piper-$id", onnxName = "$id.onnx",
    )

    private val a = voice("a")
    private val b = voice("b")
    private val library = VoiceLibrary(base, listOf(a, b))

    /** Lays out an installed voice the way the installer leaves it. */
    private fun install(spec: VoiceSpec) {
        File(base, "${spec.id}/${spec.dirName}").apply { mkdirs() }.also { File(it, ".nanovoice-ready").writeText("ok") }
    }

    @Test fun nothingInstalledByDefault() {
        assertEquals(emptyList(), library.installedIds())
        assertNull(library.choose("a"))
        assertEquals(ModelState.NotInstalled, library.state("a"))
    }

    @Test fun choosesThePreferredVoiceWhenInstalled() {
        install(a); install(b)
        assertEquals(b, library.choose("b"))
    }

    @Test fun fallsBackToAnotherInstalledVoice() {
        install(b)
        assertEquals(b, library.choose("a"))
        assertEquals(b, library.choose(null))
    }

    @Test fun deletingTheActiveVoiceFallsBack() {
        install(a); install(b)
        library.delete("a")
        assertEquals(listOf("b"), library.installedIds())
        assertEquals(b, library.choose("a"))
    }

    @Test fun phonemeDataIsSharedAndOnlyAvailableOnceInstalled() {
        assertNull(library.dataDir())
        File(base, "_espeak/espeak-ng-data").apply { mkdirs() }.also { File(it, ".nanovoice-ready").writeText("ok") }
        assertEquals(File(base, "_espeak/espeak-ng-data"), library.dataDir())
    }

    @Test fun unknownVoicesAreIgnored() {
        assertNull(library.spec("nope"))
        assertNull(library.modelDir("nope"))
        assertNull(library.choose("nope"))
    }

    @Test fun removeUnknownVoicesDeletesDroppedVoicesButKeepsTheRest() {
        install(a)
        File(base, "paola-medium/old").mkdirs()
        File(base, "_espeak/espeak-ng-data").mkdirs()
        library.removeUnknownVoices()
        assertFalse(File(base, "paola-medium").exists())
        assertTrue(File(base, "a").exists())
        assertTrue(File(base, "_espeak").exists())
    }
}
