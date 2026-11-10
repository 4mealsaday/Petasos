package com.petasos.app.platform.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.playback.PlaybackMode

/**
 * Two TYPE_APPLICATION_OVERLAY windows drawn on top of whatever screen (Pleos Navigation included)
 * is currently in front. Owned by a foreground service, never an Activity, so both windows survive
 * the host Activity going away. Both windows are opaque and touchable, and each is sized exactly to
 * its visible shape (the card's side/bottom margins are window offsets, not transparent padding), so
 * touches anywhere else still reach the map beneath.
 */
class CompanionOverlay(private val context: Context, private val host: PetasosSessionHost) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private var buttonView: View? = null
    private var cardView: View? = null
    private var carPolicy: CarPolicySource? = null
    private var restricted = false
    private val refreshListener: () -> Unit = { refresh() }

    fun attach() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (buttonView != null) return
        val button = inflater.inflate(R.layout.view_overlay_button, null)
        val card = inflater.inflate(R.layout.view_overlay_card, null)
        button.setOnClickListener { host.talk() }
        try {
            windowManager.addView(button, buttonParams())
            windowManager.addView(card, cardParams())
        } catch (_: Exception) {
            return
        }
        buttonView = button
        cardView = card
        host.setOverlayCaptionVisible(this, true)
        host.setOverlayTalkVisible(this, true)
        host.addUiListener(refreshListener)
        carPolicy = CarPolicySource(context, onUx = { value ->
            restricted = value.requiresDistractionOptimization
            refresh()
        }, onPower = {}).also { it.start() }
        refresh()
    }

    fun detach() {
        check(Looper.myLooper() == Looper.getMainLooper())
        host.removeUiListener(refreshListener)
        host.setOverlayTalkVisible(this, false)
        host.setOverlayCaptionVisible(this, false)
        carPolicy?.close(); carPolicy = null
        buttonView?.let { safeRemove(it) }
        cardView?.let { safeRemove(it) }
        buttonView = null
        cardView = null
    }

    private fun safeRemove(view: View) {
        try { windowManager.removeView(view) } catch (_: Exception) { /* Already detached. */ }
    }

    private fun refresh() {
        val card = cardView ?: return
        val text = card.findViewById<TextView>(R.id.overlay_card_text)
        val snapshot = host.snapshot()
        val caption = when (snapshot?.mode) {
            // Nothing is selected yet (region not confirmed): stay silent and say so.
            PlaybackMode.IDLE -> context.getString(R.string.overlay_card_waiting)
            PlaybackMode.TALK_PENDING -> context.getString(R.string.playback_talk_pending)
            PlaybackMode.PROMPT -> context.getString(R.string.overlay_card_speak_now)
            PlaybackMode.LISTENING -> context.getString(R.string.playback_listening)
            // While the failure notice ("answer server unreachable") is being spoken it owns the card.
            PlaybackMode.GENERATING -> host.currentOverlayCaption(this)?.value
                ?: host.lastHeardText()?.let { context.getString(R.string.overlay_card_heard, it) }
                ?: context.getString(R.string.overlay_card_thinking)
            PlaybackMode.MUSIC -> snapshot.musicCheckpoint?.track?.assetId?.let { assetId ->
                context.getString(R.string.journey_title_music, host.musicTitle(assetId), host.musicArtist(assetId))
            }
            else -> host.currentOverlayCaption(this)?.value
        } ?: context.getString(R.string.overlay_card_idle)
        text.text = caption
        text.maxLines = if (restricted) 1 else 2
        text.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            context.resources.getDimension(if (restricted) R.dimen.overlay_card_text_size_restricted else R.dimen.overlay_card_text_size),
        )
    }

    private fun buttonParams() = WindowManager.LayoutParams(
        context.resources.getDimensionPixelSize(R.dimen.overlay_button_size),
        context.resources.getDimensionPixelSize(R.dimen.overlay_button_size),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        x = context.resources.getDimensionPixelSize(R.dimen.overlay_button_margin_end)
    }

    /** The whole display, not the app resources' metrics (those can report a narrower area on Pleos). */
    @Suppress("DEPRECATION")
    private fun screenWidth(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds.width()
        else android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.widthPixels

    private fun cardParams(): WindowManager.LayoutParams {
        val resources = context.resources
        val margin = resources.getDimensionPixelSize(R.dimen.overlay_card_margin_horizontal)
        return WindowManager.LayoutParams(
            screenWidth() - 2 * margin,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = resources.getDimensionPixelSize(R.dimen.overlay_card_margin_bottom)
        }
    }
}
