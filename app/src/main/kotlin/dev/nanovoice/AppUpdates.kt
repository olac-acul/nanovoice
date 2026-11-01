package dev.nanovoice

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A release of the app on GitHub: its [version] ("0.3.4"), its APK and checksum file, its page and when it came out. */
data class AppRelease(val version: String, val apkUrl: String, val sumUrl: String, val page: String, val published: String)

/** What the updater is doing now; [Failed] says why the last attempt stopped. */
sealed interface UpdateWork {
    data object Idle : UpdateWork
    data object Checking : UpdateWork
    data class Downloading(val percent: Int) : UpdateWork
    /** Android's own installer is open: it asks to confirm, and to allow installs from the app the first time. */
    data object Installing : UpdateWork
    /** Installs from this app are not allowed yet: Android's page for that is open, then "Scarica e installa" again. */
    data object NeedsPermission : UpdateWork
    data class Failed(val reason: String) : UpdateWork
}

/** "v0.3.4", "0.3.4" or "0.3.4-dev" as numbers; null when it is no version. */
internal fun versionParts(version: String): List<Int>? =
    Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(version.trim())?.groupValues?.drop(1)?.map { it.toInt() }

/** Whether [candidate] is a later version than [current]; never for something that is not a version. */
internal fun isNewer(candidate: String, current: String): Boolean {
    val a = versionParts(candidate) ?: return false
    val b = versionParts(current) ?: return true
    for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
    return false
}

/**
 * The newest release in GitHub's list of releases ([json]), with its APK and checksum file. Pre-releases count: every
 * NanoVoice release so far is a beta. Drafts, and releases without the two files, do not.
 */
internal fun newestRelease(json: String): AppRelease? {
    val list = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
    return list.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        fun str(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
        val tag = str(o, "tag_name") ?: return@mapNotNull null
        val version = versionParts(tag)?.joinToString(".") ?: return@mapNotNull null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun asset(name: String) = assets.firstOrNull { str(it, "name") == name }?.let { str(it, "browser_download_url") }
        val apk = asset("nanovoice-$version.apk") ?: return@mapNotNull null
        val sum = asset("nanovoice-$version.apk.sha256") ?: return@mapNotNull null
        if (!apk.startsWith("https://") || !sum.startsWith("https://")) return@mapNotNull null
        AppRelease(version, apk, sum, str(o, "html_url") ?: "", str(o, "published_at") ?: "")
    }.maxWithOrNull { a, b -> if (isNewer(a.version, b.version)) 1 else if (isNewer(b.version, a.version)) -1 else 0 }
}

/** The hash in a `.sha256` file ("<64 hex>  nanovoice-0.3.4.apk"), lowercase; null when there is none. */
internal fun checksumOf(text: String): String? =
    Regex("""^\s*([0-9a-fA-F]{64})\b""").find(text)?.groupValues?.get(1)?.lowercase()

/**
 * Updates of the app from its GitHub releases, without opening the page: once a day (and when the app is opened after
 * a day without a look) it asks GitHub for the list of releases; [badge] marks a newer one until the "Aggiornamenti"
 * page is seen. "Scarica e installa" downloads the APK and hands it to Android's installer only after checking it: the
 * checksum published with it, the app's package, a higher version, and the release signing key, pinned here. A copy
 * signed with the development key (built on the owner's computer) cannot take a release over it: it is told so.
 * The only thing sent is the request for the list and the download: GitHub sees the phone's network address.
 */
