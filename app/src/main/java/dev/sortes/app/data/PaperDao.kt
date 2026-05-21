package dev.sortes.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PaperDao {
    @Query("SELECT * FROM papers ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Paper>>

    @Query("SELECT COUNT(*) FROM papers")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(papers: List<Paper>)
}
