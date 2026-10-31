package dev.sortes.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.sortes.app.data.Paper
import dev.sortes.app.importing.ShareRequest

/**
 * Where the app goes after something is shared to it: the folder sources screen for an alphaXiv
 * folder link, where its sync shows, and otherwise the paper list, where import progress shows.
 * [seq] tells two shares of the same kind apart.
 */
internal data class ShareLanding(val seq: Int, val toFolderSources: Boolean) {
    companion object {
        fun after(previous: ShareLanding?, request: ShareRequest) =
            ShareLanding(
                seq = (previous?.seq ?: 0) + 1,
                toFolderSources = request is ShareRequest.AlphaxivFolder,
            )
    }
}

/**
 * Switches between the paper list, the folder sources screen (opened from the list's ⋮ menu) and
 * the reader. Which one is showing survives rotation and process death. The ← arrow and the system
 * Back both return from folder sources or the reader to the list.
 */
@Composable
internal fun SortesNavigation(
    papers: List<Paper>,
    share: ShareLanding?,
    paperList: @Composable (onOpenPaper: (Paper) -> Unit, onOpenFolderSources: () -> Unit) -> Unit,
    folderSources: @Composable (onBack: () -> Unit) -> Unit,
    reader: @Composable (paper: Paper, onClose: () -> Unit) -> Unit,
) {
    var openPaperId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFolderSources by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(share) {
        if (share != null) {
            openPaperId = null
            showFolderSources = share.toFolderSources
        }
    }

    // A paper deleted while open falls back to the list.
    val openPaper = papers.find { it.id == openPaperId }
    if (openPaper != null) {
        val closeReader = { openPaperId = null }
        BackHandler(onBack = closeReader)
        reader(openPaper, closeReader)
    } else if (showFolderSources) {
        val closeFolderSources = { showFolderSources = false }
        BackHandler(onBack = closeFolderSources)
        folderSources(closeFolderSources)
    } else {
        paperList(
            {
                OpenTimeline.tapped(it.id)
                openPaperId = it.id
            },
            { showFolderSources = true },
        )
    }
}
