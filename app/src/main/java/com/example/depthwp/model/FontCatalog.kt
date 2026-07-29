package com.example.depthwp.model

/**
 * The set of selectable fonts for the time and date text. Each option is a built-in Android font
 * family plus an optional horizontal squeeze ([scaleX]) applied via `Paint.textScaleX` — this is
 * how the "extra condensed" look is achieved crisply (measureText honours textScaleX, so all
 * geometry stays correct and the glyphs are never distorted vertically).
 *
 * [key] is what gets persisted; [family] is the actual Android font family to load.
 */
object FontCatalog {

    data class FontOption(
        val key: String,
        val label: String,
        val family: String,
        val scaleX: Float = 1.0f
    )

    val OPTIONS: List<FontOption> = listOf(
        FontOption("sans-serif", "Standard", "sans-serif"),
        FontOption("sans-serif-condensed-light", "iOS-Style", "sans-serif-condensed-light"),
        FontOption("sans-serif-condensed", "Schmal", "sans-serif-condensed"),
        FontOption("sans-serif-condensed-medium", "Schmal Kräftig", "sans-serif-condensed-medium"),
        FontOption("ultra-condensed", "Extra Schmal", "sans-serif-condensed-medium", 0.58f),
        FontOption("sans-serif-smallcaps", "Kapitälchen", "sans-serif-smallcaps"),
        FontOption("serif", "Serife", "serif"),
        FontOption("monospace", "Mono", "monospace"),
        FontOption("casual", "Casual", "casual"),
        FontOption("cursive", "Handschrift", "cursive")
    )

    const val DEFAULT_FAMILY = "sans-serif"

    private fun optionFor(key: String): FontOption =
        OPTIONS.firstOrNull { it.key == key } ?: OPTIONS.first()

    fun familyFor(key: String): String = optionFor(key).family
    fun scaleXFor(key: String): Float = optionFor(key).scaleX
    fun labelFor(key: String): String = optionFor(key).label
}
