package dev.nanovoice.push

import dev.nanovoice.NanoVoiceApp
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Receives the distributor's events. Starting this service also starts the app process, hence the server connection. */
class PushServiceImpl : PushService() {
    private val push get() = (application as NanoVoiceApp).container.push

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = push.onEndpoint(endpoint.url, endpoint.temporary)

    override fun onMessage(message: PushMessage, instance: String) = push.onWake()

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = push.onRegistrationFailed(reason)

    override fun onUnregistered(instance: String) = push.onUnregistered()
}
