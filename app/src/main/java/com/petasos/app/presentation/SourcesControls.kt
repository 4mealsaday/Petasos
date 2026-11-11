package com.petasos.app.presentation

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import kotlin.math.roundToInt

/**
 * Data-sources page: each source is a name followed by 제공 / 라이선스 / 비고 rows. Public data and map/font
 * entries are static strings; music credits stay read-only from the host (one text block per track) and are
 * only laid out here. The host owns all catalog/file work.
 */
class SourcesControls(private val activity: Activity) {
    private val music = activity.findViewById<TextView>(R.id.sources_music)
    private val musicList = activity.findViewById<LinearLayout>(R.id.sources_music_list)
    private val musicEntries = mutableListOf<View>()
    private var host: PetasosSessionHost? = null
    private var visible = false
    private var epoch = 0L
    private var loaded = false

    init {
        val public = activity.findViewById<LinearLayout>(R.id.sources_public_list)
        entry(public, s(R.string.sources_boundary_name), listOf(
            R.string.sources_key_provider to s(R.string.sources_boundary_provider),
            R.string.sources_key_license to s(R.string.sources_boundary_license),
            R.string.sources_key_note to s(R.string.sources_boundary_note)))
        entry(public, s(R.string.sources_story_name), listOf(
            R.string.sources_key_provider to s(R.string.sources_story_provider),
            R.string.sources_key_license to s(R.string.sources_story_license),
            R.string.sources_key_note to s(R.string.sources_story_note)))
        entry(public, s(R.string.sources_encyclopedia_name), listOf(
            R.string.sources_key_provider to s(R.string.sources_encyclopedia_provider),
            R.string.sources_key_note to s(R.string.sources_encyclopedia_note)))
        val misc = activity.findViewById<LinearLayout>(R.id.sources_misc_list)
        entry(misc, s(R.string.sources_map_name), listOf(
            R.string.sources_key_provider to s(R.string.sources_map_provider),
            R.string.sources_key_note to s(R.string.sources_map_note)))
        entry(misc, s(R.string.sources_font_name), listOf(
            R.string.sources_key_provider to s(R.string.sources_font_provider),
            R.string.sources_key_license to s(R.string.sources_font_license)))
        entry(misc, s(R.string.sources_icon_name), listOf(
            R.string.sources_key_provider to s(R.string.sources_icon_provider),
            R.string.sources_key_license to s(R.string.sources_icon_license)))
    }

    fun update(value: PetasosSessionHost?, show: Boolean) {
        val usable = show && value != null
        if (host !== value || visible != usable) {
            epoch++
            loaded = false
            if (!usable) showMusicStatus(R.string.sources_music_loading)
        }
        host = value
        visible = usable
        if (usable && !loaded) load(value!!)
    }

    private fun load(current: PetasosSessionHost) {
        loaded = true
        val serial = ++epoch
        try {
            val request = current.musicCredits()
            request.addListener({
                if (!visible || epoch != serial || host !== current || activity.isFinishing || activity.isDestroyed) return@addListener
                val text = try { request.get() } catch (_: Exception) { null }
                when {
                    text == null -> showMusicStatus(R.string.sources_music_unavailable)
                    text.isBlank() -> showMusicStatus(R.string.sources_music_empty)
                    else -> showMusic(text)
                }
            }, activity.mainExecutor)
        } catch (_: Exception) {
            showMusicStatus(R.string.sources_music_unavailable)
        }
    }

    private fun showMusicStatus(text: Int) {
        clearMusic()
        music.setText(text)
        music.visibility = View.VISIBLE
    }

    /**
     * One block per track (ReviewedMusicBundle.displayText): "title — artist", album, attribution, "license: url",
     * "원본: url", change notice. Unknown shapes are shown line by line, never dropped.
     */
    private fun showMusic(text: String) {
        clearMusic()
        music.visibility = View.GONE
        for (block in text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val rows = mutableListOf<Pair<Int, String>>()
            val notes = mutableListOf<String>()
            lines.drop(1).forEachIndexed { index, line ->
                when {
                    index == 0 -> rows += R.string.sources_key_album to line
                    line.startsWith("원본:") -> rows += R.string.sources_key_origin to line.removePrefix("원본:").trim()
                    LICENSE.matches(line) -> rows += R.string.sources_key_license to
                        line.substringBefore(":").trim() + "\n" + line.substringAfter(":").trim()
                    else -> notes += line
                }
            }
            musicEntries += entry(musicList, lines.first(), rows, notes)
        }
    }

    private fun clearMusic() {
        musicEntries.forEach { musicList.removeView(it) }
        musicEntries.clear()
    }

    /** name, then key/value rows, then small notes; a thin line separates it from the entry above. */
    private fun entry(parent: LinearLayout, name: String, rows: List<Pair<Int, String>>, notes: List<String> = emptyList()): View {
        val block = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        block.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.line)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(20); bottomMargin = dp(20) })
        block.addView(TextView(activity, null, 0, R.style.Text_Label).apply {
            text = name
            setTextColor(activity.getColor(R.color.text_strong))
        })
        for ((key, value) in rows) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(activity, null, 0, R.style.Text_Caption).apply { setText(key) },
                LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(activity, null, 0, R.style.Text_Caption).apply {
                text = value
                setTextColor(activity.getColor(R.color.text))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            block.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
        }
        for (note in notes) {
            block.addView(TextView(activity, null, 0, R.style.Text_Caption).apply {
                text = note
                setTextColor(activity.getColor(R.color.text_hint))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
        }
        parent.addView(block, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return block
    }

    private fun s(id: Int) = activity.getString(id)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        val LICENSE = Regex("^(CC|Creative Commons)[^:]*:\\s*https?://\\S+$", RegexOption.IGNORE_CASE)
    }
}
