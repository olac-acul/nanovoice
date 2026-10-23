package dev.nanovoice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.nanovoice.voice.VoiceCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Which colour theme the app uses. */
enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    /**
     * Simple commands (time, date, timers, alarms, apps, calls, music, notes) answered on the phone, at once and
     * offline. Off: everything goes to the agent, except on the lock screen, where it cannot be reached anyway.
     */
    val localCommands: Boolean = true,
    /** Read replies aloud when the request was spoken. */
    val speakReplies: Boolean = true,
    /** Id of the Piper voice used to read replies. */
    val voiceId: String = VoiceCatalog.default.id,
    /** The first-run voice setup has been completed or skipped. */
    val setupDone: Boolean = false,
    /** UnifiedPush endpoint the server wakes this phone with. A capability URL, so stored encrypted like the token. */
    val pushEndpoint: String = "",
    val themeMode: ThemeMode = ThemeMode.System,
    /** Take the colours from the system palette (Material You, Android 12+) instead of the app's own. */
    val dynamicColors: Boolean = false,
    /** Show a small preview card for the first link of a message. It downloads the page from the phone. */
    val linkPreviews: Boolean = true,
    /** Listen for the wake word in the background (a foreground service with a permanent notification). */
    val wakeWord: Boolean = false,
    /** Transcribe each utterance again with Parakeet (if downloaded): more accurate than Vosk alone. */
    val accurateStt: Boolean = true,
    /** What the agent may do on the phone, beyond timers, alarms, apps and calls (see PhoneActionHandler). */
    val phoneControls: Boolean = true,
    val phoneLinks: Boolean = true,
    val phoneDrafts: Boolean = true,
    val phoneStatus: Boolean = true,
    /** Reads that hand private data to the agent: off until turned on (and the permission granted). */
    val readCalendar: Boolean = false,
    /** Allow changes to individual events, each with an approval on the phone. */
    val editCalendar: Boolean = false,
    val readLocation: Boolean = false,
    val readNotifications: Boolean = false,
    /** Navidrome (Subsonic API) for "riproduci …" in Tempo; the password is stored encrypted like the token. */
    val navidromeUrl: String = "",
    val navidromeUser: String = "",
    val navidromePassword: String = "",
    /** The agent may send a photo to Pl@ntNet or TinEye, or a barcode to Open Food Facts, for the request it came with. */
    val photoServices: Boolean = true,
    /** Pl@ntNet API key (free account at my.plantnet.org) for "che pianta è?"; stored encrypted like the token. */
    val plantNetKey: String = "",
    /** The kind of product scanned last (a ProductKind id): offered first next time. */
    val lastProductKind: String = "food",
) {
    fun musicAccount(): dev.nanovoice.actions.music.MusicAccount? =
        if (navidromeUrl.startsWith("https://") && navidromeUser.isNotBlank() && navidromePassword.isNotEmpty()) {
            dev.nanovoice.actions.music.MusicAccount(navidromeUrl.trim(), navidromeUser.trim(), navidromePassword)
        } else {
            null
        }
}

/**
 * These settings with only what the settings screen changed applied: [baseline] is what the screen started from,
 * [edited] what it holds now. A value changed elsewhere meanwhile (the wake word turned off from its notification)
 * is kept instead of being overwritten with the screen's stale copy.
 */
