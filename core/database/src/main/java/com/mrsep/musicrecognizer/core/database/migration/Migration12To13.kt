package com.mrsep.musicrecognizer.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val Migration12To13 = object : Migration(12, 13) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE enqueued_recognition ADD COLUMN work_status TEXT NOT NULL DEFAULT 'IDLE'")
        db.execSQL("ALTER TABLE enqueued_recognition ADD COLUMN queued_at INTEGER")
        db.execSQL("ALTER TABLE enqueued_recognition ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE enqueued_recognition ADD COLUMN next_attempt_at INTEGER")
    }
}
