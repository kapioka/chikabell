package com.chikabell.siwc

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Base64
import kotlinx.serialization.json.*

object SiwcProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
    const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
    private val scopes = "openid profile email offline_access resource.invoke $DIRECT_SCOPE"
    private val random = SecureRandom()
    fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
    fun form(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }
    fun authorization(attempt: AuthorizationAttempt, name: String, hostId: String, selected: ChatGptAccount?): String {
        require(hostId.startsWith("urn:uuid:"))
        val values = linkedMapOf(
            "client_id" to (selected?.clientId ?: "dynamic_agent_client"), "ext_agent_host_id" to hostId,
            "response_type" to "code", "redirect_uri" to attempt.redirectUri, "scope" to scopes,
            "resource" to RESOURCE, "state" to attempt.state, "nonce" to attempt.nonce,
            "code_challenge_method" to "S256", "code_challenge" to challenge(attempt.verifier),
        )
        if (selected == null) values["agent_name_hint"] = name
        else {
            selected.idToken.takeIf(String::isNotBlank)?.let { values["id_token_hint"] = it }
            selected.email.takeIf(String::isNotBlank)?.let { values["login_hint"] = it }
        }
        return "$ISSUER/api/accounts/authorize?" + form(values)
    }
    fun callback(uri: URI, attempt: AuthorizationAttempt, selectedClient: String?): AuthorizationCode {
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.path == "/auth/callback" && uri.fragment == null) { "callback_invalid" }
        require(uri.port == URI(attempt.redirectUri).port) { "callback_invalid" }
        val query = uri.rawQuery.orEmpty()
        require(query.length <= 16_384) { "callback_invalid" }
        val values = linkedMapOf<String, String>()
        query.split("&").filter(String::isNotEmpty).forEach {
            val split = it.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(split[0], "UTF-8")
            require(key !in values) { "callback_invalid" }
            values[key] = java.net.URLDecoder.decode(split.getOrElse(1) { "" }, "UTF-8")
        }
        require(MessageDigest.isEqual(values["state"].orEmpty().toByteArray(), attempt.state.toByteArray())) { "state_invalid" }
        require(values["error"] == null) { "authorization_denied" }
        val client = values["client_id"] ?: selectedClient
        require(client != null && client.matches(Regex("oaiapp_[A-Za-z0-9_-]{1,200}"))) { "registration_incomplete" }
        require(selectedClient == null || selectedClient == client) { "registration_mismatch" }
        val code = values["code"].orEmpty()
        require(code.isNotBlank() && code.length <= 8192) { "callback_invalid" }
        return AuthorizationCode(code, client)
    }
    fun identity(token: String, jwks: JsonObject, clientId: String, nonce: String?, nowSeconds: Long): VerifiedIdentity {
        require(token.length in 1..32_768) { "identity_invalid" }
        val pieces = token.split(".")
        require(pieces.size == 3) { "identity_invalid" }
        val header = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8)).jsonObject
        require(header["alg"]?.jsonPrimitive?.content == "RS256") { "identity_invalid" }
        require(header["crit"] == null) { "identity_invalid" }
        val kid = header["kid"]?.jsonPrimitive?.content ?: error("identity_invalid")
        val keys = jwks["keys"]?.jsonArray ?: error("identity_invalid")
        val matching = keys.filter { it.jsonObject["kid"]?.jsonPrimitive?.content == kid }
        require(matching.size == 1) { "identity_invalid" }
        val key = matching.single().jsonObject
        require(key["kty"]?.jsonPrimitive?.content == "RSA") { "identity_invalid" }
        key["alg"]?.jsonPrimitive?.content?.let { require(it == "RS256") { "identity_invalid" } }
        key["use"]?.jsonPrimitive?.content?.let { require(it == "sig") { "identity_invalid" } }
        val modulus = BigInteger(1, Base64.getUrlDecoder().decode(key.getValue("n").jsonPrimitive.content))
        val exponent = BigInteger(1, Base64.getUrlDecoder().decode(key.getValue("e").jsonPrimitive.content))
        require(modulus.bitLength() in 2048..8192 && exponent.bitLength() <= 32) { "identity_invalid" }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update((pieces[0] + "." + pieces[1]).toByteArray(StandardCharsets.US_ASCII))
        require(verifier.verify(Base64.getUrlDecoder().decode(pieces[2]))) { "identity_invalid" }
        val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(pieces[1]), StandardCharsets.UTF_8)).jsonObject
        require(claims["iss"]?.jsonPrimitive?.content == ISSUER) { "identity_invalid" }
        val aud = claims["aud"]
        val audience = if (aud is JsonArray) aud.map { it.jsonPrimitive.content } else listOfNotNull((aud as? JsonPrimitive)?.content)
        require(clientId in audience) { "identity_invalid" }
        if (audience.size > 1) require(claims["azp"]?.jsonPrimitive?.content == clientId) { "identity_invalid" }
        require((claims["exp"]?.jsonPrimitive?.longOrNull ?: 0) > nowSeconds) { "identity_expired" }
        claims["nbf"]?.jsonPrimitive?.longOrNull?.let { require(it <= nowSeconds + 60) { "identity_invalid" } }
        claims["iat"]?.jsonPrimitive?.longOrNull?.let { require(it <= nowSeconds + 60) { "identity_invalid" } }
        if (nonce != null) require(MessageDigest.isEqual(claims["nonce"]?.jsonPrimitive?.content.orEmpty().toByteArray(), nonce.toByteArray())) { "nonce_invalid" }
        val subject = claims["sub"]?.jsonPrimitive?.content.orEmpty()
        require(subject.isNotBlank() && subject.length <= 256) { "identity_invalid" }
        return VerifiedIdentity(subject, claims["email"]?.jsonPrimitive?.content.orEmpty().take(256))
    }
    fun models(value: JsonObject): List<ChatGptModel> {
        val catalog = value["models"] as? JsonArray ?: error("model_catalog_invalid")
        return catalog.mapNotNull {
            val model = it as? JsonObject ?: return@mapNotNull null
            val slug = (model["slug"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if ((model["visibility"] as? JsonPrimitive)?.contentOrNull != "list" || !slug.matches(Regex("[A-Za-z0-9._-]{1,120}"))) null
            else ChatGptModel(slug, (model["display_name"] as? JsonPrimitive)?.contentOrNull?.take(160) ?: slug)
        }.distinctBy { it.slug }.take(100).also { require(it.isNotEmpty()) { "model_catalog_empty" } }
    }
}
class AuthorizationAttempt(val redirectUri: String, val state: String = SiwcProtocol.randomToken(), val nonce: String = SiwcProtocol.randomToken(), val verifier: String = SiwcProtocol.randomToken())
class AuthorizationCode(val code: String, val clientId: String)
data class VerifiedIdentity(val subject: String, val email: String)
data class ChatGptModel(val slug: String, val name: String)
class ChatGptAccount(val clientId: String, val subject: String, val email: String, val accessToken: String, val refreshToken: String, val idToken: String, val scopes: Set<String>, val expiresAt: Long) {
    override fun toString(): String = "ChatGptAccount(redacted)"
    val canInfer: Boolean get() = SiwcProtocol.DIRECT_SCOPE in scopes && "resource.invoke" in scopes
}
