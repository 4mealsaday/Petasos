package com.petasos.app.planning

import org.junit.Assert.*
import org.junit.Test

class WindowLandmarkGuideTest {
    // Heading north along longitude 127.0 at ~28 m/s (one fix per second). 1e-5 deg lat ~ 1.11 m.
    private fun drive(guide: WindowLandmarkGuide, fromLat: Double, seconds: Int, speedMps: Double = 28.0, startMs: Long = 0) {
        for (t in 0..seconds) guide.observe(fromLat + t * speedMps / 111_195.0, 127.0, startMs + t * 1000L)
    }
    private fun landmark(id: String, lat: Double, lng: Double, visibility: WindowLandmark.Visibility = WindowLandmark.Visibility.SMALL) =
        WindowLandmark(id, "한빛탑", lat, lng, visibility, "1993년에 세운 전망대예요.")

    @Test fun smallLandmarkAheadOnTheRightIsRightFront() {
        // 100 m east of the road, 500 m ahead of the start: about 17 degrees ahead when it is due.
        val target = landmark("a", 37.0 + 500 / 111_195.0, 127.0 + 100 / 88_800.0)
        val guide = WindowLandmarkGuide(listOf(target))
        drive(guide, 37.0, 3) // at ~84 m: 416 m ahead = 14.9 s
        assertNull(guide.due())
        guide.observe(37.0 + 4 * 28 / 111_195.0, 127.0, 4000) // 112 m: 388 m = 13.9 s
        guide.observe(37.0 + 6 * 28 / 111_195.0, 127.0, 6000) // 168 m: 332 m = 11.9 s
        assertEquals("a", guide.due()?.placeId)
        val now = guide.announcementNow(target)!!
        assertEquals(WindowDirection.RIGHT_FRONT, now.direction)
        assertEquals("오른쪽 앞으로 한빛탑이 보여요. 1993년에 세운 전망대예요.", now.spoken)
        assertEquals("오른쪽 앞 · 한빛탑", now.label)
    }

    @Test fun largeLandmarkStillFarAndPassingWithinHundredMetersIsAheadFar() {
        val tower = landmark("t", 37.0 + 500 / 111_195.0, 127.0 - 90 / 88_800.0, WindowLandmark.Visibility.LARGE)
        val guide = WindowLandmarkGuide(listOf(tower))
        drive(guide, 37.0, 6) // 168 m driven: 332 m ahead, 90 m left
        val now = guide.announcementNow(tower)!!
        assertEquals(WindowDirection.AHEAD_FAR, now.direction)
        assertEquals("앞쪽 멀리 한빛탑이 보여요. 1993년에 세운 전망대예요.", now.spoken)
        assertEquals("앞쪽 멀리 · 한빛탑", now.label)
        // The same tower, said later when it is under 200 m ahead: no longer "far", so the side it passes on.
        guide.observe(37.0 + 330 / 111_195.0, 127.0, 12_000)
        assertEquals(WindowDirection.LEFT_FRONT, guide.announcementNow(tower)!!.direction)
        assertEquals("왼쪽 앞으로 한빛탑이 보여요. 1993년에 세운 전망대예요.", guide.announcementNow(tower)!!.spoken)
    }

    @Test fun largeLandmarkPassingAtHundredMetersOrMoreIsNotAheadFar() {
        val mountain = landmark("m", 37.0 + 500 / 111_195.0, 127.0 + 120 / 88_800.0, WindowLandmark.Visibility.LARGE)
        val guide = WindowLandmarkGuide(listOf(mountain))
        drive(guide, 37.0, 6)
        assertEquals(WindowDirection.RIGHT_FRONT, guide.announcementNow(mountain)!!.direction)
    }

