package com.chikabell.siwc

import java.net.URL
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class SiwcTransport {
    private val json = Json
    suspend fun objectRequest(url: String, token: String? = null, form: Map<String, String>? = null, maxBytes: Int = 256_000): JsonObject {
        require(maxBytes in 1..2_000_000)
        val activeJob = currentCoroutineContext()[Job]
        val started = System.currentTimeMillis()
        return request(url, token, form?.let { SiwcProtocol.form(it) }, "application/x-www-form-urlencoded") { connection ->
            val content = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(activeJob?.isCancelled != true) { "cancelled" }
                    require(System.currentTimeMillis() - started < 60_000) { "response_timeout" }
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= maxBytes) { "response_too_large" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            val parsed = try { json.parseToJsonElement(content) }
                catch (_: kotlinx.serialization.SerializationException) { error("response_json_invalid") }
            parsed as? JsonObject ?: error("response_object_invalid")
        }
    }
    suspend fun identity(idToken: String, client: String, nonce: String?): VerifiedIdentity {
        val discovery = objectRequest(SiwcProtocol.DISCOVERY_URL)
        require(discovery["issuer"]?.jsonPrimitive?.content == SiwcProtocol.ISSUER) { "identity_invalid" }
        val jwksUrl = discovery["jwks_uri"]?.jsonPrimitive?.content ?: error("identity_invalid")
        requireTrustedAuthUrl(jwksUrl)
        return SiwcProtocol.identity(idToken, objectRequest(jwksUrl), client, nonce, System.currentTimeMillis() / 1000)
    }
    suspend fun exchange(code: AuthorizationCode, attempt: AuthorizationAttempt): ChatGptAccount {
        val token = objectRequest(SiwcProtocol.TOKEN_URL, form = mapOf(
            "grant_type" to "authorization_code", "client_id" to code.clientId, "code" to code.code,
            "code_verifier" to attempt.verifier, "redirect_uri" to attempt.redirectUri, "resource" to SiwcProtocol.RESOURCE,
        ))
        val idToken = token["id_token"]?.jsonPrimitive?.content ?: error("identity_missing")
        return account(token, code.clientId, identity(idToken, code.clientId, attempt.nonce), idToken, null)
    }
    suspend fun refresh(previous: ChatGptAccount): ChatGptAccount {
        require(previous.refreshToken.isNotBlank()) { "reauthorize" }
        val token = objectRequest(SiwcProtocol.TOKEN_URL, form = mapOf(
            "grant_type" to "refresh_token", "client_id" to previous.clientId, "refresh_token" to previous.refreshToken,
            "resource" to SiwcProtocol.RESOURCE,
        ))
        val idToken = token["id_token"]?.jsonPrimitive?.content
        val identity = if (idToken != null) identity(idToken, previous.clientId, null) else VerifiedIdentity(previous.subject, previous.email)
        require(identity.subject == previous.subject) { "account_mismatch" }
        return account(token, previous.clientId, identity, idToken ?: previous.idToken, previous)
    }
    private fun account(token: JsonObject, client: String, identity: VerifiedIdentity, idToken: String, previous: ChatGptAccount?): ChatGptAccount {
        require(token["token_type"]?.jsonPrimitive?.content?.equals("Bearer", true) == true) { "token_invalid" }
        val scopes = token["scope"]?.jsonPrimitive?.content?.split(" ")?.filter(String::isNotBlank)?.toSet()
            ?: previous?.scopes ?: emptySet()
        val access = token["access_token"]?.jsonPrimitive?.content.orEmpty()
        val expires = token["expires_in"]?.jsonPrimitive?.longOrNull ?: error("token_invalid")
        require(access.isNotBlank() && access.length <= 32_768 && expires in 1..86400) { "token_invalid" }
        return ChatGptAccount(client, identity.subject, identity.email, access,
            token["refresh_token"]?.jsonPrimitive?.content ?: previous?.refreshToken.orEmpty(),
            idToken, scopes, System.currentTimeMillis() + expires * 1000)
    }
    suspend fun models(account: ChatGptAccount): List<ChatGptModel> {
        require(account.canInfer) { "plan_permission_missing" }
        return SiwcProtocol.models(objectRequest(SiwcProtocol.RESOURCE + "/models", account.accessToken, maxBytes = 2_000_000))
    }
    suspend fun infer(account: ChatGptAccount, model: String, input: String, instructions: String, webSearch: Boolean = false): String =
        inferDetailed(account, model, input, instructions, webSearch).text
    suspend fun inferDetailed(account: ChatGptAccount, model: String, input: String, instructions: String, webSearch: Boolean = false): SiwcInferenceResult {
        require(account.canInfer) { "plan_permission_missing" }
        require(model.matches(Regex("[A-Za-z0-9._-]{1,120}")) && input.length <= 100_000 && instructions.length <= 100_000)
        val payload = buildJsonObject {
            put("model", model); put("store", false); put("stream", true)
            put("instructions", instructions)
            put("input", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", input) }) })
            if (webSearch) put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
        }.toString()
        val started = System.currentTimeMillis()
        val activeJob = currentCoroutineContext()[Job]
        return request(SiwcProtocol.RESOURCE + "/responses", account.accessToken, payload, "application/json") { connection ->
            val type = connection.contentType.orEmpty().lowercase()
            require(type.isBlank() || type.startsWith("text/event-stream")) { "stream_invalid" }
            connection.inputStream.reader(Charsets.UTF_8).use {
                SiwcStream.readDetailed(it) { System.currentTimeMillis() - started > 180_000L || activeJob?.isCancelled == true || Thread.currentThread().isInterrupted }
            }
        }
    }
    suspend fun revoke(account: ChatGptAccount): Boolean = try {
        val discovery = objectRequest(SiwcProtocol.DISCOVERY_URL)
        val endpoint = discovery["revocation_endpoint"]?.jsonPrimitive?.content ?: error("revoke_unavailable")
        requireTrustedAuthUrl(endpoint)
        request(endpoint, body = SiwcProtocol.form(mapOf("token" to account.refreshToken, "token_type_hint" to "refresh_token", "client_id" to account.clientId)),
            type = "application/x-www-form-urlencoded") { true }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    private fun requireTrustedAuthUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "auth.openai.com" && uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null) { "endpoint_invalid" }
    }
    private suspend fun <T> request(url: String, token: String? = null, body: String? = null, type: String = "application/json", read: (HttpsURLConnection) -> T): T = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host in setOf("auth.openai.com", "api.openai.com") && uri.port in setOf(-1, 443) && uri.userInfo == null)
        val connection = URL(url).openConnection() as HttpsURLConnection
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", if (url.endsWith("/responses")) "text/event-stream" else "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "$type; charset=utf-8")
                val bytes = body.toByteArray(Charsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorStarted = System.currentTimeMillis()
                val known = try {
                    val text = connection.errorStream?.use { input ->
                        val buffer = ByteArray(4096)
                        val output = java.io.ByteArrayOutputStream()
                        while (output.size() <= 64_000) {
                            ensureActive()
                            require(System.currentTimeMillis() - errorStarted < 60_000) { "response_timeout" }
                            val count = input.read(buffer); if (count < 0) break
                            if (output.size() + count > 64_000) return@use null
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    text?.let { SiwcFailure.knownServerCode((json.parseToJsonElement(it) as? JsonObject)?.get("error")) }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                error(known ?: "http_$status")
            }
            ensureActive()
            read(connection).also { ensureActive() }
        } finally { cancellation?.dispose(); connection.disconnect() }
    }
}
