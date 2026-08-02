package com.chikabell.app.importexport

import com.chikabell.app.domain.model.SavedLocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object LocationTransferCodec {
    const val SCHEMA_VERSION = 2
    const val MAX_FILE_CHARS = 2_000_000
    const val MAX_ROWS = 1_000
    private const val MAX_CSV_COLUMNS = 12
    private val json = Json { prettyPrint = true; explicitNulls = false }
    private val csvHeaders = listOf(
        "id", "name", "latitude", "longitude", "radiusMeters", "message",
        "loiteringDelaySeconds", "cooldownMinutes", "transitionType", "enabled", "sourceUrl", "tags",
    )
    private val requiredCsvHeaders = setOf(
        "name", "latitude", "longitude", "radiusMeters", "message", "cooldownMinutes", "transitionType", "enabled",
    )
    private val formulaSensitiveCsvColumns = setOf(0, 1, 5, 10, 11)

    fun exportJson(locations: List<SavedLocation>, exportedAt: Long, appVersion: String): String {
        val root = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", exportedAt)
            put("appVersion", appVersion)
            put("locations", buildJsonArray { locations.forEach { add(it.toJson()) } })
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    fun exportCsv(locations: List<SavedLocation>): String {
        val rows = buildList {
            add(csvHeaders.joinToString(","))
            locations.forEach { location ->
                add(
                    listOf(
                        location.id,
                        location.name,
                        location.latitude.toString(),
                        location.longitude.toString(),
                        location.radiusMeters.toString(),
                        location.message,
                        ((location.loiteringDelayMs ?: 60_000) / 1_000).toString(),
                        location.cooldownMinutes.toString(),
                        location.transitionType.name,
                        location.enabled.toString(),
                        location.sourceUrl.orEmpty(),
                        location.tags.joinToString("|") { tag -> tag.name },
                    ).mapIndexed { index, value ->
                        escapeCsv(value, neutralizeFormula = index in formulaSensitiveCsvColumns)
                    }.joinToString(",")
                )
            }
        }
        return "\uFEFF" + rows.joinToString("\r\n") + "\r\n"
    }

    fun parseJson(content: String): TransferParseResult {
        if (content.length > MAX_FILE_CHARS) return TransferParseResult.Failure("ファイルが2MB相当の上限を超えています")
        return runCatching {
            requireJsonLocationCountWithinLimit(content)
            val root = json.parseToJsonElement(content).jsonObject
            val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            require(version == 1 || version == SCHEMA_VERSION) { "未対応のschemaVersionです" }
            val locations = root["locations"]?.jsonArray ?: error("locationsがありません")
            require(locations.size <= MAX_ROWS) { "地点数が${MAX_ROWS}件の上限を超えています" }
            TransferParseResult.Success(locations.mapIndexed { index, element -> element.jsonObject.toRecord(index + 1) })
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            val safeMessage = if (
                message.startsWith("未対応のschemaVersion") ||
                message.startsWith("locationsがありません") ||
                message.startsWith("地点数が") ||
                message.startsWith("JSON ")
            ) message else "JSON形式が不正です"
            TransferParseResult.Failure(safeMessage)
        }
    }

    fun parseCsv(content: String): TransferParseResult {
        if (content.length > MAX_FILE_CHARS) return TransferParseResult.Failure("ファイルが2MB相当の上限を超えています")
        return runCatching {
            val rows = parseCsvRows(content.removePrefix("\uFEFF"))
            require(rows.isNotEmpty()) { "CSVが空です" }
            require(rows.size - 1 <= MAX_ROWS) { "地点数が${MAX_ROWS}件の上限を超えています" }
            val headers = rows.first().mapIndexed { index, value -> value.trim().let { require(it.isNotEmpty()) { "列${index + 1}の名前が空です" }; it } }
            require(headers.toSet().containsAll(requiredCsvHeaders)) { "CSVの必須列が不足しています" }
            require(headers.size == headers.toSet().size) { "CSVに重複した列名があります" }
            val known = csvHeaders.toSet()
            require(headers.all { it in known }) { "未対応のCSV列があります" }
            val index = headers.withIndex().associate { it.value to it.index }
            TransferParseResult.Success(rows.drop(1).filterNot { row -> row.all(String::isBlank) }.mapIndexed { rowIndex, row ->
                require(row.size == headers.size) { "CSV ${rowIndex + 2}行目の列数が一致しません" }
                fun value(name: String): String = row[index.getValue(name)].trim()
                LocationTransferRecord(
                    id = index["id"]?.let(row::get)?.trim()?.takeIf(String::isNotEmpty),
                    name = value("name"),
                    latitude = value("latitude").toDoubleOrNull() ?: error("CSV ${rowIndex + 2}行目の緯度が不正です"),
                    longitude = value("longitude").toDoubleOrNull() ?: error("CSV ${rowIndex + 2}行目の経度が不正です"),
                    radiusMeters = value("radiusMeters").toIntOrNull() ?: error("CSV ${rowIndex + 2}行目の半径が不正です"),
                    message = value("message"),
                    loiteringDelaySeconds = index["loiteringDelaySeconds"]?.let(row::get)?.trim()?.toIntOrNull() ?: 60,
                    cooldownMinutes = value("cooldownMinutes").toLongOrNull() ?: error("CSV ${rowIndex + 2}行目の再通知分が不正です"),
                    enabled = parseBoolean(value("enabled"), "CSV ${rowIndex + 2}行目"),
                    sourceUrl = index["sourceUrl"]?.let(row::get)?.trim()?.takeIf(String::isNotEmpty),
                    tags = index["tags"]?.let(row::get)?.splitTags().orEmpty(),
                ).also { require(value("transitionType") == "DWELL") { "CSV ${rowIndex + 2}行目のtransitionTypeはDWELLのみ対応です" } }
            })
        }.getOrElse { TransferParseResult.Failure(it.message ?: "CSVを解析できません") }
    }

    private fun SavedLocation.toJson(): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("message", message)
        put("latitude", latitude); put("longitude", longitude); put("radiusMeters", radiusMeters)
        put("loiteringDelaySeconds", (loiteringDelayMs ?: 60_000) / 1_000)
        put("cooldownMinutes", cooldownMinutes); put("transitionType", transitionType.name); put("enabled", enabled)
        sourceUrl?.let { put("sourceUrl", it) }
        put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it.name)) } })
    }

    private fun JsonObject.toRecord(row: Int): LocationTransferRecord {
        fun required(name: String): JsonPrimitive = this[name]?.jsonPrimitive ?: error("JSON ${row}件目の${name}がありません")
        val transition = required("transitionType").content
        require(transition == "DWELL") { "JSON ${row}件目のtransitionTypeはDWELLのみ対応です" }
        return LocationTransferRecord(
            id = this["id"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty),
            name = required("name").content,
            message = required("message").content,
            latitude = required("latitude").doubleOrNull ?: error("JSON ${row}件目の緯度が不正です"),
            longitude = required("longitude").doubleOrNull ?: error("JSON ${row}件目の経度が不正です"),
            radiusMeters = required("radiusMeters").intOrNull ?: error("JSON ${row}件目の半径が不正です"),
            loiteringDelaySeconds = this["loiteringDelaySeconds"]?.jsonPrimitive?.intOrNull ?: 60,
            cooldownMinutes = required("cooldownMinutes").longOrNull ?: error("JSON ${row}件目の再通知分が不正です"),
            enabled = required("enabled").booleanOrNull ?: error("JSON ${row}件目のenabledが不正です"),
            sourceUrl = this["sourceUrl"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty),
            tags = this["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }.orEmpty(),
        )
    }

    private fun String.splitTags(): List<String> =
        split("|", "、", "，", ";")
            .map { it.trim().removePrefix("#").trim() }
            .filter(String::isNotBlank)

    private fun parseBoolean(value: String, row: String): Boolean = when (value.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> error("$row のenabledが不正です")
    }

    private fun escapeCsv(value: String, neutralizeFormula: Boolean): String {
        val firstContentIndex = value.indexOfFirst { !it.isWhitespace() }
        val safeValue = if (
            neutralizeFormula &&
            firstContentIndex >= 0 &&
            value[firstContentIndex] in setOf('=', '+', '-', '@')
        ) {
            "'$value"
        } else {
            value
        }
        if (safeValue.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return safeValue
        return "\"${safeValue.replace("\"", "\"\"")}\""
    }

    private fun parseCsvRows(content: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                quoted && char == '"' && index + 1 < content.length && content[index + 1] == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    require(row.size < MAX_CSV_COLUMNS) { "CSVの列数が上限を超えています" }
                    row.add(field.toString())
                    field.clear()
                }
                !quoted && (char == '\r' || char == '\n') -> {
                    row.add(field.toString())
                    field.clear()
                    require(rows.size < MAX_ROWS + 1) { "地点数が${MAX_ROWS}件の上限を超えています" }
                    rows.add(row)
                    row = mutableListOf()
                    if (char == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSVの引用符が閉じていません" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            require(row.size < MAX_CSV_COLUMNS) { "CSVの列数が上限を超えています" }
            row.add(field.toString())
            require(rows.size < MAX_ROWS + 1) { "地点数が${MAX_ROWS}件の上限を超えています" }
            rows.add(row)
        }
        return rows
    }

    /**
     * Counts the top-level locations array without constructing a JSON tree so oversized
     * imports fail before kotlinx.serialization allocates every location object.
     */
    private fun requireJsonLocationCountWithinLimit(content: String) {
        var index = content.indexOfFirst { !it.isWhitespace() }
        if (index < 0 || content[index] != '{') return
        var depth = 1
        var expectingTopLevelKey = true
        index++
        while (index < content.length && depth > 0) {
            when (content[index]) {
                '"' -> {
                    val token = readJsonString(content, index) ?: return
                    index = token.second
                    if (depth == 1 && expectingTopLevelKey) {
                        expectingTopLevelKey = false
                        var valueIndex = index
                        while (valueIndex < content.length && content[valueIndex].isWhitespace()) valueIndex++
                        if (valueIndex >= content.length || content[valueIndex] != ':') continue
                        valueIndex++
                        while (valueIndex < content.length && content[valueIndex].isWhitespace()) valueIndex++
                        if (token.first == "locations" && valueIndex < content.length && content[valueIndex] == '[') {
                            index = requireJsonArrayCountWithinLimit(content, valueIndex) ?: return
                        }
                    }
                }
                '{', '[' -> { depth++; index++ }
                '}', ']' -> { depth--; index++ }
                ',' -> {
                    if (depth == 1) expectingTopLevelKey = true
                    index++
                }
                else -> index++
            }
        }
    }

    private fun requireJsonArrayCountWithinLimit(content: String, openingBracket: Int): Int? {
        var index = openingBracket + 1
        var depth = 1
        var expectingElement = true
        var count = 0
        while (index < content.length) {
            val char = content[index]
            if (char == '"') {
                if (depth == 1 && expectingElement) {
                    count++
                    require(count <= MAX_ROWS) { "地点数が${MAX_ROWS}件の上限を超えています" }
                    expectingElement = false
                }
                val token = readJsonString(content, index) ?: return null
                index = token.second
                continue
            }
            if (depth == 1) {
                when {
                    char.isWhitespace() -> { index++; continue }
                    char == ']' -> return index + 1
                    char == ',' -> { expectingElement = true; index++; continue }
                    expectingElement -> {
                        count++
                        require(count <= MAX_ROWS) { "地点数が${MAX_ROWS}件の上限を超えています" }
                        expectingElement = false
                    }
                }
            }
            when (char) {
                '[', '{' -> depth++
                ']', '}' -> depth--
            }
            index++
        }
        return null
    }

    private fun readJsonString(content: String, openingQuote: Int): Pair<String, Int>? {
        val decoded = StringBuilder()
        var index = openingQuote + 1
        while (index < content.length) {
            val char = content[index++]
            when (char) {
                '"' -> return decoded.toString() to index
                '\\' -> {
                    if (index >= content.length) return null
                    when (val escaped = content[index++]) {
                        '"', '\\', '/' -> decoded.append(escaped)
                        'b' -> decoded.append('\b')
                        'f' -> decoded.append('\u000C')
                        'n' -> decoded.append('\n')
                        'r' -> decoded.append('\r')
                        't' -> decoded.append('\t')
                        'u' -> {
                            if (index + 4 > content.length) return null
                            val codePoint = content.substring(index, index + 4).toIntOrNull(16) ?: return null
                            decoded.append(codePoint.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                }
                else -> decoded.append(char)
            }
        }
        return null
    }
}
