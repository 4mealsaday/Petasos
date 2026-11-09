package com.petasos.app.presentation.interests

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.GridLayout
import com.petasos.app.R
import kotlin.math.roundToInt

/** Shared onboarding/settings editor. The owner decides when a draft is explicitly saved. */
class InterestGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GridLayout(context, attrs) {
    var onChoice: ((InterestChoice) -> Unit)? = null
    private val tiles = linkedMapOf<InterestChoice, CheckBox>()

    init {
        columnCount = 4
        isSaveEnabled = false
        setSaveFromParentEnabled(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        useDefaultMargins = false
        alignmentMode = ALIGN_BOUNDS
        InterestChoice.values().forEach { choice ->
            val tile = CheckBox(context).apply {
                text = context.getString(label(choice))
                textSize = 19f
                isAllCaps = false
                setTextColor(context.getColor(R.color.text_primary))
                buttonTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
                setBackgroundResource(R.drawable.interest_tile_background)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14), dp(12), dp(12), dp(12))
                compoundDrawablePadding = dp(8)
                minHeight = dp(104)
                minWidth = 0
                maxLines = 2
                isSaveEnabled = false
                setSaveFromParentEnabled(false)
                setOnClickListener { onChoice?.invoke(choice) }
            }
            tiles[choice] = tile
            addView(tile)
        }
        layoutTiles()
    }

    /** Does not notify the selection listener or save values when binding a stored/draft state. */
    fun render(selectedTags: Set<String>) {
        tiles.forEach { (choice, tile) -> tile.isChecked = choice.tag in selectedTags }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        tiles.values.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.45f }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val columns = ((width - paddingLeft - paddingRight) / dp(168)).coerceIn(1, 4)
        if (columnCount != columns) layoutTiles(columns)
    }

    private fun layoutTiles(columns: Int = columnCount) {
        // GridLayout rejects reducing columnCount while old child specs still reference a later column.
        removeAllViews()
        columnCount = columns
        tiles.values.forEachIndexed { index, tile ->
            tile.layoutParams = LayoutParams().apply {
                width = 0
                height = dp(104)
                rowSpec = spec(index / columnCount)
                columnSpec = spec(index % columnCount, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
                setGravity(Gravity.FILL)
            }
            addView(tile)
        }
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
}
