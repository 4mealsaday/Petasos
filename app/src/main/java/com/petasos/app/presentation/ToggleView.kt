package com.petasos.app.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.petasos.app.R

/** DESIGN.md toggle: 72x40dp track, on `primary`, off `line`, white thumb. State is set by the owner. */
class ToggleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var checked: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    init {
        isClickable = true
        isFocusable = true
        stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resources.getDimensionPixelSize(R.dimen.toggle_width), resources.getDimensionPixelSize(R.dimen.toggle_height))
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = context.getColor(if (checked) R.color.primary else R.color.line)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        val inset = dp(4).toFloat()
        val radius = height / 2f - inset
        val cx = if (checked) width - inset - radius else inset + radius
        paint.color = context.getColor(R.color.surface)
        canvas.drawCircle(cx, height / 2f, radius, paint)
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.45f }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
