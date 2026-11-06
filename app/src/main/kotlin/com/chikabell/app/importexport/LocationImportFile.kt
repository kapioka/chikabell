package com.chikabell.app.importexport

import android.content.Context
import android.net.Uri
import java.io.IOException

internal class TransferFileTooLarge : IOException()

internal fun readTransferText(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri) ?: error("ファイルを開けません")
    return stream.bufferedReader(Charsets.UTF_8).use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (result.length + count > LocationTransferCodec.MAX_FILE_CHARS) throw TransferFileTooLarge()
            result.append(buffer, 0, count)
        }
        result.toString()
    }
}

internal fun detectTransferFormat(name: String, mime: String?, content: String): TransferFormat? = when {
    name.endsWith(".json", ignoreCase = true) -> TransferFormat.JSON
    name.endsWith(".csv", ignoreCase = true) -> TransferFormat.CSV
    mime == "application/json" || mime == "text/json" -> TransferFormat.JSON
    mime == "text/csv" || mime == "text/comma-separated-values" -> TransferFormat.CSV
    else -> {
        val text = content.removePrefix("\uFEFF").trimStart()
        when {
            text.startsWith("{") || text.startsWith("[") || text.startsWith("```") -> TransferFormat.JSON
            ',' in text.lineSequence().first() -> TransferFormat.CSV
            else -> null
        }
    }
}
