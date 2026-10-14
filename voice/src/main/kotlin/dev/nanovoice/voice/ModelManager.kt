package dev.nanovoice.voice

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A downloadable model archive. The checksum is pinned: the archive is verified before anything is unpacked. */
data class ModelSpec(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Name of the single top-level directory inside the archive; it becomes the installed model directory. */
    val dirName: String,
)

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val progress: Float) : ModelState

    /** The archive is downloaded and being unpacked (CPU-bound, can take a while on a phone). */
    data class Unpacking(val progress: Float) : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/** Something that can fetch a model into place, report progress and remove it again. */
interface ModelInstaller {
    val state: StateFlow<ModelState>

    /** The installed model directory, or null if it is not (completely) installed. */
    fun modelDir(): File?

    suspend fun install()

    fun delete()
}

/** Downloads, verifies and unpacks one model archive into [baseDir]. */
class ModelManager(
    private val baseDir: File,
    private val spec: ModelSpec,
    private val open: (String) -> InputStream = ::openHttps,
) : ModelInstaller {
    private val _state = MutableStateFlow<ModelState>(if (modelDir() != null) ModelState.Installed else ModelState.NotInstalled)
    override val state: StateFlow<ModelState> = _state

    /** Held by an install and by the start-up cleanup, so the cleanup never deletes a download in progress. */
    private val busy = Mutex()

    init {
        // A download cut short by the process dying leaves its partial files (hundreds of MB): gone at the next start.
        Thread {
            runBlocking { busy.withLock { for (name in listOf("download.part", "unpack.tmp")) File(baseDir, name).deleteRecursively() } }
        }.apply { isDaemon = true }.start()
    }

    override fun modelDir(): File? = File(baseDir, spec.dirName).takeIf { File(it, READY_MARKER).isFile }

    override suspend fun install() = busy.withLock { installLocked() }

    private suspend fun installLocked() = withContext(Dispatchers.IO) {
        if (modelDir() != null) {
            _state.value = ModelState.Installed
            return@withContext
        }
        val archive = File(baseDir, "download.part")
        val unpack = File(baseDir, "unpack.tmp")
        try {
            baseDir.mkdirs()
            _state.value = ModelState.Downloading(0f)
            download(archive)
            unpack.deleteRecursively()
            _state.value = ModelState.Unpacking(0f)
            val started = System.nanoTime()
            ArchiveUnpacker.unpack(
                archive, unpack, maxBytes = spec.sizeBytes * MAX_EXPANSION,
                onProgress = { _state.value = ModelState.Unpacking(it) },
            )
            Log.i(TAG, "unpacked ${spec.dirName} in ${(System.nanoTime() - started) / 1_000_000} ms")
            val unpacked = File(unpack, spec.dirName)
            if (!unpacked.isDirectory) throw IOException("archive does not contain ${spec.dirName}/")
            File(unpacked, READY_MARKER).writeText(spec.sha256)
            val target = File(baseDir, spec.dirName)
            target.deleteRecursively()
            if (!unpacked.renameTo(target)) throw IOException("cannot move the model into place")
            _state.value = ModelState.Installed
        } catch (e: CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            archive.delete()
            unpack.deleteRecursively()
        }
    }

    override fun delete() {
        File(baseDir, spec.dirName).deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    private suspend fun download(target: File) =
        downloadVerified(open, RemoteFile(spec.url, spec.sha256, spec.sizeBytes), target) { _state.value = ModelState.Downloading(it) }

    private companion object {
        const val TAG = "ModelManager"
        const val READY_MARKER = ".nanovoice-ready"
        const val MAX_EXPANSION = 4L
    }
}

/** Downloads [file] into [target], refusing anything bigger than declared or not matching its SHA-256. */
internal suspend fun downloadVerified(
    open: (String) -> InputStream,
    file: RemoteFile,
    target: File,
    onProgress: (Float) -> Unit = {},
) {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    open(file.url).use { input ->
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > file.sizeBytes * 11 / 10) throw IOException("download larger than expected")
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                onProgress((total.toFloat() / file.sizeBytes).coerceAtMost(1f))
            }
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (actual != file.sha256) throw IOException("checksum mismatch: the downloaded file is not the expected one")
}

internal fun openHttps(url: String): InputStream {
    require(url.startsWith("https://")) { "model downloads must use https" }
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    if (conn.responseCode != 200) {
        conn.disconnect()
        throw IOException("download failed: HTTP ${conn.responseCode}")
    }
    return conn.inputStream
}

/**
 * Unpacks zip archives, refusing entries that escape the destination and archives that expand beyond [maxBytes] or
 * hold more than [maxEntries] entries.
 */
internal object ArchiveUnpacker {
    fun unpack(
        archive: File, destDir: File, maxBytes: Long, maxEntries: Int = 10_000,
        onProgress: (Float) -> Unit = {},
    ) {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        var bytes = 0L
        var entries = 0
        var lastReport = 0L
        // Progress is measured on the compressed input: output size is unknown until the end.
        val counting = CountingInputStream(archive.inputStream().buffered(256 * 1024))
        counting.use { raw ->
            ZipInputStream(raw).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (++entries > maxEntries) throw IOException("archive has too many entries")
                    val out = File(root, entry.name).canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) {
                        throw IOException("unsafe path in archive: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            bytes += n
                            if (bytes > maxBytes) throw IOException("archive expands beyond the allowed size")
                            fos.write(buf, 0, n)
                            if (bytes - lastReport >= REPORT_EVERY) {
                                lastReport = bytes
                                onProgress((counting.count.toFloat() / archive.length()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }
        onProgress(1f)
    }

    private const val REPORT_EVERY = 512 * 1024L

    private class CountingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
