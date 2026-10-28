package dev.sortes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sortes.app.ui.PapersScreen
import dev.sortes.app.ui.PapersViewModel
import dev.sortes.app.ui.ReaderScreen
import dev.sortes.app.ui.theme.SortesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SortesTheme {
                val viewModel: PapersViewModel = viewModel()
                val papers by viewModel.papers.collectAsStateWithLifecycle()
                var openPaperId by rememberSaveable { mutableStateOf<Long?>(null) }
                val openPaper = papers.find { it.id == openPaperId }

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
                    } else {
                        PapersScreen(
                            onPaperClick = { openPaperId = it.id },
                            modifier = contentModifier,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}
