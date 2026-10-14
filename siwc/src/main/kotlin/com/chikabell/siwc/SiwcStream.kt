package com.chikabell.siwc

import java.io.BufferedReader
import java.io.Reader
import kotlinx.serialization.json.*

data class SiwcInferenceResult(val text: String, val webSearchCompleted: Boolean)

object SiwcStream {
    const val MAX_CHARS = 2_000_000
    fun read(reader: Reader, cancelled: () -> Boolean = { false }): String = readDetailed(reader, cancelled).text
    fun readDetailed(reader: Reader, cancelled: () -> Boolean = { false }): SiwcInferenceResult {
        val buffered = if (reader is BufferedReader) reader else reader.buffered()
        val parts = linkedMapOf<String, StringBuilder>()
        val done = linkedMapOf<String, String>()
        val event = StringBuilder()
        var total = 0
        var completed = false
        var webSearchCompleted = false
        fun dispatch() {
            if (event.isEmpty()) return
            val data = event.toString().trimEnd()
            event.setLength(0)
            if (data == "[DONE]") return
            val value = Json.parseToJsonElement(data).jsonObject
            val type = value["type"]?.jsonPrimitive?.content
            val key = (value["output_index"]?.jsonPrimitive?.content ?: "0") + ":" + (value["content_index"]?.jsonPrimitive?.content ?: "0")
            when (type) {
                "response.web_search_call.completed" -> webSearchCompleted = true
                "response.output_text.delta" -> {
                    require(parts.size < 64 || key in parts) { "stream_invalid" }
                    parts.getOrPut(key) { StringBuilder() }.append(value["delta"]?.jsonPrimitive?.content.orEmpty())
                }
                "response.output_text.done" -> done[key] = value["text"]?.jsonPrimitive?.content.orEmpty()
                "response.failed", "response.incomplete", "error" -> {
                    val remoteError = (value["response"] as? JsonObject)?.get("error") ?: value["error"] ?: value
                    error(SiwcFailure.knownServerCode(remoteError) ?: "inference_failed")
                }
                "response.completed" -> {
                    val response = value["response"]?.jsonObject
                    if (response?.get("output")?.jsonArray.orEmpty().any {
                        val item = it as? JsonObject
                        item?.get("type")?.jsonPrimitive?.content == "web_search_call" && item["status"]?.jsonPrimitive?.content == "completed"
                    }) webSearchCompleted = true
                    response?.get("status")?.jsonPrimitive?.content?.let { require(it == "completed") { "inference_failed" } }
                    val finalText = response?.get("output")?.jsonArray.orEmpty().flatMap {
                        it.jsonObject["content"]?.jsonArray.orEmpty()
                    }.mapNotNull {
                        val content = it.jsonObject
                        if (content["type"]?.jsonPrimitive?.content == "output_text") content["text"]?.jsonPrimitive?.content else null
                    }.joinToString("\n")
                    if (finalText.isNotBlank()) { parts.clear(); done.clear(); done["0:0"] = finalText }
                    completed = true
                }
            }
        }
        while (!completed) {
            check(!cancelled()) { "cancelled" }
            // Read one bounded line; BufferedReader.readLine alone can allocate an unbounded event.
            val line = StringBuilder()
            while (true) {
                val char = buffered.read()
                if (char == -1) {
                    if (line.isEmpty()) { dispatch(); require(completed) { "stream_interrupted" }; break }
                    break
                }
                total++; require(total <= MAX_CHARS) { "stream_too_large" }
                if (total % 256 == 0) check(!cancelled()) { "cancelled" }
                if (char == 10) break
                if (char != 13) line.append(char.toChar())
                require(line.length <= 256_000) { "stream_invalid" }
            }
            if (completed) break
            if (line.isEmpty()) dispatch()
            else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.substring(5).trimStart())
                require(event.length <= 256_000) { "stream_invalid" }
            }
        }
        check(!cancelled()) { "cancelled" }
        val keys = (parts.keys + done.keys).distinct()
        val text = keys.joinToString("\n") { done[it] ?: parts[it].toString() }
        require(text.isNotBlank() && text.length <= MAX_CHARS) { "empty_response" }
        return SiwcInferenceResult(text, webSearchCompleted)
    }
}
