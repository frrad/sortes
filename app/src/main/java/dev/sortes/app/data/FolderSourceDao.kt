package dev.sortes.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderSourceDao {
    @Query("SELECT * FROM folder_sources ORDER BY id ASC")
    fun observeAll(): Flow<List<FolderSource>>

    @Query("SELECT * FROM folder_sources ORDER BY id ASC") suspend fun getAll(): List<FolderSource>

    @Query("SELECT * FROM folder_sources WHERE id = :id")
    suspend fun findById(id: Long): FolderSource?

    @Query("SELECT * FROM folder_sources WHERE folderId = :folderId")
    suspend fun findByFolderId(folderId: String): FolderSource?

    /** Returns the new row id, or -1 if the folder is already saved. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(source: FolderSource): Long

    /** Records a sync attempt; a null [name] keeps the stored one. */
    @Query(
        "UPDATE folder_sources SET name = COALESCE(:name, name), lastSyncedAt = :at, lastResult = :result " +
            "WHERE id = :id"
    )
    suspend fun recordSync(id: Long, name: String?, at: Long, result: String)

    @Query("DELETE FROM folder_sources WHERE id = :id") suspend fun delete(id: Long)
}
