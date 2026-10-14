package dev.nanovoice.voice

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawPiperInstallerTest {
    private val base = Files.createTempDirectory("raw-piper").toFile()

    @AfterTest fun cleanup() { base.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val model = ByteArray(5000) { (it % 251).toByte() }
    private val config = """{"phoneme_id_map":{"_":[0],"^":[1],"a":[2],"aɪ":[3]}}""".toByteArray()
    private val espeakZip: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("espeak-ng-data/it_dict")); z.write("dict".toByteArray()); z.closeEntry()
        }
    }.toByteArray()

    private val espeakSpec = ModelSpec("https://example.test/espeak.zip", sha(espeakZip), espeakZip.size.toLong(), "espeak-ng-data")
    private val voice = VoiceSpec(
        id = "v", displayName = "V", quality = "q", licenseNote = "l",
        onnx = RemoteFile("https://example.test/v.onnx", sha(model), model.size.toLong()),
        config = RemoteFile("https://example.test/v.onnx.json", sha(config), config.size.toLong()),
        metadata = mapOf("model_type" to "vits", "voice" to "it"),
        dirName = "piper-v", onnxName = "v.onnx",
    )
    private val downloads = mutableListOf<String>()

    private fun open(url: String): java.io.InputStream {
        downloads += url
        return ByteArrayInputStream(
            when (url) {
                voice.onnx.url -> model
                voice.config.url -> config
                espeakSpec.url -> espeakZip
                else -> throw IOException("unexpected url $url")
            },
        )
    }

    private fun installer(spec: VoiceSpec = voice, espeak: ModelManager = ModelManager(File(base, "_espeak"), espeakSpec, ::open)) =
        RawPiperInstaller(File(base, "voice"), spec, espeak, ::open) to espeak

    @Test fun installsModelWithMetadataTokensAndSharedEspeakData() = runBlocking<Unit> {
        val (i, espeak) = installer()
        i.install()
        assertEquals(ModelState.Installed, i.state.value)
        val dir = assertNotNull(i.modelDir())
        val installed = File(dir, "v.onnx").readBytes()
        assertTrue(installed.copyOf(model.size).contentEquals(model), "original bytes are kept")
        assertTrue(installed.size > model.size, "metadata appended")
        assertEquals("_ 0\n^ 1\na 2\n", File(dir, "tokens.txt").readText())
        assertFalse(File(dir, "config.json").exists())
        assertNotNull(espeak.modelDir())
    }

    @Test fun espeakDataIsDownloadedOnlyOnce() = runBlocking<Unit> {
        val espeak = ModelManager(File(base, "_espeak"), espeakSpec, ::open)
        val (i, _) = installer(espeak = espeak)
        i.install()
        i.delete()
        i.install()
        assertEquals(1, downloads.count { it == espeakSpec.url })
    }

    @Test fun aWrongModelHashInstallsNothing() = runBlocking<Unit> {
        val bad = voice.copy(onnx = voice.onnx.copy(sha256 = "0".repeat(64)))
        val (i, _) = installer(bad)
        i.install()
        assertTrue(i.state.value is ModelState.Failed)
        assertNull(i.modelDir())
        assertFalse(File(base, "voice/piper-v").exists())
        assertFalse(File(base, "voice/work.tmp").exists())
    }

    @Test fun aBrokenConfigInstallsNothing() = runBlocking<Unit> {
        val broken = "{}".toByteArray()
        val spec = voice.copy(config = RemoteFile(voice.config.url, sha(broken), broken.size.toLong()))
        val i = RawPiperInstaller(File(base, "voice"), spec, ModelManager(File(base, "_espeak"), espeakSpec, ::open),
            open = { url -> if (url == spec.config.url) ByteArrayInputStream(broken) else open(url) })
        i.install()
        assertTrue(i.state.value is ModelState.Failed)
        assertNull(i.modelDir())
    }

    @Test fun missingEspeakDataFailsClearly() = runBlocking<Unit> {
        val failingEspeak = ModelManager(File(base, "_espeak"), espeakSpec, open = { throw IOException("offline") })
        val (i, _) = installer(espeak = failingEspeak)
        i.install()
        val state = i.state.value
        assertTrue(state is ModelState.Failed && state.message.contains("espeak"), state.toString())
    }

    @Test fun deleteRemovesTheVoiceButKeepsEspeak() = runBlocking<Unit> {
        val (i, espeak) = installer()
        i.install()
        i.delete()
        assertNull(i.modelDir())
        assertEquals(ModelState.NotInstalled, i.state.value)
        assertNotNull(espeak.modelDir())
    }
}