class AppUpdates(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** The version installed now ("0.3.3", or "0.3.3-dev" for a development copy). */
    val installed: String = runCatching { packageInfo(0).versionName }.getOrNull().orEmpty()

    /** Signed with the release key: a release can be installed over it. */
    val releaseSigned: Boolean = runCatching { signerDigests(packageInfo(signingFlags())) == setOf(RELEASE_CERT_SHA256) }.getOrDefault(false)

    private val _latest = MutableStateFlow(loadLatest())
    /** The newest release seen at the last check; null before the first one. */
    val latest: StateFlow<AppRelease?> = _latest

    private val _checkedAt = MutableStateFlow(prefs.getLong(KEY_CHECKED_AT, 0L))
    /** When GitHub was last asked (epoch ms), 0 never. */
    val checkedAt: StateFlow<Long> = _checkedAt

    private val _daily = MutableStateFlow(prefs.getBoolean(KEY_DAILY, true))
    /** "Controlla ogni giorno". */
    val daily: StateFlow<Boolean> = _daily

    private val _work = MutableStateFlow<UpdateWork>(UpdateWork.Idle)
    val work: StateFlow<UpdateWork> = _work

    private val seen = MutableStateFlow(prefs.getString(KEY_SEEN, null))

    /** A newer release that this copy can install, not yet seen on the "Aggiornamenti" page. */
    val badge: StateFlow<Boolean> = combine(_latest, seen) { latest, seen ->
        latest != null && releaseSigned && isNewer(latest.version, installed) && latest.version != seen
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        schedule(_daily.value)
    }

    fun setDaily(on: Boolean) {
        prefs.edit().putBoolean(KEY_DAILY, on).apply()
        _daily.value = on
        schedule(on)
    }

    /** The "Aggiornamenti" page was opened: the newest release is known, the badge goes until a newer one comes. */
    fun markSeen() {
        val version = _latest.value?.version ?: return
        prefs.edit().putString(KEY_SEEN, version).apply()
        seen.value = version
    }

    /** At the app's start: a look when the daily check is on and the last one is more than a day old. */
    fun checkIfDue() {
        if (_daily.value && System.currentTimeMillis() - _checkedAt.value > DAY_MS) scope.launch { check() }
    }

    fun checkNow() {
        scope.launch { check() }
    }

    /** Asks GitHub for the releases; false when it could not. */
    suspend fun check(): Boolean = lock.withLock {
        _work.value = UpdateWork.Checking
        val found = withContext(Dispatchers.IO) {
            runCatching { newestRelease(String(get(RELEASES_URL, MAX_LIST_BYTES), Charsets.UTF_8)) }
        }
        found.onSuccess { release ->
            val now = System.currentTimeMillis()
            val edit = prefs.edit().putLong(KEY_CHECKED_AT, now)
            saveLatest(edit, release)
            edit.apply()
            _checkedAt.value = now
            _latest.value = release
            _work.value = UpdateWork.Idle
        }.onFailure {
            Log.w(TAG, "release check failed: ${it.javaClass.simpleName}")
            _work.value = UpdateWork.Failed(context.getString(R.string.update_check_failed))
        }
        found.isSuccess
    }

    /** Downloads the newest release, checks it and opens Android's installer for it. */
    fun downloadAndInstall() {
        scope.launch {
            lock.withLock {
                val release = _latest.value ?: return@withLock
                if (!releaseSigned || !isNewer(release.version, installed)) return@withLock
                if (!context.packageManager.canRequestPackageInstalls()) {
                    _work.value = UpdateWork.NeedsPermission
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    return@withLock
                }
                _work.value = UpdateWork.Downloading(0)
                val apk = withContext(Dispatchers.IO) { runCatching { download(release) } }
                apk.onSuccess { file ->
                    _work.value = UpdateWork.Installing
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                    val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(install) }
                        .onFailure { _work.value = UpdateWork.Failed(context.getString(R.string.update_install_failed)) }
                }.onFailure {
                    Log.w(TAG, "update download failed: ${it.message}")
                    _work.value = UpdateWork.Failed(it.message?.takeIf { m -> m.startsWith(CHECK_PREFIX) }?.removePrefix(CHECK_PREFIX)
                        ?: context.getString(R.string.update_download_failed))
                }
            }
        }
    }

    /** The APK in the cache (the folder emptied first), checked; throws with the reason otherwise. */
    private fun download(release: AppRelease): File {
        val expected = checksumOf(String(get(release.sumUrl, 4096), Charsets.UTF_8)) ?: refuse(R.string.update_bad_checksum)
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "nanovoice-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        open(release.apkUrl).let { connection ->
            try {
                val total = connection.contentLengthLong
                if (total > MAX_APK_BYTES) refuse(R.string.update_bad_file)
                var done = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > MAX_APK_BYTES) refuse(R.string.update_bad_file)
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            if (total > 0) _work.value = UpdateWork.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != expected) refuse(R.string.update_bad_checksum)
        val archive = archiveInfo(file) ?: refuse(R.string.update_bad_file)
        if (archive.packageName != context.packageName) refuse(R.string.update_bad_file)
        if (archive.longVersion() <= packageInfo(0).longVersion()) refuse(R.string.update_not_newer)
        if (signerDigests(archive) != setOf(RELEASE_CERT_SHA256)) refuse(R.string.update_bad_signature)
        return file
    }

    private fun refuse(reason: Int): Nothing = throw IllegalStateException(CHECK_PREFIX + context.getString(reason))

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true // GitHub's files are served from another https host
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        connection.setRequestProperty("User-Agent", "NanoVoice/$installed")
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP ${connection.responseCode}")
        }
        if (connection.url.protocol != "https") {
            connection.disconnect()
            error("not https")
        }
        return connection
    }

    private fun get(url: String, max: Int): ByteArray {
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(max + 1)
                if (bytes.size > max) error("too long")
                return bytes
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun schedule(on: Boolean) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!on) {
            jobs.cancel(JOB_ID)
            return
        }
        if (jobs.getPendingJob(JOB_ID) != null) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DAY_MS)
                .build(),
        )
    }

    private fun packageInfo(flags: Int): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(file.path, signingFlags())

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.toList()
        } else {
            info.signatures?.toList()
        }.orEmpty()
        val sha = MessageDigest.getInstance("SHA-256")
        return certs.map { cert -> sha.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersion(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun loadLatest(): AppRelease? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        return AppRelease(
            version,
            prefs.getString(KEY_APK, null) ?: return null,
            prefs.getString(KEY_SUM, null) ?: return null,
            prefs.getString(KEY_PAGE, "").orEmpty(),
            prefs.getString(KEY_PUBLISHED, "").orEmpty(),
        )
    }

    private fun saveLatest(edit: android.content.SharedPreferences.Editor, release: AppRelease?) {
        if (release == null) {
            edit.remove(KEY_VERSION).remove(KEY_APK).remove(KEY_SUM).remove(KEY_PAGE).remove(KEY_PUBLISHED)
        } else {
            edit.putString(KEY_VERSION, release.version).putString(KEY_APK, release.apkUrl).putString(KEY_SUM, release.sumUrl)
                .putString(KEY_PAGE, release.page).putString(KEY_PUBLISHED, release.published)
        }
    }

    companion object {
        /** SHA-256 of the release signing certificate: the same as tools/release-cert.sha256 (a test checks it). */
        const val RELEASE_CERT_SHA256 = "8969f19b759c73a805052c37c074260dd4e39536e2e30b9f69c52f9f3e6ed3ce"
        private const val RELEASES_URL = "https://api.github.com/repos/olac-acul/nanovoice/releases?per_page=20"
        private const val APK_TYPE = "application/vnd.android.package-archive"
        private const val MAX_LIST_BYTES = 2 * 1024 * 1024
        private const val MAX_APK_BYTES = 300L * 1024 * 1024
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val JOB_ID = 4242
        private const val CHECK_PREFIX = "check:"
        private const val TAG = "AppUpdates"
        private const val KEY_DAILY = "daily"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_SEEN = "seen"
        private const val KEY_VERSION = "latest_version"
        private const val KEY_APK = "latest_apk"
        private const val KEY_SUM = "latest_sum"
        private const val KEY_PAGE = "latest_page"
        private const val KEY_PUBLISHED = "latest_published"
    }
}

/** Up to [max] bytes of the stream (InputStream.readNBytes is Android 13+). */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** The daily look at GitHub's releases, run by Android when the phone has a network. */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as NanoVoiceApp).container
        container.scope.launch {
            val ok = container.updates.check()
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
