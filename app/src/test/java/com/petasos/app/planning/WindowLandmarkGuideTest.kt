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

    @Test fun dueAboutTwelveSecondsBeforeClosestApproachOnTheRight() {
        // 100 m east of the road, 500 m ahead of the start.
        val target = landmark("a", 37.0 + 500 / 111_195.0, 127.0 + 100 / 88_800.0)
        val guide = WindowLandmarkGuide(listOf(target))
        drive(guide, 37.0, 3) // at ~84 m: 416 m ahead = 14.9 s
        assertNull(guide.due())
        guide.observe(37.0 + 4 * 28 / 111_195.0, 127.0, 4000) // 112 m: 388 m = 13.9 s
        guide.observe(37.0 + 6 * 28 / 111_195.0, 127.0, 6000) // 168 m: 332 m = 11.9 s
        assertEquals("a", guide.due()?.placeId)
        // 332 m ahead, 100 m right: about 17 degrees, still "ahead".
        assertEquals(WindowDirection.FRONT, guide.announcementNow(target)!!.direction)
        // Speaking a few seconds later (after the current sentence): 220 m ahead -> about 24 degrees.
        guide.observe(37.0 + 280 / 111_195.0, 127.0, 10_000)
        val now = guide.announcementNow(target)!!
        assertEquals(WindowDirection.FRONT_RIGHT, now.direction)
        assertEquals("오른쪽 앞 창밖으로 한빛탑이 있어요. 1993년에 세운 전망대예요.", now.spoken)
        assertEquals("오른쪽 앞 · 한빛탑", now.label)
    }

    @Test fun lateralDistanceUsesTheVisibilityRadius() {
        val far = landmark("far", 37.0 + 300 / 111_195.0, 127.0 - 400 / 88_800.0)
        val small = WindowLandmarkGuide(listOf(far))
        drive(small, 37.0, 3)
        assertNull("400 m to the side is beyond small's 150 m", small.due())
        val large = WindowLandmarkGuide(listOf(far.copy(visibility = WindowLandmark.Visibility.LARGE)))
        drive(large, 37.0, 3)
        assertEquals("far", large.due()?.placeId)
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

    @Test fun bearingBandsAndParticles() {
        assertEquals(WindowDirection.FRONT, WindowDirection.of(-19.0))
        assertEquals(WindowDirection.FRONT_LEFT, WindowDirection.of(-45.0))
        assertEquals(WindowDirection.RIGHT, WindowDirection.of(119.0))
        assertNull(WindowDirection.of(121.0))
        assertEquals("이", WindowAnnouncement.subjectParticle("숭례문"))
        assertEquals("가", WindowAnnouncement.subjectParticle("반포대교 달빛무지개분수"))
        assertEquals("가", WindowAnnouncement.subjectParticle("서울타워"))
        assertEquals("가", WindowAnnouncement.subjectParticle("문화역 서울 284")) // 이백팔십사가
        assertEquals("이", WindowAnnouncement.subjectParticle("청계산"))
    }
}
