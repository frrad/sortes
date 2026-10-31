package dev.sortes.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sortes.app.importing.PaperImports
import dev.sortes.app.importing.toShareRequest
import dev.sortes.app.ui.PapersScreen
import dev.sortes.app.ui.PapersViewModel
import dev.sortes.app.ui.ReaderScreen
import dev.sortes.app.ui.ShareLanding
import dev.sortes.app.ui.SortesNavigation
import dev.sortes.app.ui.SourcesScreen
import dev.sortes.app.ui.prefetchFirstPage
import dev.sortes.app.ui.theme.SortesTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PapersViewModel by viewModels()

    // Set on each share so the UI can go where the share's progress shows.
    private var share by mutableStateOf<ShareLanding?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is black on white whatever the system theme, so the bars always get dark icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.WHITE, Color.BLACK),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.BLACK),
        )
        handleShare(intent, restored = savedInstanceState != null)
        setContent {
            SortesTheme {
                val papers by viewModel.papers.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier.padding(innerPadding)
                    SortesNavigation(
                        papers = papers,
                        share = share,
                        paperList = { onOpenPaper, onOpenFolderSources ->
                            PapersScreen(
                                onPaperClick = { paper ->
                                    prefetchFirstPage(this, paper)
                                    onOpenPaper(paper)
                                },
                                onOpenFolderSources = onOpenFolderSources,
                                modifier = contentModifier,
                                viewModel = viewModel,
                            )
                        },
                        folderSources = { onBack ->
                            SourcesScreen(onBack = onBack, modifier = contentModifier)
                        },
                        // The reader hides the system bars and keeps clear of any that show itself.
                        reader = { paper, onClose ->
                            ReaderScreen(paper = paper, onClose = onClose)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent, restored = false)
    }

    private fun handleShare(intent: Intent, restored: Boolean) {
        val request = intent.toShareRequest(restored) ?: return
        PaperImports.get(this).import(request)
        share = ShareLanding.after(share, request)
    }
}
