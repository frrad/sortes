package dev.sortes.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import dev.sortes.app.data.Paper
import dev.sortes.app.data.ZoomStore
import dev.sortes.app.pdf.PageSnapshots
import dev.sortes.app.pdf.PageSource
import dev.sortes.app.pdf.PdfPages
import dev.sortes.app.pdf.PdfSource
import dev.sortes.app.zoom.AnalysisCache
import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.FullPage
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PaperAnalysis
import dev.sortes.app.zoom.Placement
import dev.sortes.app.zoom.ZoomCurve
import dev.sortes.app.zoom.analyzePaper
import dev.sortes.app.zoom.regionOn
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ReaderState {
    data object Loading : ReaderState

    /**
     * [file]'s pages are open; [content] is its [AnalysisCache.contentHash], or null if it couldn't
     * be read.
     */
    data class Ready(val pages: PdfPages, val content: String?, val file: File) : ReaderState

    data class Failed(val message: String) : ReaderState
}

// Lint false positive: it flags even a trivial `produceState { value = x }` here.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun ReaderScreen(paper: Paper, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // The system bars are hidden to give the page the whole screen, and on Boox firmware
    // edge swipes run Boox gestures rather than revealing Back, so the reader
    // provides its own way out.
    val barsHiding = HideSystemBars()
    val context = LocalContext.current
    val zoomStore = remember(context) { ZoomStore(context) }
    val analyses = remember(context) { ReaderAnalyses.get(context) }
    var savedScale by remember(paper.id) { mutableStateOf(zoomStore.get(paper.id)) }
    val timeline = remember(paper.id) { OpenTimeline.begin(paper.id) }
    DisposableEffect(timeline) {
        timeline.mark("reader shown")
        onDispose { timeline.abandon() }
    }
    val state by
        produceState<ReaderState>(ReaderState.Loading, paper.id) {
            // This coroutine owns the PdfPages: it lives until the screen leaves composition.
            val loaded = load(PdfSource(context), paper, timeline)
            value = loaded
            awaitDispose { (loaded as? ReaderState.Ready)?.pages?.close() }
        }
    // The first page as it was last shown, read while the PDF opens: shown before it's open.
    val firstPage by
        produceState<FirstPage?>(null, paper.id) {
            value = ReaderFirstPages.take(context, paper, savedScale, timeline)
        }
    // First pages stored (or found stored) this time, not to store them again on every return to
    // page 1.
    val firstPagesStored = remember(paper.id) { mutableSetOf<FirstPage.Key>() }

    ReaderFrame(onClose, modifier, barsHiding = barsHiding) {
        val s = state
        if (s is ReaderState.Failed) {
            Message("Couldn't open PDF: ${s.message}", timeline = timeline)
            return@ReaderFrame
        }
        val ready = s as? ReaderState.Ready
        // A PDF on disk opens in tens of milliseconds: only say so if it takes longer
        // (downloading), rather than flash the message, a refresh on e-ink.
        if (ready == null && firstPage == null)
            DelayedMessage("Loading…", LOADING_MESSAGE_DELAY_MS, timeline = timeline)
        // Composed before the PDF is open, to show the stored first page; the same reader then
        // confirms it, so it's never drawn twice.
        PageReader(
            pages = ready?.pages,
            savedScale = savedScale,
            onSaveScale = { scale ->
                zoomStore.set(paper.id, scale)
                savedScale = scale
            },
            timeline = timeline,
            onCropsSolved = { analysis -> ready?.content?.let { analyses.update(it, analysis) } },
            analyze = { aspect, onFresh ->
                // Only called once the pages are open, in a composition that had them.
                val r = checkNotNull(ready) { "No pages to analyse" }
                val content = r.content
                // Without a hash (the file went away), analyse without the cache.
                if (content == null) {
                    onFresh()
                    analyzePaper(r.pages, aspect)
                } else {
                    analyses.analyze(r.pages, content, aspect, onMiss = onFresh)
                }
            },
            firstPage = firstPage,
            onFirstPageShown = { page, stored ->
                if (firstPagesStored.add(page.key) && !stored && ready != null) {
                    ReaderFirstPages.save(context, paper, ready.file, page)
                }
            },
        )
    }
}

