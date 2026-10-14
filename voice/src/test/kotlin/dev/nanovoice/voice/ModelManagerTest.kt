package dev.nanovoice.voice

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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

class ModelManagerTest {
    private val base = Files.createTempDirectory("vosk-test").toFile()

    @AfterTest fun cleanup() { base.deleteRecursively() }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manager(archive: ByteArray, declaredSha: String = sha(archive)) = ModelManager(
        baseDir = base,
        spec = ModelSpec("https://example.test/m.zip", declaredSha, archive.size.toLong(), "model-it"),
        open = { ByteArrayInputStream(archive) },
    )

    private val good = zip("model-it/am/final.mdl" to "weights", "model-it/conf/model.conf" to "conf")

    @Test fun installsAVerifiedArchive() = runBlocking {
        val m = manager(good)
        assertNull(m.modelDir())
        m.install()
        assertEquals(ModelState.Installed, m.state.value)
        val dir = assertNotNull(m.modelDir())
        assertEquals("weights", File(dir, "am/final.mdl").readText())
        assertFalse(File(base, "download.zip.part").exists())
        assertFalse(File(base, "unpack.tmp").exists())
    }

    @Test fun rejectsAChecksumMismatchAndInstallsNothing() = runBlocking {
        val m = manager(good, declaredSha = "0".repeat(64))
        m.install()
        assertTrue(m.state.value is ModelState.Failed)
        assertNull(m.modelDir())
        assertFalse(File(base, "model-it").exists())
    }

    @Test fun rejectsPathsThatEscapeTheDestination() = runBlocking {
        val evil = zip("model-it/am/final.mdl" to "x", "../escaped.txt" to "pwned")
        val m = manager(evil)
        m.install()
        assertTrue(m.state.value is ModelState.Failed)
        assertNull(m.modelDir())
        assertFalse(File(base.parentFile, "escaped.txt").exists())
    }

    @Test fun rejectsAnArchiveWithoutTheModelDirectory() = runBlocking {
        val m = manager(zip("other/file" to "x"))
        m.install()
        assertTrue(m.state.value is ModelState.Failed)
        assertNull(m.modelDir())
    }

    @Test fun rejectsADownloadLargerThanDeclared() = runBlocking {
        val m = ModelManager(
            base,
            ModelSpec("https://example.test/m.zip", sha(good), 10, "model-it"),
            open = { ByteArrayInputStream(good) },
        )
        m.install()
        assertTrue(m.state.value is ModelState.Failed)
    }

    @Test fun downloadErrorsBecomeAFailedState() = runBlocking {
        val m = ModelManager(base, ModelSpec("https://example.test/m.zip", "a".repeat(64), 100, "model-it"), open = { throw IOException("offline") })
        m.install()
        assertEquals(ModelState.Failed("offline"), m.state.value)
    }

    @Test fun deleteRemovesTheModel() = runBlocking {
        val m = manager(good)
        m.install()
        m.delete()
        assertNull(m.modelDir())
        assertEquals(ModelState.NotInstalled, m.state.value)
    }

    @Test fun anInterruptedInstallIsNotSeenAsInstalled() {
        File(base, "model-it").mkdirs() // directory without the ready marker
        assertNull(manager(good).modelDir())
    }

    @Test fun httpsIsRequired() {
        assertTrue(runCatching { openHttps("http://example.test/m.zip") }.isFailure)
    }

    @Test fun unpackingReportsMonotonicProgressEndingAtOne() {
        val big = "x".repeat(3_000_000)
        val archive = File(base, "a.zip").apply { writeBytes(zip("model-it/model.onnx" to big, "model-it/tokens.txt" to "a")) }
        val seen = mutableListOf<Float>()
        ArchiveUnpacker.unpack(archive, File(base, "out"), maxBytes = 10_000_000, onProgress = { seen += it })
        assertTrue(seen.isNotEmpty())
        assertEquals(1f, seen.last())
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.all { it in 0f..1f })
    }

    @Test fun installPassesThroughTheUnpackingState() = runBlocking<Unit> {
        val m = manager(good)
        val states = mutableListOf<ModelState>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { m.state.collect { states += it } }
        m.install()
        job.cancel()
        assertTrue(states.any { it is ModelState.Unpacking }, states.toString())
        assertEquals(ModelState.Installed, states.last())
    }
}
