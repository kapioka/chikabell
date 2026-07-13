package com.chikabell.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chikabell.app.geofence.GeofenceEventPolicy;
import org.junit.Test;

public class GeofenceEventPolicyTest {
    @Test
    public void detectsDuplicateEventsInsideOneMinute() {
        assertTrue(GeofenceEventPolicy.INSTANCE.isDuplicateEvent(1_000L, 30_000L));
    }

    @Test
    public void allowsEventsAfterOneMinute() {
        assertFalse(GeofenceEventPolicy.INSTANCE.isDuplicateEvent(1_000L, 61_000L));
    }

    @Test
    public void detectsCooldownWindow() {
        assertTrue(GeofenceEventPolicy.INSTANCE.isInCooldown(1_000L, 10L, 100_000L));
    }

    @Test
    public void zeroCooldownDoesNotSuppress() {
        assertFalse(GeofenceEventPolicy.INSTANCE.isInCooldown(1_000L, 0L, 1_001L));
    }
}
