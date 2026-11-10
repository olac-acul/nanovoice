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

/** A download opened from byte [from] on: 0 when the server sends the whole file again (it ignored the range). */
class Opened(val input: InputStream, val from: Long)

/** Opens [url] from the byte [from] on (a download cut short goes on where it stopped). */
typealias Opener = (url: String, from: Long) -> Opened

/** An opener that always sends the whole file (tests, servers without ranges). */
fun wholeFiles(open: (String) -> InputStream): Opener = { url, _ -> Opened(open(url), 0) }

/**
 * Room for [needed] more bytes in [dir], said before downloading hundreds of MB, not after. An unknown free space (0)
 * is not refused.
 */
internal fun ensureSpace(dir: File, needed: Long) {
    dir.mkdirs()
    val free = dir.usableSpace
    if (free in 1 until needed) {
        throw IOException("spazio insufficiente sul telefono: servono circa ${needed / 1_000_000} MB liberi, ce ne sono ${free / 1_000_000}")
    }
}

/** Downloads, verifies and unpacks one model archive into [baseDir]. */
class ModelManager(
    private val baseDir: File,
    private val spec: ModelSpec,
    private val open: Opener = ::openHttpsFrom,
) : ModelInstaller {
    private val _state = MutableStateFlow<ModelState>(if (modelDir() != null) ModelState.Installed else ModelState.NotInstalled)
    override val state: StateFlow<ModelState> = _state

    /** Held by an install and by the start-up cleanup, so the cleanup never deletes a download in progress. */
    private val busy = Mutex()

    init {
        // A download cut short by the process dying is kept and goes on at the next install; a half unpacking is not.
        Thread {
            runBlocking { busy.withLock { File(baseDir, "unpack.tmp").deleteRecursively() } }
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
        var done = false
        try {
            baseDir.mkdirs()
            // The rest of the archive, and room to unpack it next to it.
            ensureSpace(baseDir, spec.sizeBytes - (if (archive.isFile) archive.length() else 0L) + spec.sizeBytes * 2)
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
            done = true
            _state.value = ModelState.Installed
        } catch (e: CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            // Kept when cut short (the network, the app closed): the next try goes on from there.
            if (done) archive.delete()
            unpack.deleteRecursively()
        }
    }

    override fun delete() {
        File(baseDir, spec.dirName).deleteRecursively()
        File(baseDir, "download.part").delete()
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

/**
 * Downloads [file] into [target], refusing anything bigger than declared or not matching its SHA-256. What [target]
 * already holds (a download cut short) is kept and the rest asked for; the SHA-256 is always of the whole file, and a
 * file that does not match is deleted, so the next try starts over.
 */
internal suspend fun downloadVerified(
    open: Opener,
    file: RemoteFile,
    target: File,
    onProgress: (Float) -> Unit = {},
) {
    val limit = file.sizeBytes * 11 / 10
    var have = if (target.isFile) target.length() else 0L
    if (have > limit) {
        target.delete()
        have = 0L
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val opened = open(file.url, have)
    if (opened.from != have) {
        // The server sends the whole file again: start over.
        have = 0L
    } else if (have > 0) {
        target.inputStream().use { kept ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = kept.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }
    var total = have
    opened.input.use { input ->
        java.io.FileOutputStream(target, have > 0).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) {
                    target.delete()
                    throw IOException("download larger than expected")
                }
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                onProgress((total.toFloat() / file.sizeBytes).coerceAtMost(1f))
            }
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (actual != file.sha256) {
        target.delete()
        throw IOException("checksum mismatch: the downloaded file is not the expected one")
    }
}

/**
 * [openHttps] from the byte [from] on: a server that honours the range answers 206 with the rest; one that does not
 * answers 200 with the whole file ([Opened.from] 0); 416 means nothing is left to send.
 */
internal fun openHttpsFrom(url: String, from: Long): Opened {
    if (from <= 0) return Opened(openHttps(url), 0)
    require(url.startsWith("https://")) { "model downloads must use https" }
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.setRequestProperty("Range", "bytes=$from-")
    return when (conn.responseCode) {
        206 -> Opened(conn.inputStream, from)
        200 -> Opened(conn.inputStream, 0)
        416 -> {
            conn.disconnect()
            Opened(java.io.ByteArrayInputStream(ByteArray(0)), from)
        }
        else -> {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("download failed: HTTP $code")
        }
    }
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
