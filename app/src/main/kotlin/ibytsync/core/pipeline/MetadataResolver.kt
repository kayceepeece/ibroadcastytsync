package ibytsync.core.pipeline

import ibytsync.core.download.YtDlpEngine
import ibytsync.core.metadata.CorrectionCandidate
import ibytsync.core.metadata.CorrectionClient
import ibytsync.core.metadata.ITunesCorrection
import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.metadata.SpotifyCorrection
import ibytsync.core.metadata.SpotifyScraper
import ibytsync.core.metadata.UrlClassifier
import ibytsync.core.metadata.UrlKind
import ibytsync.core.metadata.YtSuggestionBuilder
import ibytsync.core.metadata.YtTitleSplitter
import ibytsync.core.metadata.groupReleases
import ibytsync.core.pipeline.RowStates.afterSpotifyAlbumFetch
import ibytsync.core.pipeline.RowStates.afterSpotifyTrackFetch
import ibytsync.core.pipeline.RowStates.afterYouTubeSuggest
import java.io.File

/**
 * Production metadata-resolution step for pasted URLs and local files.
 *
 * Drives rows from [RowStatus.FETCHING_METADATA] to their next state:
 * - Local audio files -> query iTunes/Deezer correction clients; if official
 *   editions found, lands at [RowStatus.AWAITING_ACCEPT] with store options,
 *   original tags, and raw/no-metadata options. If no store matches found,
 *   preserves local tags and lands at [RowStatus.METADATA_READY].
 * - [UrlKind.Invalid] -> `Skipped — bad URL` immediately, no network.
 * - [UrlKind.SpotifyTrack]/[UrlKind.SpotifyAlbum] -> scraper fetch with
 *   scraped release and no-metadata options populated.
 * - [UrlKind.YouTube] -> `describeVideo` + [YtSuggestionBuilder]; suggestions
 *   include store releases, raw video info, and no-metadata options.
 *
 * Rows not in [RowStatus.FETCHING_METADATA] pass through untouched. Never throws.
 */