/**
 * Draws [content] full size with a back button over its top-left corner. The reader is black on
 * white whatever the app theme, so the button and messages are visible in every state.
 *
 * Takes the whole window and keeps [content] out of whatever the system draws over: system bars
 * that are showing, and a display cutout. With the bars hidden that is the whole window, so the
 * page's crop is analysed and drawn for exactly the area you see.
 *
 * While [barsHiding] (the bars were asked to hide but may still show, see [HideSystemBars]),
 * [content] is laid out for the window they'll leave, keeping clear only of a cutout: laid out
 * around the bars first, the page would be analysed and rendered for two sizes in turn.
 */
@Composable
internal fun ReaderFrame(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    barsHiding: Boolean = false,
    content: @Composable () -> Unit,
) {
    val insets = if (barsHiding) WindowInsets.displayCutout else WindowInsets.safeDrawing
    CompositionLocalProvider(LocalContentColor provides ReaderInk) {
        Box(modifier.fillMaxSize().background(ReaderPaper).windowInsetsPadding(insets)) {
            content()
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to papers")
            }
        }
    }
}

/**
 * The papers' stored zoom analyses, one cache for the process: fresh analyses are written in an
 * application scope, so leaving the reader right after one finishes still keeps it.
 */
private object ReaderAnalyses {
    @Volatile private var cache: AnalysisCache? = null

    fun get(context: Context): AnalysisCache =
        cache
            ?: synchronized(this) {
                cache
                    ?: AnalysisCache(
                            File(context.applicationContext.cacheDir, "zoom-analysis"),
                            build = appBuild(context),
                            saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        )
                        .also { cache = it }
            }
}

/**
 * Starts reading [paper]'s stored first page, for its reader to show: call it on the tap that opens
 * the paper, to read it while the reader is composed.
 */
fun prefetchFirstPage(context: Context, paper: Paper) = ReaderFirstPages.prefetch(context, paper)

/**
 * A paper's first page as the reader showed it at [key]'s size and zoom ([PageSnapshots]): shown at
 * once when the paper is opened again, if the reader's size and zoom are the same.
 */
internal class FirstPage(val key: Key, val bitmap: Bitmap) {
    /**
     * The page [index], rendered [width] x [height] at the paper's chosen zoom [scale] (null:
     * automatic).
     */
    data class Key(val index: Int, val width: Int, val height: Int, val scale: Float?)
}

/** The papers' stored first pages ([PageSnapshots]), one store for the process. */
private object ReaderFirstPages {
    @Volatile private var store: PageSnapshots? = null

    // Stores pages after they're shown, even if the reader has closed since.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun get(context: Context): PageSnapshots =
        store
            ?: synchronized(this) {
                store
                    ?: PageSnapshots(
                            File(context.applicationContext.cacheDir, "first-pages"),
                            build = appBuild(context),
                        )
                        .also { store = it }
            }

    private fun key(file: File, index: Int, scale: Float?) =
        PageSnapshots.Key(PageSnapshots.fileIdentity(file), CropConfig.DEFAULT, index, scale)

    /** A read started when a paper was tapped, for the reader to take. */
    private class Pending(val paperId: Long, val scale: Float?, val page: Deferred<FirstPage?>)

    @Volatile private var pending: Pending? = null

    /**
     * Starts reading [paper]'s first page, as stored for its zoom, for [take] to take: on the tap,
     * before the reader is composed.
     */
    fun prefetch(context: Context, paper: Paper) {
        val scale = ZoomStore(context).get(paper.id)
        val context = context.applicationContext
        pending = Pending(paper.id, scale, readScope.async { read(context, paper, scale) })
    }

    /** [paper]'s first page stored for its zoom [scale], if any and its PDF is on the device. */
    suspend fun take(
        context: Context,
        paper: Paper,
        scale: Float?,
        timeline: OpenTimeline?,
    ): FirstPage? {
        val prefetched =
            pending?.also { pending = null }?.takeIf { it.paperId == paper.id && it.scale == scale }
        val page =
            prefetched?.page?.await()
                ?: if (prefetched == null) read(context, paper, scale) else null
        timeline?.mark(if (page != null) "stored page read" else "no stored page")
        return page
    }

