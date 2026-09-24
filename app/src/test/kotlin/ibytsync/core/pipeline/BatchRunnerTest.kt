package ibytsync.core.pipeline

import ibytsync.core.download.DownloadRequest
import ibytsync.core.download.DownloadResult
import ibytsync.core.download.YtDlpEngine
import ibytsync.core.matching.YtCandidate
import ibytsync.core.metadata.YtExtract
import ibytsync.core.upload.BatchSummary
import ibytsync.core.upload.Playlist
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BatchRunnerTest {

    private fun quickEngine(): YtDlpEngine = object : YtDlpEngine {
        override fun download(request: DownloadRequest): DownloadResult? {
            val f = File.createTempFile("run", ".mp3")
            f.writeBytes(ByteArray(256) { 0 })
            return DownloadResult(f.absolutePath, "fake", 0)
        }
        override fun searchCandidates(query: String, count: Int): List<YtCandidate> =
            listOf(YtCandidate("id1", "Song Official Audio", "Artist", 213.0, 100))
        override fun describeVideo(url: String): YtExtract? = null
        override fun activeVariantLog(): List<String> = emptyList()
    }

    private fun quickHooks(): PipelineHooks = object : PipelineHooks {
        override fun search(query: String, count: Int): List<YtCandidate> =
            listOf(YtCandidate("id1", "Song Official Audio", "Artist", 213.0, 100))
        override fun uploadFile(tmp: File, playlistId: String?): Boolean = true
        override fun libraryPairs(): Map<String, Pair<String, String>> = emptyMap()
        override fun libraryChecksums(): Set<String> = emptySet()
        override fun playlists(): Map<String, Playlist> = emptyMap()
    }

    private val tmpDir: File = Files.createTempDirectory("runner-test").toFile()

    private fun readyRow(i: Int) = QueueRow("$i", "x$i", RowStatus.METADATA_READY, "Song$i", "Artist", "Album", 213_000L)

    @Test fun batchRunsRowsInOrderWithProgress() {
        val runner = BatchRunner({ BatchOrchestrator(quickEngine(), quickHooks()) }, tmpDir)
        val events = mutableListOf<BatchEvent>()
        runBlocking {
            val id = runner.runBatch(listOf(readyRow(1), readyRow(2)), BatchConfig()) { events.add(it) }
            assertTrue(id.startsWith("run_"))
            var guard = 0
            while (runner.isRunning() && guard++ < 200) delay(50)
        }
        val finished = events.filterIsInstance<BatchEvent.Finished>()
        assertEquals(1, finished.size)
        assertEquals(2, finished.single().summary.uploaded)
        val progress = events.filterIsInstance<BatchEvent.Progress>()
        assertTrue(progress.any { it.progress.done == 2 && it.progress.total == 2 })
        val updates = events.filterIsInstance<BatchEvent.RowUpdate>()
        assertTrue(updates.any { it.row.status == RowStatus.DONE })
    }

    @Test fun cancelMarksRemainingCancelled() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slowEngine = object : YtDlpEngine {
            override fun download(request: DownloadRequest): DownloadResult? {
                started.countDown()
                release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                val f = File.createTempFile("run", ".mp3")
                f.writeBytes(ByteArray(64) { 0 })
                return DownloadResult(f.absolutePath, "fake", 0)
            }
            override fun searchCandidates(query: String, count: Int): List<YtCandidate> =
                listOf(YtCandidate("id1", "Song", "Artist", 213.0, 100))
            override fun describeVideo(url: String): YtExtract? = null
            override fun activeVariantLog(): List<String> = emptyList()
        }
        val runner = BatchRunner({ BatchOrchestrator(slowEngine, quickHooks()) }, tmpDir)
        val events = java.util.Collections.synchronizedList(mutableListOf<BatchEvent>())
        runner.runBatch((1..4).map { readyRow(it) }, BatchConfig()) { events.add(it) }
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        runBlocking {
            runner.cancelAndJoin()
        }
        release.countDown()
        var guard = 0
        while (events.none { it is BatchEvent.Cancelled || it is BatchEvent.Finished } && guard++ < 150) {
            Thread.sleep(100)
        }
        Thread.sleep(500)
        val kinds = events.map { it.javaClass.simpleName }
        println("CANCEL kinds=$kinds")
        val cancelled = events.filterIsInstance<BatchEvent.Cancelled>()
        assertEquals(1, cancelled.size)
        val rows = cancelled.single().completedRows
        assertEquals(4, rows.size)
        assertTrue(rows.any { it.status == RowStatus.CANCELLED_UPLOAD })
        assertFalse(rows.any { it.status == RowStatus.QUEUED || it.status == RowStatus.METADATA_READY })
    }

    @Test fun dispatcherSerializesAndTimesOut() {
        var concurrent = 0
        var maxSeen = 0
        val engine = object : YtDlpEngine {
            override fun download(request: DownloadRequest): DownloadResult? {
                concurrent++
                maxSeen = maxOf(maxSeen, concurrent)
                Thread.sleep(200)
                concurrent--
                val f = File.createTempFile("run", ".mp3")
                return DownloadResult(f.absolutePath, "fake", 0)
            }
            override fun searchCandidates(query: String, count: Int): List<YtCandidate> = emptyList()
            override fun describeVideo(url: String): YtExtract? = null
            override fun activeVariantLog(): List<String> = emptyList()
        }
        val disp = SerialDownloadDispatcher(engine)
        runBlocking {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val jobs = (1..4).map { i: Int ->
                scope.async {
                    disp.download(ibytsync.core.download.DownloadRequest("q$i", "t$i"))
                }
            }
            jobs.forEach { it.await() }
            scope.cancel()
        }
        assertEquals(1, maxSeen)

        val hanging = object : YtDlpEngine {
            override fun download(request: DownloadRequest): DownloadResult? {
                var waited = 0
                while (waited < 5000) {
                    if (Thread.currentThread().isInterrupted) return null
                    Thread.sleep(100)
                    waited += 100
                }
                return null
            }
            override fun searchCandidates(query: String, count: Int): List<YtCandidate> = emptyList()
            override fun describeVideo(url: String): YtExtract? = null
            override fun activeVariantLog(): List<String> = emptyList()
        }
        val disp2 = SerialDownloadDispatcher(hanging)
        val start = System.currentTimeMillis()
        val res = runBlocking {
            withTimeoutOrNull(2500L) {
                disp2.download(ibytsync.core.download.DownloadRequest("q", "t"), timeoutMs = 300L)
            }
        }
        assertNull(res)
        assertTrue(System.currentTimeMillis() - start < 3000L)
    }

    @Test fun summaryMessage() {
        assertEquals("Done — 0 uploaded, 0 duplicates, 0 failed, 0 cancelled", BatchSummary().message())
        assertEquals("Done — 1 uploaded, 2 duplicates, 3 failed, 0 cancelled", BatchSummary(1, 2, 3).message())
    }
}
