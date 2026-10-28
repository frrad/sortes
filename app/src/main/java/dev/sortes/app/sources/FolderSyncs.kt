package dev.sortes.app.sources

import android.content.Context
import android.util.Log
import dev.sortes.app.alphaxiv.HttpAlphaxivClient
import dev.sortes.app.data.PaperDatabase
import dev.sortes.app.importing.PaperImports
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide [FolderSources], so syncs finish even if the screen that started them goes away. */
object FolderSyncs {
    @Volatile
    private var instance: FolderSources? = null

    fun get(context: Context): FolderSources =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): FolderSources {
        val db = PaperDatabase.get(context)
        // Shares the share/add-dialog importer, so concurrent imports of one paper fetch it once.
        val importer = PaperImports.get(context).importer
        // FolderSources reports its own failures; this is a backstop so a bug can't crash the app.
        val handler = CoroutineExceptionHandler { _, e -> Log.e("FolderSyncs", "Unhandled sync failure", e) }
        return FolderSources(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler),
            dao = db.folderSourceDao(),
            syncer = FolderSyncer(HttpAlphaxivClient()) { importer.importArxiv(it) },
        )
    }
}
