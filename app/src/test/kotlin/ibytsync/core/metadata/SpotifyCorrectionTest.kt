package ibytsync.core.metadata

import org.junit.Assert.*
import org.junit.Test

class SpotifyCorrectionTest {

    private val sampleGraphQLResponse = """
        {
          "data": {
            "searchV2": {
              "tracksV2": {
                "items": [
                  {
                    "item": {
                      "data": {
                        "name": "Stranger Things",
                        "artists": {
                          "items": [
                            { "profile": { "name": "Kyle Dixon & Michael Stein" } }
                          ]
                        },
                        "albumOfTrack": {
                          "name": "Stranger Things, Vol. 1 (a Netflix Original Series Soundtrack)",
                          "coverArt": {
                            "sources": [
                              { "url": "https://i.scdn.co/image/ab67616d0000b273small", "width": 300, "height": 300 },
                              { "url": "https://i.scdn.co/image/ab67616d0000b273large", "width": 640, "height": 640 }
                            ]
                          },
                          "date": { "year": 2016 }
                        },
                        "duration": { "totalMilliseconds": 67500 },
                        "trackNumber": 1
                      }
                    }
                  }
                ]
              }
            }
          }
        }
    """.trimIndent()

    private val sampleWolfResponse = """
        {
          "results": [
            {
              "title": "Stranger Things",
              "artist": "Kyle Dixon & Michael Stein",
              "album": "Stranger Things, Vol. 1",
              "thumbnail": "https://i.scdn.co/image/ab67616d0000b273wolf",
              "duration_ms": 67500,
              "release_date": "2016-08-12",
              "track_number": 1
            }
          ]
        }
    """.trimIndent()

    @Test
    fun computeTotpGeneratesSixDigitCode() {
        val otp = SpotifyCorrection.computeTotp(1710000000L)
        assertEquals(6, otp.length)
        assertTrue(otp.all { it.isDigit() })
    }

    @Test
    fun parseServerTimeExtractsTimestamp() {
        val sec = SpotifyCorrection.parseServerTime("""{"serverTime":1712345678}""")
        assertEquals(1712345678L, sec)
        assertNull(SpotifyCorrection.parseServerTime(null))
        assertNull(SpotifyCorrection.parseServerTime("invalid json"))
    }

    @Test
    fun parseTokenResponseExtractsTokenAndExpiry() {
        val body = """{"accessToken":"test-token-123","accessTokenExpirationTimestampMs":1712350000000}"""
        val (token, exp) = SpotifyCorrection.parseTokenResponse(body)
        assertEquals("test-token-123", token)
        assertEquals(1712350000000L, exp)
    }

    @Test
    fun parseGraphQLTracksExtractsFieldsAndLargestCover() {
        val tracks = SpotifyCorrection.parseGraphQLTracks(sampleGraphQLResponse)
        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("Kyle Dixon & Michael Stein", track.artist)
        assertEquals("Stranger Things", track.trackTitle)
        assertEquals("Stranger Things, Vol. 1 (a Netflix Original Series Soundtrack)", track.album)
        assertEquals("https://i.scdn.co/image/ab67616d0000b273large", track.artworkUrl)
        assertEquals(67500L, track.durationMs)
        assertEquals("2016", track.year)
        assertEquals(1, track.trackNumber)
        assertEquals("spotify", track.source)
    }

    @Test
    fun parseWolfResultsExtractsCandidates() {
        val tracks = SpotifyCorrection.parseWolfResults(sampleWolfResponse)
        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("Kyle Dixon & Michael Stein", track.artist)
        assertEquals("Stranger Things", track.trackTitle)
        assertEquals("Stranger Things, Vol. 1", track.album)
        assertEquals("https://i.scdn.co/image/ab67616d0000b273wolf", track.artworkUrl)
        assertEquals(67500L, track.durationMs)
        assertEquals("2016", track.year)
        assertEquals(1, track.trackNumber)
        assertEquals("spotify", track.source)
    }

    @Test
    fun candidatesUsesGraphQLWhenTokenAvailable() {
        val fetcher = object : SpotifyCorrectionFetcher {
            override fun get(url: String, headers: Map<String, String>): String? {
                if ("server-time" in url) return """{"serverTime":1710000000}"""
                if ("token" in url) return """{"accessToken":"fake-token","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3600000}}"""
                return null
            }
            override fun post(url: String, body: String, headers: Map<String, String>): String? {
                if ("pathfinder" in url) return sampleGraphQLResponse
                return null
            }
        }
        val client = SpotifyCorrection(fetcher)
        val results = client.candidates("Kyle Dixon", "Stranger Things")
        assertEquals(1, results.size)
        assertEquals("Stranger Things", results[0].trackTitle)
    }

    @Test
    fun candidatesFallsBackToWolfWhenGraphQLFails() {
        val fetcher = object : SpotifyCorrectionFetcher {
            override fun get(url: String, headers: Map<String, String>): String? {
                if ("xwolf.space" in url) return sampleWolfResponse
                return null
            }
            override fun post(url: String, body: String, headers: Map<String, String>): String? = null
        }
        val client = SpotifyCorrection(fetcher)
        val results = client.candidates("Kyle Dixon", "Stranger Things")
        assertEquals(1, results.size)
        assertEquals("Stranger Things", results[0].trackTitle)
    }

    @Test
    fun candidatesNeverThrowsOnNetworkFailure() {
        val failingFetcher = object : SpotifyCorrectionFetcher {
            override fun get(url: String, headers: Map<String, String>): String? = throw RuntimeException("network down")
            override fun post(url: String, body: String, headers: Map<String, String>): String? = throw RuntimeException("network down")
        }
        val client = SpotifyCorrection(failingFetcher)
        val results = client.candidates("test", "test")
        assertTrue(results.isEmpty())
    }
}
