package dev.sortes.app.zoom

/**
 * A kind of ink the zoom shouldn't try to keep on screen, like a stamp in the page margin. Rules
 * run on each page's mask before the crop model sees it, so ink they remove neither pulls the
 * window toward it nor counts as lost when cut off.
 */
fun interface IgnorableInkRule {
    /** [mask] without the ink this rule ignores; [mask] itself if it has none. */
    fun strip(mask: InkMask): InkMask
}

/**
 * The per-page rules every config starts from: margin stamps. [CropConfig.DEFAULT] adds
 * [IsolatedMarks].
 */
val IGNORABLE_INK_RULES: List<IgnorableInkRule> = listOf(MarginStampRule)

/** This mask with each of [rules] applied in turn. */
fun InkMask.withoutIgnorableInk(rules: List<IgnorableInkRule> = IGNORABLE_INK_RULES): InkMask =
    rules.fold(this) { mask, rule -> rule.strip(mask) }
