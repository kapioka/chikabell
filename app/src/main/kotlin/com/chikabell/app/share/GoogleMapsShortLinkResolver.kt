package com.chikabell.app.share

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object GoogleMapsShortLinkResolver {
    private const val MAX_REDIRECTS = 6
    private const val MAX_URL_LENGTH = 4_096
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_BYTES = 512_000
    private val RETRY_DELAYS_MS = longArrayOf(750L, 1_500L)
    private val googleMapsUrlPattern = Regex(
        "https:\\\\/\\\\/[^\\s\\\"'<>]+|https://[^\\s\\\"'<>]+",
        RegexOption.IGNORE_CASE,
    )
    private val structuredTagPattern = Regex(
        "<(?:meta|link)\\b[^>]*(?:canonical|og:url|twitter:url)[^>]*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val allowedContentTypes = setOf("text/html", "application/xhtml+xml")

    suspend fun resolve(place: ParsedSharedPlace): ParsedSharedPlace = withContext(Dispatchers.IO) {
        val shortUrl = place.shortUrl ?: place.sourceUrl?.takeIf(::isShortMapsUrl)
        if (shortUrl == null || !isShortMapsUrl(shortUrl)) return@withContext place

        val result = resolveDetailedWithRetry(place, shortUrl)
        val resolved = result.place ?: return@withContext place.withResolutionFailure(
            confidence = if (result.failureKind?.isRetryable == true) {
                SharedPlaceConfidence.NETWORK_FAILURE
            } else {
                SharedPlaceConfidence.UNRESOLVED
            },
            message = result.failureKind?.userMessage
                ?: "Googleマップのリンクから位置を特定できませんでした。住所または緯度・経度を確認してください",
            failureReason = result.failureKind?.reason ?: "unresolved_short_url",
        )
        resolved.copy(
            nameCandidate = place.nameCandidate.ifBlank { resolved.nameCandidate },
            sourceUrl = shortUrl,
            shortUrl = shortUrl,
            rawText = place.rawText,
        )
    }

    internal fun isAllowedUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return host == "maps.app.goo.gl" ||
            (host == "goo.gl" && uri.path.orEmpty().startsWith("/maps/")) ||
            host == "google.com" || host.endsWith(".google.com") ||
            host == "google.co.jp" || host.endsWith(".google.co.jp")
    }

    internal fun isAllowedContentType(value: String?): Boolean =
        value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase() in allowedContentTypes

    internal fun readLimitedResponse(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESPONSE_BYTES) { "応答サイズが上限を超えています" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isShortMapsUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return isAllowedUrl(value) &&
            (host == "maps.app.goo.gl" || (host == "goo.gl" && uri.path.orEmpty().startsWith("/maps/")))
    }

    internal data class ResolvedResponse(
        val finalUrl: String,
        val html: String?,
        val contentType: String = "text/html",
    )

    internal enum class ShortLinkFailureKind(
        val reason: String,
        val isRetryable: Boolean,
        val userMessage: String,
    ) {
        DEAD_LINK(
            "dead_link",
            false,
            "共有リンクは無効または期限切れです。Googleマップで場所を開き直して共有してください",
        ),
        TRANSIENT_HTTP(
            "transient_http",
            true,
            "Googleマップが一時的に応答しませんでした。通信状態を確認して再試行してください",
        ),
        NETWORK(
            "network_failure",
            true,
            "Googleマップのリンクを確認できませんでした。通信状態を確認して再試行してください",
        ),
        POLICY_REJECTED(
            "policy_rejected",
            false,
            "安全に確認できない共有リンクでした。Googleマップで場所を開き直して共有してください",
        ),
        PAYLOAD_TOO_LARGE(
            "payload_too_large",
            false,
            "共有リンクの応答が大きすぎるため解析を停止しました。住所または緯度・経度を確認してください",
        ),
        REDIRECT_LIMIT(
            "redirect_limit",
            false,
            "共有リンクの転送回数が多すぎるため解析を停止しました。Googleマップで場所を開き直してください",
        ),
        MALFORMED_RESPONSE(
            "malformed_response",
            false,
            "Googleマップの応答を安全に解析できませんでした。住所または緯度・経度を確認してください",
        ),
    }

    internal class ShortLinkFetchException(
        val kind: ShortLinkFailureKind,
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    internal suspend fun resolveWithRetry(
        place: ParsedSharedPlace,
        shortUrl: String,
        retryDelaysMs: LongArray = RETRY_DELAYS_MS,
        fetch: (String) -> ResolvedResponse = ::followRedirects,
    ): ParsedSharedPlace? = resolveDetailedWithRetry(place, shortUrl, retryDelaysMs, fetch).place

    private data class RetryResult(
        val place: ParsedSharedPlace?,
        val failureKind: ShortLinkFailureKind?,
    )

    private suspend fun resolveDetailedWithRetry(
        place: ParsedSharedPlace,
        shortUrl: String,
        retryDelaysMs: LongArray = RETRY_DELAYS_MS,
        fetch: (String) -> ResolvedResponse = ::followRedirects,
    ): RetryResult {
        var accumulated = place
        var lastFailure: ShortLinkFailureKind? = null
        repeat(retryDelaysMs.size + 1) { attempt ->
            val response = try {
                fetch(shortUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ShortLinkFetchException) {
                lastFailure = failure.kind
                null
            } catch (_: IOException) {
                lastFailure = ShortLinkFailureKind.NETWORK
                null
            } catch (_: RuntimeException) {
                lastFailure = ShortLinkFailureKind.MALFORMED_RESPONSE
                null
            }
            if (response != null) {
                if (isDeadLinkPage(response.html)) {
                    lastFailure = ShortLinkFailureKind.DEAD_LINK
                } else {
                    accumulated = SharedPlaceParser.merge(
                        accumulated,
                        coordinateEvidence(
                            response,
                            accumulated.rootLineageId ?: responseLineage(shortUrl),
                        ),
                    )
                    if (accumulated.confidence == SharedPlaceConfidence.HIGH_CONFIDENCE) {
                        return RetryResult(accumulated, failureKind = null)
                    }
                    lastFailure = null
                }
            }
            val shouldRetry = lastFailure?.isRetryable == true || (response != null && lastFailure == null)
            if (attempt < retryDelaysMs.size && shouldRetry) {
                delay(retryDelaysMs[attempt])
            } else if (!shouldRetry) {
                return RetryResult(
                    place = accumulated.takeIf { it.candidates.isNotEmpty() },
                    failureKind = lastFailure,
                )
            }
        }
        return RetryResult(
            place = accumulated.takeIf { it.candidates.isNotEmpty() },
            failureKind = lastFailure,
        )
    }

    internal fun coordinateCandidates(response: ResolvedResponse): List<String> {
        val candidates = mutableListOf(response.finalUrl)
        structuredTagPattern.findAll(normalizeHtml(response.html.orEmpty()))
            .flatMap { tag -> googleMapsUrlPattern.findAll(tag.value) }
            .map { it.value.trimEnd('\\', '.', ',', ')', ']', '}', '"', '\'') }
            .filter(::isAllowedUrl)
            .forEach(candidates::add)
        return candidates.distinct()
    }

    private fun coordinateEvidence(
        response: ResolvedResponse,
        lineageId: String,
    ): List<SharedPlaceEvidence> {
        val structuredUrls = coordinateCandidates(response).drop(1)
        return buildList {
            add(
                SharedPlaceEvidence(
                    value = response.finalUrl,
                    family = CoordinateEvidenceFamily.REDIRECT_FINAL_URL,
                    evidenceId = "redirect-final:${response.finalUrl.hashCode()}",
                    lineageId = lineageId,
                ),
            )
            structuredUrls.forEach { url ->
                add(
                    SharedPlaceEvidence(
                        value = url,
                        family = CoordinateEvidenceFamily.HTML_STRUCTURED_URL,
                        evidenceId = "html-structured:${url.hashCode()}",
                        lineageId = lineageId,
                    ),
                )
            }
            response.html?.takeIf(String::isNotBlank)?.let { html ->
                add(
                    SharedPlaceEvidence(
                        value = html,
                        family = CoordinateEvidenceFamily.HTML_PAGE_STATE,
                        evidenceId = "html-page-state:${response.finalUrl.hashCode()}",
                        lineageId = lineageId,
                    ),
                )
            }
        }
    }

    private fun followRedirects(initialUrl: String): ResolvedResponse {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (current.length > MAX_URL_LENGTH || !isAllowedUrl(current)) {
                throw ShortLinkFetchException(ShortLinkFailureKind.POLICY_REJECTED, "許可されていないURLです")
            }
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
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw ShortLinkFetchException(
                                ShortLinkFailureKind.REDIRECT_LIMIT,
                                "リダイレクト回数が上限を超えました",
                            )
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw ShortLinkFetchException(
                                ShortLinkFailureKind.MALFORMED_RESPONSE,
                                "移動先URLがありません",
                            )
                        if (location.length > MAX_URL_LENGTH) {
                            throw ShortLinkFetchException(
                                ShortLinkFailureKind.POLICY_REJECTED,
                                "移動先URLが長すぎます",
                            )
                        }
                        current = URI(current).resolve(location).toString()
                    }
                    in 200..299 -> {
                        val contentType = connection.contentType
                            ?: throw ShortLinkFetchException(
                                ShortLinkFailureKind.MALFORMED_RESPONSE,
                                "Content-Typeがありません",
                            )
                        if (!isAllowedContentType(contentType)) {
                            throw ShortLinkFetchException(
                                ShortLinkFailureKind.POLICY_REJECTED,
                                "解析対象外のContent-Typeです",
                            )
                        }
                        val declaredLength = connection.contentLengthLong
                        if (declaredLength > MAX_RESPONSE_BYTES) {
                            throw ShortLinkFetchException(
                                ShortLinkFailureKind.PAYLOAD_TOO_LARGE,
                                "応答サイズが上限を超えています",
                            )
                        }
                        val bytes = try {
                            connection.inputStream.use(::readLimitedResponse)
                        } catch (tooLarge: IllegalArgumentException) {
                            throw ShortLinkFetchException(
                                ShortLinkFailureKind.PAYLOAD_TOO_LARGE,
                                tooLarge.message.orEmpty(),
                                tooLarge,
                            )
                        }
                        return ResolvedResponse(current, bytes.toString(Charsets.UTF_8), contentType)
                    }
                    HttpURLConnection.HTTP_NOT_FOUND,
                    HttpURLConnection.HTTP_GONE,
                    -> throw ShortLinkFetchException(
                        ShortLinkFailureKind.DEAD_LINK,
                        "HTTP ${connection.responseCode}",
                    )
                    408, 425, 429, in 500..599 -> throw ShortLinkFetchException(
                        ShortLinkFailureKind.TRANSIENT_HTTP,
                        "HTTP ${connection.responseCode}",
                    )
                    else -> throw ShortLinkFetchException(
                        ShortLinkFailureKind.POLICY_REJECTED,
                        "HTTP ${connection.responseCode}",
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
        throw ShortLinkFetchException(
            ShortLinkFailureKind.REDIRECT_LIMIT,
            "リダイレクトを解決できません",
        )
    }

    private fun normalizeHtml(html: String): String = html
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private fun isDeadLinkPage(html: String?): Boolean {
        val normalized = html.orEmpty().lowercase()
        return "dynamic link not found" in normalized ||
            "requested url was not found" in normalized
    }

    private fun responseLineage(shortUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(shortUrl.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "short-link-response:$digest"
    }

    private fun ParsedSharedPlace.withResolutionFailure(
        confidence: SharedPlaceConfidence,
        message: String,
        failureReason: String,
    ) = copy(
        latitude = null,
        longitude = null,
        parseMethod = SharedPlaceParseMethod.MANUAL_REQUIRED,
        warnings = listOf(message),
        confidence = confidence,
        selectedCandidateIndex = null,
        failureReason = failureReason,
    )
}
