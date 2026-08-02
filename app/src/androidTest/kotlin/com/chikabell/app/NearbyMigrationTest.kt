package com.chikabell.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chikabell.app.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration10To12PreservesLocationAndAddsSafeNearbyDefaults() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                """
                INSERT INTO locations (
                    id, name, message, latitude, longitude, radiusMeters, transitionType,
                    loiteringDelayMs, cooldownMinutes, enabled, sourceType, sourceUrl, sourceText,
                    createdAt, updatedAt, lastNotifiedAt, lastEventAt, registrationStatus,
                    registrationErrorCode, registrationErrorMessage, lastRegisteredAt, sortOrder,
                    registrationGenerationId
                ) VALUES (
                    'saved', '既存地点', '既存メッセージ', 35.0, 139.0, 500, 'DWELL',
                    60000, 720, 1, 'MANUAL', NULL, NULL,
                    1000, 1000, 900, 950, 'REGISTERED',
                    NULL, NULL, 800, 1, 'generation'
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            12,
            true,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
        ).use { db ->
            db.query(
                "SELECT name, radiusMeters, nearbyState, snoozedUntil, lastVerificationAt, lastValidLocationAt " +
                    "FROM locations WHERE id = 'saved'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("既存地点", cursor.getString(0))
                assertEquals(500, cursor.getInt(1))
                assertEquals("MONITORING", cursor.getString(2))
                assertEquals(true, cursor.isNull(3))
                assertEquals(true, cursor.isNull(4))
                assertEquals(true, cursor.isNull(5))
            }
        }
    }

    private companion object {
        const val TEST_DB = "nearby-migration-test"
    }
}
