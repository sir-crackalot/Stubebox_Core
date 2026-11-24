package de.stubebox.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v54 → v55: the per-playlist "Prefer HLS for catch-up" switch — `sources.catchupPreferHls`, off for
 * every existing playlist, so catch-up keeps asking for MPEG-TS until the user asks. Additive only. Last
 * hop, so it carries [StubeboxDatabase.healSchema] (standing rule).
 */
val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(connection: SQLiteConnection) {
        if (!StubeboxDatabase.hasColumn(connection, "sources", "catchupPreferHls")) {
            connection.execSQL("ALTER TABLE `sources` ADD COLUMN `catchupPreferHls` INTEGER NOT NULL DEFAULT 0")
        }
        StubeboxDatabase.healSchema(connection)
    }
}
