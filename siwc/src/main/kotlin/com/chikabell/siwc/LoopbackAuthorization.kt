package com.chikabell.siwc

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.*

class LoopbackAuthorization : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val attempt = AuthorizationAttempt("http://127.0.0.1:" + socket.localPort + "/auth/callback")
    suspend fun await(selected: ChatGptAccount?, returnPackage: String): AuthorizationCode = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        require(returnPackage.matches(Regex("[A-Za-z0-9_.]+")))
        while (System.currentTimeMillis() - started < 300_000) {
            ensureActive()
            val connection = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
            connection.use { client ->
                client.soTimeout = 3000
                val input = client.getInputStream()
                val header = StringBuilder()
                try {
                    while (header.length < 16384 && !header.endsWith("\r\n\r\n")) {
                        ensureActive()
                        if (System.currentTimeMillis() - started >= 300_000) error("authorization_timeout")
                        val byte = input.read(); if (byte < 0) break; header.append(byte.toChar())
                    }
                } catch (_: IOException) { return@use }
                if (!header.endsWith("\r\n\r\n")) return@use
                val path = header.toString().substringBefore("\r\n").split(" ").takeIf { it.size == 3 && it[0] == "GET" }?.get(1)
                var denied = false
                val result = try {
                    require(path != null && path.length < 16384)
                    SiwcProtocol.callback(URI("http://127.0.0.1:" + socket.localPort + path), attempt, selected?.clientId)
                } catch (error: Exception) { denied = error.message == "authorization_denied"; null }
                val html = if (result != null)
                    "<!doctype html><html lang='ja'><meta charset='UTF-8'><title>ちかベル認証</title><p>認証応答を受け取りました。アプリで接続結果を確認してください。</p><a href='intent://return#Intent;scheme=chikabell-siwc;package=$returnPackage;end'>アプリに戻る</a></html>"
                    else "<!doctype html><html lang='ja'><meta charset='UTF-8'><p>認証応答を確認できません。アプリへ戻ってキャンセルまたは接続をやり直してください。</p></html>"
                val bytes = html.toByteArray(Charsets.UTF_8)
                try { client.getOutputStream().apply {
                    write(("HTTP/1.1 " + (if (result != null) "200 OK" else "400 Bad Request") +
                        "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(bytes); flush()
                } } catch (_: IOException) { /* A disconnected browser does not invalidate a verified callback. */ }
                if (result != null) return@withContext result
                if (denied) error("authorization_denied")
            }
        }
        error("authorization_timeout")
    }
    override fun close() { socket.close() }
}
