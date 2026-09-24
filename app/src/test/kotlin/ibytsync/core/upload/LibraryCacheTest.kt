package ibytsync.core.upload

import org.junit.Assert.*
import org.junit.Test

class LibraryCacheTest {

    private fun libraryJson(): String = """
        {"result":true,"status":{"lastmodified":"2026-09-10 21:05:17"},
         "library":{
           "tracks":{
             "11":{"title":"Mrs Magic","artist":"Strawberry Guy","md5":"abc123"},
             "12":{"title":"Other","artist":"Someone","checksum":"DEF456"}
           },
           "playlists":{
             "9":{"name":"Fav"},
             "3":{"name":"chill"}
           }
         }}""".trimIndent()

    @Test fun parsesPairsChecksumsPlaylists() {
        val snap = LibraryCache.parseLibrary(libraryJson())!!
        assertEquals("2026-09-10 21:05:17", snap.lastModified)
        assertEquals("Mrs Magic" to "Strawberry Guy", snap.pairs["11"])
        assertTrue(snap.checksums.contains("abc123"))
        assertTrue(snap.checksums.contains("def456"))
        assertEquals(Playlist("9", "Fav"), snap.playlists["9"])
    }

    @Test fun parsesOfficialIBroadcastFormat() {
        val json = """
        {
          "result": true,
          "status": {"lastmodified": "2026-09-10 21:05:17"},
          "library": {
            "artists": {
              "map": {"name": 0, "tracks": 1},
              "22917502": ["Zoe.LeelA", [211504300]]
            },
            "tracks": {
              "map": {"track": 0, "year": 1, "title": 2, "genre": 3, "length": 4, "album_id": 5, "artwork_id": 6, "artist_id": 7, "md5": 8},
              "211504300": [1, 2013, "Pop Up", "Pop", 105, 78903103, 160155, 22917502, "abc123md5"]
            },
            "playlists": {
              "map": {"name": 0, "tracks": 1, "uid": 2},
              "329378": ["Starter Songs", [211504300], 1234],
              "555555": ["Favorites", [211504300], 1234]
            },
            "trash": {
              "map": {"name": 0, "tracks": 1},
              "0": ["Trash"]
            }
          }
        }
        """.trimIndent()

        val snap = LibraryCache.parseLibrary(json)!!
        assertEquals("2026-09-10 21:05:17", snap.lastModified)
        assertEquals("Pop Up" to "Zoe.LeelA", snap.pairs["211504300"])
        assertTrue(snap.checksums.contains("abc123md5"))
        assertEquals(2, snap.playlists.size)
        assertEquals("Starter Songs", snap.playlists["329378"]?.name)
        assertEquals("Favorites", snap.playlists["555555"]?.name)
        assertNull(snap.playlists["map"])
    }

    @Test fun garbageYieldsNull() {
        assertNull(LibraryCache.parseLibrary("not json"))
        assertNull(LibraryCache.parseLibrary("""{"result":true}"""))
    }

    @Test fun statusFreshness() {
        assertTrue(LibraryCache.isFresh(
            """{"result":true,"status":{"lastmodified":"2026-09-10 21:05:17"}}""",
            "2026-09-10 21:05:17"))
        assertFalse(LibraryCache.isFresh(
            """{"result":true,"status":{"lastmodified":"2026-09-10 22:00:00"}}""",
            "2026-09-10 21:05:17"))
        assertFalse(LibraryCache.isFresh("garbage", "2026-09-10 21:05:17"))
        assertFalse(LibraryCache.isFresh("""{"result":true,"status":{}}""", null))
        assertFalse(LibraryCache.isFresh("""{"result":true}""", "2026-09-10 21:05:17"))
    }

