package com.chikabell.siwc

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.io.StringReader
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.net.Socket
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class SiwcProtocolTest {
    private val attempt = AuthorizationAttempt("http://127.0.0.1:4567/auth/callback", "expected", "nonce", "verifier")
    @Test fun loopbackSurvivesDisconnectedAndInvalidBrowserRequests(): Unit = runBlocking {
        LoopbackAuthorization().use { listener ->
            val result = async(Dispatchers.IO) { listener.await(null, "com.chikabell.siwcpoc") }
            val port = URI(listener.attempt.redirectUri).port
            Socket("127.0.0.1", port).close()
            fun request(path: String): String = Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                client.getInputStream().bufferedReader().readText()
            }
            assertTrue(request("/favicon.ico").startsWith("HTTP/1.1 400"))
            val reply = request("/auth/callback?state=${listener.attempt.state}&code=test&client_id=oaiapp_test")
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            assertFalse(reply.contains(listener.attempt.state))
            assertFalse(reply.contains("code=test"))
            assertEquals("oaiapp_test", withTimeout(5000) { result.await() }.clientId)
        }
    }
    @Test fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SiwcProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun classifiesUsageFailureWithoutLeakingRemoteMessagesOrUnknownCodes() {
        val known = buildJsonObject { put("code", "subscription_sharing_usage_limit_exceeded"); put("message", "PRIVATE REMOTE TEXT") }
        assertEquals("subscription_sharing_usage_limit_exceeded", SiwcFailure.knownServerCode(known))
        assertNull(SiwcFailure.knownServerCode(buildJsonObject { put("code", "unknown-private-value") }))
        assertFalse(SiwcFailure.explanation(IllegalStateException("PRIVATE REMOTE TEXT")).contains("PRIVATE"))
        val failed = "data: " + buildJsonObject { put("type", "response.failed"); put("response", buildJsonObject { put("error", known) }) } + "\n\n"
        val error = assertThrows(IllegalStateException::class.java) { SiwcStream.read(StringReader(failed)) }
        assertEquals("subscription_sharing_usage_limit_exceeded", error.message)
    }
    @Test fun validatesCallbackStateIssuedClientAndExactUri() {
        val valid = "http://127.0.0.1:4567/auth/callback?state=expected&code=test&client_id=oaiapp_test"
        assertEquals("oaiapp_test", SiwcProtocol.callback(URI(valid), attempt, null).clientId)
        for (uri in listOf(valid.replace("expected", "wrong"), valid.replace("/auth/callback", "/callback"),
            valid.replace("oaiapp_test", "dynamic_agent_client"), valid + "&state=expected", valid.replace("127.0.0.1", "localhost"))) {
            assertThrows(IllegalArgumentException::class.java) { SiwcProtocol.callback(URI(uri), attempt, null) }
        }
        assertThrows(IllegalArgumentException::class.java) { SiwcProtocol.callback(URI(valid), attempt, "oaiapp_other") }
    }
    @Test fun rejectsDeniedConsentBeforeExchange() {
        assertThrows(IllegalArgumentException::class.java) { SiwcProtocol.callback(URI("http://127.0.0.1:4567/auth/callback?state=expected&error=access_denied"), attempt, null) }
    }
    @Test fun separatesFirstRegistrationAndReturningAccount() {
        val first = SiwcProtocol.authorization(attempt, "ChikaBell", "urn:uuid:host", null)
        assertTrue(first.contains("dynamic_agent_client"))
        assertTrue(first.contains("agent_name_hint=ChikaBell"))
        val account = ChatGptAccount("oaiapp_test", "subject", "test@example.com", "", "", "hint", emptySet(), 0)
        val returning = SiwcProtocol.authorization(attempt, "ChikaBell", "urn:uuid:host", account)
        assertFalse(returning.contains("agent_name_hint"))
        assertTrue(returning.contains("client_id=oaiapp_test"))
        assertTrue(returning.contains("id_token_hint=hint"))
    }
    @Test fun filtersAccountSpecificModelCatalog() {
        val models = SiwcProtocol.models(Json.parseToJsonElement("""{"models":[{"slug":"allowed","display_name":"Allowed","visibility":"list"},{"slug":"hidden","visibility":"hidden"}]}""").jsonObject)
        assertEquals(listOf("allowed"), models.map { it.slug })
        assertThrows(IllegalStateException::class.java) { SiwcProtocol.models(buildJsonObject { put("data", buildJsonArray {}) }) }
        assertThrows(IllegalArgumentException::class.java) { SiwcProtocol.models(buildJsonObject { put("models", buildJsonArray {}) }) }
    }
    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    @Test fun verifiesSignatureIssuerAudienceNonceAndExpiry() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = pair.public as RSAPublicKey
        fun unsigned(value: java.math.BigInteger) = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val jwks = buildJsonObject { put("keys", buildJsonArray { add(buildJsonObject {
            put("kty", "RSA"); put("kid", "test"); put("n", base64(unsigned(public.modulus))); put("e", base64(unsigned(public.publicExponent)))
        }) }) }
        fun token(claims: String): String {
            val input = base64("""{"alg":"RS256","kid":"test"}""".toByteArray()) + "." + base64(claims.toByteArray())
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(input.toByteArray()) }.sign()
            return input + "." + base64(signature)
        }
        val valid = """{"iss":"https://auth.openai.com","sub":"subject","aud":"oaiapp_test","exp":2000,"nonce":"nonce"}"""
        assertEquals("subject", SiwcProtocol.identity(token(valid), jwks, "oaiapp_test", "nonce", 1000).subject)
        for (claims in listOf(valid.replace("https://auth.openai.com", "https://evil.example"), valid.replace("oaiapp_test", "oaiapp_other"), valid.replace("2000", "999"), valid.replace("nonce\"}", "wrong\"}"))) {
            assertThrows(IllegalArgumentException::class.java) { SiwcProtocol.identity(token(claims), jwks, "oaiapp_test", "nonce", 1000) }
        }
        val tampered = token(valid).let { it.substringBeforeLast(".") + ".AAAA" }
        assertThrows(Exception::class.java) { SiwcProtocol.identity(tampered, jwks, "oaiapp_test", "nonce", 1000) }
    }
    private fun events(vararg data: String) = data.joinToString("") { "data: $it\n\n" }
    @Test fun waitsForTerminalEventAndUsesDeltaWhenFinalOutputIsEmpty() {
        val stream = events("""{"type":"response.output_text.delta","delta":"日本語"}""", """{"type":"response.completed","response":{"status":"completed","output":[]}}""")
        assertEquals("日本語", SiwcStream.read(StringReader(stream)))
    }
    @Test fun finalAndDoneTextDoNotDuplicateDeltas() {
        val stream = events("""{"type":"response.output_text.delta","delta":"hello"}""", """{"type":"response.output_text.done","text":"hello"}""", """{"type":"response.completed","response":{"status":"completed","output":[]}}""")
        assertEquals("hello", SiwcStream.read(StringReader(stream)))
    }
    @Test fun rejectsFailedIncompleteInterruptedEmptyAndCancelledStreams() {
        val delta = """{"type":"response.output_text.delta","delta":"partial"}"""
        for (stream in listOf(events(delta), events(delta, """{"type":"response.failed"}"""), events(delta, """{"type":"response.incomplete"}"""), events("""{"type":"response.completed","response":{"output":[]}}"""))) {
            assertThrows(Exception::class.java) { SiwcStream.read(StringReader(stream)) }
        }
        assertThrows(IllegalStateException::class.java) { SiwcStream.read(StringReader(events(delta))) { true } }
    }
    @Test fun rejectsOversizedStreamBeforeUnboundedAllocation() {
        assertThrows(IllegalArgumentException::class.java) { SiwcStream.read(StringReader("data: " + "a".repeat(256001))) }
    }
    @Test fun neverTreatsIdentityAsPlanPermission() {
        assertFalse(ChatGptAccount("c", "s", "e", "a", "", "", setOf("openid"), 100).canInfer)
        assertTrue(ChatGptAccount("c", "s", "e", "a", "", "", setOf(SiwcProtocol.DIRECT_SCOPE, "resource.invoke"), 100).canInfer)
    }
}