    private suspend fun read(context: Context, paper: Paper, scale: Float?): FirstPage? =
        withContext(Dispatchers.IO) {
            try {
                val file = PdfSource(context).cachedFile(paper) ?: return@withContext null
                val snapshot =
                    get(context).load(paper.id, key(file, 0, scale)) ?: return@withContext null
                val bitmap =
                    Bitmap.createBitmap(snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(snapshot.pixels))
                FirstPage(
                    FirstPage.Key(snapshot.index, snapshot.width, snapshot.height, scale),
                    bitmap,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the stored first page", e)
                null
            }
        }

    /** Stores [page] as [paper]'s, rendered from [file], in the background. */
    fun save(context: Context, paper: Paper, file: File, page: FirstPage) {
        saveScope.launch {
            try {
                val pixels = ByteBuffer.allocate(page.bitmap.byteCount)
                page.bitmap.copyPixelsToBuffer(pixels)
                get(context)
                    .save(
                        paper.id,
                        key(file, page.key.index, page.key.scale),
                        page.key.width,
                        page.key.height,
                        pixels.array(),
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't store the first page", e)
            }
        }
    }
}

/**
 * The installed build: its versionCode and when it was installed or last updated, which changes
 * with every new APK even when the versionCode doesn't (debug builds).
 */
private fun appBuild(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return "${PackageInfoCompat.getLongVersionCode(info)}@${info.lastUpdateTime}"
}

/** The reader's colors, whatever the app theme: black ink on white paper. */
internal val ReaderInk = Color.Black
internal val ReaderPaper = Color.White

/** Fetches and opens [paper]'s PDF, hashing it meanwhile to look up its stored zoom analysis. */
private suspend fun load(source: PdfSource, paper: Paper, timeline: OpenTimeline): ReaderState {
    var pages: PdfPages? = null
    return try {
        val file = source.fetch(paper)
        timeline.mark("pdf fetched")
        coroutineScope {
            val content =
                async(Dispatchers.IO) {
                    runCatching { AnalysisCache.contentHash(file) }
                        .getOrNull()
                        .also { timeline.mark("pdf hashed") }
                }
            withContext(Dispatchers.IO) {
                pages =
                    try {
                        PdfPages.open(file)
                    } catch (e: Exception) {
                        source.evict(paper)
                        throw e
                    }
            }
            timeline.mark("pdf opened")
            ReaderState.Ready(pages!!, content.await(), file)
        }
    } catch (e: CancellationException) {
        pages?.close()
        throw e
    } catch (e: Exception) {
        ReaderState.Failed(e.message ?: e.toString())
    }
}

/**
 * One page at a time, zoomed to the paper's crop: tap the left half to go back, the right half to
 * go forward, and the bottom middle for zoom controls and the page number.
 */
@SuppressLint("ProduceStateDoesNotAssignValue") // Same false positive as above.
@Composable
internal fun PageReader(
    /** The paper's pages; null until its PDF is open. */
    pages: PageSource?,
    savedScale: Float?,
    onSaveScale: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The paper's zoom analysis for a screen of the given aspect: by default computed afresh. It
     * calls `onFresh` (on the caller's context) once it knows it must compute one, which takes
     * seconds: until then (while it looks for a stored one) the reader shows no page rather than a
     * whole page that zooms in moments later, two refreshes on e-ink.
     */
    analyze: suspend (aspect: Float, onFresh: () -> Unit) -> PaperAnalysis = { aspect, onFresh ->
        onFresh()
        analyzePaper(checkNotNull(pages), aspect)
    },
    /** Where to report the steps of opening the paper, if anywhere. */
    timeline: OpenTimeline? = null,
    /** Called when the analysis has solved crops at a new scale (a saved zoom's), to keep them. */
    onCropsSolved: suspend (PaperAnalysis) -> Unit = {},
    /**
     * The first page as stored when it was last shown: if it was stored for this size and zoom,
     * it's shown at once, before the pages open or the analysis is read, and kept if the page then
     * renders identical, so it's drawn once.
     */
    firstPage: FirstPage? = null,
    /**
     * Called with the first page each time it's shown at its crop (rendered for the screen and
     * placed by the analysis), to store it; `stored` if it's [firstPage], found identical.
     */
    onFirstPageShown: (page: FirstPage, stored: Boolean) -> Unit = { _, _ -> },
    /** Called each time a page bitmap is drawn (tests). */
    onDrawn: (Bitmap) -> Unit = {},
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Nothing to render or analyze for until there's a real size to fill.
        if (width <= 0 || height <= 0) return@BoxWithConstraints
        val aspect = width.toFloat() / height

        // Whether the analysis is being computed afresh, rather than read from storage.
        var analyzingAfresh by remember(pages, aspect) { mutableStateOf(false) }
        // Pages render whole while it's computed afresh, so long papers are readable straight away.
        val analysis by
            produceState<Result<PaperAnalysis>?>(null, pages, aspect) {
                value = null // Don't keep using an analysis for a different screen shape.
                if (pages == null) return@produceState
                value =
                    catching {
                        val started = SystemClock.elapsedRealtime()
                        timeline?.mark("analysis started for ${width}x$height")
                        analyze(aspect) {
                                timeline?.mark("analyzing afresh")
                                analyzingAfresh = true
                            }
                            .also {
                                timeline?.mark(
                                    "analysis ready${if (it.restored) " (cached)" else ""} for ${width}x$height"
                                )
                                Log.i(
                                    TAG,
                                    "Zoom analysis${if (it.restored) " (cached)" else ""}: ${it.pagesAnalyzed} of ${it.pageCount} pages (${it.pagesMeasured} measured for text), " +
                                        "${it.auto.k} window(s) at ${it.auto.scale} in ${SystemClock.elapsedRealtime() - started} ms " +
                                        "(${it.solveMillis} ms solving), ${it.config.name}, body text ${it.glyphPx?.let { px -> "%.1f".format(px) }} px at scale 1",
                                )
                            }
                    }
                        .onFailure { Log.w(TAG, "Zoom analysis failed; showing whole pages", it) }
            }
        val paper = analysis?.getOrNull()?.takeIf { it.aspect == aspect }
        // The model's crops: at its automatic scale, or re-solved at the saved one. Solving can
        // fail (a saved scale that's out of range): the reader then shows whole pages. Crops
        // already solved (the automatic ones, or kept with a stored analysis) are used in the
        // same composition as the analysis, so the page starts rendering a frame sooner.
        val solvedCrops = paper?.let {
            if (savedScale == null) it.auto else it.solvedAt(savedScale)
        }
        val solving by
            produceState<Solving?>(null, paper, savedScale) {
                value = null
                if (paper == null || savedScale == null || paper.solvedAt(savedScale) != null)
                    return@produceState
                val result = catching { paper.cropsAt(savedScale) }
                timeline?.mark("crops solved at saved scale $savedScale")
                value = Solving(paper, savedScale, result)
                if (result.isSuccess) onCropsSolved(paper)
            }
        val cropsResult =
            solvedCrops?.let { Result.success(it) }
                ?: solving?.takeIf { it.paper === paper && it.scale == savedScale }?.result
        val crops = cropsResult?.getOrNull()
        val scale = crops?.scale ?: savedScale ?: paper?.auto?.scale ?: 1f

        val currentCrops = crops
        val currentCropsResult = cropsResult
        // A stored analysis is moments away: wait for it rather than show a whole page first.
        val awaitingAnalysis = analysis == null && !analyzingAfresh
        val analysisPending = analysis == null
        // The stored first page, if it was stored for this size and zoom.
        val stored = firstPage?.takeIf {
            it.key == FirstPage.Key(it.key.index, width, height, savedScale)
        }
        // Keeps showing the previous page until the next one has rendered.
        val page by
            produceState<Result<ShownPage>?>(
                null,
                pages,
                index,
                width,
                height,
                paper,
                currentCropsResult,
                awaitingAnalysis,
                analysisPending,
                stored,
            ) {
                // The stored first page shows as soon as it's read, if nothing else has: it's
                // checked
                // against the page rendered as always, once that can be.
                if (value == null && stored != null && stored.key.index == index) {
                    timeline?.mark("showing stored page ${index + 1}")
                    value =
                        Result.success(
                            ShownPage(index, stored.bitmap, aspect, placement = null, zoomed = true)
                        )
                }
                val showingStored =
                    stored != null &&
                        value?.getOrNull()?.let {
                            it.bitmap === stored.bitmap && it.index == index
                        } == true
                // Until the pages are open and the analysis read, and while it's computed afresh,
                // the stored first page stands in for the page the analysis will place.
                if (pages == null || awaitingAnalysis || (analysisPending && showingStored))
                    return@produceState
                // The zoom is about to change: wait for it rather than render the page twice.
                if (paper != null && currentCropsResult == null) return@produceState
                value = catching {
                    val placement =
                        if (paper != null && currentCrops != null) {
                            timeline?.mark("placing page ${index + 1}")
                            paper.place(index, currentCrops)
                        } else {
                            null
                        }
                    val window = placement?.window ?: FullPage
                    timeline?.mark(
                        "rendering ${if (placement != null) "zoomed" else "whole"} page ${index + 1}"
                    )
                    val bitmap =
                        pages.renderRegion(
                            index,
                            window.regionOn(pages.pageSize(index), aspect),
                            width,
                            height,
                        )
                    val key = FirstPage.Key(index, width, height, savedScale)
                    if (
                        placement != null &&
                            showingStored &&
                            withContext(Dispatchers.Default) { bitmap.sameAs(stored!!.bitmap) }
                    ) {
                        // The page on screen already: keep its bitmap, so it isn't drawn again.
                        Log.i(TAG, "Stored page ${index + 1} is the page rendered")
                        onFirstPageShown(stored!!, true)
                        ShownPage(index, stored.bitmap, aspect, placement)
                    } else {
                        if (showingStored)
                            Log.w(
                                TAG,
                                "Stored page ${index + 1} differs from the page rendered; replacing it",
                            )
                        if (placement != null && index == 0)
                            onFirstPageShown(FirstPage(key, bitmap), false)
                        ShownPage(index, bitmap, aspect, placement)
                    }
                }
            }
        // The model's lost-ink curve, solved point by point while the zoom panel is open.
        val curve by
            produceState<ZoomCurve?>(null, paper, panelOpen) {
                value = null // Not the last paper's or screen shape's curve, even for a frame.
                if (paper != null && panelOpen) paper.curve().collect { value = it }
            }

        Box(
            Modifier.fillMaxSize().pointerInput(pages) {
                detectTapGestures { offset ->
                    // No turning pages until there are pages to turn.
                    if (pages == null) return@detectTapGestures
                    when (tapZone(offset.x, offset.y, size.width, size.height)) {
                        TapZone.Back -> index = (index - 1).coerceAtLeast(0)
                        TapZone.Forward -> index = (index + 1).coerceAtMost(pages.pageCount - 1)
                        // Nothing to show until the analysis is done.
                        TapZone.ZoomControls -> if (analysis != null) panelOpen = !panelOpen
                    }
                }
            }
        ) {
            val result = analysis
            val pageLabel = "${index + 1} / ${pages?.pageCount}"
            // With the zoom panel open, the page sits above it rather than under it, so the
            // whole crop stays in view while you judge it.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    page
                        ?.onSuccess { shown ->
                            // Rendered for the whole screen; above the panel it's only scaled down,
                            // until the whole-page preview has rendered over it. Nothing here
                            // depends
                            // on more than the bitmap, so the stored first page, once confirmed,
                            // isn't
                            // drawn again.
                            val bitmap = shown.bitmap
                            val shownIndex = shown.index
                            val zoomed = shown.zoomed
                            val image = remember(bitmap) { bitmap.asImageBitmap() }
                            Image(
                                bitmap = image,
                                contentDescription = "Page ${shownIndex + 1}",
                                modifier =
                                    Modifier.fillMaxSize()
                                        .testTag("page")
                                        // Its own layer: whatever else changes on screen (the
                                        // system
                                        // bars leaving, say) doesn't draw the page again.
                                        .graphicsLayer()
                                        .drawWithContent {
                                            drawContent()
                                            timeline?.pageDrawn(bitmap, shownIndex, zoomed = zoomed)
                                            onDrawn(bitmap)
                                        },
                            )
                            // Drawn for the screen shape it was placed for, and only while that
                            // shape's analysis is current.
                            if (
                                panelOpen &&
                                    pages != null &&
                                    paper != null &&
                                    shown.placement != null
                            ) {
                                CropPreview(pages, shown.index, shown.aspect, shown.placement)
                            }
                        }
                        ?.onFailure {
                            Message("Couldn't render page ${index + 1}: ${it.message ?: it}")
                        }
                }
                val zoomError = cropsResult?.exceptionOrNull()
                if (panelOpen && paper != null && zoomError != null) {
                    // The panel stays below, so another zoom or Auto can put it right.
                    Text(
                        text =
                            "$pageLabel · Couldn't zoom this paper to ${savedScale ?: scale}: " +
                                (zoomError.message ?: zoomError),
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(ReaderPaper)
                                .padding(16.dp)
                                .testTag("zoomError"),
                    )
                }
                if (panelOpen && paper != null) {
                    ZoomPanel(
                        pageLabel = pageLabel,
                        curve =
                            curve
                                ?: ZoomCurve(
                                    paper.scales,
                                    emptyMap(),
                                    paper.pagesAnalyzed,
                                    paper.auto.scale,
                                ),
                        crops = crops,
                        sampled = paper.pagesAnalyzed < paper.pageCount,
                        scale = scale,
                        isAuto = savedScale == null,
                        onScaleChange = onSaveScale,
                        onAuto = { onSaveScale(null) },
                        onClose = { panelOpen = false },
                    )
                }
            }
            if (panelOpen && result != null && paper == null) {
                val error = result.exceptionOrNull()
                Text(
                    text =
                        "$pageLabel · Couldn't analyze this paper for zoom: " +
                            (error?.message ?: error),
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(ReaderPaper)
                            .padding(16.dp),
                )
            }
        }
    }
}