    @Test fun statusParsing() {
        val full = """{"result":true,"user":{"id":2236706,"user_id":2236706,"userid":2236706,
            "email_address":"private@example.com","session":{"sessions":[]}},
            "status":{"lastmodified":"2026-09-10 21:05:17"}}"""
        val info = LibraryCache.parseStatus(full)!!
        assertEquals("2236706", info.accountId)
        assertEquals("2026-09-10 21:05:17", info.lastModified)

        assertEquals("777", LibraryCache.parseStatus("""{"user":{"user_id":777}}""")?.accountId)
        assertEquals("888", LibraryCache.parseStatus("""{"user":{"userid":888}}""")?.accountId)
        assertEquals(null, LibraryCache.parseStatus("""{"user":{"id":9}}""")?.lastModified)
        assertNull(LibraryCache.parseStatus("""{"result":true}"""))
        assertNull(LibraryCache.parseStatus("""{"user":{}}"""))
        assertNull(LibraryCache.parseStatus("garbage"))
    }

    @Test fun pollResultMapping() {
        // A 400 + authorization_pending is the normal "not approved yet" reply. Treating
        // it as an error was the original bug: the user saw raw JSON as a false failure.
        assertTrue(IBroadcastOAuth.mapTokenResponse(
            400, """{"error":"authorization_pending"}""", 1) is PollResult.Pending)

        assertEquals(6, (IBroadcastOAuth.mapTokenResponse(
            400, """{"error":"slow_down"}""", 1) as PollResult.SlowDown).nextIntervalSec)

        val expired = IBroadcastOAuth.mapTokenResponse(
            400, """{"error":"expired_token","error_description":"code expired"}""", 1)
        assertTrue(expired is PollResult.Terminal)
        assertEquals("code expired", (expired as PollResult.Terminal).serverDetail)

        assertTrue(IBroadcastOAuth.mapTokenResponse(
            400, """{"error":"access_denied"}""", 1) is PollResult.Terminal)
        assertTrue(IBroadcastOAuth.mapTokenResponse(
            400, """{"error":"invalid_client"}""", 1) is PollResult.Terminal)

        val ok = IBroadcastOAuth.mapTokenResponse(
            200, """{"access_token":"at","refresh_token":"rt","expires_in":3599}""", 1)
        assertTrue(ok is PollResult.Success)
        assertEquals("at", (ok as PollResult.Success).tokens.accessToken)
        assertEquals(3599, ok.tokens.expiresIn)

        assertTrue(IBroadcastOAuth.mapTokenResponse(500, "boom", 1) is PollResult.Terminal)
        assertTrue(IBroadcastOAuth.mapTokenResponse(200, "not json", 1) is PollResult.Terminal)
    }

    @Test fun playlistBodiesAndParsing() {
        assertTrue(PlaylistMutations.createBody("My Mix").contains("My Mix"))
        val append = PlaylistMutations.appendBody("9", listOf("11", "12"))
        assertTrue(append.contains("9") && append.contains("11"))
        assertEquals("9", PlaylistMutations.parseCreate("""{"result":true,"playlist_id":"9"}"""))
        assertNull(PlaylistMutations.parseCreate("""{"result":false}"""))
        assertTrue(PlaylistMutations.parseAppend("""{"result":true}"""))
        assertFalse(PlaylistMutations.parseAppend("""{"result":false}"""))
        assertFalse(PlaylistMutations.parseAppend("garbage"))
    }

    @Test fun playlistsSortAZ() {
        val sorted = PlaylistMutations.sortedPlaylists(
            mapOf("9" to Playlist("9", "Zebra"), "3" to Playlist("3", "apple")).values)
        assertEquals(listOf("apple", "Zebra"), sorted.map { it.name })
    }

    @Test fun staleFavoriteDetection() {
        val pls = mapOf("9" to Playlist("9", "Fav"))
        assertTrue(UploadRouter.staleFavoriteFallback(RoutePreference.Favorite("gone", "G"), pls))
        assertFalse(UploadRouter.staleFavoriteFallback(RoutePreference.Favorite("9", "Fav"), pls))
        assertNull(UploadRouter.resolve(RoutePreference.Favorite("gone", "G"), pls))
    }

    @Test fun checksummerRoundtrip() {
        val f = java.io.File.createTempFile("md5", ".bin")
        try {
            f.writeBytes("hello".toByteArray())
            assertEquals("5d41402abc4b2a76b9719d911017c592", Checksummer.md5(f))
        } finally {
            f.delete()
        }
    }
}
