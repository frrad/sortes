# Agent notes

## Testing

- Be thorough with tests. New code, especially anything with I/O, error
  handling, or edge cases, should come with tests that exercise it.
- When you find a bug, add a test that fails without the fix and passes with
  it, in the same change as the fix.
- Prefer fast JVM unit tests (`app/src/test`) — structure code so logic can be
  tested without Android framework classes (e.g. pass a `File` rather than a
  `Context`). Use Robolectric or instrumented tests only for what genuinely
  needs Android.

## Zoom analysis cache

The reader stores each paper's zoom analysis on disk (`zoom/AnalysisCache.kt`), keyed by the
PDF's content, the screen's shape, the `CropConfig` and the installed app build (versionCode
and install time), so every new build recomputes them. Still bump
`AnalysisCache.PIPELINE_VERSION` when a change alters what `analyzePaper` computes (it also
keys zoom-eval and tests), and `FORMAT_VERSION` when the file format changes.

## Design for e-ink

The app is used on e-ink tablets (a Boox Note Air5 C), colour and black-and-white
alike, so it must work on a monochrome screen.

- Black on white. No colour and no grey fills: tints and greys dither into mush.
  The theme (`ui/theme/Theme.kt`) is black-on-white for every Material colour role,
  with no dynamic colour and no dark variant, so prefer theme colours over
  hard-coded ones and don't reintroduce tinted roles.
- Prefer outlines (`InkBorder`) over filled, tinted or shadowed surfaces: cards,
  dialogs and buttons get a black outline on white.
- Avoid animations, ripples and transitions. E-ink refreshes slowly and leaves
  ghosting behind. Ripples are off in the theme, and dialogs don't dim the screen
  (`NoDialogDim`).
- Keep contrast high: don't convey state by colour or by faded text alone.
- Test on the device. Robolectric screenshots don't show how a screen looks on
  e-ink.

## The PDF page is canonical

The reader renders each PDF page one canonical way, exactly as the PDF draws it.
Our job is only to choose the best crop (and to draw UI overlays on top).

- Never change how page content is rendered: no reflowing, no re-typesetting, no
  changing font sizes or weights, and no cutting the page into strips to squeeze
  out white space between paragraphs.
- The crop window has one size for the whole paper. Don't zoom individual pages
  differently.
- Prefer a crop that stays put from page to page over one that shifts slightly to
  balance each page's white space.
