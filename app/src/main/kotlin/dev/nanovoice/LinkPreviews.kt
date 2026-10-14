package dev.nanovoice

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.nanovoice.linkpreview.LinkMetadata
import dev.nanovoice.linkpreview.LinkPreviewFetcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A page's preview ready to draw. */
class LinkPreview(val meta: LinkMetadata, val image: ImageBitmap?)

/**
 * Loads link previews and remembers them (also the failures) for the life of the app, so scrolling or reopening
 * the chat does not hit the network again.
 */
class LinkPreviews(private val fetcher: LinkPreviewFetcher = LinkPreviewFetcher()) {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, LinkPreview?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreview?>) = size > MAX_ENTRIES
    }

    /** One download per URL at a time: cards for the same link share it, and it outlives a card that goes away. */
    private val inFlight = HashMap<String, Deferred<LinkPreview?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun load(url: String): LinkPreview? {
        val job = mutex.withLock {
            if (cache.containsKey(url)) return cache[url]
            inFlight.getOrPut(url) {
                scope.async {
                    val preview = runCatching {
                        fetcher.fetch(url)?.let { LinkPreview(it, it.imageUrl?.let { image -> fetcher.fetchImage(image)?.let(::decode) }) }
                    }.getOrNull()
                    mutex.withLock {
                        cache[url] = preview
                        inFlight.remove(url)
                    }
                    preview
                }
            }
        }
        return job.await()
    }

    /**
     * Decodes a downscaled copy, off the main thread: the card shows a small thumbnail. The sample follows the longer
     * side (a very wide or tall image must shrink too), and an image too large to decode safely is skipped.
     */
    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) return@runCatching null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= THUMB_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()

    private companion object {
        const val MAX_ENTRIES = 50
        const val THUMB_PX = 256
        const val MAX_SOURCE_PIXELS = 50_000_000L
    }
}
