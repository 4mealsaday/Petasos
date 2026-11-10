package com.petasos.app.planning

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** One landmark from the installed pack (CONTENT_PIPELINE.md 6장). */
data class WindowLandmark(
    val placeId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val visibility: Visibility,
    val intro: String,
) {
    /** Largest lateral distance from the travel line at which the place is mentioned. */
    enum class Visibility(val radiusM: Double) { SMALL(150.0), LARGE(1_000.0) }
    override fun toString() = "WindowLandmark([redacted])"
}

/**
 * Where the landmark is said to be (CONTENT_PIPELINE.md 6장): the side it passes on -- the sign of its lateral
 * position relative to the travel line, not the angle at announcement time -- or, for a large landmark that is
 * still far ahead and within +-20 degrees of the heading, straight ahead.
 */
enum class WindowDirection(val words: String) {
    AHEAD_FAR("앞쪽 멀리"), RIGHT("오른쪽"), LEFT("왼쪽");

    companion object {
        const val AHEAD_HALF_ANGLE_DEG = 20.0
        /** "Still far": at least this far along the heading (a place due in 12 s at 17 m/s or more). */
        const val FAR_FORWARD_M = 200.0

        fun of(landmark: WindowLandmark, forwardM: Double, lateralM: Double, bearingDeg: Double): WindowDirection =
            if (landmark.visibility.radiusM >= WindowLandmark.Visibility.LARGE.radiusM &&
                abs(bearingDeg) <= AHEAD_HALF_ANGLE_DEG && forwardM >= FAR_FORWARD_M) AHEAD_FAR
            else if (lateralM >= 0) RIGHT else LEFT
    }
}

/** What the app says and shows. The sentence informs; it never tells the driver to look (6장). */
data class WindowAnnouncement(val landmark: WindowLandmark, val direction: WindowDirection) {
    private val subject get() = "${landmark.name}${subjectParticle(landmark.name)}"
    val spoken: String get() = when (direction) {
        WindowDirection.AHEAD_FAR -> "앞쪽 멀리 $subject 보여요. ${landmark.intro}"
        else -> "잠시 후 ${direction.words}으로 $subject 보여요. ${landmark.intro}"
    }
    /** Caption card label line: "오른쪽 · 한빛탑". The body is the intro. */
    val label: String get() = "${direction.words} · ${landmark.name}"
    override fun toString() = "WindowAnnouncement([redacted])"

    companion object {
        /** 이/가 after the last spoken syllable; digits read as Korean numerals; anything else takes 이. */
        fun subjectParticle(name: String): String {
            val last = name.trim().lastOrNull { it.isLetterOrDigit() } ?: return "이"
            return when (last) {
                in '가'..'힣' -> if ((last - '가') % 28 != 0) "이" else "가"
                in '0'..'9' -> if (last in "2459") "가" else "이"
                else -> "이"
            }
        }
    }
}

/**
 * Decides when a landmark is due, from recent GPS fixes only (6장). The host feeds it on its serial worker
 * and reads it on the main thread right before speaking, so every call is synchronized. Heading and speed come from the latest fix and the oldest recent fix at least
 * [minimumBaseM] behind it. A landmark is due once the time to its closest approach at the current speed is at
 * most [leadMaxMs] (8-12 s target window; a later first sighting is still announced while it is not behind),
 * its lateral distance is within its visibility radius, and it has not been mentioned on this trip.
 */