    @Test fun beyondSixtyDegreesIsSaidAsPlainSide() {
        val side = landmark("s", 37.0 + 250 / 111_195.0, 127.0 + 140 / 88_800.0) // 80 m driven -> 170 m ahead, 140 m right = 40 deg
        val guide = WindowLandmarkGuide(listOf(side))
        drive(guide, 37.0, 3)
        assertEquals(WindowDirection.RIGHT_FRONT, guide.announcementNow(side)!!.direction)
        guide.observe(37.0 + 230 / 111_195.0, 127.0, 8_000) // 20 m ahead, 140 m right = 82 deg
        guide.observe(37.0 + 235 / 111_195.0, 127.0, 8_200)
        assertEquals(WindowDirection.RIGHT, guide.announcementNow(side)!!.direction)
        assertEquals("잠시 후 오른쪽으로 한빛탑이 보여요. 1993년에 세운 전망대예요.", guide.announcementNow(side)!!.spoken)
    }

    @Test fun largeLandmarkOutsideTwentyDegreesUsesItsSide() {
        val mountain = landmark("m", 37.0 + 500 / 111_195.0, 127.0 + 250 / 88_800.0, WindowLandmark.Visibility.LARGE)
        val guide = WindowLandmarkGuide(listOf(mountain))
        drive(guide, 37.0, 6) // 332 m ahead, 250 m right = 37 degrees
        assertEquals(WindowDirection.RIGHT_FRONT, guide.announcementNow(mountain)!!.direction)
    }

    @Test fun lateralDistanceUsesTheVisibilityRadius() {
        val far = landmark("far", 37.0 + 300 / 111_195.0, 127.0 - 400 / 88_800.0)
        val small = WindowLandmarkGuide(listOf(far))
        drive(small, 37.0, 3)
        assertNull("400 m to the side is beyond small's 150 m", small.due())
        val large = WindowLandmarkGuide(listOf(far.copy(visibility = WindowLandmark.Visibility.LARGE)))
        drive(large, 37.0, 3)
        assertEquals("far", large.due()?.placeId)
        // 400 m to the left and about 216 m ahead is 62 degrees: not ahead, so by the side it passes on.
        assertEquals(WindowDirection.LEFT, large.announcementNow(far.copy(visibility = WindowLandmark.Visibility.LARGE))!!.direction)
    }

    @Test fun behindIsNeverSaidAndEachPlaceOnlyOnce() {
        val target = landmark("a", 37.0 + 200 / 111_195.0, 127.0 + 50 / 88_800.0)
        val guide = WindowLandmarkGuide(listOf(target))
        drive(guide, 37.0, 3)
        assertEquals("a", guide.due()?.placeId)
        guide.mention("a")
        assertNull(guide.due())
        drive(guide, 37.0 + 400 / 111_195.0, 2, startMs = 10_000) // now 200+ m past it
        assertNull(guide.announcementNow(target))
    }

    @Test fun noHeadingWhileStopped() {
        val guide = WindowLandmarkGuide(listOf(landmark("a", 37.0001, 127.0)))
        for (t in 0..5) guide.observe(37.0, 127.0, t * 1000L)
        assertNull(guide.motion())
        assertNull(guide.due())
    }

    @Test fun nothingIsDueAtSpeedsNoCarReaches() {
        val target = landmark("a", 37.0 + 2_000 / 111_195.0, 127.0 + 50 / 88_800.0)
        val guide = WindowLandmarkGuide(listOf(target))
        drive(guide, 37.0, 3, speedMps = 300.0) // replay fast-forward: 2 km in under 7 s
        assertNull(guide.motion())
        assertNull(guide.due())
    }

    @Test fun particles() {
        assertEquals("이", WindowAnnouncement.subjectParticle("숭례문"))
        assertEquals("가", WindowAnnouncement.subjectParticle("반포대교 달빛무지개분수"))
        assertEquals("가", WindowAnnouncement.subjectParticle("서울타워"))
        assertEquals("가", WindowAnnouncement.subjectParticle("문화역 서울 284")) // 이백팔십사가
        assertEquals("이", WindowAnnouncement.subjectParticle("청계산"))
    }
}
