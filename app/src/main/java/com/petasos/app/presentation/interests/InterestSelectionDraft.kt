package com.petasos.app.presentation.interests

/** Explicit single-tag choices. Labels may change without changing catalog matching or saved tags. */
enum class InterestChoice(val tag: String) {
    HISTORY("history"), CULTURE("culture"), ARCHITECTURE("architecture"), NATURE("nature"),
    PLACE_NAMES("place_names"), SCIENCE("science"), FOOD("food"), SPORTS("sports"),
    INDUSTRY("industry"), ART("art"), LOCAL_LIFE("local_life"), PEOPLE("people"),
}

/** In-memory editor only: no inference, persistence, aliases, or implicit expansion of a choice. */
class InterestSelectionDraft private constructor(
    originalOrder: List<String>,
    selectedTags: Set<String>,
    val customText: String,
) {
    private val originalOrder = originalOrder.toList()
    val selectedTags = selectedTags.toSet()

    fun toggle(choice: InterestChoice): InterestSelectionDraft = InterestSelectionDraft(
        originalOrder,
        if (choice.tag in selectedTags) selectedTags - choice.tag else selectedTags + choice.tag,
        customText,
    )

    fun withCustomText(text: String) = InterestSelectionDraft(originalOrder, selectedTags, text)

    /** Null keeps an invalid draft visible, rather than truncating or silently normalizing it. */
    fun valuesForSave(): List<String>? {
        val known = InterestChoice.values().map { it.tag }.toSet()
        val originalCustom = originalOrder.filterNot { it in known }
        // Existing valid storage may contain commas or outer spaces. An untouched field, including
        // when only a tile changed, must preserve those exact user keywords instead of reparsing them.
        val custom = when {
            customText == originalCustom.joinToString(", ") -> originalCustom
            customText.isBlank() -> emptyList()
            else -> customText.split(',').map(String::trim)
        }
        val values = InterestChoice.values().map { it.tag }.filter { it in selectedTags } + custom
        if (!isValid(values)) return null
        // Preserve the order of retained existing tags, including arbitrary saved keywords.
        return originalOrder.filter { it in values } + values.filter { it !in originalOrder }
    }

    override fun toString() = "InterestSelectionDraft([redacted])"

    companion object {
        const val MAX_KEYWORDS = 20
        fun empty() = fromStored(emptyList())

        fun fromStored(values: List<String>): InterestSelectionDraft {
            require(isValid(values))
            val known = InterestChoice.values().map { it.tag }.toSet()
            return InterestSelectionDraft(values, values.filter { it in known }.toSet(),
                values.filterNot { it in known }.joinToString(", "))
        }

        private fun isValid(values: List<String>) = values.size <= MAX_KEYWORDS &&
            values.none { it.isBlank() || it.length > 256 } && values.distinct().size == values.size
    }
}
