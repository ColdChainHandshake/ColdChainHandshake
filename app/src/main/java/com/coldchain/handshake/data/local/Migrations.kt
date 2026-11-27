package com.coldchain.handshake.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds product profiles, the journey start, alert timing and the handover findings. Rows already
 * on a device keep their data, and old shipments get the strictest profile. The literal values must
 * not change later, because a migration has to keep doing what it did when it shipped.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE shipments ADD COLUMN productProfileId TEXT NOT NULL DEFAULT 'vaccine-2-8-freeze-sensitive'")
        db.execSQL("ALTER TABLE shipments ADD COLUMN journeyStartedAt INTEGER")
        db.execSQL("ALTER TABLE alerts ADD COLUMN excursionStart INTEGER")
        db.execSQL("ALTER TABLE alerts ADD COLUMN acknowledgedAt INTEGER")
        db.execSQL("ALTER TABLE handovers ADD COLUMN reason TEXT")
        db.execSQL("ALTER TABLE handovers ADD COLUMN vvmStage TEXT NOT NULL DEFAULT 'NOT_RECORDED'")
        db.execSQL("ALTER TABLE handovers ADD COLUMN shakeTest TEXT NOT NULL DEFAULT 'NOT_DONE'")
    }
}

/**
 * Adds what chain version 2 needs. Readings already on a device keep their values and old hashes.
 * They have no sequence numbers, so they won't verify as version 2 chains, and they stay readable.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE temperature_events ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE shipments ADD COLUMN genesisHash TEXT")
    }
}

/**
 * Keeps when the server received each reading, which only readings downloaded from it have.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE temperature_events ADD COLUMN receivedAt INTEGER")
    }
}

/** Version 5 queues the locations the recording service records (MASTER 5.5 item 2). Nothing existing changes. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Room's own statements, from schemas/.../5.json.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `gps_points` (`id` TEXT NOT NULL, `shipmentId` TEXT NOT NULL, " +
                "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `accuracyMeters` REAL NOT NULL, " +
                "`deviceTimestamp` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_gps_points_shipmentId` ON `gps_points` (`shipmentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_gps_points_syncStatus` ON `gps_points` (`syncStatus`)")
    }
}
