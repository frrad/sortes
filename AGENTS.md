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
