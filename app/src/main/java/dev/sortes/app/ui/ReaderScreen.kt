package dev.sortes.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private sealed interface ReaderState {
    data object Loading : ReaderState
    data class Ready(val pages: PdfPages) : ReaderState
    data class Failed(val message: String) : ReaderState
}

// Lint false positive: it flags even a trivial `produceState { value = x }` here.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun ReaderScreen(paper: Paper, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // The navigation bar is hidden to give the page more room, and on Boox firmware
    // edge swipes run Boox gestures rather than revealing Back, so the reader
    // provides its own way out.
    HideNavigationBar()
    val context = LocalContext.current
    val state by produceState<ReaderState>(ReaderState.Loading, paper.id) {
        // This coroutine owns the PdfPages: it lives until the screen leaves composition.
        val loaded = load(PdfSource(context), paper)
        value = loaded
        awaitDispose { (loaded as? ReaderState.Ready)?.pages?.close() }
    }

    ReaderFrame(onClose, modifier) {
        when (val s = state) {
            ReaderState.Loading -> Message("Loading…")
            is ReaderState.Failed -> Message("Couldn't open PDF: ${s.message}")
            is ReaderState.Ready -> PageReader(s.pages)
        }
    }
}

/**
 * Draws [content] full size with a back button over its top-left corner. The reader
 * is black on white whatever the app theme, so the button and messages are visible
 * in every state.
 */
@Composable
internal fun ReaderFrame(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Color.Black) {
        Box(modifier.fillMaxSize().background(Color.White)) {
            content()
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to papers")
            }
        }
    }
}

private suspend fun load(source: PdfSource, paper: Paper): ReaderState {
    var pages: PdfPages? = null
    return try {
        val file = source.fetch(paper)
        withContext(Dispatchers.IO) {
            pages = try {
                PdfPages.open(file)
            } catch (e: Exception) {
                source.evict(paper)
                throw e
            }
        }
        ReaderState.Ready(pages!!)
    } catch (e: CancellationException) {
        pages?.close()
        throw e
    } catch (e: Exception) {
        ReaderState.Failed(e.message ?: e.toString())
    }
}

/** One page at a time: tap the left half to go back, the right half to go forward. */
@Composable
private fun PageReader(pages: PdfPages, modifier: Modifier = Modifier) {
    var index by rememberSaveable { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
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
        val page by produceState<Result<Bitmap>?>(null, pages, index, width, height) {
            value = try {
                Result.success(pages.render(index, width, height))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        page?.onSuccess {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.align(Alignment.Center),
            )
        }?.onFailure {
            Message("Couldn't render page ${index + 1}: ${it.message ?: it}")
        }
        Text(
            text = "${index + 1} / ${pages.pageCount}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(4.dp),
        )
    }
}

@Composable
internal fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
