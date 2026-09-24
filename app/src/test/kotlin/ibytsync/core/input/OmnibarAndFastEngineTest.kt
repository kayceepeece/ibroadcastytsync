package ibytsync.core.input

import ibytsync.core.metadata.YouTubeHttpScraper
import ibytsync.core.metadata.YouTubeHttpSearch
import ibytsync.core.metadata.YouTubeUrlHelper
import ibytsync.core.pipeline.BatchViewModel
import org.junit.Assert.*
import org.junit.Test

class OmnibarAndFastEngineTest {

    @Test
    fun testUrlBatchExtractor() {
        val single = "https://www.youtube.com/watch?v=d_HlPboLRL8"
        assertEquals(listOf(single), UrlBatchExtractor.extractAudioUrls(single))

        val musicYt = "https://music.youtube.com/watch?v=d_HlPboLRL8"
        assertEquals(listOf(musicYt), UrlBatchExtractor.extractAudioUrls(musicYt))

        val multiLine = """
            https://youtu.be/ApXoWvfEYVU
            https://open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp
            https://music.youtube.com/watch?v=d_HlPboLRL8
        """.trimIndent()
        val extracted = UrlBatchExtractor.extractAudioUrls(multiLine)
        assertEquals(3, extracted.size)
        assertTrue(extracted.contains("https://youtu.be/ApXoWvfEYVU"))
        assertTrue(extracted.contains("https://open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp"))
        assertTrue(extracted.contains("https://music.youtube.com/watch?v=d_HlPboLRL8"))

        val noisyText = "Check this out https://youtu.be/ApXoWvfEYVU, and also this: https://music.youtube.com/watch?v=d_HlPboLRL8; cool right?"
        val noisyExtracted = UrlBatchExtractor.extractAudioUrls(noisyText)
        assertEquals(2, noisyExtracted.size)
        assertEquals("https://youtu.be/ApXoWvfEYVU", noisyExtracted[0])
        assertEquals("https://music.youtube.com/watch?v=d_HlPboLRL8", noisyExtracted[1])
    }

    @Test
    fun testOmnibarClassifier() {
        assertEquals(OmnibarIntent.Empty, OmnibarClassifier.classify(""))
        assertEquals(OmnibarIntent.Empty, OmnibarClassifier.classify("   "))

        val search = OmnibarClassifier.classify("post malone sunflower")
        assertTrue(search is OmnibarIntent.SearchQuery)
        assertEquals("post malone sunflower", (search as OmnibarIntent.SearchQuery).query)

        val singleYt = OmnibarClassifier.classify("https://music.youtube.com/watch?v=d_HlPboLRL8")
        assertTrue(singleYt is OmnibarIntent.SingleUrl)
        assertEquals("https://music.youtube.com/watch?v=d_HlPboLRL8", (singleYt as OmnibarIntent.SingleUrl).url)

        val batch = OmnibarClassifier.classify("https://youtu.be/1\nhttps://youtu.be/2")
        assertTrue(batch is OmnibarIntent.BatchUrls)
        assertEquals(2, (batch as OmnibarIntent.BatchUrls).urls.size)
    }

    @Test
    fun testYouTubeUrlHelper() {
        assertEquals("d_HlPboLRL8", YouTubeUrlHelper.extractVideoId("https://www.youtube.com/watch?v=d_HlPboLRL8"))
        assertEquals("d_HlPboLRL8", YouTubeUrlHelper.extractVideoId("https://music.youtube.com/watch?v=d_HlPboLRL8&feature=share"))
        assertEquals("ApXoWvfEYVU", YouTubeUrlHelper.extractVideoId("https://youtu.be/ApXoWvfEYVU"))
        assertEquals("12345678901", YouTubeUrlHelper.extractVideoId("https://www.youtube.com/shorts/12345678901"))
        assertEquals("https://www.youtube.com/watch?v=d_HlPboLRL8", YouTubeUrlHelper.canonicalWatchUrl("d_HlPboLRL8"))
    }

    @Test
    fun testYouTubeHttpScraperDurationParsing() {
        val scraper = YouTubeHttpScraper()

        val htmlWithLengthSeconds = "blah blah \"lengthSeconds\":\"250\" blah"
        assertEquals(250_000L, scraper.parseDurationMs(htmlWithLengthSeconds))

        val htmlWithMeta = """<meta itemprop="duration" content="PT4M10S">"""
        assertEquals(250_000L, scraper.parseDurationMs(htmlWithMeta))

        val htmlWithApprox = """"approxDurationMs":"250000""""
        assertEquals(250_000L, scraper.parseDurationMs(htmlWithApprox))

        assertEquals(250L, scraper.parseIsoDurationSeconds("4M10S"))
        assertEquals(3750L, scraper.parseIsoDurationSeconds("1H2M30S"))
        assertEquals(45L, scraper.parseIsoDurationSeconds("45S"))
    }

    @Test
    fun testYouTubeHttpSearchParsing() {
        val search = YouTubeHttpSearch()

        assertEquals(225.0, search.parseDurationSeconds("3:45"), 0.01)
        assertEquals(3735.0, search.parseDurationSeconds("1:02:15"), 0.01)
        assertEquals(45.0, search.parseDurationSeconds("45"), 0.01)

        assertEquals(1_200_000L, search.parseViewCount("1.2M views"))
        assertEquals(150_000L, search.parseViewCount("150K views"))
        assertEquals(1_500_000_000L, search.parseViewCount("1.5B views"))
        assertEquals(2450L, search.parseViewCount("2,450 views"))
    }

    @Test
    fun testBatchViewModelAddInputs() {
        val vm = BatchViewModel()
        assertEquals(0, vm.rows.value.size)

        vm.addInputs(listOf(
            "https://music.youtube.com/watch?v=d_HlPboLRL8",
            "https://youtu.be/ApXoWvfEYVU"
        ))
        assertEquals(2, vm.rows.value.size)
        assertEquals("https://music.youtube.com/watch?v=d_HlPboLRL8", vm.rows.value[0].sourceInput)
        assertEquals("https://youtu.be/ApXoWvfEYVU", vm.rows.value[1].sourceInput)

        // Test addInput with batch pasted text
        val vm2 = BatchViewModel()
        vm2.addInput("Check https://youtu.be/1 and https://youtu.be/2")
        assertEquals(2, vm2.rows.value.size)
        assertEquals("https://youtu.be/1", vm2.rows.value[0].sourceInput)
        assertEquals("https://youtu.be/2", vm2.rows.value[1].sourceInput)
    }
}
