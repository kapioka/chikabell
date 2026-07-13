package com.chikabell.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.chikabell.app.data.database.dao.LocationDao
import com.chikabell.app.data.database.dao.NotificationHistoryDao
import com.chikabell.app.data.database.dao.NotificationPresetDao
import com.chikabell.app.data.database.dao.GeofenceRestoreAttemptDao
import com.chikabell.app.data.database.dao.GeofenceHealthCheckDao
import com.chikabell.app.data.database.dao.GeofenceRegistrationAttemptDao
import com.chikabell.app.data.database.entity.GeofenceHealthCheckEntity
import com.chikabell.app.data.database.entity.GeofenceRegistrationAttemptEntity
import com.chikabell.app.data.database.entity.LocationEntity
import com.chikabell.app.data.database.entity.LocationTagCrossRef
import com.chikabell.app.data.database.entity.NotificationHistoryEntity
import com.chikabell.app.data.database.entity.NotificationPresetEntity
import com.chikabell.app.data.database.entity.GeofenceRestoreAttemptEntity
import com.chikabell.app.data.database.entity.TagEntity
import com.chikabell.app.domain.model.LocationNotificationDefaults

@Database(
    entities = [
        LocationEntity::class,
        NotificationHistoryEntity::class,
        NotificationPresetEntity::class,
        GeofenceRestoreAttemptEntity::class,
        GeofenceHealthCheckEntity::class,
        GeofenceRegistrationAttemptEntity::class,
        TagEntity::class,
        LocationTagCrossRef::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun notificationPresetDao(): NotificationPresetDao
    abstract fun geofenceRestoreAttemptDao(): GeofenceRestoreAttemptDao
    abstract fun geofenceHealthCheckDao(): GeofenceHealthCheckDao
    abstract fun geofenceRegistrationAttemptDao(): GeofenceRegistrationAttemptDao

    companion object {
        private const val DATABASE_NAME = "nearby_location_reminder.db"
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_history` (
                        `id` TEXT NOT NULL,
                        `locationId` TEXT,
                        `locationNameSnapshot` TEXT NOT NULL,
                        `messageSnapshot` TEXT NOT NULL,
                        `latitudeSnapshot` REAL NOT NULL,
                        `longitudeSnapshot` REAL NOT NULL,
                        `radiusSnapshot` INTEGER NOT NULL,
                        `transitionType` TEXT NOT NULL,
                        `eventAt` INTEGER NOT NULL,
                        `postedAt` INTEGER,
                        `deliveryStatus` TEXT NOT NULL,
                        `deliveryReason` TEXT,
                        `userState` TEXT NOT NULL,
                        `readAt` INTEGER,
                        `completedAt` INTEGER,
                        `dismissedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_eventAt` ON `notification_history` (`eventAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_deliveryStatus` ON `notification_history` (`deliveryStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_locationId` ON `notification_history` (`locationId`)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM `notification_history`")
                db.execSQL(
                    """
                    UPDATE `locations`
                    SET
                        `transitionType` = 'DWELL',
                        `loiteringDelayMs` = ${LocationNotificationDefaults.LOITERING_DELAY_MS},
                        `cooldownMinutes` = ${LocationNotificationDefaults.COOLDOWN_MINUTES},
                        `lastNotifiedAt` = NULL,
                        `lastEventAt` = NULL,
                        `registrationStatus` = 'INACTIVE',
                        `registrationErrorCode` = NULL,
                        `registrationErrorMessage` = NULL,
                        `lastRegisteredAt` = NULL,
                        `updatedAt` = ${System.currentTimeMillis()}
                    """.trimIndent(),
                )
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE `locations`
                    SET
                        `loiteringDelayMs` = ${LocationNotificationDefaults.LOITERING_DELAY_MS},
                        `registrationStatus` = 'INACTIVE',
                        `registrationErrorCode` = NULL,
                        `registrationErrorMessage` = NULL,
                        `lastRegisteredAt` = NULL,
                        `updatedAt` = ${System.currentTimeMillis()}
                    WHERE `transitionType` = 'DWELL'
                    """.trimIndent(),
                )
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_presets` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `radiusMeters` INTEGER NOT NULL,
                        `loiteringDelaySeconds` INTEGER NOT NULL,
                        `cooldownMinutes` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                val presets = listOf(
                    "('walk', '徒歩', 300, 60, 720, 0)",
                    "('early_walk', '徒歩早め', 400, 60, 720, 1)",
                    "('bicycle', '自転車', 500, 45, 720, 2)",
                    "('car', '車', 1000, 30, 720, 3)",
                    "('custom', 'カスタム', 300, 60, 720, 4)",
                )
                presets.forEach { values ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `notification_presets` " +
                            "(`id`, `name`, `radiusMeters`, `loiteringDelaySeconds`, `cooldownMinutes`, `sortOrder`) " +
                            "VALUES $values",
                    )
                }
                db.execSQL(
                    """
                    UPDATE `locations`
                    SET `loiteringDelayMs` = 60000,
                        `registrationStatus` = 'INACTIVE',
                        `registrationErrorCode` = NULL,
                        `registrationErrorMessage` = NULL,
                        `lastRegisteredAt` = NULL,
                        `updatedAt` = ${System.currentTimeMillis()}
                    WHERE `transitionType` = 'DWELL' AND `loiteringDelayMs` = 90000
                    """.trimIndent(),
                )
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `geofence_restore_attempts` (
                        `id` TEXT NOT NULL,
                        `trigger` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER,
                        `result` TEXT NOT NULL,
                        `runAttemptCount` INTEGER NOT NULL,
                        `enabledCount` INTEGER NOT NULL,
                        `registeredCount` INTEGER NOT NULL,
                        `errorCode` TEXT,
                        `message` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notification_history` ADD COLUMN `deviceLatitude` REAL")
                db.execSQL("ALTER TABLE `notification_history` ADD COLUMN `deviceLongitude` REAL")
                db.execSQL("ALTER TABLE `notification_history` ADD COLUMN `deviceAccuracyMeters` REAL")
                db.execSQL("ALTER TABLE `notification_history` ADD COLUMN `deviceLocationAt` INTEGER")
                db.execSQL("ALTER TABLE `notification_history` ADD COLUMN `deviceLocationProvider` TEXT")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tags` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalizedName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_normalizedName` ON `tags` (`normalizedName`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `location_tags` (
                        `locationId` TEXT NOT NULL,
                        `tagId` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`locationId`, `tagId`),
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_tags_locationId` ON `location_tags` (`locationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_tags_tagId` ON `location_tags` (`tagId`)")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `geofence_health_checks` (
                        `id` TEXT NOT NULL,
                        `trigger` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER NOT NULL,
                        `result` TEXT NOT NULL,
                        `enabledCount` INTEGER NOT NULL,
                        `registeredCount` INTEGER NOT NULL,
                        `notRegisteredCount` INTEGER NOT NULL,
                        `errorCount` INTEGER NOT NULL,
                        `staleRegisteredCount` INTEGER NOT NULL,
                        `oldestRegisteredAt` INTEGER,
                        `newestRegisteredAt` INTEGER,
                        `lastEventAt` INTEGER,
                        `lastNotifiedAt` INTEGER,
                        `shouldRestore` INTEGER NOT NULL,
                        `restoreReason` TEXT NOT NULL,
                        `restoreAttemptId` TEXT,
                        `googlePlayServices` TEXT NOT NULL,
                        `locationServices` TEXT NOT NULL,
                        `foregroundLocation` TEXT NOT NULL,
                        `backgroundLocation` TEXT NOT NULL,
                        `notificationPermission` TEXT NOT NULL,
                        `errorCode` TEXT,
                        `message` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_geofence_health_checks_startedAt` ON `geofence_health_checks` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_geofence_health_checks_result` ON `geofence_health_checks` (`result`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_geofence_health_checks_trigger` ON `geofence_health_checks` (`trigger`)")
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `locations` ADD COLUMN `registrationGenerationId` TEXT")
                db.execSQL("ALTER TABLE `notification_history` ADD COLUMN `registrationGenerationId` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `geofence_registration_attempts` (
                        `id` TEXT NOT NULL,
                        `generationId` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER,
                        `result` TEXT NOT NULL,
                        `requestedCount` INTEGER NOT NULL,
                        `acceptedCount` INTEGER NOT NULL,
                        `errorCode` TEXT,
                        `message` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_geofence_registration_attempts_generationId` " +
                        "ON `geofence_registration_attempts` (`generationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_geofence_registration_attempts_startedAt` " +
                        "ON `geofence_registration_attempts` (`startedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_geofence_registration_attempts_result` " +
                        "ON `geofence_registration_attempts` (`result`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_geofence_registration_attempts_source` " +
                        "ON `geofence_registration_attempts` (`source`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_locations_registrationGenerationId` ON `locations` (`registrationGenerationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_registrationGenerationId` ON `notification_history` (`registrationGenerationId`)")
            }
        }

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .build()
        }
    }
}
