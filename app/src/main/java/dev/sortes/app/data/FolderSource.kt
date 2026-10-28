package dev.sortes.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved alphaXiv shared folder whose papers can be synced into the library. */
@Entity(tableName = "folder_sources", indices = [Index(value = ["folderId"], unique = true)])
data class FolderSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Lower-case alphaXiv folder UUID; the share URL is derived from it. */
    val folderId: String,
    /** Folder name as alphaXiv reported it on the last sync, if any. */
    val name: String? = null,
    /** When the last sync attempt finished, in epoch millis. */
    val lastSyncedAt: Long? = null,
    /** One-line outcome of the last sync attempt. */
    val lastResult: String? = null,
)
