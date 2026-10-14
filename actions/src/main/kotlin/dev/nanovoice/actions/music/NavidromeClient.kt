package dev.nanovoice.actions.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** Talks to Navidrome over https. Short timeouts and a size cap: answers are small JSON documents. */
class NavidromeClient(private val account: MusicAccount) {
    private val random = SecureRandom()

    /** A fresh salt for every request, as the Subsonic token scheme expects. */
    fun salt(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    suspend fun ping(): Result<Unit> = get(Subsonic.pingUrl(account, salt())).mapCatching { Subsonic.response(it).getOrThrow(); Unit }

    suspend fun search(query: String): Result<SearchResult> =
        get(Subsonic.searchUrl(account, salt(), query)).mapCatching { Subsonic.parseSearch(it).getOrThrow() }

    suspend fun albumSongs(albumId: String): List<Song> =
        get(Subsonic.albumUrl(account, salt(), albumId)).mapCatching { Subsonic.parseAlbumSongs(it).getOrThrow() }.getOrDefault(emptyList())

    suspend fun artistAlbums(artistId: String): List<Named> =
        get(Subsonic.artistUrl(account, salt(), artistId)).mapCatching { Subsonic.parseArtistAlbums(it).getOrThrow() }.getOrDefault(emptyList())

    fun streamUrl(song: Song) = Subsonic.streamUrl(account, salt(), song.id)

    fun coverUrl(song: Song) = song.coverArt?.let { Subsonic.coverUrl(account, salt(), it) }

    private suspend fun get(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Navidrome must be reached over https" }
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = false
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    // At most one byte over the limit is read (InputStream.readNBytes needs Android 13).
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (buffer.size() <= MAX_BYTES) {
                        val read = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - buffer.size()))
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                    }
                    val bytes = buffer.toByteArray()
                    if (bytes.size > MAX_BYTES) error("answer too large")
                    String(bytes, Charsets.UTF_8)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
