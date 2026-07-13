package com.chikabell.app.share

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object GoogleMapsShortLinkResolver {
    private const val MAX_REDIRECTS = 6
    private const val MAX_URL_LENGTH = 4_096
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_CHARS = 512_000
    private val RETRY_DELAYS_MS = longArrayOf(750L, 1_500L)
    private val googleMapsUrlPattern = Regex(
        "https:\\\\/\\\\/[^\\s\\\"'<>]+|https://[^\\s\\\"'<>]+",
        RegexOption.IGNORE_CASE,
    )

    suspend fun resolve(place: ParsedSharedPlace): ParsedSharedPlace = withContext(Dispatchers.IO) {
        val shortUrl = place.sourceUrl
        if (place.hasCoordinates || shortUrl == null || !isShortMapsUrl(shortUrl)) return@withContext place

        val resolved = resolveWithRetry(place, shortUrl)
            ?: return@withContext place.withResolutionWarning("Googleマップのリンクから座標を自動取得できませんでした。通信状態を確認するか緯度・経度を手入力してください")
        resolved.copy(
            nameCandidate = place.nameCandidate.ifBlank { resolved.nameCandidate },
            sourceUrl = shortUrl,
            rawText = place.rawText,
            warnings = emptyList(),
        )
    }

    internal fun isAllowedUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return host == "maps.app.goo.gl" ||
            host == "google.com" || host.endsWith(".google.com") ||
            host == "google.co.jp" || host.endsWith(".google.co.jp")
    }

    private fun isShortMapsUrl(value: String): Boolean =
        runCatching { URI(value).host.equals("maps.app.goo.gl", ignoreCase = true) }.getOrDefault(false) &&
            isAllowedUrl(value)

    internal data class ResolvedResponse(val finalUrl: String, val html: String?)

    internal suspend fun resolveWithRetry(
        place: ParsedSharedPlace,
        shortUrl: String,
        retryDelaysMs: LongArray = RETRY_DELAYS_MS,
        fetch: (String) -> ResolvedResponse = ::followRedirects,
    ): ParsedSharedPlace? {
        repeat(retryDelaysMs.size + 1) { attempt ->
            val response = runCatching { fetch(shortUrl) }.getOrNull()
            val resolved = response?.let(::coordinateCandidates)
                ?.asSequence()
                ?.map { SharedPlaceParser.parse(place.nameCandidate, place.rawText, it) }
                ?.firstOrNull { it.hasCoordinates }
            if (resolved != null) return resolved
            if (attempt < retryDelaysMs.size) delay(retryDelaysMs[attempt])
        }
        return null
    }

    internal fun coordinateCandidates(response: ResolvedResponse): List<String> {
        val candidates = mutableListOf(response.finalUrl)
        response.html.orEmpty()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .let(googleMapsUrlPattern::findAll)
            .map { it.value.trimEnd('\\', '.', ',', ')', ']', '}') }
            .filter(::isAllowedUrl)
            .forEach(candidates::add)
        // Some Google Maps pages keep the selected place coordinates only in
        // page state rather than in the canonical URL. The parser deliberately
        // looks only for Maps-specific @lat,lon / !3dlat!4dlon markers.
        response.html?.let(candidates::add)
        return candidates.distinct()
    }

    private fun followRedirects(initialUrl: String): ResolvedResponse {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(current.length <= MAX_URL_LENGTH && isAllowedUrl(current)) { "許可されていないURLです" }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ChikaBell/0.1 Android")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        check(redirectCount < MAX_REDIRECTS) { "リダイレクト回数が上限を超えました" }
                        val location = connection.getHeaderField("Location") ?: error("移動先URLがありません")
                        check(location.length <= MAX_URL_LENGTH) { "移動先URLが長すぎます" }
                        current = URI(current).resolve(location).toString()
                    }
                    in 200..299 -> {
                        val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(8_192)
                            val body = StringBuilder()
                            while (body.length < MAX_RESPONSE_CHARS) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - body.length))
                                if (count < 0) break
                                body.append(buffer, 0, count)
                            }
                            body.toString()
                        }
                        return ResolvedResponse(current, html)
                    }
                    else -> error("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("リダイレクトを解決できません")
    }

    private fun ParsedSharedPlace.withResolutionWarning(message: String) = copy(warnings = listOf(message))
}