fun AppSettings.withChanges(baseline: AppSettings, edited: AppSettings): AppSettings = copy(
    serverUrl = if (edited.serverUrl != baseline.serverUrl) edited.serverUrl else serverUrl,
    token = if (edited.token != baseline.token) edited.token else token,
    localCommands = if (edited.localCommands != baseline.localCommands) edited.localCommands else localCommands,
    speakReplies = if (edited.speakReplies != baseline.speakReplies) edited.speakReplies else speakReplies,
    voiceId = if (edited.voiceId != baseline.voiceId) edited.voiceId else voiceId,
    setupDone = if (edited.setupDone != baseline.setupDone) edited.setupDone else setupDone,
    pushEndpoint = if (edited.pushEndpoint != baseline.pushEndpoint) edited.pushEndpoint else pushEndpoint,
    themeMode = if (edited.themeMode != baseline.themeMode) edited.themeMode else themeMode,
    dynamicColors = if (edited.dynamicColors != baseline.dynamicColors) edited.dynamicColors else dynamicColors,
    linkPreviews = if (edited.linkPreviews != baseline.linkPreviews) edited.linkPreviews else linkPreviews,
    wakeWord = if (edited.wakeWord != baseline.wakeWord) edited.wakeWord else wakeWord,
    accurateStt = if (edited.accurateStt != baseline.accurateStt) edited.accurateStt else accurateStt,
    phoneControls = if (edited.phoneControls != baseline.phoneControls) edited.phoneControls else phoneControls,
    phoneLinks = if (edited.phoneLinks != baseline.phoneLinks) edited.phoneLinks else phoneLinks,
    phoneDrafts = if (edited.phoneDrafts != baseline.phoneDrafts) edited.phoneDrafts else phoneDrafts,
    phoneStatus = if (edited.phoneStatus != baseline.phoneStatus) edited.phoneStatus else phoneStatus,
    readCalendar = if (edited.readCalendar != baseline.readCalendar) edited.readCalendar else readCalendar,
    editCalendar = if (edited.editCalendar != baseline.editCalendar) edited.editCalendar else editCalendar,
    readLocation = if (edited.readLocation != baseline.readLocation) edited.readLocation else readLocation,
    readNotifications = if (edited.readNotifications != baseline.readNotifications) edited.readNotifications else readNotifications,
    navidromeUrl = if (edited.navidromeUrl != baseline.navidromeUrl) edited.navidromeUrl else navidromeUrl,
    navidromeUser = if (edited.navidromeUser != baseline.navidromeUser) edited.navidromeUser else navidromeUser,
    navidromePassword = if (edited.navidromePassword != baseline.navidromePassword) edited.navidromePassword else navidromePassword,
    photoServices = if (edited.photoServices != baseline.photoServices) edited.photoServices else photoServices,
    plantNetKey = if (edited.plantNetKey != baseline.plantNetKey) edited.plantNetKey else plantNetKey,
    lastProductKind = if (edited.lastProductKind != baseline.lastProductKind) edited.lastProductKind else lastProductKind,
)

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun save(settings: AppSettings)
}

