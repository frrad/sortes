package dev.sortes.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaperDatabaseMigrationTest {

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            PaperDatabase::class.java,
        )

    @Test
    fun migrate3To4KeepsPapersAndAddsFolderSources() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO papers (title, authors, abstract, year, venue, pdfUrl, localFile) " +
                    "VALUES ('Mine', '[\"A\"]', '', 2024, 'arXiv', 'https://arxiv.org/pdf/2311.08105', NULL)"
            )
        }

        // Validates the migrated schema against 4.json.
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT title, pdfUrl FROM papers").use {
                it.moveToFirst()
                assertEquals("Mine", it.getString(0))
                assertEquals("https://arxiv.org/pdf/2311.08105", it.getString(1))
            }
            val source =
                ContentValues().apply { put("folderId", "01a1838a-db89-71d0-956c-e8067fdfee2a") }
            db.insert("folder_sources", SQLiteDatabase.CONFLICT_ABORT, source)
            // The unique index is what keeps a folder from being saved twice.
            assertThrows(SQLiteConstraintException::class.java) {
                db.insert("folder_sources", SQLiteDatabase.CONFLICT_ABORT, source)
            }
        }
    }

    // Room validates a migrated database against the current entities when it opens it, so
    // this also catches entities drifting from the exported schema.
    @Test
    fun migratedDatabaseOpensWithRoom() {
        helper.createDatabase(TEST_DB, 3).close()
        helper.runMigrationsAndValidate(TEST_DB, 4, true, *PaperDatabase.MIGRATIONS).close()

        val db =
            PaperDatabase.configure(
                    Room.databaseBuilder(
                            ApplicationProvider.getApplicationContext(),
                            PaperDatabase::class.java,
                            TEST_DB,
                        )
                        .allowMainThreadQueries()
                )
                .build()
        try {
            runBlocking {
                val dao = db.folderSourceDao()
                dao.insert(FolderSource(folderId = "f"))
                assertEquals(listOf("f"), dao.observeAll().first().map { it.folderId })
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-3-4.db"
    }
}
