package com.chikabell.app

import com.chikabell.app.geofence.GeofenceEventBatchProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceEventBatchProcessorTest {
    @Test fun `all request ids share one event budget`() = runBlocking {
        val processed = mutableListOf<String>()

        val completed = GeofenceEventBatchProcessor.processWithinBudget(
            requestIds = listOf("first", "second", "third"),
            budgetMillis = 100L,
        ) { requestId ->
            processed += requestId
            delay(60L)
        }

        assertFalse(completed)
        assertEquals(listOf("first", "second"), processed)
    }

    @Test fun `duplicate request ids are processed once within budget`() = runBlocking {
        val processed = mutableListOf<String>()

        val completed = GeofenceEventBatchProcessor.processWithinBudget(
            requestIds = listOf("first", "first", "second"),
            budgetMillis = 1_000L,
        ) { processed += it }

        assertTrue(completed)
        assertEquals(listOf("first", "second"), processed)
    }
}
