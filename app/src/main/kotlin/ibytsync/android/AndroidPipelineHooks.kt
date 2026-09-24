package ibytsync.android

import android.content.Context
import android.util.Log
import ibytsync.core.download.YtDlpEngine
import ibytsync.core.matching.YtCandidate
import ibytsync.core.pipeline.PipelineHooks
import ibytsync.core.settings.SettingsStore
import ibytsync.core.upload.IBroadcastOAuth
import ibytsync.core.upload.LibrarySnapshot
import ibytsync.core.upload.Playlist
import java.io.File

class AndroidPipelineHooks(
    private val context: Context,
    private val engine: YtDlpEngine,
    private val settings: SettingsStore
) : PipelineHooks {

    private var cachedSnapshot: LibrarySnapshot? = null

    private val musicSearch = ibytsync.core.metadata.YouTubeMusicSearch()

    /**
     * YouTube Music first: it labels the album track, so the released master can be chosen
     * outright. Plain video search is the fallback for tracks YouTube Music does not carry —
     * it is the only path available there, but it has to guess from titles and durations.
     */
    override fun search(query: String, count: Int): List<YtCandidate> {
        return try {
            val songs = musicSearch.search(query, count)
            if (songs.isNotEmpty()) songs else engine.searchCandidates(query, count)
        } catch (e: Exception) {
            Log.e("PipelineHooks", "Search error: ${e.message}", e)
            emptyList()
        }
    }

    override fun uploadFile(tmp: File, playlistId: String?): Boolean =
        uploadFileWithResult(tmp, playlistId).success

    override fun uploadFileWithResult(
        tmp: File,
        playlistId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): ibytsync.core.pipeline.UploadOutcome = uploadFileWithResult(tmp, playlistId, null, onProgress)

    override fun uploadFileWithResult(
        tmp: File,
        playlistId: String?,
        md5: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): ibytsync.core.pipeline.UploadOutcome =
        uploadFileWithResult(tmp, listOfNotNull(playlistId), md5, onProgress)

    override fun uploadFileWithResult(
        tmp: File,
        playlistIds: List<String>,
        md5: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): ibytsync.core.pipeline.UploadOutcome {
        val clientId = settings.getClientId()
        val accessToken = settings.getAccessToken()
        val refreshToken = settings.getRefreshToken()

        if (accessToken.isBlank()) {
            Log.w("PipelineHooks", "No iBroadcast access token; skipping upload.")
            return ibytsync.core.pipeline.UploadOutcome(false)
        }

        var uploadedTrackId: String? = null
        val success = try {
            IBroadcastOAuth.withRefreshOnce(
                clientId = clientId,
                refreshToken = refreshToken,
                accessToken = accessToken,
                onNewTokens = { renewed ->
                    settings.setTokens(renewed.accessToken, renewed.refreshToken, renewed.expiresIn)
                }
            ) { token ->
                val uploadResult = IBroadcastOAuth.uploadTest(token, tmp, onProgress, knownMd5 = md5)
                Log.i("PipelineHooks", "Upload result: $uploadResult")
                val ok = uploadResult.contains("http=200") || uploadResult.contains("upload http=200")
                if (ok) {
                    uploadedTrackId = IBroadcastOAuth.extractTrackId(uploadResult)
                    if (!uploadedTrackId.isNullOrBlank()) {
                        val validIds = playlistIds.filter { it.isNotBlank() && it != "lib_only" }.distinct()
                        for (pid in validIds) {
                            try {
                                IBroadcastOAuth.addToPlaylist(token, pid, listOf(uploadedTrackId!!))
                            } catch (e: Exception) {
                                Log.e("PipelineHooks", "Failed adding to playlist $pid: ${e.message}", e)
                            }
                        }
                    }
                }
                ok
            }
        } catch (e: Exception) {
            Log.e("PipelineHooks", "Upload error: ${e.message}", e)
            false
        }
        return ibytsync.core.pipeline.UploadOutcome(success, uploadedTrackId)
    }

    override fun trashTracks(trackIds: List<String>): Boolean {
        val accessToken = settings.getAccessToken()
        if (accessToken.isBlank() || trackIds.isEmpty()) return false
        return try {
            IBroadcastOAuth.trashTracks(accessToken, trackIds)
        } catch (e: Exception) {
            Log.e("PipelineHooks", "Failed trashing tracks: ${e.message}", e)
            false
        }
    }

    override fun libraryPairs(): Map<String, Pair<String, String>> {
        return cachedSnapshot?.pairs ?: emptyMap()
    }

    override fun libraryChecksums(): Set<String> {
        return cachedSnapshot?.checksums ?: emptySet()
    }

    override fun playlists(): Map<String, Playlist> {
        return cachedSnapshot?.playlists ?: emptyMap()
    }

    override fun createPlaylist(name: String): Playlist? {
        val accessToken = settings.getAccessToken()
        if (accessToken.isBlank() || name.isBlank()) return null
        return try {
            val pl = IBroadcastOAuth.createPlaylist(accessToken, name)
            if (pl != null) {
                addPlaylistToCache(pl)
            }
            pl
        } catch (e: Exception) {
            Log.e("PipelineHooks", "Failed creating playlist '$name': ${e.message}", e)
            null
        }
    }

    private val _playlistsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Playlist>>(emptyList())
    val playlistsFlow: kotlinx.coroutines.flow.StateFlow<List<Playlist>> = _playlistsFlow

    private val _snapshotFlow = kotlinx.coroutines.flow.MutableStateFlow<LibrarySnapshot?>(null)
    val snapshotFlow: kotlinx.coroutines.flow.StateFlow<LibrarySnapshot?> = _snapshotFlow

    override fun libraryTracks(): Collection<ibytsync.core.upload.LibraryTrackInfo> {
        return cachedSnapshot?.tracks?.values ?: emptyList()
    }

    fun updateLibraryCache(snapshot: LibrarySnapshot?) {
        cachedSnapshot = snapshot
        _snapshotFlow.value = snapshot
        _playlistsFlow.value = snapshot?.playlists?.values?.toList() ?: emptyList()
    }

    fun addPlaylistToCache(playlist: Playlist) {
        val currentSnap = cachedSnapshot ?: LibrarySnapshot(null, emptyMap(), emptySet(), emptyMap())
        val updatedPlaylists = currentSnap.playlists + (playlist.id to playlist)
        val updatedSnap = currentSnap.copy(playlists = updatedPlaylists)
        updateLibraryCache(updatedSnap)
    }
}
