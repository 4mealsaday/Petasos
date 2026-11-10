package com.petasos.app.presentation.interests

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import com.petasos.app.R
import kotlin.math.roundToInt

/** Shared onboarding/settings editor: 12 chips, several may be on. The owner decides when a draft is saved. */
class InterestGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GridLayout(context, attrs) {
    var onChoice: ((InterestChoice) -> Unit)? = null
    private val chips = linkedMapOf<InterestChoice, TextView>()

    init {
        columnCount = COLUMNS
        isSaveEnabled = false
        setSaveFromParentEnabled(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        useDefaultMargins = false
        alignmentMode = ALIGN_BOUNDS
        InterestChoice.values().forEachIndexed { index, choice ->
            val chip = TextView(context, null, 0, R.style.Widget_Petasos_Chip).apply {
                text = context.getString(label(choice))
                isSingleLine = true
                isSaveEnabled = false
                setSaveFromParentEnabled(false)
                setOnClickListener { onChoice?.invoke(choice) }
            }
            chips[choice] = chip
            addView(chip, LayoutParams().apply {
                width = 0
                height = dp(56)
                rowSpec = spec(index / COLUMNS)
                columnSpec = spec(index % COLUMNS, 1f)
                setMargins(dp(6), dp(6), dp(6), dp(6))
                setGravity(Gravity.FILL)
            })
        }
    }

    /** Does not notify the selection listener or save values when binding a stored/draft state. */
    fun render(selectedTags: Set<String>) {
        chips.forEach { (choice, chip) -> chip.isSelected = choice.tag in selectedTags }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        chips.values.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.45f }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun label(choice: InterestChoice) = when (choice) {
        InterestChoice.HISTORY -> R.string.interests_history
        InterestChoice.CULTURE -> R.string.interests_culture
        InterestChoice.ARCHITECTURE -> R.string.interests_architecture
        InterestChoice.NATURE -> R.string.interests_nature
        InterestChoice.PLACE_NAMES -> R.string.interests_place_names
        InterestChoice.SCIENCE -> R.string.interests_science
        InterestChoice.FOOD -> R.string.interests_food
        InterestChoice.SPORTS -> R.string.interests_sports
        InterestChoice.INDUSTRY -> R.string.interests_industry
        InterestChoice.ART -> R.string.interests_art
        InterestChoice.LOCAL_LIFE -> R.string.interests_local_life
        InterestChoice.PEOPLE -> R.string.interests_people
    }

    private companion object { const val COLUMNS = 4 }
}
