package com.chikabell.app

import com.chikabell.app.ai.*
import com.chikabell.app.importexport.*
import com.chikabell.app.share.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiPlaceVerifierTest {
    private val record = LocationTransferRecord(null, "fictional place", "", 1.0, 2.0, 300, 60, 720, false, "https://example.com/place")
    private val request = AiSearchRequest(area = "fictional area", target = "place")
    @Test fun independentLocationIsRequiredAndAiCoordinatesAreReplacedOnlyAfterSelection(): Unit = runBlocking {
        val verifier = AiPlaceVerifier(AddressCandidateProvider { listOf(AddressCoordinateCandidate("verified address", 35.0, 135.0)) })
        val result = verifier.verify(LocationTransferCodec.encodeImportJson(listOf(record)), request, 0)
        assertEquals(1, result.candidates.size)
        val selected = AiImportText.parse(verifier.selectedJson(result.candidates, mapOf(0 to 0))) as TransferParseResult.Success
        assertEquals(35.0, selected.records.single().latitude, 0.0)
        assertEquals(135.0, selected.records.single().longitude, 0.0)
        assertThrows(IllegalArgumentException::class.java) { verifier.selectedJson(result.candidates, emptyMap()) }
    }
    @Test fun rejectsUnverifiedOrSourceFreePlaces() = runBlocking {
        val verifier = AiPlaceVerifier(AddressCandidateProvider { emptyList() })
        val unverified = verifier.verify(LocationTransferCodec.encodeImportJson(listOf(record)), request, 0)
        assertTrue(unverified.candidates.isEmpty())
        assertEquals(1, unverified.excludedReasons.size)
        val noSource = verifier.verify(LocationTransferCodec.encodeImportJson(listOf(record.copy(sourceUrl = null))), request, 0)
        assertTrue(noSource.candidates.isEmpty())
    }
    @Test fun excludesLocationsOutsideExplicitSearchRadius() = runBlocking {
        val verifier = AiPlaceVerifier(AddressCandidateProvider { listOf(AddressCoordinateCandidate("far", 36.0, 135.0)) })
        val gpsRequest = request.copy(areaMode = SearchAreaMode.DEVICE_LOCATION, origin = SearchOrigin(35.0, 135.0, 10f, 1000), distanceKm = 1)
        assertTrue(verifier.verify(LocationTransferCodec.encodeImportJson(listOf(record)), gpsRequest, 1000).candidates.isEmpty())
    }
    @Test fun rejectsMalformedSchemaAndOversizedCandidateBatch() = runBlocking {
        val verifier = AiPlaceVerifier(AddressCandidateProvider { error("must not query") })
        assertTrue(verifier.verify("{broken}", request, 0).candidates.isEmpty())
        assertTrue(verifier.verify(LocationTransferCodec.encodeImportJson(listOf(record, record.copy(name = "other"))), request.copy(count = 1), 0).candidates.isEmpty())
        assertTrue(verifier.verify(LocationTransferCodec.encodeImportJson(listOf(record.copy(latitude = 999.0))), request, 0).candidates.isEmpty())
    }
}
