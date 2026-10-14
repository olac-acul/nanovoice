package dev.nanovoice.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Installs a [FilesSpec]: every file is downloaded and verified before the directory is moved into place. */
class FilesInstaller(
    private val baseDir: File,
    private val spec: FilesSpec,
    private val open: (String) -> InputStream = ::openHttps,
) : ModelInstaller {
    private val _state = MutableStateFlow<ModelState>(if (modelDir() != null) ModelState.Installed else ModelState.NotInstalled)
    override val state: StateFlow<ModelState> = _state

    /** Held by an install and by the start-up cleanup, so the cleanup never deletes a download in progress. */
    private val busy = Mutex()

    init {
        // A download cut short by the process dying leaves its partial files (hundreds of MB): gone at the next start.
        Thread {
            runBlocking { busy.withLock { for (name in listOf("work.tmp")) File(baseDir, name).deleteRecursively() } }
        }.apply { isDaemon = true }.start()
    }

    override fun modelDir(): File? = File(baseDir, spec.dirName).takeIf { File(it, READY_MARKER).isFile }

    override suspend fun install() = busy.withLock { installLocked() }

    private suspend fun installLocked() = withContext(Dispatchers.IO) {
        if (modelDir() != null) {
            _state.value = ModelState.Installed
            return@withContext
        }
        val work = File(baseDir, "work.tmp")
        try {
            work.deleteRecursively()
            work.mkdirs()
            _state.value = ModelState.Downloading(0f)
            val total = spec.sizeBytes.toFloat()
            var done = 0L
            for ((name, file) in spec.files) {
                downloadVerified(open, file, File(work, name)) { _state.value = ModelState.Downloading((done + it * file.sizeBytes) / total) }
                done += file.sizeBytes
            }
            File(work, READY_MARKER).writeText(spec.files.values.joinToString("\n") { it.sha256 })
            val target = File(baseDir, spec.dirName)
            target.deleteRecursively()
            if (!work.renameTo(target)) throw IOException("cannot move the model into place")
            _state.value = ModelState.Installed
        } catch (e: CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            work.deleteRecursively()
        }
    }

    override fun delete() {
        File(baseDir, spec.dirName).deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    private companion object {
        const val READY_MARKER = ".nanovoice-ready"
    }
}
