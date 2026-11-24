package de.stubebox.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v56 → v57: `episodes.imageUrl`, the provider's own picture of an episode. Null for every existing
 * row: episodes are catalogue, filled again the next time a show is opened or its playlist synced.
 * Additive only — an `ALTER TABLE … ADD COLUMN` touches no row, so it is instant on any catalogue size.
 * No transaction is opened here — Room already runs the chain inside one. Last hop, so it carries
 * [StubeboxDatabase.healSchema] (standing rule).
 */
val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(connection: SQLiteConnection) {
        if (!StubeboxDatabase.hasColumn(connection, "episodes", "imageUrl")) {
            connection.execSQL("ALTER TABLE `episodes` ADD COLUMN `imageUrl` TEXT")
        }
        StubeboxDatabase.healSchema(connection)
    }
}
