package dev.sortes.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers the zoom level chosen by hand for each paper; papers without one use automatic zoom.
 */
class ZoomStore(private val prefs: SharedPreferences) {

    constructor(
        context: Context
    ) : this(context.applicationContext.getSharedPreferences("reader_zoom", Context.MODE_PRIVATE))

    /** The chosen crop scale for [paperId], or null for automatic. */
    fun get(paperId: Long): Float? {
        val key = key(paperId)
        return if (prefs.contains(key)) prefs.getFloat(key, 1f) else null
    }

    /** Saves [scale] for [paperId]; null goes back to automatic. */
    fun set(paperId: Long, scale: Float?) {
        prefs.edit {
            if (scale == null) remove(key(paperId)) else putFloat(key(paperId), scale)
        }
    }

    private fun key(paperId: Long) = "scale_$paperId"
}
