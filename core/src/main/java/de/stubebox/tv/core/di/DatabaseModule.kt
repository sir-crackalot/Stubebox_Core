package de.stubebox.tv.core.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import de.stubebox.tv.core.database.StubeboxDatabase

/**
 * Provides the Room database (WAL journal mode for fast concurrent reads during large imports) and
 * each DAO. Foreign-key enforcement is on by default in Room.
 *
 * There is deliberately NO destructive-migration fallback: [StubeboxDatabase.ALL_MIGRATIONS] covers every
 * shipped version, and a wipe-on-mismatch "safety net" would silently delete a user's profiles,
 * sources, favorites, history and resume positions on the first schema surprise. A missing or
 * failing migration must surface as an error we can fix, not as an empty app.
 */
val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), StubeboxDatabase::class.java, StubeboxDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // The engine swap: SQLite ships inside the APK. Moving to the driver API and swapping the
            // engine are deliberately separate, so this stays one line.
            //
            // What it buys: minSdk 26 means an Android 8 device runs SQLite 3.18, which has no
            // UPSERT (3.24), no window functions (3.25), no UPDATE…FROM (3.33) and no RETURNING
            // (3.35). Bundling the engine makes those available on every device instead of none,
            // and the DAOs use them.
            //
            // Revert path, if 3.x ever misbehaves on a specific device: change this to
            // AndroidSQLiteDriver(). Nothing else in the codebase knows the difference.
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*StubeboxDatabase.ALL_MIGRATIONS.map(de.stubebox.tv.core.database.DatabaseUpgrade::watch).toTypedArray())
            .addCallback(object : RoomDatabase.Callback() {
                // Self-heal index/FTS drift on every open (no-op when healthy): an interrupted bulk
                // import can leave BulkInsertHelper's dropped indexes missing, which is invisible
                // now but fails Room's full-schema validation at the NEXT version bump (the
                // 4.0.x → 4.1.0 crash-loop). Healing here repairs drift long before that migration.
                //
                // Gated behind a single sqlite_master count so a healthy open pays one index
                // lookup instead of ~30 DDL statements on the thread issuing the first query. The
                // heal itself stays in onOpen — moving it off would let a query beat it to a missing
                // index. The StubeboxPerf timeline reports both the cost and whether it healed.
                override fun onOpen(connection: SQLiteConnection) {
                    // Connection tuning, applied before anything queries. Both settings are per-open,
                    // so they belong here rather than in a migration.
                    //
                    // synchronous=NORMAL is the documented companion to WAL: FULL fsyncs the WAL on
                    // every single commit, which on a TV's eMMC is the dominant cost of a sync writing
                    // tens of thousands of rows. NORMAL still fsyncs at checkpoints, so the durability
                    // it trades away is only "the last few committed transactions survive a sudden
                    // power cut" — not database integrity, which WAL still guarantees. A cut power
                    // cable can cost the tail of a catalogue import, and that re-syncs.
                    //
                    // The page cache is per-connection and defaults to ~2MB. Home alone opens a dozen
                    // profile-scoped queries over the same handful of index pages, and every screen
                    // after it re-reads them; a negative value is SQLite's "this many KiB" form.
                    runCatching {
                        connection.execSQL("PRAGMA synchronous=NORMAL")
                        connection.execSQL("PRAGMA cache_size=-8000")
                    }
                    // Answered in code rather than by reading a log off a device.
                    // `setJournalMode(WRITE_AHEAD_LOGGING)` is a Support-path builder API and it is
                    // undocumented whether it still applies once a driver is configured. Losing WAL
                    // silently would look like a general slowdown with no cause, so this reads the
                    // mode back and sets it if the builder did not. Both are cheap and idempotent.
                    val journal = runCatching {
                        connection.prepare("PRAGMA journal_mode").use { if (it.step()) it.getText(0) else "" }
                    }.getOrDefault("")
                    val journalFixed = if (!journal.equals("wal", ignoreCase = true)) {
                        runCatching {
                            connection.prepare("PRAGMA journal_mode=WAL").use { it.step() }
                        }.isSuccess
                    } else {
                        false
                    }
                    // Record which engine actually ran, so a release logcat answers it
                    // without guesswork. Release builds are obfuscated — ask for mapping.txt
                    // alongside any crash trace from this phase.
                    val engineVersion = runCatching {
                        connection.prepare("SELECT sqlite_version()").use { if (it.step()) it.getText(0) else "?" }
                    }.getOrDefault("?")
                    val healed = runCatching { StubeboxDatabase.healSchemaIfDrifted(connection) }.getOrDefault(false)
                    // The stamp is not optional. Under a driver, a Callback.onOpen written against
                    // the old Support type is silently skipped — no exception, nothing in CI, and
                    // the PRAGMAs and the schema self-heal simply stop running. This line is the
                    // only thing that makes that failure visible, so it reports what actually ran.
                    de.stubebox.tv.core.database.DatabaseUpgrade.opened()
                    de.stubebox.tv.core.util.Perf.stamp(
                        "db-open(sqlite=" + engineVersion + ", journal=" + journal.lowercase() +
                            (if (journalFixed) " forced-wal" else "") +
                            ", heal=" + (if (healed) "repaired" else "clean") + ")",
                    )
                }
            })
            .build()
    }

    single { get<StubeboxDatabase>().profileDao() }
    single { get<StubeboxDatabase>().sourceDao() }
    single { get<StubeboxDatabase>().categoryDao() }
    single { get<StubeboxDatabase>().channelDao() }
    single { get<StubeboxDatabase>().movieDao() }
    single { get<StubeboxDatabase>().seriesDao() }
    single { get<StubeboxDatabase>().favoriteDao() }
    single { get<StubeboxDatabase>().historyDao() }
    single { get<StubeboxDatabase>().progressDao() }
    single { get<StubeboxDatabase>().contentOrderDao() }
    single { get<StubeboxDatabase>().playbackPrefsDao() }
    single { get<StubeboxDatabase>().playbackQuirkDao() }
    single { get<StubeboxDatabase>().customCategoryDao() }
    single { get<StubeboxDatabase>().seriesSortOrderDao() }
    single { get<StubeboxDatabase>().tombstoneDao() }
    single { get<StubeboxDatabase>().tvProviderProgramDao() }
    single { get<StubeboxDatabase>().downloadDao() }
    single { get<StubeboxDatabase>().recordingDao() }
    single { get<StubeboxDatabase>().reminderDao() }
    single { get<StubeboxDatabase>().catalogBackfillDao() }
    single { get<StubeboxDatabase>().epgDao() }
    single { get<StubeboxDatabase>().metadataDao() }
    single { get<StubeboxDatabase>().trendingDao() }
    single { get<StubeboxDatabase>().subtitleDao() }
}
