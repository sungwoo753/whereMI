package com.wheremi.line4

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainTrackerTest {

    private fun idx(name: String) = Line4.stations.indexOf(name).also { require(it >= 0) { name } }

    private fun pos(no: String, st: String, status: Int, updn: Int, at: Long = 0L, term: String? = null) =
        TrainPos(no, idx(st), status, updn, at, term?.let { idx(it) })

    @Test
    fun stationIdsMatchApi() {
        // 실제 API 응답에서 확인한 값들
        assertEquals(idx("불암산"), Line4.indexOf("1004000409", "불암산"))
        assertEquals(idx("동작"), Line4.indexOf("1004000431", "동작"))
        assertEquals(idx("사당"), Line4.indexOf("1004000433", "사당"))
        assertEquals(idx("금정"), Line4.indexOf("1004000443", "금정"))
        assertEquals(idx("서울역"), Line4.indexOf("1004000426", "서울"))
        assertEquals(idx("오이도"), Line4.indexOf("1004000456", "오이도"))
        // 이름만으로도 매칭
        assertEquals(idx("서울역"), Line4.indexOf(null, "서울"))
        assertEquals(idx("총신대입구(이수)"), Line4.indexOf(null, "이수"))
        assertEquals(idx("불암산"), Line4.indexOf(null, "당고개"))
        assertEquals(idx("진접"), Line4.indexOf(null, "진접"))
    }

    @Test
    fun parsesRealApiRecord() {
        val o = JSONObject(
            """{"subwayId":"1004","statnId":"1004000431","statnNm":"동작","trainNo":"4085",
               "recptnDt":"2026-11-21 14:41:10","updnLine":"1","statnTid":"1004000433",
               "statnTnm":"사당","trainSttus":"1"}"""
        )
        val p = SubwayApi.parsePosition(o)!!
        assertEquals("4085", p.trainNo)
        assertEquals(idx("동작"), p.stationIdx)
        assertEquals(1, p.status)
        assertEquals(1, p.updn)
        assertEquals(idx("사당"), p.terminalIdx)
        assertNotNull(SubwayApi.parseTime("2026-11-21 14:41:10"))
    }

    @Test
    fun boardsApproachingTrainAndTracksIt() {
        // 혜화 → 사당 (하행, 인덱스 증가)
        val t = TrainTracker(idx("혜화"), idx("사당"))
        assertEquals(1, t.updn)

        val now = 1_000_000L
        t.onPositions(
            listOf(
                pos("A", "한성대입구", 2, 1, now),  // 혜화로 다가오는 열차 (1역 전)
                pos("B", "성신여대입구", 1, 1, now), // 그 뒤 열차
                pos("C", "동대문", 2, 1, now),       // 이미 지나간 열차
                pos("D", "한성대입구", 2, 0, now),   // 반대 방향
            ),
            now,
        )
        assertEquals(Phase.WAITING, t.phase)
        assertEquals("A", t.view(now).trainNo)
        // A 는 한성대입구를 출발해 혜화로 오는 중 = 전역을 지남 → 진행 바
        assertEquals(0, t.view(now).stopsBefore)
        assertNotNull(t.view(now).progress)

        // A 가 혜화를 출발 → 탑승한 것으로 판단
        val t2 = now + 120_000
        t.onPositions(listOf(pos("A", "혜화", 2, 1, t2), pos("B", "한성대입구", 1, 1, t2)), t2)
        assertEquals(Phase.RIDING, t.phase)
        assertEquals("A", t.trainNo)
        val v = t.view(t2 + 30_000)
        assertEquals(idx("동대문"), v.nextIdx)
        assertEquals(idx("사당"), v.targetIdx)
        assertEquals(idx("사당") - idx("동대문") + 1, v.stopsBefore)
        assertEquals("13역 전", RideText.pill(v).sub)
        assertNull(v.progress)

        // 사당 도착
        val t3 = t2 + 1_800_000
        t.onPositions(listOf(pos("A", "사당", 1, 1, t3)), t3)
        assertEquals(Phase.ARRIVED, t.phase)
    }

    @Test
    fun northboundDirection() {
        // 금정 → 사당 (상행, 인덱스 감소)
        val t = TrainTracker(idx("금정"), idx("사당"))
        assertEquals(0, t.updn)
        t.onPositions(listOf(pos("X", "금정", 1, 0, 0L, term = "불암산")), 0L)
        assertEquals("X", t.view(0L).trainNo)
        assertTrue(t.view(0L).atTarget)
        assertEquals("도착", RideText.pill(t.view(0L)).sub)
        assertNull(t.view(0L).shortTerminalIdx)

        t.onPositions(listOf(pos("X", "범계", 3, 0, 10_000L)), 10_000L)
        assertEquals(Phase.RIDING, t.phase)
        val v = t.view(10_000L)
        assertEquals(idx("범계"), v.nextIdx)
    }

    @Test
    fun warnsWhenTrainTerminatesEarly() {
        // 서울역 → 오이도 인데 사당행 열차
        val t = TrainTracker(idx("서울역"), idx("오이도"))
        t.onPositions(listOf(pos("S", "회현", 2, 1, 0L, term = "사당")), 0L)
        assertEquals(idx("사당"), t.view(0L).shortTerminalIdx)
    }

    @Test
    fun skipAndLateConfirm() {
        val t = TrainTracker(idx("혜화"), idx("사당"))
        val trains = listOf(
            pos("A", "한성대입구", 2, 1),
            pos("B", "성신여대입구", 2, 1),
            pos("C", "동대문", 2, 1),
        )
        t.onPositions(trains, 0L)
        assertEquals("A", t.view(0L).trainNo)
        t.skipTrain()
        assertEquals("B", t.view(0L).trainNo)

        // B 는 아직 2역 전이라 "곧 도착" 이 아님 → 방금 혜화를 떠난 C 로 인식 (늦게 누른 경우)
        assertTrue(t.confirmBoarded(0L))
        assertEquals(Phase.RIDING, t.phase)
        assertEquals("C", t.trainNo)
    }

    @Test
    fun finalStretchShowsProgressAndUsesHint() {
        // 혜화 → 동대문 (한 정거장). 혜화를 출발하면 바로 마지막 구간
        val t = TrainTracker(idx("혜화"), idx("동대문"))
        t.onPositions(listOf(pos("A", "혜화", 1, 1, 0L)), 0L)
        t.onPositions(listOf(pos("A", "혜화", 2, 1, 0L)), 0L)
        assertEquals(Phase.RIDING, t.phase)
        assertEquals(30f / 150f, t.view(0L).progress!!, 0.01f) // 전체 = 정차 30 + 주행 120
        assertEquals(90f / 150f, t.view(60_000L).progress!!, 0.01f)

        // 도착정보 API: 50초에 도착 → 10초 시점 40초 남음
        t.onArrivalHint(ArrivalHint("A", idx("동대문"), 50_000L))
        val v = t.view(10_000L)
        assertEquals(40, v.etaSec)
        assertEquals(1f - 40f / 150f, v.progress!!, 0.01f)
        assertNull(RideText.pill(v).sub)
        assertEquals("곧", RideText.chip(v))

        // 다른 역에 대한 힌트는 무시
        t.onArrivalHint(ArrivalHint("A", idx("충무로"), 999_000L))
        assertEquals(40, t.view(10_000L).etaSec)

        // 도착
        t.onPositions(listOf(pos("A", "동대문", 1, 1, 60_000L)), 60_000L)
        assertEquals(Phase.ARRIVED, t.phase)
    }

    @Test
    fun nextStopDestinationShowsBarEvenWhileDwelling() {
        val t = TrainTracker(idx("혜화"), idx("충무로"))
        t.onPositions(listOf(pos("A", "혜화", 1, 1, 0L)), 0L)
        t.onPositions(listOf(pos("A", "혜화", 2, 1, 0L)), 0L)
        // 동대문(2역 전) 정차 중 → "2역 전"
        t.onPositions(listOf(pos("A", "동대문", 1, 1, 0L)), 0L)
        assertEquals("2역 전", RideText.pill(t.view(0L)).sub)
        // 동대문역사문화공원(충무로 전역) 정차 중 = 도착지가 다음 역 → 진행 바 0% 부터
        t.onPositions(listOf(pos("A", "동대문역사문화공원", 1, 1, 0L)), 0L)
        assertEquals(0f, RideText.pill(t.view(0L)).progress!!, 0.01f)
        // 출발 → 0% 로 되돌아가지 않고 정차 30초만큼 진행한 지점(30/150)에서 이어서 차오름
        t.onPositions(listOf(pos("A", "동대문역사문화공원", 2, 1, 0L)), 0L)
        assertEquals(30f / 150f, RideText.pill(t.view(0L)).progress!!, 0.01f)
        assertTrue(RideText.pill(t.view(35_000L)).progress!! > 0.4f)
    }

    @Test
    fun waitingRules() {
        // 인덕원 → 선바위 (상행). 평촌을 막 출발한 열차 = 출발역의 전역을 지남 → 진행 바
        val t = TrainTracker(idx("인덕원"), idx("선바위"))
        t.onPositions(listOf(pos("P", "평촌", 2, 0, 0L)), 0L)
        val v = t.view(0L)
        assertEquals(Line4.segmentSec(idx("평촌"), idx("인덕원")), v.etaSec)
        assertEquals("인덕원", RideText.pill(v).main)
        assertEquals(30f / 180f, RideText.pill(v).progress!!, 0.01f) // 정차 30 + 평촌→인덕원 150
        assertEquals("3분", RideText.chip(v))

        // 범계 정차 중 → 열차 2역 전
        val t2 = TrainTracker(idx("인덕원"), idx("선바위"))
        t2.onPositions(listOf(pos("Q", "범계", 1, 0, 0L)), 0L)
        assertEquals("2역 전", RideText.pill(t2.view(0L)).sub)
        assertEquals("2역 전", RideText.chip(t2.view(0L)))
        assertEquals(30 + 120 + 30 + 150, t2.view(0L).etaSec)

        // 도착정보 API 힌트 우선
        t.onArrivalHint(ArrivalHint("P", idx("인덕원"), 70_000L))
        assertEquals(60, t.view(10_000L).etaSec)

        // 인덕원 정차 중
        t.onPositions(listOf(pos("P", "인덕원", 1, 0, 20_000L)), 20_000L)
        assertEquals("도착", RideText.pill(t.view(20_000L)).sub)
    }

    @Test
    fun frozenApiIsExtrapolatedByTimetable() {
        // 실제 상황 재현: 4606 열차가 인덕원 '도착' 상태로 API 가 멈춤 (인덕원 → 선바위, 상행)
        val t = TrainTracker(idx("인덕원"), idx("선바위"))
        val frozen = listOf(pos("4606", "인덕원", 1, 0, 0L, term = "불암산"))
        t.onPositions(frozen, 10_000L)
        assertEquals(Phase.WAITING, t.phase)
        assertTrue(t.view(10_000L).atTarget)

        // 정차 30초 x1.5 + 20초 = 65초가 지나도 그대로면 → 30초에 출발한 것으로 추정 → 탑승
        t.onPositions(frozen, 70_000L)
        assertEquals(Phase.RIDING, t.phase)
        assertEquals("4606", t.trainNo)
        assertTrue(t.view(70_000L).estimated)

        // 계속 멈춰 있으면 시간표대로 진행: 출발(30s) + 인덕원→정부과천청사(120s) 후 정부과천청사 정차
        val e = t.extrapolate(frozen[0], 30_000L + 120_000L + 5_000L)
        assertEquals(idx("정부과천청사"), e.stationIdx)
        assertEquals(1, e.status)

        // API 가 다시 살아나면 그 값을 쓴다
        t.onPositions(listOf(pos("4606", "과천", 1, 0, 200_000L)), 200_000L)
        assertEquals(idx("과천"), t.view(200_000L).nextIdx!! + 1)
        assertTrue(!t.view(200_000L).estimated)
    }

    @Test
    fun confirmBoardedPicksTheArrivingTrain() {
        // 인덕원 → 선바위. 평촌 정차 중(=인덕원이 다음 역) 열차가 "곧 도착할 열차"
        val t = TrainTracker(idx("인덕원"), idx("선바위"))
        val trains = listOf(
            pos("NEAR", "평촌", 1, 0, 0L),
            pos("FAR", "범계", 2, 0, 0L),
            pos("GONE", "정부과천청사", 2, 0, 0L), // 이미 인덕원을 떠난 지 오래된 열차
        )
        t.onPositions(trains, 0L)
        assertTrue(RideText.pill(t.view(0L)).boardable)
        assertTrue(t.confirmBoarded(0L))
        assertEquals(Phase.RIDING, t.phase)
        assertEquals("NEAR", t.trainNo)
        // 탑승 확정 후엔 도착역(선바위) 기준. 열차는 아직 평촌이므로 인덕원~선바위 6역 전
        assertEquals(idx("선바위"), t.view(0L).targetIdx)
        assertEquals(6, t.view(0L).stopsBefore)
        assertTrue(!RideText.pill(t.view(0L)).boardable)

        // 열차가 인덕원 정차 중일 때 눌러도 그 열차
        val t2 = TrainTracker(idx("인덕원"), idx("선바위"))
        t2.onPositions(listOf(pos("AT", "인덕원", 1, 0, 0L), pos("GONE", "정부과천청사", 2, 0, 0L)), 0L)
        assertTrue(t2.confirmBoarded(0L))
        assertEquals("AT", t2.trainNo)
    }
}
