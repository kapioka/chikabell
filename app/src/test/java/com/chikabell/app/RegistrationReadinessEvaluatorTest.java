package com.chikabell.app;

import static org.junit.Assert.assertTrue;

import com.chikabell.app.geofence.RegistrationReadiness;
import com.chikabell.app.geofence.RegistrationReadinessEvaluator;
import com.chikabell.app.permission.BackgroundLocationStatus;
import com.chikabell.app.permission.ForegroundLocationStatus;
import com.chikabell.app.permission.GooglePlayServicesStatus;
import com.chikabell.app.permission.LocationServicesStatus;
import com.chikabell.app.permission.NotificationPermissionStatus;
import com.chikabell.app.permission.PermissionSnapshot;
import org.junit.Test;

public class RegistrationReadinessEvaluatorTest {
    @Test
    public void readyWhenPreciseBackgroundLocationAndPlayServicesAreAvailable() {
        PermissionSnapshot snapshot = new PermissionSnapshot(
                NotificationPermissionStatus.Granted,
                ForegroundLocationStatus.Precise,
                BackgroundLocationStatus.Granted,
                LocationServicesStatus.Enabled,
                GooglePlayServicesStatus.Available
        );

        RegistrationReadiness result = RegistrationReadinessEvaluator.INSTANCE.evaluate(snapshot, 1);

        assertTrue(result instanceof RegistrationReadiness.Ready);
    }

    @Test
    public void blocksApproximateOnlyLocation() {
        PermissionSnapshot snapshot = new PermissionSnapshot(
                NotificationPermissionStatus.Granted,
                ForegroundLocationStatus.ApproximateOnly,
                BackgroundLocationStatus.Granted,
                LocationServicesStatus.Enabled,
                GooglePlayServicesStatus.Available
        );

        RegistrationReadiness result = RegistrationReadinessEvaluator.INSTANCE.evaluate(snapshot, 1);

        assertTrue(result instanceof RegistrationReadiness.Blocked);
    }

    @Test
    public void blocksWhenNoEnabledLocationsExist() {
        PermissionSnapshot snapshot = new PermissionSnapshot(
                NotificationPermissionStatus.Granted,
                ForegroundLocationStatus.Precise,
                BackgroundLocationStatus.Granted,
                LocationServicesStatus.Enabled,
                GooglePlayServicesStatus.Available
        );

        RegistrationReadiness result = RegistrationReadinessEvaluator.INSTANCE.evaluate(snapshot, 0);

        assertTrue(result instanceof RegistrationReadiness.Blocked);
    }
}
