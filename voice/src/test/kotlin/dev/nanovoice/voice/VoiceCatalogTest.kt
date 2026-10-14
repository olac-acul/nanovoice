package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoiceCatalogTest {
    private val hash = Regex("[0-9a-f]{64}")

    @Test fun idsAreUnique() = assertEquals(VoiceCatalog.all.size, VoiceCatalog.all.map { it.id }.toSet().size)

    @Test fun everyVoiceIsPinnedAndDownloadedOverHttps() {
        for (v in VoiceCatalog.all) {
            assertTrue(v.onnxName.endsWith(".onnx"), v.id)
            for (f in listOf(v.onnx, v.config)) {
                assertTrue(f.url.startsWith("https://"), v.id)
                assertTrue(hash.matches(f.sha256), v.id)
            }
        }
    }

    @Test fun everyVoiceCarriesTheMetadataSherpaRequires() {
        for (v in VoiceCatalog.all) {
            for (key in listOf("model_type", "comment", "language", "voice", "has_espeak", "n_speakers", "sample_rate")) {
                assertTrue(key in v.metadata, "${v.id}: $key")
            }
            assertEquals("vits", v.metadata["model_type"])
        }
    }

    @Test fun defaultVoiceIsInTheCatalog() {
        assertNotNull(VoiceCatalog.byId(VoiceCatalog.default.id))
    }

    @Test fun unknownIdIsNull() = assertEquals(null, VoiceCatalog.byId("nope"))

    @Test fun sizeCountsModelAndConfig() = assertEquals(114_204_024L + 5_252L, VoiceCatalog.SERENA.sizeBytes)

    @Test fun espeakDataIsPinned() {
        assertTrue(hash.matches(VoiceCatalog.ESPEAK_DATA.sha256))
        assertEquals("espeak-ng-data", VoiceCatalog.ESPEAK_DATA.dirName)
    }

    @Test fun voskModelIsPinned() = assertTrue(hash.matches(VoskModels.ITALIAN_SMALL.sha256))
}
