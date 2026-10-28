package dev.sortes.app.importing

/** Something shared to the app ("Share to Sortes") that should be imported. */
sealed interface ShareRequest {
    data class Arxiv(val text: String) : ShareRequest
    data class Pdf(val uri: String) : ShareRequest

    companion object {
        private const val ACTION_SEND = "android.intent.action.SEND"

        // Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, inlined to keep this free of Android classes.
        internal const val FLAG_LAUNCHED_FROM_HISTORY = 0x00100000

        /**
         * Maps an incoming intent's parts to a request, or null if there is nothing to import.
         * An intent seen again because the activity is being [restored] (rotation, process death)
         * or relaunched from Recents (see [flags]) was already handled, so yields null.
         */
        fun from(
            action: String?,
            type: String?,
            text: String?,
            stream: String?,
            flags: Int = 0,
            restored: Boolean = false,
        ): ShareRequest? {
            if (restored || flags and FLAG_LAUNCHED_FROM_HISTORY != 0) return null
            if (action != ACTION_SEND) return null
            return when {
                type == "application/pdf" && stream != null && isContentUri(stream) -> Pdf(stream)
                type == "text/plain" && !text.isNullOrBlank() -> Arxiv(text)
                else -> null
            }
        }

        // Only content URIs carry a grant; file:// and friends would read with our permissions.
        private fun isContentUri(uri: String) = uri.startsWith("content://", ignoreCase = true)
    }
}
