package com.chikabell.app

import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.importexport.LocationImportPlanner
import com.chikabell.app.importexport.LocationTransferCodec
import com.chikabell.app.importexport.LocationTransferRecord
import com.chikabell.app.importexport.TransferFormat
import com.chikabell.app.importexport.TransferParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTransferCodecTest {
    @Test
    fun rejectsCsvWithMoreThanSupportedColumnsDuringTokenization() {
        val content = (1..13).joinToString(",") { "column$it" }

        val result = LocationTransferCodec.parseCsv(content)

        assertTrue(result is TransferParseResult.Failure)
        val failure = result as TransferParseResult.Failure
        assertTrue(failure.message.contains("列数"))
    }

    @Test
    fun jsonRoundTripKeepsLocationValues() {
        val exported = LocationTransferCodec.exportJson(listOf(location(message = "メモ\n2行目")), 123L, "0.1.0")
        val parsed = LocationTransferCodec.parseJson(exported) as TransferParseResult.Success

        assertEquals(1, parsed.records.size)
        assertEquals("地点", parsed.records.single().name)
        assertEquals("メモ\n2行目", parsed.records.single().message)
        assertEquals(34.1, parsed.records.single().latitude, 0.0)
    }

    @Test
    fun csvRoundTripHandlesBomCommaQuoteAndNewline() {
        val exported = LocationTransferCodec.exportCsv(listOf(location(name = "店,\"A\"", message = "1行\n2行")))
        val parsed = LocationTransferCodec.parseCsv(exported) as TransferParseResult.Success

        assertTrue(exported.startsWith("\uFEFF"))
        assertEquals("店,\"A\"", parsed.records.single().name)
        assertEquals("1行\n2行", parsed.records.single().message)
    }

    @Test
    fun csvExportNeutralizesSpreadsheetFormulaMarkersInTextCells() {
        val exported = LocationTransferCodec.exportCsv(
            listOf(
                location(id = "=id", name = "=cmd", message = "+cmd"),
                location(id = "-id", name = "  -cmd", message = "@cmd", longitude = -135.0),
            )
        )
        val parsed = LocationTransferCodec.parseCsv(exported) as TransferParseResult.Success

        assertTrue(exported.contains("'=id,'=cmd"))
        assertTrue(exported.contains("'-id,'  -cmd"))
        assertTrue(exported.contains("'+cmd"))
        assertTrue(exported.contains("'@cmd"))
        assertEquals(-135.0, parsed.records.last().longitude, 0.0)
    }

    @Test
    fun csvRejectsRowsBeyondLimitDuringTokenization() {
        val header = "name,latitude,longitude,radiusMeters,message,cooldownMinutes,transitionType,enabled"
        val row = "A,34,135,300,m,60,DWELL,true"
        val csv = buildString {
            appendLine(header)
            repeat(LocationTransferCodec.MAX_ROWS + 1) { appendLine(row) }
        }

        val failure = LocationTransferCodec.parseCsv(csv) as TransferParseResult.Failure

        assertTrue(failure.message.contains("${LocationTransferCodec.MAX_ROWS}件"))
    }

    @Test
    fun jsonRejectsLocationsBeyondLimitBeforeTreeParsing() {
        val json = buildString {
            append("{\"schemaVersion\":2,\"loc\\u0061tions\":[")
            repeat(LocationTransferCodec.MAX_ROWS + 1) { index ->
                if (index > 0) append(',')
                append("{}")
            }
            append("]}")
        }

        val failure = LocationTransferCodec.parseJson(json) as TransferParseResult.Failure

        assertTrue(failure.message.contains("${LocationTransferCodec.MAX_ROWS}件"))
    }

    @Test
    fun rejectsUnknownJsonSchema() {
        val parsed = LocationTransferCodec.parseJson("""{"schemaVersion":99,"locations":[]}""")
        assertTrue(parsed is TransferParseResult.Failure)
    }

    @Test
    fun rejectsCsvWithUnknownColumn() {
        val csv = "name,latitude,longitude,radiusMeters,message,cooldownMinutes,transitionType,enabled,unknown\nA,34,135,300,m,60,DWELL,true,x"
        assertTrue(LocationTransferCodec.parseCsv(csv) is TransferParseResult.Failure)
    }

    @Test
    fun previewSkipsSameIdAndNearbySameName() {
        val existing = listOf(location())
        val records = listOf(
            record(id = "id-1", name = "別名", latitude = 35.0),
            record(id = "new", name = "地点", latitude = 34.1001),
            record(id = "new-2", name = "新規", latitude = 35.0),
        )
        val preview = LocationImportPlanner.preview(records, existing, TransferFormat.JSON)

        assertEquals(2, preview.duplicateCount)
        assertEquals(1, preview.candidates.size)
        assertTrue(preview.canApply)
    }

    @Test
    fun previewBlocksMoreThanOneHundredEnabledLocations() {
        val existing = (1..100).map { location(id = "id-$it", name = "地点$it", latitude = 30.0 + it / 1000.0) }
        val preview = LocationImportPlanner.preview(listOf(record(id = "new", name = "追加", latitude = 40.0)), existing, TransferFormat.CSV)

        assertFalse(preview.canApply)
        assertTrue(preview.errors.single().contains("100件"))
    }

    private fun record(id: String, name: String, latitude: Double) = LocationTransferRecord(
        id = id,
        name = name,
        message = "メモ",
        latitude = latitude,
        longitude = 135.0,
        radiusMeters = 300,
        loiteringDelaySeconds = 60,
        cooldownMinutes = 720,
        enabled = true,
        sourceUrl = null,
    )

    private fun location(
        id: String = "id-1",
        name: String = "地点",
        message: String = "メモ",
        latitude: Double = 34.1,
        longitude: Double = 135.0,
    ) = SavedLocation(
        id = id,
        name = name,
        message = message,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = 300,
        transitionType = TransitionType.DWELL,
        loiteringDelayMs = 60_000,
        cooldownMinutes = 720,
        enabled = true,
        sourceType = SourceType.MANUAL,
        sourceUrl = null,
        sourceText = null,
        createdAt = 1,
        updatedAt = 1,
        lastNotifiedAt = null,
        lastEventAt = null,
        registrationStatus = RegistrationStatus.REGISTERED,
        registrationErrorCode = null,
        registrationErrorMessage = null,
        lastRegisteredAt = 1,
        sortOrder = 1,
    )
}
