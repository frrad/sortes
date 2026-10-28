package dev.sortes.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(entities = [Paper::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao

    companion object {
        @Volatile
        private var instance: PaperDatabase? = null

        /**
         * Every schema change from version 3 on needs a migration here (and a test in
         * PaperDatabaseMigrationTest): v3 is the first version holding user imports.
         */
        internal val MIGRATIONS: Array<Migration> = arrayOf()

        fun get(context: Context): PaperDatabase =
            instance ?: synchronized(this) {
                instance ?: configure(
                    Room.databaseBuilder(context.applicationContext, PaperDatabase::class.java, "papers.db"),
                ).build().also { instance = it }
            }

        internal fun configure(builder: RoomDatabase.Builder<PaperDatabase>): RoomDatabase.Builder<PaperDatabase> =
            builder
                .addMigrations(*MIGRATIONS)
                // Versions before 3 only ever held seed data, so they may still be dropped.
                .fallbackToDestructiveMigrationFrom(1, 2)
    }
}
