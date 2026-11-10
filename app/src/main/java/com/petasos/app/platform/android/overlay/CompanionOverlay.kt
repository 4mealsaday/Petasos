package com.petasos.app.platform.android.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.playback.PlaybackMode
import com.petasos.app.presentation.DesignScale
import com.petasos.app.presentation.records.CurrentRegionLabel

/**
 * Two TYPE_APPLICATION_OVERLAY windows drawn on top of whatever screen (Pleos Navigation included)
 * is currently in front. Owned by a foreground service, never an Activity, so both windows survive
 * the host Activity going away. Both windows are opaque and touchable, and each is sized exactly to
 * its visible shape (the card's margins are window offsets, not transparent padding), so touches
 * anywhere else still reach the map beneath. They stay inside the navigation map area: the vehicle
 * status panel on the left (one third of the display) is never covered.
 */
class CompanionOverlay(serviceContext: Context, private val host: PetasosSessionHost) {
    private enum class Ring { IDLE, LISTENING, CAUTION }
    private class CardState(val icon: Int, val label: String, val body: String?, val ring: Ring, val dots: Boolean)

    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // Sizes follow the navigation map area (two thirds of the display), see DesignScale.
    private val context: Context = DesignScale.wrap(serviceContext, displayWidthDp(serviceContext) * 2 / 3)
    private val inflater = LayoutInflater.from(context)
    private val handler = Handler(Looper.getMainLooper())
    private var buttonView: View? = null
    private var cardView: View? = null
    private var carPolicy: CarPolicySource? = null
    private var regionLabel: CurrentRegionLabel? = null
    private var restricted = false
    private val refreshListener: () -> Unit = { refresh() }
    private val frontListener: () -> Unit = { applyAppInFront() }

