package dev.sortes.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Paper::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao

    companion object {
        @Volatile
        private var instance: PaperDatabase? = null

        fun get(context: Context): PaperDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PaperDatabase::class.java,
                    "papers.db",
                ).build().also { instance = it }
            }
    }
}
