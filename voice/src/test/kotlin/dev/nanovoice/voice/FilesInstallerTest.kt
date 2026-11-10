package dev.nanovoice.voice

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesInstallerTest {
    private val base = Files.createTempDirectory("files-installer").toFile()

    @AfterTest fun cleanup() { base.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val content = mapOf("https://x/a.onnx" to "model a".toByteArray(), "https://x/tokens.txt" to "x 1\n".toByteArray())

    private fun spec(hashOf: (String) -> String = { sha(content.getValue(it)) }) = FilesSpec(
        dirName = "m",
        files = mapOf(
            "a.onnx" to RemoteFile("https://x/a.onnx", hashOf("https://x/a.onnx"), content.getValue("https://x/a.onnx").size.toLong()),
            "tokens.txt" to RemoteFile("https://x/tokens.txt", hashOf("https://x/tokens.txt"), content.getValue("https://x/tokens.txt").size.toLong()),
        ),
    )

    private fun open(url: String) = ByteArrayInputStream(content[url] ?: throw IOException("404"))

    @Test fun installsEveryFile() = runBlocking<Unit> {
        val installer = FilesInstaller(base, spec(), wholeFiles(::open))
        installer.install()
        assertEquals(ModelState.Installed, installer.state.value)
        val dir = assertNotNull(installer.modelDir())
        assertEquals("model a", File(dir, "a.onnx").readText())
        assertEquals("x 1\n", File(dir, "tokens.txt").readText())
        assertTrue(base.listFiles()!!.none { it.name == "work.tmp" })
    }

    @Test fun oneWrongHashInstallsNothing() = runBlocking<Unit> {
        val installer = FilesInstaller(base, spec { if (it.endsWith("tokens.txt")) "0".repeat(64) else sha(content.getValue(it)) }, wholeFiles(::open))
        installer.install()
        assertIs<ModelState.Failed>(installer.state.value)
        assertNull(installer.modelDir())
        assertTrue(base.listFiles()!!.none { it.name == "m" })
        // The file that did not match is gone (the next try fetches it again); the verified one is kept.
        kotlin.test.assertFalse(File(base, "work.tmp/tokens.txt").exists())
        assertTrue(File(base, "work.tmp/a.onnx").isFile)
    }

    @Test fun aDownloadCutShortGoesOnWhereItStopped() = runBlocking<Unit> {
        // The app was ended half way through a.onnx: its first bytes are there, with a file of another model.
        val body = content.getValue("https://x/a.onnx")
        File(base, "work.tmp").mkdirs()
        File(base, "work.tmp/a.onnx").writeBytes(body.copyOf(3))
        File(base, "work.tmp/stale.bin").writeBytes(ByteArray(10))
        val asked = mutableListOf<Pair<String, Long>>()
        val ranged: Opener = { url, from ->
            asked += url to from
            val all = content[url] ?: throw IOException("404")
            Opened(ByteArrayInputStream(all.copyOfRange(from.toInt(), all.size)), from)
        }
        val installer = FilesInstaller(base, spec(), ranged)
        installer.install()
        assertEquals(ModelState.Installed, installer.state.value)
        assertEquals("model a", File(installer.modelDir(), "a.onnx").readText())
        assertEquals(listOf("https://x/a.onnx" to 3L, "https://x/tokens.txt" to 0L), asked)
        kotlin.test.assertFalse(File(installer.modelDir(), "stale.bin").exists())
    }

    @Test fun aServerThatIgnoresTheRangeStartsOver() = runBlocking<Unit> {
        File(base, "work.tmp").mkdirs()
        File(base, "work.tmp/a.onnx").writeBytes("XXX".toByteArray()) // not even the right start
        val installer = FilesInstaller(base, spec(), wholeFiles(::open))
        installer.install()
        assertEquals("model a", File(installer.modelDir(), "a.onnx").readText())
    }

    @Test fun notEnoughRoomIsSaidBeforeDownloading() = runBlocking<Unit> {
        val huge = FilesSpec("m", mapOf("a.onnx" to RemoteFile("https://x/a.onnx", "0".repeat(64), Long.MAX_VALUE / 4)))
        var opened = false
        val installer = FilesInstaller(base, huge) { _, _ -> opened = true; error("not reached") }
        installer.install()
        val failed = assertIs<ModelState.Failed>(installer.state.value)
        assertTrue(failed.message.startsWith("spazio insufficiente"), failed.message)
        kotlin.test.assertFalse(opened)
    }

    @Test fun deleteRemovesTheModel() = runBlocking<Unit> {
        val installer = FilesInstaller(base, spec(), wholeFiles(::open))
        installer.install()
        installer.delete()
        assertNull(installer.modelDir())
        assertEquals(ModelState.NotInstalled, installer.state.value)
    }
}
