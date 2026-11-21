package com.wheremi.line4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineTest {
    private val kst = ZoneId.of("Asia/Seoul")
    // 2026-11-23 은 월요일
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, kst)

    private val commute = Routine("a", "출근", 27, 14, days = setOf(1, 2, 3, 4, 5), startMin = 7 * 60 + 30, endMin = 9 * 60)

    @Test
    fun weekdayWindow() {
        assertTrue(commute.isActiveAt(at(23, 8, 0)))    // 월 08:00
        assertFalse(commute.isActiveAt(at(23, 9, 0)))   // 끝 시각은 미포함
        assertFalse(commute.isActiveAt(at(23, 7, 29)))
        assertFalse(commute.isActiveAt(at(22, 8, 0)))   // 일요일
        assertEquals("평일", commute.daysLabel())
        assertEquals("07:30–09:00", commute.timeLabel())
    }

    @Test
    fun windowAcrossMidnight() {
        // 금요일 23:00 ~ 다음날 01:00
        val late = commute.copy(days = setOf(5), startMin = 23 * 60, endMin = 60)
        assertTrue(late.isActiveAt(at(27, 23, 30)))  // 금 23:30
        assertTrue(late.isActiveAt(at(28, 0, 30)))   // 토 00:30 (금요일 시간대의 연장)
        assertFalse(late.isActiveAt(at(29, 0, 30)))  // 일 00:30
    }

    @Test
    fun nextStart() {
        // 월 10:00 이후 → 화 07:30
        assertEquals(at(24, 7, 30), commute.nextStart(at(23, 10, 0)))
        // 금 10:00 이후 → 다음 월 07:30
        assertEquals(at(30, 7, 30), commute.nextStart(at(27, 10, 0)))
    }

    @Test
    fun jsonRoundTrip() {
        val r = commute.copy(lat = 37.476, lng = 126.981, radiusM = 500, enabled = false)
        assertEquals(r, Routine.fromJson(r.toJson()))
    }

    @Test
    fun stationPlaceFollowsOrigin() {
        val r = commute.copy(originIdx = Line4.stations.indexOf("인덕원")).let { it.atStation(it.originIdx) }
        assertEquals("인덕원역 근처", r.placeLabel())
        assertEquals(37.4016, r.lat!!, 0.001)
        // 출발역을 사당으로 바꾸면 위치도 사당역으로
        val moved = r.withOrigin(Line4.stations.indexOf("사당"))
        assertEquals("사당역 근처", moved.placeLabel())
        assertEquals(37.4789, moved.lat!!, 0.001)
        // 현재 위치로 직접 정한 경우는 그대로
        val custom = r.copy(lat = 1.0, lng = 2.0, placeStationIdx = null).withOrigin(0)
        assertEquals(1.0, custom.lat!!, 0.0)
    }

    @Test
    fun allStationsHaveCoordsInsideCapitalArea() {
        assertEquals(Line4.stations.size, Line4.coords.size)
        Line4.coords.forEachIndexed { i, (lat, lng) ->
            assertTrue("${Line4.stations[i]} $lat,$lng", lat in 37.2..37.8 && lng in 126.6..127.3)
        }
    }
}