/** Runs [block], turning failures other than cancellation into a failed [Result]. */
internal suspend fun <T> catching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** Crops solved for [paper] at [scale]. */
private class Solving(val paper: PaperAnalysis, val scale: Float, val result: Result<ModelCrops>)

/**
 * A page as the reader shows it, rendered for a screen of [aspect]: [placement] is null while
 * there's no zoom to place, or (for a stored first page, [zoomed]) until the analysis places it.
 */
private class ShownPage(
    val index: Int,
    val bitmap: Bitmap,
    val aspect: Float,
    val placement: Placement?,
    val zoomed: Boolean = placement != null,
)

internal enum class TapZone {
    Back,
    Forward,
    ZoomControls,
}

/** Which control a tap at ([x], [y]) on a [width] x [height] reader hits. */
internal fun tapZone(x: Float, y: Float, width: Int, height: Int): TapZone =
    when {
        y >= height * (1 - ZOOM_ZONE_HEIGHT) && x >= width / 3f && x < width * 2 / 3f ->
            TapZone.ZoomControls
        x < width / 2f -> TapZone.Back
        else -> TapZone.Forward
    }

/** The zoom controls open from the middle third of this bottom share of the screen. */
private const val ZOOM_ZONE_HEIGHT = 0.15f

/** A [Message] that shows only once it's been in composition for [delayMillis]. */
@Composable
internal fun DelayedMessage(
    text: String,
    delayMillis: Long,
    modifier: Modifier = Modifier,
    timeline: OpenTimeline? = null,
) {
    var due by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        due = true
    }
    if (due) Message(text, modifier, timeline)
}

@Composable
internal fun Message(text: String, modifier: Modifier = Modifier, timeline: OpenTimeline? = null) {
    if (timeline != null) SideEffect { timeline.messageShown(text) }
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val TAG = "Sortes"

/** The reader says it's loading only once opening the PDF has taken this long. */
internal const val LOADING_MESSAGE_DELAY_MS = 500L
