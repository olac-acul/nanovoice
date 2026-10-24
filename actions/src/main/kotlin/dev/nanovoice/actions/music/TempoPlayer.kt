package dev.nanovoice.actions.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays songs in Tempo. Tempo accepts media controllers on its session but plays only what it is given as a media
 * URI (it cannot search), so the songs arrive with their Navidrome stream URL plus title, artist and cover.
 */
class TempoPlayer(private val context: Context) {
    /** The installed Tempo build, or null. */
    fun installed(): String? = PACKAGES.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    /**
     * The installed Tempo is signed with the expected key. The songs handed to it carry the Navidrome token in their
     * URLs: an app that only took Tempo's package name (from another source) must not get them.
     */
    fun trusted(pkg: String): Boolean {
        // Checking an app's signing certificate by digest needs Android 9; below it no app is trusted with the songs.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val digests = SIGNERS[pkg] ?: return false
        return digests.any { hex ->
            val cert = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            runCatching { context.packageManager.hasSigningCertificate(pkg, cert, PackageManager.CERT_INPUT_SHA256) }.getOrDefault(false)
        }
    }

    /** [Playing]: music is heard. [Queued]: Tempo took the songs, but no music is playing yet. */
    enum class Outcome { Playing, Queued, Failed }

    suspend fun play(pkg: String, items: List<MediaItem>): Outcome = withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(pkg, SERVICE))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<MediaController?> { cont ->
                future.addListener({ if (cont.isActive) cont.resume(runCatching { future.get() }.getOrNull()) {} }, MAIN_EXECUTOR)
                cont.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
        } ?: return@withContext Outcome.Failed.also {
            Log.w(TAG, "could not connect to Tempo's media session")
            MediaController.releaseFuture(future)
        }
        try {
            Log.i(TAG, "connected to Tempo, queueing ${items.size} item(s)")
            controller.setMediaItems(items)
            controller.prepare()
            // "Play" goes through the platform session: Android then lets Tempo's media service run in the
            // foreground. Started only through the media3 connection, Tempo played but Android muted it as soon
            // as its screen went to the background.
            val platform = platformController(controller)
            Log.i(TAG, "platform session ${if (platform != null) "found" else "not available, using media3"}")
            if (platform != null) platform.transportControls.play() else controller.play()
            // Wait until music is really heard. The controller's own state cannot tell: right after the commands it shows
            // what it expects (media3 masking), and Tempo's older media3 misreports the rest. The audio system can.
            val firstId = items.first().mediaId
            val audio = context.getSystemService(AudioManager::class.java)
            val heard = { audio?.isMusicActive == true && controller.currentMediaItem?.mediaId == firstId }
            val gone = { !controller.isConnected || controller.playerError != null }
            val playing = withTimeoutOrNull(PLAY_TIMEOUT_MS) {
                while (!heard() && !gone()) delay(200)
                heard()
            } ?: false
            val outcome = when {
                playing -> Outcome.Playing
                controller.isConnected && controller.playerError == null && loaded(controller, firstId) -> Outcome.Queued
                else -> Outcome.Failed
            }
            Log.i(TAG, "outcome=$outcome state=${controller.playbackState} playWhenReady=${controller.playWhenReady} " +
                "platform=${platform?.playbackState?.state} error=${controller.playerError?.errorCodeName}")
            outcome
        } catch (e: Exception) {
            Log.w(TAG, "Tempo refused the queue: $e")
            Outcome.Failed
        } finally {
            controller.release()
        }
    }

    /** The platform session behind a media3 connection (media3 keeps its token internal, hence the reflection). */
    private fun platformController(controller: MediaController): android.media.session.MediaController? = runCatching {
        val token = controller.connectedToken ?: return null
        val getter = SessionToken::class.java.getDeclaredMethod("getPlatformToken").apply { isAccessible = true }
        (getter.invoke(token) as? MediaSession.Token)?.let { android.media.session.MediaController(context, it) }
    }.getOrNull()

    /** Our first song is Tempo's current item, loading or ready: taken, though maybe not playing. */
    private fun loaded(controller: MediaController, firstId: String): Boolean =
        (controller.playbackState == Player.STATE_READY || controller.playbackState == Player.STATE_BUFFERING) &&
            controller.currentMediaItem?.mediaId == firstId

    companion object {
        /**
         * Tempo as F-Droid builds it ("notquitemy"). Upstream's own builds have another package and a signing key not
         * known here: they would only ever be refused, so they are not looked for.
         */
        val PACKAGES = listOf("com.cappielloantonio.notquitemy.tempo")

        /** SHA-256 of the signing certificates trusted per package: Tempo as built and signed by F-Droid. */
        private val SIGNERS = mapOf(
            "com.cappielloantonio.notquitemy.tempo" to setOf("3be2a9b416ecc036013a2de1eb6cb252bac829400fed756bccd4614f071309f5"),
        )
        private const val SERVICE = "com.cappielloantonio.tempo.service.MediaService"
        /** Runs on the main thread (Context.getMainExecutor needs Android 9). */
        private val MAIN_EXECUTOR = java.util.concurrent.Executor { Handler(Looper.getMainLooper()).post(it) }
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val PLAY_TIMEOUT_MS = 10_000L
        private const val TAG = "TempoPlayer"

        @OptIn(UnstableApi::class)
        fun item(song: Song, streamUrl: String, coverUrl: String?): MediaItem {
            val uri = Uri.parse(streamUrl)
            val extras = tempoExtras(song, streamUrl)
            return MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(uri)
                .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setExtras(extras)
                        .apply { coverUrl?.let { setArtworkUri(Uri.parse(it)) } }
                        .build(),
                )
                .build()
        }

        /**
         * The extras Tempo puts on its own media items (its MappingUtil): its player screen reads them without
         * checking, and crashed on the bare items NanoVoice sent before.
         */
        internal fun tempoExtras(song: Song, streamUrl: String): Bundle = Bundle().apply {
            val f = song.fields
            fun text(key: String, field: String = key) = putString(key, f[field])
            fun int(key: String, field: String = key) = putInt(key, f[field]?.toIntOrNull() ?: 0)
            putString("id", song.id)
            text("parentId", "parent")
            putBoolean("isDir", false)
            putString("title", song.title)
            putString("album", song.album)
            putString("artist", song.artist)
            int("track")
            int("year")
            text("genre")
            putString("coverArtId", song.coverArt)
            putLong("size", f["size"]?.toLongOrNull() ?: 0L)
            text("contentType")
            text("suffix")
            text("transcodedContentType")
            text("transcodedSuffix")
            int("duration")
            int("bitrate", "bitRate")
            text("path")
            putBoolean("isVideo", false)
            int("userRating")
            putDouble("averageRating", f["averageRating"]?.toDoubleOrNull() ?: 0.0)
            putLong("playCount", f["playCount"]?.toLongOrNull() ?: 0L)
            int("discNumber")
            text("albumId")
            text("artistId")
            putString("type", "music")
            putLong("bookmarkPosition", 0L)
            int("originalWidth")
            int("originalHeight")
            putString("uri", streamUrl)
        }
    }
}
