package dev.nanovoice.actions.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer

/** A Navidrome (Subsonic API) account. [baseUrl] is https, e.g. "https://music.example.org". */
data class MusicAccount(val baseUrl: String, val user: String, val password: String)

/** [fields] keeps every plain value the server sent (track, duration, albumId...): Tempo needs them to show a song. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverArt: String?,
    val fields: Map<String, String> = emptyMap(),
)
data class Named(val id: String, val name: String)
data class SearchResult(val artists: List<Named>, val albums: List<Named>, val songs: List<Song>)

/** What to play for a request: the songs in order, and how it was understood. */
data class PlayPlan(val songs: List<Song>, val kind: Kind, val label: String) {
    enum class Kind { Song, Album, Artist }
}

/**
 * The Subsonic API bits NanoVoice needs: token authentication (the password itself never leaves the phone, only
 * md5(password + salt)), the URLs for search, album and stream, and reading the JSON answers.
 */
object Subsonic {
    private const val API_VERSION = "1.16.1"
    private const val CLIENT = "NanoVoice"
    private val json = Json { ignoreUnknownKeys = true }

    fun authParams(account: MusicAccount, salt: String): String {
        val token = md5(account.password + salt)
        return "u=${enc(account.user)}&t=$token&s=$salt&v=$API_VERSION&c=$CLIENT"
    }

    fun searchUrl(account: MusicAccount, salt: String, query: String) =
        "${base(account)}/rest/search3?${authParams(account, salt)}&f=json&query=${enc(query)}&artistCount=3&albumCount=3&songCount=30"

    fun albumUrl(account: MusicAccount, salt: String, albumId: String) =
        "${base(account)}/rest/getAlbum?${authParams(account, salt)}&f=json&id=${enc(albumId)}"

    fun artistUrl(account: MusicAccount, salt: String, artistId: String) =
        "${base(account)}/rest/getArtist?${authParams(account, salt)}&f=json&id=${enc(artistId)}"

    fun pingUrl(account: MusicAccount, salt: String) = "${base(account)}/rest/ping?${authParams(account, salt)}&f=json"

    fun streamUrl(account: MusicAccount, salt: String, songId: String) =
        "${base(account)}/rest/stream?${authParams(account, salt)}&id=${enc(songId)}"

    fun coverUrl(account: MusicAccount, salt: String, coverArt: String) =
        "${base(account)}/rest/getCoverArt?${authParams(account, salt)}&id=${enc(coverArt)}&size=512"

    /** The "subsonic-response" object, or an error message when the server refused (wrong password, ...). */
    fun response(body: String): Result<JsonObject> = runCatching {
        val root = (json.parseToJsonElement(body) as JsonObject)["subsonic-response"] as? JsonObject ?: error("not a Subsonic response")
        if ((root["status"] as? JsonPrimitive)?.contentOrNull != "ok") {
            val message = ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            error(message ?: "the server refused the request")
        }
        root
    }

    fun parseSearch(body: String): Result<SearchResult> = response(body).map { root ->
        val r = root["searchResult3"] as? JsonObject ?: JsonObject(emptyMap())
        SearchResult(
            artists = list(r["artist"]).mapNotNull { named(it) },
            albums = list(r["album"]).mapNotNull { named(it) },
            songs = list(r["song"]).mapNotNull { song(it) },
        )
    }

    fun parseAlbumSongs(body: String): Result<List<Song>> =
        response(body).map { root -> list((root["album"] as? JsonObject)?.get("song")).mapNotNull { song(it) } }

    fun parseArtistAlbums(body: String): Result<List<Named>> =
        response(body).map { root -> list((root["artist"] as? JsonObject)?.get("album")).mapNotNull { named(it) } }

    /**
     * Picks what to play. When the request is just an artist's or an album's name, the whole artist or album; otherwise
     * the best song (Navidrome ranks them). [fetchAlbum] loads an album's songs, [fetchArtistAlbums] an artist's albums.
     */
    suspend fun plan(
        query: String,
        result: SearchResult,
        fetchAlbum: suspend (String) -> List<Song>,
        fetchArtistAlbums: suspend (String) -> List<Named>,
    ): PlayPlan? {
        // A query of punctuation alone has no key: an exact match would then take any name without one.
        val q = key(query).ifEmpty { null }
        result.artists.firstOrNull { key(it.name) == q }?.let { artist ->
            val songs = fetchArtistAlbums(artist.id).take(MAX_ARTIST_ALBUMS).flatMap { fetchAlbum(it.id) }
                .ifEmpty { result.songs.filter { key(it.artist) == q } }
            if (songs.isNotEmpty()) return PlayPlan(songs.take(MAX_QUEUE).shuffled(), PlayPlan.Kind.Artist, artist.name)
        }
        // A song with exactly that title wins over an album of the same name ("Bohemian Rhapsody" is both).
        result.songs.firstOrNull { key(it.title) == q }?.let { return PlayPlan(listOf(it), PlayPlan.Kind.Song, it.title) }
        result.albums.firstOrNull { key(it.name) == q }?.let { album ->
            val songs = fetchAlbum(album.id)
            if (songs.isNotEmpty()) return PlayPlan(songs, PlayPlan.Kind.Album, album.name)
        }
        val song = result.songs.firstOrNull() ?: return null
        return PlayPlan(listOf(song), PlayPlan.Kind.Song, song.title)
    }

    /** Comparable form of a name: lowercase, no accents, no punctuation; letters of any script are kept. */
    fun key(text: String): String {
        val noMarks = Regex("\\p{Mn}+").replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
        return Regex("[^\\p{L}\\p{N}]+").replace(noMarks, " ").trim()
    }

    private fun base(account: MusicAccount) = account.baseUrl.trimEnd('/')
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun list(e: JsonElement?): List<JsonObject> = when (e) {
        is JsonArray -> e.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(e) // some servers send a single object instead of a one-item array
        else -> emptyList()
    }
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    private fun named(o: JsonObject) = str(o, "id")?.let { id -> Named(id, str(o, "name") ?: str(o, "title") ?: "") }
    private fun song(o: JsonObject): Song? {
        val id = str(o, "id") ?: return null
        val fields = o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        return Song(id, str(o, "title") ?: "", str(o, "artist") ?: "", str(o, "album") ?: "", str(o, "coverArt"), fields)
    }

    internal fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val MAX_QUEUE = 100
    private const val MAX_ARTIST_ALBUMS = 8
}
