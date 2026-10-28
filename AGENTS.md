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
