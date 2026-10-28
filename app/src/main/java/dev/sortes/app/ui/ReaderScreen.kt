package dev.sortes.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import dev.sortes.app.data.ZoomStore
import dev.sortes.app.pdf.PageSource
import dev.sortes.app.pdf.PdfPages
import dev.sortes.app.pdf.PdfSource
import dev.sortes.app.zoom.Anchor
import dev.sortes.app.zoom.FullPage
import dev.sortes.app.zoom.PaperAnalysis
import dev.sortes.app.zoom.analyzePaper
import dev.sortes.app.zoom.regionOn
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
    val zoomStore = remember(context) { ZoomStore(context) }
    var savedScale by remember(paper.id) { mutableStateOf(zoomStore.get(paper.id)) }
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
            is ReaderState.Ready -> PageReader(
                pages = s.pages,
                savedScale = savedScale,
                onSaveScale = { scale ->
                    zoomStore.set(paper.id, scale)
                    savedScale = scale
                },
            )
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
    CompositionLocalProvider(LocalContentColor provides ReaderInk) {
        Box(modifier.fillMaxSize().background(ReaderPaper)) {
            content()
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to papers")
            }
        }
    }
}

/** The reader's colors, whatever the app theme: black ink on white paper. */
internal val ReaderInk = Color.Black
internal val ReaderPaper = Color.White

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

/**
 * One page at a time, zoomed to the paper's crop: tap the left half to go back, the right half
 * to go forward, and the bottom middle for zoom controls and the page number.
 */
@SuppressLint("ProduceStateDoesNotAssignValue") // Same false positive as above.
@Composable
internal fun PageReader(
    pages: PageSource,
    savedScale: Float?,
    onSaveScale: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Nothing to render or analyze for until there's a real size to fill.
        if (width <= 0 || height <= 0) return@BoxWithConstraints
        val aspect = width.toFloat() / height

        // Pages render whole until this finishes, so long papers are readable straight away.
        val analysis by produceState<Result<PaperAnalysis>?>(null, pages, aspect) {
            value = null // Don't keep using an analysis for a different screen shape.
            value = catching { analyzePaper(pages, aspect) }
        }
        val paper = analysis?.getOrNull()?.takeIf { it.aspect == aspect }
        val crop = paper?.crop
        val scale = savedScale ?: crop?.autoScale ?: 1f
        val anchor by produceState<Anchor?>(null, crop, scale) {
            value = null
            value = crop?.let { withContext(Dispatchers.Default) { it.anchor(scale) } }
        }

        val currentAnchor = anchor
        // Keeps showing the previous page until the next one has rendered.
        val page by produceState<Result<Bitmap>?>(
            null, pages, index, width, height, paper, scale, currentAnchor,
        ) {
            // The zoom is about to change: wait for it rather than render the page twice.
            if (paper != null && currentAnchor == null) return@produceState
            value = catching {
                val window = if (paper != null && currentAnchor != null) {
                    paper.window(pages, index, scale, currentAnchor)
                } else {
                    FullPage
                }
                pages.renderRegion(index, window.regionOn(pages.pageSize(index), aspect), width, height)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pages) {
                    detectTapGestures { offset ->
                        when (tapZone(offset.x, offset.y, size.width, size.height)) {
                            TapZone.Back -> index = (index - 1).coerceAtLeast(0)
                            TapZone.Forward -> index = (index + 1).coerceAtMost(pages.pageCount - 1)
                            // Nothing to show until the analysis is done.
                            TapZone.ZoomControls -> if (analysis != null) panelOpen = !panelOpen
                        }
                    }
                },
        ) {
            page?.onSuccess {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    modifier = Modifier.align(Alignment.Center),
                )
            }?.onFailure {
                Message("Couldn't render page ${index + 1}: ${it.message ?: it}")
            }
            val result = analysis
            if (panelOpen && result != null) {
                val panelModifier = Modifier.align(Alignment.BottomCenter)
                val pageLabel = "${index + 1} / ${pages.pageCount}"
                if (paper != null) {
                    ZoomPanel(
                        pageLabel = pageLabel,
                        analysis = paper.crop,
                        sampled = paper.crop.pagesAnalyzed < paper.pageCount,
                        scale = scale,
                        isAuto = savedScale == null,
                        onScaleChange = onSaveScale,
                        onAuto = { onSaveScale(null) },
                        onClose = { panelOpen = false },
                        modifier = panelModifier,
                    )
                } else {
                    val error = result.exceptionOrNull()
                    Text(
                        text = "$pageLabel · Couldn't analyze this paper for zoom: " +
                            (error?.message ?: error),
                        modifier = panelModifier
                            .fillMaxWidth()
                            .background(ReaderPaper)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Runs [block], turning failures other than cancellation into a failed [Result]. */
private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

internal enum class TapZone { Back, Forward, ZoomControls }

/** Which control a tap at ([x], [y]) on a [width] x [height] reader hits. */
internal fun tapZone(x: Float, y: Float, width: Int, height: Int): TapZone = when {
    y >= height * (1 - ZOOM_ZONE_HEIGHT) && x >= width / 3f && x < width * 2 / 3f ->
        TapZone.ZoomControls
    x < width / 2f -> TapZone.Back
    else -> TapZone.Forward
}

/** The zoom controls open from the middle third of this bottom share of the screen. */
private const val ZOOM_ZONE_HEIGHT = 0.15f

@Composable
internal fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
