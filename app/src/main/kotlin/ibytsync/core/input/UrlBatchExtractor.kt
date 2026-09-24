package ibytsync.core.input

object UrlBatchExtractor {
    private val URL_REGEX = Regex(
        "https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+",
        RegexOption.IGNORE_CASE
    )

    fun extractAudioUrls(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        return URL_REGEX.findAll(rawText)
            .map { match ->
                match.value.trim().trimEnd(',', ';', ')', ']', '>', '"', '\'')
            }
            .filter { url ->
                url.contains("youtube.com", ignoreCase = true) ||
                url.contains("youtu.be", ignoreCase = true) ||
                url.contains("spotify.com", ignoreCase = true)
            }
            .distinct()
            .toList()
    }
}
