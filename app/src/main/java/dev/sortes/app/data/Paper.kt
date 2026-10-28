package dev.sortes.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Unique pdfUrl dedupes arXiv imports; local imports have a null pdfUrl, which SQLite allows repeatedly.
@Entity(tableName = "papers", indices = [Index(value = ["pdfUrl"], unique = true)])
data class Paper(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val authors: List<String>,
    val abstract: String,
    val year: Int?,
    val venue: String?,
    val pdfUrl: String? = null,
    /** Name of an imported PDF in the app's library directory; takes precedence over [pdfUrl]. */
    val localFile: String? = null,
)
