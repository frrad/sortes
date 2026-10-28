package dev.sortes.app.importing

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Reads a "Share to Sortes" intent; see [ShareRequest.from]. */
fun Intent.toShareRequest(restored: Boolean): ShareRequest? = ShareRequest.from(
    action = action,
    type = type,
    // Some apps share a Spanned rather than a String.
    text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
    stream = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
    flags = flags,
    restored = restored,
)