/** Token encrypted with an AES-GCM key that lives in the Android Keystore; URL and switches in plain preferences. */
class SecureSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<AppSettings> = _settings

    override fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_URL, settings.serverUrl)
            .putString(KEY_TOKEN, if (settings.token.isEmpty()) "" else encrypt(settings.token))
            .putString(KEY_PUSH, if (settings.pushEndpoint.isEmpty()) "" else encrypt(settings.pushEndpoint))
            .putBoolean(KEY_LOCAL_COMMANDS, settings.localCommands)
            .putBoolean(KEY_SPEAK, settings.speakReplies)
            .putString(KEY_VOICE, settings.voiceId)
            .putBoolean(KEY_SETUP, settings.setupDone)
            .putString(KEY_THEME, settings.themeMode.name)
            .putBoolean(KEY_DYNAMIC, settings.dynamicColors)
            .putBoolean(KEY_PREVIEWS, settings.linkPreviews)
            .putBoolean(KEY_WAKE, settings.wakeWord)
            .putBoolean(KEY_ACCURATE_STT, settings.accurateStt)
            .putBoolean(KEY_PHONE_CONTROLS, settings.phoneControls)
            .putBoolean(KEY_PHONE_LINKS, settings.phoneLinks)
            .putBoolean(KEY_PHONE_DRAFTS, settings.phoneDrafts)
            .putBoolean(KEY_PHONE_STATUS, settings.phoneStatus)
            .putBoolean(KEY_READ_CALENDAR, settings.readCalendar)
            .putBoolean(KEY_EDIT_CALENDAR, settings.editCalendar)
            .putBoolean(KEY_READ_LOCATION, settings.readLocation)
            .putBoolean(KEY_READ_NOTIFICATIONS, settings.readNotifications)
            .putString(KEY_NAVIDROME_URL, settings.navidromeUrl)
            .putString(KEY_NAVIDROME_USER, settings.navidromeUser)
            .putString(KEY_NAVIDROME_PASSWORD, if (settings.navidromePassword.isEmpty()) "" else encrypt(settings.navidromePassword))
            .putBoolean(KEY_PHOTO_SERVICES, settings.photoServices)
            .putString(KEY_PLANTNET, if (settings.plantNetKey.isEmpty()) "" else encrypt(settings.plantNetKey))
            .putString(KEY_PRODUCT_KIND, settings.lastProductKind)
            .apply()
        _settings.value = settings
    }

    private fun load() = AppSettings(
        serverUrl = prefs.getString(KEY_URL, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "")?.takeIf { it.isNotEmpty() }?.let(::decrypt).orEmpty(),
        localCommands = prefs.getBoolean(KEY_LOCAL_COMMANDS, true),
        speakReplies = prefs.getBoolean(KEY_SPEAK, true),
        // A stored voice that left the catalog (e.g. Paola) falls back to the default one.
        voiceId = VoiceCatalog.byId(prefs.getString(KEY_VOICE, null).orEmpty())?.id ?: VoiceCatalog.default.id,
        setupDone = prefs.getBoolean(KEY_SETUP, false),
        themeMode = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System,
        dynamicColors = prefs.getBoolean(KEY_DYNAMIC, false),
        linkPreviews = prefs.getBoolean(KEY_PREVIEWS, true),
        wakeWord = prefs.getBoolean(KEY_WAKE, false),
        accurateStt = prefs.getBoolean(KEY_ACCURATE_STT, true),
        phoneControls = prefs.getBoolean(KEY_PHONE_CONTROLS, true),
        phoneLinks = prefs.getBoolean(KEY_PHONE_LINKS, true),
        phoneDrafts = prefs.getBoolean(KEY_PHONE_DRAFTS, true),
        phoneStatus = prefs.getBoolean(KEY_PHONE_STATUS, true),
        readCalendar = prefs.getBoolean(KEY_READ_CALENDAR, false),
        editCalendar = prefs.getBoolean(KEY_EDIT_CALENDAR, false),
        readLocation = prefs.getBoolean(KEY_READ_LOCATION, false),
        readNotifications = prefs.getBoolean(KEY_READ_NOTIFICATIONS, false),
        navidromeUrl = prefs.getString(KEY_NAVIDROME_URL, "").orEmpty(),
        navidromeUser = prefs.getString(KEY_NAVIDROME_USER, "").orEmpty(),
        navidromePassword = prefs.getString(KEY_NAVIDROME_PASSWORD, "")?.takeIf { it.isNotEmpty() }?.let(::decrypt).orEmpty(),
        pushEndpoint = prefs.getString(KEY_PUSH, "")?.takeIf { it.isNotEmpty() }?.let(::decrypt).orEmpty(),
        photoServices = prefs.getBoolean(KEY_PHOTO_SERVICES, true),
        plantNetKey = prefs.getString(KEY_PLANTNET, "")?.takeIf { it.isNotEmpty() }?.let(::decrypt).orEmpty(),
        lastProductKind = prefs.getString(KEY_PRODUCT_KIND, null) ?: "food",
    )

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray())
        return b64(cipher.iv) + ":" + b64(data)
    }

    /** Returns null if the stored value cannot be decrypted (e.g. key lost after a restore): the user re-enters the token. */
    private fun decrypt(stored: String): String? = runCatching {
        val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(data))
    }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "nanovoice_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token_encrypted"
        const val KEY_LOCAL_COMMANDS = "local_commands"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_VOICE = "voice_id"
        const val KEY_SETUP = "setup_done"
        const val KEY_PREVIEWS = "link_previews"
        const val KEY_WAKE = "wake_word"
        const val KEY_ACCURATE_STT = "accurate_stt"
        const val KEY_PHONE_CONTROLS = "phone_controls"
        const val KEY_PHONE_LINKS = "phone_links"
        const val KEY_PHONE_DRAFTS = "phone_drafts"
        const val KEY_PHONE_STATUS = "phone_status"
        const val KEY_READ_CALENDAR = "read_calendar"
        const val KEY_EDIT_CALENDAR = "edit_calendar"
        const val KEY_READ_LOCATION = "read_location"
        const val KEY_READ_NOTIFICATIONS = "read_notifications"
        const val KEY_NAVIDROME_URL = "navidrome_url"
        const val KEY_NAVIDROME_USER = "navidrome_user"
        const val KEY_NAVIDROME_PASSWORD = "navidrome_password_encrypted"
        const val KEY_DYNAMIC = "dynamic_colors"
        const val KEY_THEME = "theme_mode"
        const val KEY_PUSH = "push_endpoint_encrypted"
        const val KEY_PLANTNET = "plantnet_key_encrypted"
        const val KEY_PRODUCT_KIND = "last_product_kind"
        const val KEY_PHOTO_SERVICES = "photo_services"
    }
}
