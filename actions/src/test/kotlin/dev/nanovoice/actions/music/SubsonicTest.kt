package dev.nanovoice.actions.music

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsonicTest {
    private val account = MusicAccount("https://music.example.org/", "demo", "sesame")

    @Test fun tokenAuthNeverSendsThePassword() {
        // Subsonic spec example: password "sesame", salt "c19b2d" -> token 26719a1196d2a940705a59634eb18eab
        val params = Subsonic.authParams(account, "c19b2d")
        assertEquals("u=demo&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=NanoVoice", params)
        assertTrue("sesame" !in Subsonic.streamUrl(account, "c19b2d", "x"))
    }

    @Test fun urlsAreEncoded() {
        val url = Subsonic.searchUrl(account, "abc", "bohemian rhapsody & co")
        assertTrue(url.startsWith("https://music.example.org/rest/search3?"))
        assertTrue("query=bohemian+rhapsody+%26+co" in url)
    }

    private val search = """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{
        "artist":[{"id":"ar1","name":"Queen"}],
        "album":[{"id":"al1","name":"A Night at the Opera"}],
        "song":[{"id":"s1","title":"Bohemian Rhapsody","artist":"Queen","album":"A Night at the Opera","coverArt":"al1"},
                {"id":"s2","title":"Bohemian Like You","artist":"The Dandy Warhols","album":"Thirteen Tales"}]}}}"""

    @Test fun parsesSearchResults() {
        val r = Subsonic.parseSearch(search).getOrThrow()
        assertEquals(listOf(Named("ar1", "Queen")), r.artists)
        assertEquals("Bohemian Rhapsody", r.songs.first().title)
        assertEquals(2, r.songs.size)
    }

    @Test fun reportsServerErrors() {
        val r = Subsonic.parseSearch("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""")
        assertEquals("Wrong username or password", r.exceptionOrNull()?.message)
        assertTrue(Subsonic.parseSearch("<html>").isFailure)
    }

    private fun plan(query: String) = runBlocking {
        Subsonic.plan(
            query,
            Subsonic.parseSearch(search).getOrThrow(),
            fetchAlbum = { id -> if (id == "al1") listOf(Song("s1", "Bohemian Rhapsody", "Queen", "A Night at the Opera", null), Song("s3", "Love of My Life", "Queen", "A Night at the Opera", null)) else emptyList() },
            fetchArtistAlbums = { id -> if (id == "ar1") listOf(Named("al1", "A Night at the Opera")) else emptyList() },
        )
    }

    @Test fun namesInOtherScriptsKeepTheirLetters() {
        assertEquals("東京事変", Subsonic.key("東京事変!"))
        // Marks go in every script, as accents do: the query and the name lose the same ones.
        assertEquals("мумии тролль", Subsonic.key("Мумий Тролль"))
        assertEquals("beyonce", Subsonic.key("Beyoncé"))
    }

    @Test fun aTitlePlaysTheBestSong() {
        val p = plan("bohemian rhapsody queen")!!
        assertEquals(PlayPlan.Kind.Song, p.kind)
        assertEquals(listOf("s1"), p.songs.map { it.id })
    }

    @Test fun anAlbumNamePlaysTheWholeAlbumInOrder() {
        val p = plan("a night at the opera")!!
        assertEquals(PlayPlan.Kind.Album, p.kind)
        assertEquals(listOf("s1", "s3"), p.songs.map { it.id })
    }

    @Test fun anArtistNamePlaysTheArtist() {
        val p = plan("Queen")!!
        assertEquals(PlayPlan.Kind.Artist, p.kind)
        assertEquals(setOf("s1", "s3"), p.songs.map { it.id }.toSet())
    }

    @Test fun aSongBeatsAnAlbumOfTheSameName() {
        val song = Song("s1", "Bohemian Rhapsody", "Queen", "A Night at the Opera", null)
        val result = SearchResult(emptyList(), listOf(Named("al9", "Bohemian Rhapsody (The Original Soundtrack)"), Named("al8", "Bohemian Rhapsody")), listOf(Song("s9", "20th Century Fox Fanfare", "Queen", "Bohemian Rhapsody", null), song))
        val p = runBlocking { Subsonic.plan("Bohemian Rhapsody", result, { listOf(Song("s9", "20th Century Fox Fanfare", "Queen", "Bohemian Rhapsody", null)) }, { emptyList() }) }!!
        assertEquals(PlayPlan.Kind.Song, p.kind)
        assertEquals(listOf("s1"), p.songs.map { it.id })
    }

    @Test fun nothingFound() {
        assertNull(runBlocking { Subsonic.plan("x", SearchResult(emptyList(), emptyList(), emptyList()), { emptyList() }, { emptyList() }) })
    }
}
