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
                        34.71777884558219,
                        135.60471137023873,
                        34.71777884558219,
                        135.60471137023873,
                        300
                )
        );
    }

    @Test
    public void distantPointIsOutsideSmallRadius() {
        assertFalse(
                DistanceCalculator.INSTANCE.isWithinRadius(
                        34.71777884558219,
                        135.60471137023873,
                        34.72077884558219,
                        135.60771137023873,
                        100
                )
        );
    }
}
