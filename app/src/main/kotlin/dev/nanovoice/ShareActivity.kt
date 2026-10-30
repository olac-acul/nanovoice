package dev.nanovoice

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * "Condividi → Chiedi a NanoVoice": hands the shared photo, or text and links, to the chat already open (its own task),
 * then goes away.
 * MainActivity stays a normal activity, so coming back from the launcher keeps the scanner or a TinEye page open.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" && !text.isNullOrBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(Intent.EXTRA_SUBJECT, intent.getStringExtra(Intent.EXTRA_SUBJECT))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } else if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true && uri != null) {
            val forward = Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(intent.type)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            // The read grant comes with the photo, so the chat can open it.
            forward.clipData = ClipData.newRawUri(null, uri)
            try {
                startActivity(forward)
            } catch (_: SecurityException) {
                // The sending app gave no permission to read it, so there is none to pass on: the chat tries anyway and
                // says the photo could not be opened, instead of the app closing.
                forward.clipData = null
                forward.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(forward)
            }
        }
        finish()
    }
}
