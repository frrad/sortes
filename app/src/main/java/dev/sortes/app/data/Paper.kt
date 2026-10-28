package dev.sortes.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "papers")
data class Paper(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val authors: List<String>,
    val abstract: String,
    val year: Int?,
    val venue: String?,
    val pdfUrl: String? = null,
)
