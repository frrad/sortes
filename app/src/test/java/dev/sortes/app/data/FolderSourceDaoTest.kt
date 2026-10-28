package dev.sortes.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderSourceDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PaperDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.folderSourceDao()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun duplicateFolderIsIgnored() = runBlocking {
        val first = dao.insert(FolderSource(folderId = "f"))
        val second = dao.insert(FolderSource(folderId = "f"))

        assertTrue(first > 0)
        assertEquals(-1L, second)
        assertEquals(first, dao.findByFolderId("f")?.id)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun recordSyncUpdatesFieldsAndKeepsNameWhenNull() = runBlocking {
        val id = dao.insert(FolderSource(folderId = "f"))

        dao.recordSync(id, "Sortes", 10L, "2 added")
        assertEquals(FolderSource(id, "f", "Sortes", 10L, "2 added"), dao.findById(id))

        dao.recordSync(id, null, 20L, "Sync failed: offline")
        assertEquals(FolderSource(id, "f", "Sortes", 20L, "Sync failed: offline"), dao.findById(id))
    }

    @Test
    fun deleteRemovesOnlyThatSource() = runBlocking {
        val a = dao.insert(FolderSource(folderId = "a"))
        dao.insert(FolderSource(folderId = "b"))

        dao.delete(a)

        assertNull(dao.findById(a))
        assertEquals(listOf("b"), dao.getAll().map { it.folderId })
    }
}
