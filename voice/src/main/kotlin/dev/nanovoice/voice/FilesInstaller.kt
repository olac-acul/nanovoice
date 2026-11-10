package dev.nanovoice.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException

/** Installs a [FilesSpec]: every file is downloaded and verified before the directory is moved into place. */
class FilesInstaller(
    private val baseDir: File,
    private val spec: FilesSpec,
    private val open: Opener = ::openHttpsFrom,
) : ModelInstaller {
    private val _state = MutableStateFlow<ModelState>(if (modelDir() != null) ModelState.Installed else ModelState.NotInstalled)
    override val state: StateFlow<ModelState> = _state

    /** One install at a time. */
    private val busy = Mutex()

    // A download cut short (the network, Android ending the app) is kept in work.tmp: the next install goes on from
    // there, each file from where it stopped, instead of fetching hundreds of MB again.

    override fun modelDir(): File? = File(baseDir, spec.dirName).takeIf { File(it, READY_MARKER).isFile }

    override suspend fun install() = busy.withLock { installLocked() }

    private suspend fun installLocked() = withContext(Dispatchers.IO) {
        if (modelDir() != null) {
            _state.value = ModelState.Installed
            return@withContext
        }
        val work = File(baseDir, "work.tmp")
        var installed = false
        try {
            work.mkdirs()
            // Only the files of this model stay from an earlier try.
            work.listFiles()?.filter { it.name !in spec.files.keys }?.forEach { it.deleteRecursively() }
            val kept = spec.files.keys.sumOf { File(work, it).let { f -> if (f.isFile) f.length() else 0L } }
            ensureSpace(baseDir, spec.sizeBytes - kept)
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
            installed = true
            _state.value = ModelState.Installed
        } catch (e: CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (installed) work.deleteRecursively()
        }
    }

    override fun delete() {
        File(baseDir, spec.dirName).deleteRecursively()
        File(baseDir, "work.tmp").deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    private companion object {
        const val READY_MARKER = ".nanovoice-ready"
    }
}
