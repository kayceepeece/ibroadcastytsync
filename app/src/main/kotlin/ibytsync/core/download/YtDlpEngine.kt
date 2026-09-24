package ibytsync.core.download

data class DownloadRequest(
    val queryOrUrl: String,
    val tokenPrefix: String,
    val audioQuality: String = "192K",
    val audioFormat: String = "mp3"
)

data class DownloadResult(
    val filePath: String,
    val variantUsed: String,
    val returnCode: Int
)

interface YtDlpEngine {
    fun download(request: DownloadRequest): DownloadResult?
    fun download(
        request: DownloadRequest,
        onProgress: ((progress: Float, eta: Long, line: String) -> Unit)?
    ): DownloadResult? = download(request)
    fun searchCandidates(query: String, count: Int): List<ibytsync.core.matching.YtCandidate>
    fun describeVideo(url: String): ibytsync.core.metadata.YtExtract?
    fun extractPlaylist(playlistUrl: String): List<ibytsync.core.matching.YtCandidate> = emptyList()
    fun activeVariantLog(): List<String>
    fun isReady(): Boolean = true
    fun cancelProcess(tokenPrefix: String) {}
}
