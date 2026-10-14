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
        val installer = FilesInstaller(base, spec(), ::open)
        installer.install()
        assertEquals(ModelState.Installed, installer.state.value)
        val dir = assertNotNull(installer.modelDir())
        assertEquals("model a", File(dir, "a.onnx").readText())
        assertEquals("x 1\n", File(dir, "tokens.txt").readText())
        assertTrue(base.listFiles()!!.none { it.name == "work.tmp" })
    }

    @Test fun oneWrongHashInstallsNothing() = runBlocking<Unit> {
        val installer = FilesInstaller(base, spec { if (it.endsWith("tokens.txt")) "0".repeat(64) else sha(content.getValue(it)) }, ::open)
        installer.install()
        assertIs<ModelState.Failed>(installer.state.value)
        assertNull(installer.modelDir())
        assertTrue(base.listFiles()!!.isEmpty())
    }

    @Test fun aDownloadCutShortIsCleanedUpAtTheNextStart() {
        val leftover = java.io.File(base, "work.tmp/encoder.int8.onnx").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1024)) }
        FilesInstaller(base, spec(), ::open)
        val until = System.currentTimeMillis() + 3_000
        while (leftover.parentFile!!.exists() && System.currentTimeMillis() < until) Thread.sleep(20)
        kotlin.test.assertFalse(leftover.parentFile!!.exists())
    }

    @Test fun deleteRemovesTheModel() = runBlocking<Unit> {
        val installer = FilesInstaller(base, spec(), ::open)
        installer.install()
        installer.delete()
        assertNull(installer.modelDir())
        assertEquals(ModelState.NotInstalled, installer.state.value)
    }
}
