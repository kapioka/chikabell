package com.chikabell.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream

object SharedAttachmentMetadataReader {
    private const val MAX_ATTACHMENT_BYTES = 1_048_576
    private const val MAX_METADATA_TEXTS = 8
    private const val MAX_ATTACHMENTS = 4

    fun read(context: Context, intent: Intent): List<String> {
        if (intent.action != Intent.ACTION_SEND ||
            !intent.type.equals("image/png", ignoreCase = true) ||
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) {
            return emptyList()
        }
        val deadlineNanos = System.nanoTime() + MAX_PROCESSING_NANOS
        return attachmentUris(intent)
            .asSequence()
            .takeWhile { System.nanoTime() <= deadlineNanos }
            .mapNotNull { uri -> readBounded(context, uri, deadlineNanos) }
            .flatMap(PngTextMetadataExtractor::extract)
            .distinct()
            .take(MAX_METADATA_TEXTS)
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun attachmentUris(intent: Intent): List<Uri> = buildList {
        intent.clipData?.let { clip ->
            repeat(minOf(clip.itemCount, MAX_ATTACHMENTS)) { index ->
                clip.getItemAt(index).uri?.let(::add)
            }
        }
        if (size < MAX_ATTACHMENTS) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
        }
    }.distinct().take(MAX_ATTACHMENTS)

    private fun readBounded(context: Context, uri: Uri, deadlineNanos: Long): ByteArray? {
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        val descriptor = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        }.getOrNull() ?: return null
        descriptor.use {
            if (it.length > MAX_ATTACHMENT_BYTES) return null
        }
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                if (System.nanoTime() > deadlineNanos) return null
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ATTACHMENT_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private const val MAX_PROCESSING_NANOS = 1_000_000_000L
}

internal object PngTextMetadataExtractor {
    private const val MAX_CHUNK_BYTES = 262_144
    private const val MAX_TEXT_CHARS = 16_384
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )

    fun extract(bytes: ByteArray): Sequence<String> = sequence {
        if (bytes.size < signature.size || !bytes.copyOfRange(0, signature.size).contentEquals(signature)) {
            return@sequence
        }
        var offset = signature.size
        while (offset + 12 <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || length > MAX_CHUNK_BYTES || offset + 12L + length > bytes.size) return@sequence
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            val dataStart = offset + 8
            val data = bytes.copyOfRange(dataStart, dataStart + length)
            val text = when (type) {
                "tEXt" -> parseText(data)
                "iTXt" -> parseInternationalText(data)
                else -> null
            }
            text?.clean()?.let { yield(it) }
            offset += 12 + length
            if (type == "IEND") return@sequence
        }
    }

    private fun parseText(data: ByteArray): String? {
        val separator = data.indexOf(0)
        if (separator !in 1 until data.lastIndex) return null
        return data.copyOfRange(separator + 1, data.size).toString(Charsets.ISO_8859_1)
    }

    private fun parseInternationalText(data: ByteArray): String? {
        var cursor = data.indexOf(0)
        if (cursor !in 1 until data.lastIndex - 2) return null
        cursor += 1
        val compressionFlag = data[cursor].toInt()
        cursor += 2
        if (compressionFlag != 0) return null
        repeat(2) {
            val separator = data.indexOf(0, cursor)
            if (separator < 0) return null
            cursor = separator + 1
        }
        if (cursor >= data.size) return null
        return data.copyOfRange(cursor, data.size).toString(Charsets.UTF_8)
    }

    private fun String.clean(): String? {
        val value = replace('\u0000', ' ').trim().take(MAX_TEXT_CHARS)
        return value.takeIf { it.isNotBlank() }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 24) or
            (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun ByteArray.indexOf(value: Int, startIndex: Int = 0): Int {
        for (index in startIndex until size) {
            if (this[index].toInt() and 0xff == value) return index
        }
        return -1
    }
}
