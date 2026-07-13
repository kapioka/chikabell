package com.chikabell.app;

import static org.junit.Assert.assertTrue;

import com.chikabell.app.domain.model.LocationDraft;
import com.chikabell.app.domain.validation.LocationValidationError;
import com.chikabell.app.domain.validation.LocationValidator;
import java.util.List;
import org.junit.Test;

public class LocationValidatorTest {
    @Test
    public void acceptsValidManualLocation() {
        LocationDraft draft = new LocationDraft(
                "駅",
                "帰りに確認",
                35.681236,
                139.767125,
                300,
                60,
                0L,
                true
        );

        assertTrue(LocationValidator.INSTANCE.validate(draft).isEmpty());
    }

    @Test
    public void rejectsOutOfRangeRadius() {
        LocationDraft draft = new LocationDraft(
                "駅",
                "",
                35.681236,
                139.767125,
                50,
                60,
                0L,
                true
        );

        List<LocationValidationError> errors = LocationValidator.INSTANCE.validate(draft);

        assertTrue(errors.contains(LocationValidationError.Radius));
    }

    @Test
    public void rejectsOutOfRangeCoordinates() {
        LocationDraft draft = new LocationDraft(
                "地点",
                "",
                91.0,
                181.0,
                300,
                60,
                0L,
                true
        );

        List<LocationValidationError> errors = LocationValidator.INSTANCE.validate(draft);

        assertTrue(errors.contains(LocationValidationError.Latitude));
        assertTrue(errors.contains(LocationValidationError.Longitude));
    }

    @Test
    public void rejectsOutOfRangeLoiteringDelay() {
        LocationDraft draft = new LocationDraft(
                "地点",
                "",
                35.0,
                135.0,
                300,
                5,
                0L,
                true
        );

        assertTrue(LocationValidator.INSTANCE.validate(draft).contains(LocationValidationError.LoiteringDelay));
    }
}
