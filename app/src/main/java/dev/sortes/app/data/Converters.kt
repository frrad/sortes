package dev.sortes.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun authorsToString(authors: List<String>): String = authors.joinToString("\n")

    @TypeConverter
    fun stringToAuthors(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\n")
}
