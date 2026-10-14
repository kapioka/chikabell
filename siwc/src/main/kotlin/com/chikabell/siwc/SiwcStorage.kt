package com.chikabell.siwc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

/** Only ciphertext is written, under noBackupFilesDir; each application has its own key and host. */
class SiwcStorage(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-connections.enc"))
    private val alias = context.packageName + ".chatgpt.v1"
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized private fun load(): JsonObject {
        if (!file.baseFile.exists()) return buildJsonObject { put("host", "urn:uuid:" + UUID.randomUUID().toString()); put("accounts", JsonArray(emptyList())) }.also(::write)
        require(file.baseFile.length() <= 1_000_000) { "storage_invalid" }
        val bytes = file.readFully()
        require(bytes.size > 28) { "storage_invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return Json.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)).jsonObject
    }
    private fun write(value: JsonObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(ciphertext); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    @Synchronized fun hostId(): String = load().getValue("host").jsonPrimitive.content
    @Synchronized fun accounts(): List<ChatGptAccount> = load()["accounts"]?.jsonArray.orEmpty().map { decode(it.jsonObject) }
    @Synchronized fun active(): ChatGptAccount? {
        val root = load()
        val selected = root["active"]?.jsonPrimitive?.content ?: return null
        return root["accounts"]?.jsonArray.orEmpty().map { decode(it.jsonObject) }.find { it.clientId == selected }
    }
    @Synchronized fun select(clientId: String) {
        val root = load(); require(root["accounts"]?.jsonArray.orEmpty().any { it.jsonObject["client"]?.jsonPrimitive?.content == clientId })
        write(JsonObject(root + ("active" to JsonPrimitive(clientId))))
    }
    @Synchronized fun save(account: ChatGptAccount) {
        val root = load()
        val other = root["accounts"]?.jsonArray.orEmpty().filter { it.jsonObject["client"]?.jsonPrimitive?.content != account.clientId }
        require(other.size < 10) { "account_limit" }
        write(JsonObject(root + mapOf("active" to JsonPrimitive(account.clientId), "accounts" to JsonArray(other + encode(account)))))
    }
    @Synchronized fun clearTokens(client: String) {
        val root = load()
        val values = root["accounts"]?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            if (item["client"]?.jsonPrimitive?.content != client) element
            else JsonObject(item + mapOf("access" to JsonPrimitive(""), "refresh" to JsonPrimitive(""), "id" to JsonPrimitive(""), "scopes" to JsonArray(emptyList()), "expires" to JsonPrimitive(0)))
        }
        write(JsonObject(root.filterKeys { it != "active" } + ("accounts" to JsonArray(values))))
    }
    private fun encode(a: ChatGptAccount): JsonObject = buildJsonObject {
        put("client", a.clientId); put("subject", a.subject); put("email", a.email)
        put("access", a.accessToken); put("refresh", a.refreshToken); put("id", a.idToken)
        put("scopes", JsonArray(a.scopes.map(::JsonPrimitive))); put("expires", a.expiresAt)
    }
    private fun decode(o: JsonObject): ChatGptAccount {
        fun text(key: String) = o.getValue(key).jsonPrimitive.content
        return ChatGptAccount(text("client"), text("subject"), text("email"), text("access"), text("refresh"), text("id"),
            o.getValue("scopes").jsonArray.map { it.jsonPrimitive.content }.toSet(), o.getValue("expires").jsonPrimitive.long)
    }
}
