package com.chikabell.app

import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.geofence.GeofenceEventHandoff
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceEventHandoffTest {
    @Test fun `successful service handoff does not enqueue duplicate verification`() = runBlocking {
        var enqueued = false
        GeofenceEventHandoff.deliver(
            listOf("first", "first", "", "second"), TransitionType.DWELL, 123L,
            startService = { ids, type, at ->
                assertEquals(listOf("first", "second"), ids)
                assertEquals(TransitionType.DWELL, type)
                assertEquals(123L, at)
                true
            },
            enqueue = { _, _, _ -> enqueued = true },
        )
        assertFalse(enqueued)
    }

    @Test fun `rejected service preserves event in worker handoff`() = runBlocking {
        var accepted = false
        GeofenceEventHandoff.deliver(
            listOf("first", "first", "second"), TransitionType.ENTER, 123L,
            startService = { _, _, _ -> false },
            enqueue = { ids, type, at ->
                assertEquals(listOf("first", "second"), ids)
                assertEquals(TransitionType.ENTER, type)
                assertEquals(123L, at)
                accepted = true
            },
        )
        assertTrue(accepted)
    }

    @Test fun `exit queues cancellation without starting foreground verification`() = runBlocking {
        var queued = false
        GeofenceEventHandoff.deliver(
            listOf("first"), TransitionType.EXIT, 123L,
            startService = { _, _, _ -> error("EXIT must not start a service") },
            enqueue = { _, type, _ ->
                assertEquals(TransitionType.EXIT, type)
                queued = true
            },
        )
        assertTrue(queued)
    }

    @Test fun `stalled work persistence cannot hold receiver indefinitely`() = runBlocking {
        var cancelled = false
        var timedOut = false
        try {
            GeofenceEventHandoff.deliver(
                listOf("first"), TransitionType.ENTER, 123L,
                startService = { _, _, _ -> false },
                enqueue = { _, _, _ ->
                    try { awaitCancellation() } finally { cancelled = true }
                },
                timeoutMillis = 50L,
            )
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)
        assertTrue(cancelled)
    }

    @Test fun `persistence failure is reported without trying receiver verification`() = runBlocking {
        val failure = IllegalStateException("unavailable")
        var observed: Exception? = null
        try {
            GeofenceEventHandoff.deliver(
                listOf("first"), TransitionType.ENTER, 123L,
                startService = { _, _, _ -> false },
                enqueue = { _, _, _ -> throw failure },
            )
        } catch (error: Exception) { observed = error }
        assertEquals(failure, observed)
    }

    @Test fun `empty delivery does not start service or enqueue work`() = runBlocking {
        GeofenceEventHandoff.deliver(
            listOf("", " "), TransitionType.ENTER, 123L,
            startService = { _, _, _ -> error("empty delivery") },
            enqueue = { _, _, _ -> error("empty delivery") },
        )
    }
}
