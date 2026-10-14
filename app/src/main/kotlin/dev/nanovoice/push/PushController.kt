package dev.nanovoice.push

import android.app.Activity
import android.content.Context
import android.util.Log
import dev.nanovoice.SettingsStore
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Registration with a UnifiedPush distributor (e.g. ntfy). The pushes carry no content: they only wake the app, which
 * then connects to the server and receives the actual messages there, so private text never goes through the push server.
 */
class PushController(private val context: Context, private val settings: SettingsStore) {
    /** Distributors installed on the phone (package names). */
    fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    /** Picks the distributor (asks the user if there are several) and registers; the endpoint arrives in [onEndpoint]. */
    fun enable(activity: Activity, onResult: (Boolean) -> Unit = {}) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(context, INSTANCE)
            Log.i(TAG, "distributor ${if (ok) "chosen, registering" else "not chosen"}")
            onResult(ok)
        }
    }

    fun disable() {
        runCatching { UnifiedPush.unregister(context, INSTANCE) }
        runCatching { UnifiedPush.removeDistributor(context) }
        clearEndpoint()
    }

    fun onEndpoint(url: String, temporary: Boolean) {
        if (temporary) return // a fallback endpoint from another distributor; the primary one is still to come
        Log.i(TAG, "push endpoint registered (${url.substringBefore("/", "").take(60)}…)")
        settings.save(settings.settings.value.copy(pushEndpoint = url))
    }

    fun onUnregistered() = clearEndpoint()

    fun onRegistrationFailed(reason: FailedReason) {
        Log.w(TAG, "push registration failed: $reason")
    }

    /** A wake-up arrived. The connection to the server is opened by the app itself at startup: nothing else to do. */
    fun onWake() {
        Log.i(TAG, "wake-up push received")
    }

    private fun clearEndpoint() {
        if (settings.settings.value.pushEndpoint.isNotEmpty()) settings.save(settings.settings.value.copy(pushEndpoint = ""))
    }

    private companion object {
        const val TAG = "PushController"
        const val INSTANCE = "default"
    }
}