class MetadataResolver(
    private val scraper: SpotifyScraper,
    private val downloader: YtDlpEngine,
    private val correctionClients: List<CorrectionClient> = emptyList(),
    private val httpScraper: ibytsync.core.metadata.YouTubeHttpScraper? = null,
    private val musicSearch: ibytsync.core.metadata.YouTubeMusicSearch? = null
) {
    fun resolve(row: QueueRow): RowOutcome {
        if (row.status != RowStatus.FETCHING_METADATA) return RowOutcome.Ready(row)
        return try {
            if (isLocalSource(row.sourceInput)) {
                resolveLocal(row)
            } else {
                when (val kind = UrlClassifier.classify(row.sourceInput)) {
                    is UrlKind.Invalid ->
                        RowOutcome.Ready(row.copy(status = RowStatus.SKIPPED_BAD_URL))
                    is UrlKind.SpotifyTrack -> {
                        val meta = try {
                            scraper.getTrackMetadata(kind.id)
                        } catch (_: Exception) {
                            null
                        }
                        if (meta == null) {
                            RowOutcome.Ready(row.afterSpotifyTrackFetch(null))
                        } else {
                            val base = row.afterSpotifyTrackFetch(meta)
                            val spotifyCand = CorrectionCandidate(
                                artist = meta.artist,
                                trackTitle = meta.trackTitle,
                                album = meta.album.ifBlank { meta.trackTitle },
                                artworkUrl = meta.coverUrl,
                                source = "spotify",
                                durationMs = meta.durationMs
                            )
                            val scored = mutableListOf<Pair<CorrectionCandidate, Int>>()
                            scored.add(spotifyCand to 100)

                            if (correctionClients.isNotEmpty()) {
                                for (client in correctionClients) {
                                    if (client is SpotifyCorrection) continue
                                    val cands = try {
                                        client.candidates(meta.artist, meta.trackTitle)
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                    for (cand in cands) {
                                        val score = try {
                                            ITunesCorrection.comatchScore(
                                                "${meta.artist} ${meta.trackTitle}",
                                                cand.artist,
                                                cand.trackTitle,
                                                cand.durationMs,
                                                meta.durationMs,
                                                cand.album
                                            )
                                        } catch (_: Exception) {
                                            0
                                        }
                                        if (score >= 60) scored.add(cand to score)
                                    }
                                }
                            }

                            val merged = groupReleases(scored)
                            val spotifyOpt = merged.firstOrNull() ?: ReleaseOption(
                                artist = meta.artist,
                                trackTitle = meta.trackTitle,
                                album = meta.album.ifBlank { meta.trackTitle },
                                artworkUrl = meta.coverUrl,
                                durationMs = meta.durationMs,
                                isrc = null,
                                sources = listOf("spotify"),
                                score = 100
                            )
                            val noMetaOpt = ReleaseOption.noMetadata(meta.trackTitle, meta.durationMs)
                            val allOptions = (merged + listOf(noMetaOpt)).distinctBy { it.sources to it.trackTitle to it.album }
                            RowOutcome.Ready(
                                base.copy(
                                    coverUrl = spotifyOpt.artworkUrl ?: base.coverUrl,
                                    options = allOptions,
                                    selectedOption = spotifyOpt
                                )
                            )
                        }
                    }
                    is UrlKind.SpotifyAlbum -> {
                        val meta = try {
                            scraper.getAlbumMetadata(kind.id)
                        } catch (_: Exception) {
                            null
                        }
                        if (meta == null) {
                            RowOutcome.Ready(row.afterSpotifyAlbumFetch(null))
                        } else {
                            val base = row.afterSpotifyAlbumFetch(meta)
                            val albumOpt = ReleaseOption(
                                artist = meta.artist,
                                trackTitle = meta.album,
                                album = meta.album,
                                artworkUrl = null,
                                durationMs = null,
                                isrc = null,
                                sources = listOf("spotify"),
                                score = 100
                            )
                            val noMetaOpt = ReleaseOption.noMetadata(meta.album)
                            RowOutcome.Ready(
                                base.copy(
                                    options = listOf(albumOpt, noMetaOpt),
                                    selectedOption = albumOpt
                                )
                            )
                        }
                    }
                    is UrlKind.YouTube -> resolveYouTube(row, kind.rawUrl)
                    is UrlKind.YouTubeWatchWithPlaylist -> resolveYouTube(row, kind.rawUrl)
                    is UrlKind.SpotifyPlaylist, is UrlKind.YouTubePlaylist -> RowOutcome.Ready(row)
                }
            }
        } catch (_: Exception) {
            RowOutcome.Ready(row.copy(status = RowStatus.FAILED_METADATA))
        }
    }

    private fun isLocalSource(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.startsWith("content://", ignoreCase = true)) return true
        if (trimmed.startsWith("file://", ignoreCase = true)) return true
        if (trimmed.startsWith("/") && (File(trimmed).exists() || trimmed.contains("."))) return true
        return false
    }

    private fun resolveLocal(row: QueueRow): RowOutcome {
        val originalOpt = row.selectedOption ?: ReleaseOption.originalFile(
            title = row.title.ifBlank { "Local Audio Track" },
            artist = row.artist,
            album = row.album.ifBlank { row.title.ifBlank { "Local File" } },
            artworkUrl = row.coverUrl,
            durationMs = row.durationMs,
            year = row.year,
            genre = row.genre,
            trackNumber = row.trackNumber,
            trackCount = row.trackCount
        )
        val noMetaOpt = ReleaseOption.noMetadata(
            trackTitle = row.title.ifBlank { "Local Audio Track" },
            durationMs = row.durationMs
        )

        val (splitArtist, splitTitle) = if (row.artist.isBlank()) {
            YtTitleSplitter.split(row.title, "")
        } else {
            row.artist to row.title
        }
        val searchArtist = if (row.artist.isNotBlank()) row.artist else splitArtist
        val searchTitle = if (row.artist.isNotBlank()) row.title else splitTitle
        val query = listOf(searchArtist, searchTitle).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        val scored = mutableListOf<Pair<CorrectionCandidate, Int>>()
        if (query.isNotBlank() && correctionClients.isNotEmpty()) {
            for (client in correctionClients) {
                val cands = try {
                    client.candidates(searchArtist, searchTitle)
                } catch (_: Exception) {
                    emptyList()
                }
                for (cand in cands) {
                    val score = try {
                        ITunesCorrection.comatchScore(
                            query, cand.artist, cand.trackTitle, cand.durationMs, row.durationMs, cand.album
                        )
                    } catch (_: Exception) {
                        0
                    }
                    if (score >= 60) scored.add(cand to score)
                }
            }
        }

        val fetched = groupReleases(scored)
        val allOptions = fetched + listOf(originalOpt, noMetaOpt)

        return if (fetched.isNotEmpty()) {
            val top = fetched.first()
            val next = row.copy(
                status = RowStatus.AWAITING_ACCEPT,
                title = top.trackTitle,
                artist = top.artist,
                album = top.album.ifBlank { top.trackTitle },
                durationMs = top.durationMs ?: row.durationMs,
                coverUrl = top.artworkUrl ?: row.coverUrl,
                options = allOptions,
                selectedOption = top,
                year = top.year,
                genre = top.genre,
                trackNumber = top.trackNumber,
                trackCount = top.trackCount,
                detail = "${fetched.size} official releases found • Select edition or keep original"
            )
            RowOutcome.Ready(next)
        } else {
            val next = row.copy(
                status = RowStatus.METADATA_READY,
                title = originalOpt.trackTitle,
                artist = originalOpt.artist,
                album = originalOpt.album,
                durationMs = originalOpt.durationMs,
                coverUrl = originalOpt.artworkUrl,
                options = listOf(originalOpt, noMetaOpt),
                selectedOption = originalOpt,
                detail = "Original file tags ready"
            )
            RowOutcome.Ready(next)
        }
    }

    private fun resolveYouTube(row: QueueRow, youtubeUrl: String): RowOutcome {
        val extract = try {
            httpScraper?.describe(youtubeUrl) ?: downloader.describeVideo(youtubeUrl)
        } catch (_: Exception) {
            try {
                downloader.describeVideo(youtubeUrl)
            } catch (_: Exception) {
                null
            }
        }
        val effectiveDurationMs = extract?.durationMs
            ?: try { downloader.describeVideo(youtubeUrl)?.durationMs } catch (_: Exception) { null }
            ?: row.durationMs

        val suggestion = if (extract == null) {
            null
        } else {
            try {
                YtSuggestionBuilder.build(extract.copy(durationMs = effectiveDurationMs), *correctionClients.toTypedArray())
            } catch (_: Exception) {
                null
            }
        }
        val next = row.afterYouTubeSuggest(suggestion)
        val rawVideoOpt = extract?.let {
            ReleaseOption.rawVideo(it.videoTitle, it.channel, durationMs = effectiveDurationMs)
        }
        val noMetaOpt = ReleaseOption.noMetadata(
            extract?.videoTitle ?: row.title,
            effectiveDurationMs
        )
        val fullOptions = (next.options + listOfNotNull(rawVideoOpt, noMetaOpt))
            .distinctBy { it.sources to it.trackTitle to it.album }

        val officialSearchArtist = (
            suggestion?.correction?.artist
                ?: next.selectedOption?.artist?.takeIf { !next.selectedOption.isOriginalSource && !next.selectedOption.isNoMetadata }
                ?: next.artist
        ).trim()
        val officialSearchTitle = (
            suggestion?.correction?.trackTitle
                ?: next.selectedOption?.trackTitle?.takeIf { !next.selectedOption.isOriginalSource && !next.selectedOption.isNoMetadata }
                ?: next.title
        ).trim()

        val albumTrack = findOfficialAudio(officialSearchArtist, officialSearchTitle, effectiveDurationMs)
        val enrichedNext = next.copy(
            options = fullOptions,
            selectedOption = next.selectedOption ?: rawVideoOpt ?: noMetaOpt,
            officialAudioId = albumTrack?.id,
            officialAudioDurationMs = albumTrack?.duration?.toLong()?.times(1000L)
        )

        return if (enrichedNext.status == RowStatus.AWAITING_ACCEPT && suggestion != null) {
            RowOutcome.AwaitingPick(enrichedNext, suggestion)
        } else {
            RowOutcome.Ready(enrichedNext)
        }
    }

    /**
     * Finds the confirmed YouTube Music album track for the given song.
     */
    fun findOfficialAudio(artist: String, title: String, videoDurationMs: Long?): ibytsync.core.matching.YtCandidate? {
        val searcher = musicSearch ?: return null
        if (title.isBlank()) return null
        val query = if (artist.isNotBlank()) "$artist $title".trim() else title.trim()
        val candidates = try {
            val fullResults = searcher.search(query, OFFICIAL_AUDIO_POOL)
            if (fullResults.isNotEmpty()) {
                fullResults
            } else if (artist.isNotBlank()) {
                val cleanArtist = artist.split(Regex("[,&]|\\bfeat\\.?\\b", RegexOption.IGNORE_CASE)).firstOrNull()?.trim() ?: artist
                searcher.search("$cleanArtist $title".trim(), OFFICIAL_AUDIO_POOL)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        val albumTrack = ibytsync.core.matching.bestOfficialAudio(
            title = title,
            artist = artist,
            nearMs = videoDurationMs ?: 0L,
            candidates = candidates
        ) ?: return null
        val albumMs = (albumTrack.duration * 1000L).toLong()
        if (albumMs <= 0L) return null
        return albumTrack
    }

    private companion object {
        const val OFFICIAL_AUDIO_POOL = 10
    }
}
