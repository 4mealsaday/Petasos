package com.petasos.app.storage

import android.content.Context
import com.petasos.app.BuildConfig
import java.io.File

/**
 * DEMO-only "video" preset: one representative story per region, so a recording stays short.
 * The Cheonan -> KAIST demo plays four of these (Cheonan S08, Cheongju Heungdeok, Daejeon Daedeok,
 * Daejeon Yuseong S10); the older Seoul -> Daejeon route's representatives are kept so that pack
 * still records in 4-5 minutes. Toggled by the debug receiver (`SET_VIDEO_MODE`); persisted as a
 * marker file so it survives an app restart.
 */
object DemoVideoMode {
    private const val MARKER = "demo-video-mode"
    val REPRESENTATIVE_STORY_IDS = setOf(
        "03849d89-1640-5603-be8b-9835d371ed02", // S01 서울 중구
        "d87ba5a6-04d1-54b7-8f75-cf58dfe89ef8", // S03 용인
        "84cae426-7167-515c-b28f-808e5886f298", // S05 안성
        "16b09187-02dd-5655-8d46-4c8f4bb55d2f", // S08 천안
        "666b77d3-aba4-5ea6-b9fc-1a4c6556eef5", // S10 대전 유성
        "6d8eae9e-4e24-5a73-bbdb-3f66d49854d6", // 청주 흥덕구 (cheonan-kaist, story-guide-v4)
        "3faab0e7-baf2-58e1-bfa0-e0bece5e2ea0", // 대전 대덕구 (cheonan-kaist, story-guide-v4)
    )

    @Volatile var enabled = false
        private set

    fun load(context: Context) {
        enabled = BuildConfig.DEMO && File(context.filesDir, MARKER).exists()
    }

    fun set(context: Context, value: Boolean) {
        val marker = File(context.filesDir, MARKER)
        if (value) marker.writeText("1") else marker.delete()
        load(context)
    }

    /** True when [storyId] must be skipped by automatic planning in video mode. */
    fun excludes(storyId: String): Boolean = enabled && storyId !in REPRESENTATIVE_STORY_IDS
}
