package com.petasos.app.presentation

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import kotlin.math.roundToInt

/** Keeps full-size controls in a compact Pleos window without fixed-width rows overflowing. */
class CompanionActionGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GridLayout(context, attrs) {
    private var controls = emptyList<View>()
    private var appliedColumns = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        controls = (0 until childCount).map(::getChildAt)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val columns = (width / dp(132)).coerceIn(1, controls.size.coerceAtLeast(1))
        if (columns != appliedColumns) {
            removeAllViews() // Old child specs cannot reference columns outside the new count.
            columnCount = columns
            controls.forEachIndexed { index, child ->
                addView(child, LayoutParams().apply {
                    this.width = 0; height = dp(64)
                    rowSpec = spec(index / columns)
                    columnSpec = spec(index % columns, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3)); setGravity(Gravity.FILL)
                })
            }
            appliedColumns = columns
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
