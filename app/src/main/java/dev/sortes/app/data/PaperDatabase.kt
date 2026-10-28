package dev.sortes.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Paper::class, FolderSource::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao
    abstract fun folderSourceDao(): FolderSourceDao

    companion object {
        @Volatile
        private var instance: PaperDatabase? = null

        /**
         * Every schema change from version 3 on needs a migration here (and a test in
         * PaperDatabaseMigrationTest): v3 is the first version holding user imports.
         */
        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_3_4)

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

/** v4: saved alphaXiv folders (sync sources). */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `folder_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`folderId` TEXT NOT NULL, `name` TEXT, `lastSyncedAt` INTEGER, `lastResult` TEXT)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_sources_folderId` ON `folder_sources` (`folderId`)")
    }
}
