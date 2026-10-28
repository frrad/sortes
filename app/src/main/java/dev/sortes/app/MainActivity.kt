package dev.sortes.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sortes.app.importing.PaperImports
import dev.sortes.app.importing.ShareRequest
import dev.sortes.app.importing.toShareRequest
import dev.sortes.app.ui.PapersScreen
import dev.sortes.app.ui.PapersViewModel
import dev.sortes.app.ui.ReaderScreen
import dev.sortes.app.ui.SourcesScreen
import dev.sortes.app.ui.theme.SortesTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PapersViewModel by viewModels()

    // Bumped on each share so the UI can return to the list, where import progress shows
    // (or to the sources screen, for a shared alphaXiv folder).
    private var shareCount by mutableIntStateOf(0)
    private var lastShareWasFolder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent, restored = savedInstanceState != null)
        setContent {
            SortesTheme {
                val papers by viewModel.papers.collectAsStateWithLifecycle()
                var openPaperId by rememberSaveable { mutableStateOf<Long?>(null) }
                var showSources by rememberSaveable { mutableStateOf(false) }
                val openPaper = papers.find { it.id == openPaperId }
                LaunchedEffect(shareCount) {
                    if (shareCount > 0) {
                        openPaperId = null
                        showSources = lastShareWasFolder
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier.padding(innerPadding)
                    if (openPaper != null) {
                        val closeReader = { openPaperId = null }
                        BackHandler(onBack = closeReader)
                        ReaderScreen(
                            paper = openPaper,
                            onClose = closeReader,
                            modifier = contentModifier,
                        )
                    } else if (showSources) {
                        val closeSources = { showSources = false }
                        BackHandler(onBack = closeSources)
                        SourcesScreen(onBack = closeSources, modifier = contentModifier)
                    } else {
                        PapersScreen(
                            onPaperClick = { openPaperId = it.id },
                            onOpenSources = { showSources = true },
                            modifier = contentModifier,
                            viewModel = viewModel,
                        )
                    }
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
        lastShareWasFolder = request is ShareRequest.AlphaxivFolder
        shareCount++
    }
}
