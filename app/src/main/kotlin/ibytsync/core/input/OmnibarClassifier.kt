package ibytsync.core.input

sealed interface OmnibarIntent {
    data object Empty : OmnibarIntent
    data class SingleUrl(val url: String) : OmnibarIntent
    data class BatchUrls(val urls: List<String>) : OmnibarIntent
    data class SearchQuery(val query: String) : OmnibarIntent
}

object OmnibarClassifier {
    fun classify(rawInput: String): OmnibarIntent {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return OmnibarIntent.Empty

        val extractedUrls = UrlBatchExtractor.extractAudioUrls(trimmed)
        return when {
            extractedUrls.size > 1 -> OmnibarIntent.BatchUrls(extractedUrls)
            extractedUrls.size == 1 && (trimmed == extractedUrls[0] || trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) -> {
                OmnibarIntent.SingleUrl(extractedUrls[0])
            }
            else -> OmnibarIntent.SearchQuery(trimmed)
        }
    }
}
