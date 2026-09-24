package com.officetracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WorkdayEntity::class, StayEntity::class, TrackPointEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao

    companion object {
        private const val NAME = "office_tracker_v2.db"
        private const val LEGACY_NAME = "office_tracker_db"

        /**
         * Schema changes must ship with a Migration added here - never a destructive fallback,
         * because the database can hold GPS data that has not reached the cloud yet.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workdays ADD COLUMN checkInAt INTEGER")
                db.execSQL("ALTER TABLE workdays ADD COLUMN checkInPlace TEXT")
                db.execSQL("ALTER TABLE workdays ADD COLUMN checkOutAt INTEGER")
                db.execSQL("ALTER TABLE workdays ADD COLUMN checkOutPlace TEXT")
                db.execSQL("ALTER TABLE workdays ADD COLUMN insideMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workdays ADD COLUMN otMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workdays ADD COLUMN otApproved INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workdays ADD COLUMN zoneSince INTEGER")
            }
        }

        fun build(context: Context): AppDatabase {
            // v1 kept an incompatible schema in a different file; remove it once.
            context.getDatabasePath(LEGACY_NAME).takeIf { it.exists() }?.let { context.deleteDatabase(LEGACY_NAME) }
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
