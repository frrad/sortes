package dev.sortes.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PaperDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, PaperDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.paperDao()

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PaperDatabase::class.java,
    )

    @After
    fun tearDown() {
        db.close()
    }

    private fun paper(title: String, pdfUrl: String? = null, localFile: String? = null) =
        Paper(title = title, authors = listOf("A"), abstract = "", year = null, venue = null,
            pdfUrl = pdfUrl, localFile = localFile)

    @Test
    fun duplicatePdfUrlIsIgnored() = runBlocking {
        val first = dao.insert(paper("A", pdfUrl = "https://arxiv.org/pdf/1706.03762"))
        val second = dao.insert(paper("B", pdfUrl = "https://arxiv.org/pdf/1706.03762"))

        assertTrue(first > 0)
        assertEquals(-1L, second)
        assertEquals(listOf("A"), dao.observeAll().first().map { it.title })
    }

    @Test
    fun manyLocalImportsWithoutUrlAreAllowed() = runBlocking {
        dao.insert(paper("A", localFile = "paper-1.pdf"))
        dao.insert(paper("B", localFile = "paper-2.pdf"))

        assertEquals(2, dao.count())
    }

    @Test
    fun localFileRoundTrips() = runBlocking {
        val id = dao.insert(paper("A", localFile = "paper-1.pdf"))

        assertEquals("paper-1.pdf", dao.observeAll().first().single { it.id == id }.localFile)
    }

    @Test
    fun currentSchemaIsExported() {
        // Fails when the version is bumped without committing app/schemas/.../<version>.json.
        val version = db.openHelper.readableDatabase.version
        val schema = File("schemas/${PaperDatabase::class.java.name}/$version.json")
        assertTrue("missing ${schema.absolutePath}", schema.isFile)
    }

    @Test
    fun exportedSchemaMatchesEntities() {
        // Creates the DB from 3.json, then has Room validate it against the current entities.
        migrations.createDatabase(TEST_DB, 3).close()
        migrations.runMigrationsAndValidate(TEST_DB, 3, true, *PaperDatabase.MIGRATIONS).close()
    }

    @Test
    fun preV3DatabaseIsReplaced() {
        val name = "legacy.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
            it.execSQL("CREATE TABLE papers (id INTEGER PRIMARY KEY, title TEXT)")
            it.execSQL("INSERT INTO papers (title) VALUES ('old seed')")
            it.version = 2
        }

        val upgraded = PaperDatabase.configure(
            Room.databaseBuilder(context, PaperDatabase::class.java, name).allowMainThreadQueries(),
        ).build()
        try {
            assertEquals(0, runBlocking { upgraded.paperDao().count() })
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun v3DataSurvivesReopening() {
        val name = "v3.db"
        context.deleteDatabase(name)
        fun open() = PaperDatabase.configure(
            Room.databaseBuilder(context, PaperDatabase::class.java, name).allowMainThreadQueries(),
        ).build()

        open().apply {
            runBlocking { paperDao().insert(paper("Mine", localFile = "paper-1.pdf")) }
            close()
        }
        val reopened = open()
        try {
            assertEquals(listOf("Mine"), runBlocking { reopened.paperDao().observeAll().first() }.map { it.title })
        } finally {
            reopened.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
