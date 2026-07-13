package com.chikabell.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chikabell.app.geofence.DistanceCalculator;
import org.junit.Test;

public class DistanceCalculatorTest {
    @Test
    public void samePointIsWithinRadius() {
        assertTrue(
                DistanceCalculator.INSTANCE.isWithinRadius(
                        35.0,
                        135.0,
                        35.0,
                        135.0,
                        300
                )
        );
    }

    @Test
    public void distantPointIsOutsideSmallRadius() {
        assertFalse(
                DistanceCalculator.INSTANCE.isWithinRadius(
                        35.0,
                        135.0,
                        35.003,
                        135.003,
                        100
                )
        );
    }
}
