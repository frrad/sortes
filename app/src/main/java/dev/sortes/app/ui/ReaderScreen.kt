package dev.sortes.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sortes.app.data.Paper
import dev.sortes.app.pdf.PdfPages
import dev.sortes.app.pdf.PdfSource
import kotlin.coroutines.cancellation.CancellationException

private sealed interface ReaderState {
    data object Loading : ReaderState
    data class Ready(val pages: PdfPages) : ReaderState
    data class Failed(val message: String) : ReaderState
}

@Composable
fun ReaderScreen(paper: Paper, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<ReaderState>(ReaderState.Loading, paper.id) {
        value = try {
            val file = PdfSource(context).fetch(paper)
            ReaderState.Ready(PdfPages.open(file))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaderState.Failed(e.message ?: e.toString())
        }
    }

    when (val s = state) {
        ReaderState.Loading -> Message("Loading…", modifier)
        is ReaderState.Failed -> Message("Couldn't open PDF: ${s.message}", modifier)
        is ReaderState.Ready -> {
            DisposableEffect(s.pages) { onDispose { s.pages.close() } }
            PageReader(s.pages, modifier)
        }
    }
}

/** One page at a time: tap the left half to go back, the right half to go forward. */
@Composable
private fun PageReader(pages: PdfPages, modifier: Modifier = Modifier) {
    var index by rememberSaveable { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(pages) {
                detectTapGestures { offset ->
                    index = if (offset.x < size.width / 2) {
                        (index - 1).coerceAtLeast(0)
                    } else {
                        (index + 1).coerceAtMost(pages.pageCount - 1)
                    }
                }
            },
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Keeps showing the previous page until the next one has rendered.
        val bitmap by produceState<Bitmap?>(null, pages, index, width, height) {
            value = pages.render(index, width, height)
        }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            text = "${index + 1} / ${pages.pageCount}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(4.dp),
        )
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
