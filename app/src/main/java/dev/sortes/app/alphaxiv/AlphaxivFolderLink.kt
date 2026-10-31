package dev.sortes.app.alphaxiv

/**
 * Recognises alphaXiv shared-folder links such as `https://www.alphaxiv.org/shared/folder/<uuid>`.
 */
object AlphaxivFolderLink {
    private val PATTERN =
        Regex(
            """(?:^|[^\w./-])(?:https?://)?(?:www\.)?alphaxiv\.org/shared/folder/""" +
                """([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![0-9a-z-])""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Returns the lower-cased folder ID of the first folder link in [text], or null if there is
     * none.
     */
    fun parse(text: String): String? = PATTERN.find(text)?.groupValues?.get(1)?.lowercase()

    /** The canonical share URL for [folderId]. */
    fun url(folderId: String): String = "https://www.alphaxiv.org/shared/folder/$folderId"
}
