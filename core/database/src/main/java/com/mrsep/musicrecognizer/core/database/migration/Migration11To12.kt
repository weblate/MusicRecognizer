package com.mrsep.musicrecognizer.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

// Store sample filenames instead of absolute paths so restore works across Android user ids
internal val Migration11To12 = object : Migration(11, 12) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id, record_file FROM enqueued_recognition").use { cursor ->
            val idIndex = cursor.getColumnIndex("id")
            val fileIndex = cursor.getColumnIndex("record_file")
            while (cursor.moveToNext()) {
                val id = cursor.getInt(idIndex)
                val absolutePath = cursor.getString(fileIndex) ?: continue
                val fileName = File(absolutePath).name
                if (fileName == absolutePath) continue
                db.execSQL(
                    "UPDATE enqueued_recognition SET record_file = ? WHERE id = ?",
                    arrayOf(fileName, id)
                )
            }
        }
    }
}