    // Sentence pacing: the caption is a whole paragraph, the card shows one sentence at a time.
    private var pacedText: String? = null
    private var sentences = emptyList<String>()
    private var sentenceIndex = 0
    private val advance = Runnable { advanceSentence() }
    private var ringState: Ring? = null
    private var pulse: ObjectAnimator? = null
    private var blink: ValueAnimator? = null
    private var cautionUntil = 0L
    private val cautionEnd = Runnable { refresh() }

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
        AppInFront.add(frontListener)
        applyAppInFront()
        regionLabel = CurrentRegionLabel(context, host) { refresh() }.also { it.start() }
        carPolicy = CarPolicySource(context, onUx = { value ->
            restricted = value.requiresDistractionOptimization
            refresh()
        }, onPower = {}).also { it.start() }
        refresh()
    }

    fun detach() {
        check(Looper.myLooper() == Looper.getMainLooper())
        host.removeUiListener(refreshListener)
        AppInFront.remove(frontListener)
        host.setOverlayTalkVisible(this, false)
        host.setOverlayCaptionVisible(this, false)
        handler.removeCallbacks(advance); handler.removeCallbacks(cautionEnd)
        stopAnimations()
        regionLabel?.stop(); regionLabel = null
        carPolicy?.close(); carPolicy = null
        buttonView?.let { safeRemove(it) }
        cardView?.let { safeRemove(it) }
        buttonView = null
        cardView = null
    }

    /** Hidden and untouchable while Petasos itself is in front; shown again over navigation. */
    private fun applyAppInFront() {
        val hide = AppInFront.value
        listOf(buttonView, cardView).forEach { view ->
            view ?: return@forEach
            view.visibility = if (hide) View.GONE else View.VISIBLE
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            params.flags = if (hide) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { /* Already detached. */ }
        }
    }

    private fun safeRemove(view: View) {
        try { windowManager.removeView(view) } catch (_: Exception) { /* Already detached. */ }
    }

    private fun refresh() {
        val card = cardView ?: return
        val state = stateFor(host.snapshot()?.mode)
        card.findViewById<ImageView>(R.id.overlay_card_icon).apply {
            setImageResource(state.icon)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_sub))
        }
        card.findViewById<TextView>(R.id.overlay_card_label).text = state.label
        card.findViewById<TextView>(R.id.overlay_card_text).apply {
            text = state.body.orEmpty()
            // Driving restriction: only the one-line label stays.
            visibility = if (restricted || state.body.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        renderButton(state)
    }

    private fun stateFor(mode: PlaybackMode?): CardState {
        val region = regionLabel?.label
        fun join(vararg parts: String?) = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")
        val caption = host.currentOverlayCaption(this)?.value
        if (mode != PlaybackMode.STORY && mode != PlaybackMode.ANSWERING && mode != PlaybackMode.RESUMING &&
            mode != PlaybackMode.PAUSED && mode != PlaybackMode.GENERATING) pace(null)
        return when (mode) {
            // Nothing is selected yet (region not confirmed): stay silent and say so.
            PlaybackMode.IDLE -> CardState(R.drawable.ic_menu_book, region ?: context.getString(R.string.overlay_label_default),
                context.getString(R.string.overlay_card_waiting), Ring.IDLE, false)
            PlaybackMode.TALK_PENDING -> CardState(R.drawable.ic_mic, context.getString(R.string.overlay_label_talk_pending), null, Ring.IDLE, false)
            PlaybackMode.PROMPT -> CardState(R.drawable.ic_mic, context.getString(R.string.overlay_label_speak_now), null, Ring.LISTENING, false)
            PlaybackMode.LISTENING -> CardState(R.drawable.ic_mic, context.getString(R.string.overlay_label_listening), null, Ring.LISTENING, false)
            // While the failure notice ("answer server unreachable") is being spoken it owns the card.
            PlaybackMode.GENERATING -> {
                if (caption != null) {
                    if (cautionUntil == 0L) cautionUntil = SystemClock.elapsedRealtime() + CAUTION_MS
                    CardState(R.drawable.ic_chat_bubble, context.getString(R.string.overlay_label_answer), pace(caption),
                        if (SystemClock.elapsedRealtime() < cautionUntil) Ring.CAUTION else Ring.IDLE, false)
                } else {
                    pace(null)
                    val heard = host.lastHeardText()
                    CardState(R.drawable.ic_chat_bubble,
                        heard?.let { context.getString(R.string.overlay_card_heard, it) } ?: context.getString(R.string.overlay_label_answer),
                        context.getString(R.string.overlay_card_thinking), Ring.IDLE, true)
                }
            }
            PlaybackMode.MUSIC -> {
                val label = host.snapshot()?.musicCheckpoint?.track?.assetId?.let { assetId ->
                    context.getString(R.string.overlay_label_music, host.musicTitle(assetId), host.musicArtist(assetId))
                } ?: context.getString(R.string.overlay_label_default)
                CardState(R.drawable.ic_music_note, label, context.getString(R.string.overlay_card_music_body), Ring.IDLE, false)
            }
            PlaybackMode.ANSWERING, PlaybackMode.RESUMING ->
                CardState(R.drawable.ic_chat_bubble, context.getString(R.string.overlay_label_answer), pace(caption) ?: context.getString(R.string.overlay_card_idle), Ring.IDLE, false)
            else -> {
                val title = if (mode == PlaybackMode.STORY) host.selectedTitle() else null
                CardState(R.drawable.ic_menu_book, join(region, title).ifBlank { context.getString(R.string.overlay_label_default) },
                    pace(caption) ?: context.getString(R.string.overlay_card_idle), Ring.IDLE, false)
            }
        }.also { if (it.ring != Ring.CAUTION && mode != PlaybackMode.GENERATING) cautionUntil = 0L }
    }

    // ------------------------------------------------------------------ one sentence at a time

    /** Returns the sentence to show for [text]. The text is the whole paragraph being spoken. */
    private fun pace(text: String?): String? {
        if (text == null) {
            if (pacedText != null) { pacedText = null; sentences = emptyList(); handler.removeCallbacks(advance) }
            return null
        }
        if (text != pacedText) {
            pacedText = text
            sentences = splitSentences(text)
            sentenceIndex = 0
            scheduleAdvance()
        }
        return sentences.getOrNull(sentenceIndex)
    }

    private fun advanceSentence() {
        if (sentenceIndex < sentences.lastIndex) {
            sentenceIndex++
            scheduleAdvance()
            refresh()
        }
    }

    private fun scheduleAdvance() {
        handler.removeCallbacks(advance)
        val current = sentences.getOrNull(sentenceIndex) ?: return
        if (sentenceIndex >= sentences.lastIndex) return
        handler.postDelayed(advance, speakingMillis(current))
    }

    /** TTS reports no progress, so pace by length: roughly how long a sentence takes to be spoken. */
    private fun speakingMillis(sentence: String): Long =
        (sentence.count { !it.isWhitespace() } * 1000.0 / CHARS_PER_SECOND).toLong().coerceAtLeast(MIN_SENTENCE_MS)

    private fun splitSentences(text: String): List<String> =
        text.replace('\n', ' ').trim().split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

    // ------------------------------------------------------------------ circular button

    private fun renderButton(state: CardState) {
        val button = buttonView ?: return
        val ring = button.findViewById<View>(R.id.overlay_talk_ring)
        val mark = button.findViewById<ImageView>(R.id.overlay_talk_mark)
        val dots = button.findViewById<View>(R.id.overlay_talk_dots)
        if (ringState != state.ring) {
            ringState = state.ring
            ring.setBackgroundResource(when (state.ring) {
                Ring.IDLE -> R.drawable.ring_idle
                Ring.LISTENING -> R.drawable.ring_listening
                Ring.CAUTION -> R.drawable.ring_caution
            })
            pulse?.cancel(); pulse = null
            ring.alpha = 1f
            if (state.ring == Ring.LISTENING) {
                pulse = ObjectAnimator.ofFloat(ring, View.ALPHA, 1f, 0.25f).apply {
                    duration = PULSE_MS / 2; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                    interpolator = LinearInterpolator(); start()
                }
            }
        }
        handler.removeCallbacks(cautionEnd)
        if (state.ring == Ring.CAUTION) handler.postDelayed(cautionEnd, (cautionUntil - SystemClock.elapsedRealtime()).coerceAtLeast(50))
        dots.visibility = if (state.dots) View.VISIBLE else View.GONE
        // The microphone shrinks and moves up a little so the three dots sit below it.
        mark.translationY = if (state.dots) -12f * context.resources.displayMetrics.density else 0f
        mark.scaleX = if (state.dots) 0.75f else 1f
        mark.scaleY = mark.scaleX
        if (state.dots && blink == null) {
            val views = listOf(R.id.overlay_dot_1, R.id.overlay_dot_2, R.id.overlay_dot_3).map { button.findViewById<View>(it) }
            blink = ValueAnimator.ofFloat(0f, 3f).apply {
                duration = BLINK_MS; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val active = (animator.animatedValue as Float).toInt().coerceAtMost(2)
                    views.forEachIndexed { index, view -> view.alpha = if (index == active) 1f else 0.3f }
                }
                start()
            }
        } else if (!state.dots) { blink?.cancel(); blink = null }
    }

    private fun stopAnimations() {
        pulse?.cancel(); pulse = null
        blink?.cancel(); blink = null
        ringState = null
    }

    // ------------------------------------------------------------------ windows

    /** The whole display, not the app resources' metrics (those can report a narrower area on Pleos). */
    @Suppress("DEPRECATION")
    private fun screenWidth(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds.width()
        else android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.widthPixels

    /** Pleos shows its vehicle panel in the left third; navigation's map starts after it. */
    private fun mapLeft(): Int = screenWidth() / 3

    private fun buttonParams(): WindowManager.LayoutParams {
        val resources = context.resources
        val size = resources.getDimensionPixelSize(R.dimen.overlay_button_size)
        return WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = resources.getDimensionPixelSize(R.dimen.overlay_button_margin) + resources.getDimensionPixelSize(R.dimen.overlay_system_strip)
        }
    }

    /** Lower right of the map: right of navigation's search/guidance panel, left of its volume/settings buttons. */
    private fun cardParams(): WindowManager.LayoutParams {
        val resources = context.resources
        val left = mapLeft() + resources.getDimensionPixelSize(R.dimen.overlay_card_start_inset)
        val right = screenWidth() - resources.getDimensionPixelSize(R.dimen.overlay_card_margin_end)
        return WindowManager.LayoutParams(
            right - left,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = left
            y = resources.getDimensionPixelSize(R.dimen.overlay_card_margin_bottom)
        }
    }

    private companion object {
        fun displayWidthDp(context: Context): Int {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.width()
            else context.resources.displayMetrics.widthPixels
            return (bounds / context.resources.displayMetrics.density).toInt()
        }

        val SENTENCE_BREAK = Regex("(?<=[.!?。])\\s+")
        const val CHARS_PER_SECOND = 5.0
        const val MIN_SENTENCE_MS = 1_800L
        const val PULSE_MS = 1_200L
        const val BLINK_MS = 1_200L
        const val CAUTION_MS = 2_000L
    }
}
