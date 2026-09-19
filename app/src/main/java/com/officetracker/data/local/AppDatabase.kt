package com.officetracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WorkdayEntity::class, StayEntity::class, TrackPointEntity::class],
    version = 1,
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
        fun build(context: Context): AppDatabase {
            // v1 kept an incompatible schema in a different file; remove it once.
            context.getDatabasePath(LEGACY_NAME).takeIf { it.exists() }?.let { context.deleteDatabase(LEGACY_NAME) }
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
        }
    }
}
