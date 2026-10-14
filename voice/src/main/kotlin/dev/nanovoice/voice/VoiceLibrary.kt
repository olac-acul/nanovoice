package dev.nanovoice.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.io.InputStream

/** The voices of the catalog and what is installed of each. Every voice lives in its own directory under [baseDir]. */
class VoiceLibrary(
    private val baseDir: File,
    val voices: List<VoiceSpec> = VoiceCatalog.all,
    open: (String) -> InputStream = ::openHttps,
) {
    /** espeak data shared by the voices. */
    private val espeak = ModelManager(File(baseDir, ESPEAK_DIR), VoiceCatalog.ESPEAK_DATA, open)

    private val installers: Map<String, RawPiperInstaller> =
        voices.associate { v -> v.id to RawPiperInstaller(File(baseDir, v.id), v, espeak, open) }

    /** State of every voice, re-emitted whenever one changes. */
    val states: Flow<Map<String, ModelState>> =
        combine(voices.map { v -> installers.getValue(v.id).state }) { list -> voices.map { it.id }.zip(list).toMap() }

    fun spec(id: String): VoiceSpec? = voices.firstOrNull { it.id == id }

    fun state(id: String): ModelState = installers[id]?.state?.value ?: ModelState.NotInstalled

    /** Directory of an installed voice (model and tokens), or null. */
    fun modelDir(id: String): File? = installers[id]?.modelDir()

    /** The espeak-ng-data directory voices are loaded with, or null if it is not installed yet. */
    fun dataDir(): File? = espeak.modelDir()

    fun installedIds(): List<String> = voices.map { it.id }.filter { modelDir(it) != null }

    suspend fun install(id: String) {
        installers[id]?.install()
    }

    fun delete(id: String) {
        installers[id]?.delete()
    }

    /** The voice to speak with: the preferred one if installed, else any installed one, else null. */
    fun choose(preferredId: String?): VoiceSpec? =
        (preferredId?.takeIf { modelDir(it) != null } ?: installedIds().firstOrNull())?.let(::spec)

    /** Removes voice directories that are no longer in the catalog (e.g. voices dropped in an update). */
    fun removeUnknownVoices() {
        val known = voices.map { it.id }.toSet() + ESPEAK_DIR
        baseDir.listFiles()?.filter { it.isDirectory && it.name !in known }?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val ESPEAK_DIR = "_espeak"
    }
}