class WindowLandmarkGuide(
    private val landmarks: List<WindowLandmark>,
    private val leadMaxMs: Long = 12_000,
    private val windowMs: Long = 8_000,
    private val minimumBaseM: Double = 30.0,
    private val minimumSpeedMps: Double = 2.0,
    /** No car moves faster (~250 km/h): above it the 8-12 s lead is meaningless (GPS jump, replay fast-forward). */
    private val maximumSpeedMps: Double = 70.0,
) {
    private data class Fix(val latitude: Double, val longitude: Double, val elapsedMs: Long)
    data class Motion(val latitude: Double, val longitude: Double, val headingDeg: Double, val speedMps: Double)
    data class Relative(val forwardM: Double, val lateralM: Double, val bearingDeg: Double)

    private val fixes = ArrayDeque<Fix>()
    private val mentioned = mutableSetOf<String>()

    @Synchronized fun observe(latitude: Double, longitude: Double, elapsedMs: Long) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        if (fixes.isNotEmpty() && elapsedMs < fixes.last().elapsedMs) fixes.clear() // clock scope changed
        fixes.addLast(Fix(latitude, longitude, elapsedMs))
        while (fixes.size > 2 && elapsedMs - fixes.first().elapsedMs > windowMs) fixes.removeFirst()
    }

    @Synchronized fun motion(): Motion? {
        val last = fixes.lastOrNull() ?: return null
        // The most recent fix far enough back to give a stable heading: follows speed changes within a fix or two.
        val base = fixes.lastOrNull { it !== last && distanceM(it.latitude, it.longitude, last.latitude, last.longitude) >= minimumBaseM }
            ?: return null
        val seconds = (last.elapsedMs - base.elapsedMs) / 1000.0
        if (seconds <= 0.0) return null
        val (east, north) = local(base.latitude, base.longitude, last.latitude, last.longitude)
        val speed = hypot(east, north) / seconds
        if (speed < minimumSpeedMps || speed > maximumSpeedMps) return null
        return Motion(last.latitude, last.longitude, Math.toDegrees(atan2(east, north)), speed)
    }

    /** The landmark's position relative to the car: metres ahead, metres to the right (negative = left). */
    @Synchronized fun relative(landmark: WindowLandmark, motion: Motion): Relative {
        val (east, north) = local(motion.latitude, motion.longitude, landmark.latitude, landmark.longitude)
        val heading = Math.toRadians(motion.headingDeg)
        val forward = east * kotlin.math.sin(heading) + north * cos(heading)
        val right = east * cos(heading) - north * kotlin.math.sin(heading)
        return Relative(forward, right, Math.toDegrees(atan2(right, forward)))
    }

    /** The next landmark to mention now, or null. The caller marks it with [mention] once it acts on it. */
    @Synchronized fun due(): WindowLandmark? {
        val motion = motion() ?: return null
        return landmarks.asSequence()
            .filter { it.placeId !in mentioned }
            .mapNotNull { landmark ->
                val position = relative(landmark, motion)
                val seconds = position.forwardM / motion.speedMps
                if (position.forwardM <= 0.0 || abs(position.lateralM) > landmark.visibility.radiusM ||
                    seconds * 1000 > leadMaxMs) null
                else landmark to seconds
            }
            .minByOrNull { it.second }?.first
    }

    /** At speaking time, from the latest fix; null when it is already behind or out of its visibility radius. */
    @Synchronized fun announcementNow(landmark: WindowLandmark): WindowAnnouncement? {
        val motion = motion() ?: return null
        val position = relative(landmark, motion)
        if (position.forwardM <= 0.0 || abs(position.lateralM) > landmark.visibility.radiusM) return null
        return WindowAnnouncement(landmark, WindowDirection.of(landmark, position.forwardM, position.lateralM, position.bearingDeg))
    }

    /** Once per trip: spoken, dropped (user talking, passed) or skipped, it is never repeated. */
    @Synchronized fun mention(placeId: String) { mentioned += placeId }
    @Synchronized fun mentioned(placeId: String) = placeId in mentioned
    /** Not taken after all (another announcement was speaking): it may become due again on a later fix. */
    @Synchronized fun release(placeId: String) { mentioned -= placeId }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0

        /** (east, north) metres from a to b in a local equirectangular frame. */
        fun local(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Pair<Double, Double> {
            val k = Math.PI / 180 * EARTH_RADIUS_M
            return Pair((bLng - aLng) * k * cos(Math.toRadians((aLat + bLat) / 2)), (bLat - aLat) * k)
        }

        fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val (east, north) = local(aLat, aLng, bLat, bLng)
            return sqrt(east * east + north * north)
        }
    }
}
