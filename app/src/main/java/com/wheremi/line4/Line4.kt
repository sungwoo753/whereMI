package com.wheremi.line4

/**
 * 4호선 역 목록 (진접 → 오이도 순서, 즉 "하행" 방향 순서).
 *
 * 서울 열린데이터 실시간 API 기준:
 *  - subwayId = 1004
 *  - updnLine "0" = 상행(오이도 → 진접 방향), "1" = 하행(진접 → 오이도 방향)
 *  - statnId 는 불암산(1004000409) ~ 오이도(1004000456) 구간에서 순서대로 1씩 증가
 *  - 당고개역은 2024년 "불암산"으로 개명되어 API에서도 불암산으로 나옴
 */
object Line4 {
    const val SUBWAY_ID = "1004"
    const val LINE_NAME = "4호선"
    const val COLOR = 0xFF00A5DE.toInt()

    /** 화면 표시용 역 이름. 인덱스가 곧 노선상 위치. */
    val stations: List<String> = listOf(
        "진접", "오남", "별내별가람", "불암산", "상계", "노원", "창동", "쌍문", "수유", "미아",
        "미아사거리", "길음", "성신여대입구", "한성대입구", "혜화", "동대문", "동대문역사문화공원",
        "충무로", "명동", "회현", "서울역", "숙대입구", "삼각지", "신용산", "이촌", "동작",
        "총신대입구(이수)", "사당", "남태령", "선바위", "경마공원", "대공원", "과천", "정부과천청사",
        "인덕원", "평촌", "범계", "금정", "산본", "수리산", "대야미", "반월", "상록수", "한대앞",
        "중앙", "고잔", "초지", "안산", "신길온천", "정왕", "오이도",
    )

    /** 실시간 도착정보 API(realtimeStationArrival)에 넣을 역 이름이 표시명과 다른 경우. */
    private val arrivalApiNames = mapOf(
        "서울역" to "서울",
        "총신대입구(이수)" to "총신대입구(이수)",
    )

    private val aliases = mapOf(
        "당고개" to "불암산",
        "이수" to "총신대입구",
        "동대문역사문화공원(DDP)" to "동대문역사문화공원",
    )

    private val normalizedIndex: Map<String, Int> =
        stations.withIndex().associate { (i, name) -> normalize(name) to i }

    private fun normalize(raw: String): String {
        var s = raw.trim().replace(" ", "")
        aliases[s]?.let { s = it }
        s = s.replace(Regex("\\(.*\\)"), "")
        if (s.length > 2 && s.endsWith("역")) s = s.dropLast(1)
        aliases[s]?.let { s = it }
        return s
    }

    /** API 응답의 statnId / statnNm 으로 노선 인덱스를 찾는다. 못 찾으면 null. */
    fun indexOf(statnId: String?, statnNm: String?): Int? {
        statnId?.toLongOrNull()?.let { id ->
            val idx = (id - 1004000406L).toInt()
            if (idx in 3..stations.lastIndex) return idx
        }
        return statnNm?.let { normalizedIndex[normalize(it)] }
    }

    fun arrivalApiName(idx: Int): String = stations[idx].let { arrivalApiNames[it] ?: it }

    /** 표시용 짧은 이름 (괄호 제거). */
    fun shortName(idx: Int): String = stations[idx].replace(Regex("\\(.*\\)"), "")

    /**
     * 인접역 간 대략적인 주행 시간(초). segmentSeconds[k] = stations[k] ↔ stations[k+1].
     * 시간표 기반 근사값이며, 실제 ETA는 도착정보 API(barvlDt)가 있으면 그걸 우선 사용.
     */
    private val segmentSeconds: IntArray = IntArray(stations.size - 1) { 120 }.apply {
        fun set(a: String, sec: Int) { this[stations.indexOf(a)] = sec }
        set("진접", 180); set("오남", 180); set("별내별가람", 240)
        set("미아", 90); set("충무로", 70); set("명동", 70); set("회현", 80)
        set("숙대입구", 90); set("삼각지", 90); set("신용산", 100)
        set("이촌", 180); set("동작", 150); set("총신대입구(이수)", 90)
        set("남태령", 150); set("인덕원", 150); set("금정", 180)
        set("수리산", 180); set("대야미", 180); set("반월", 180)
        set("안산", 180); set("신길온천", 180); set("정왕", 150)
    }

    /**
     * 역 좌표 (위도 to 경도). OpenStreetMap 기준, 역 건물 또는 역 출구/역명 정류장 지점.
     * 루틴 위치를 "역 근처"로 정할 때 사용 (반경 수백 m 라 수십 m 오차는 문제없음).
     */
    val coords: List<Pair<Double, Double>> = listOf(
        37.720054 to 127.203250, // 진접
        37.704966 to 127.191827, // 오남
        37.667824 to 127.116269, // 별내별가람
        37.670098 to 127.078924, // 불암산
        37.659254 to 127.073088, // 상계
        37.656365 to 127.063480, // 노원
        37.653182 to 127.047699, // 창동
        37.647646 to 127.033974, // 쌍문
        37.637470 to 127.025281, // 수유
        37.626268 to 127.026275, // 미아
        37.611786 to 127.030451, // 미아사거리
        37.602101 to 127.024207, // 길음
        37.592613 to 127.016496, // 성신여대입구
        37.588395 to 127.006171, // 한성대입구
        37.583855 to 127.001854, // 혜화
        37.571862 to 127.011489, // 동대문
        37.565462 to 127.009247, // 동대문역사문화공원
        37.561084 to 126.993287, // 충무로
        37.560898 to 126.986376, // 명동
        37.557720 to 126.976710, // 회현
        37.552853 to 126.972572, // 서울
        37.545379 to 126.970718, // 숙대입구
        37.535507 to 126.974111, // 삼각지
        37.527010 to 126.965871, // 신용산
        37.522301 to 126.973956, // 이촌
        37.503181 to 126.976090, // 동작
        37.488247 to 126.982285, // 총신대입구
        37.478888 to 126.981933, // 사당
        37.465450 to 126.987937, // 남태령
        37.451805 to 127.002111, // 선바위
        37.443765 to 127.007896, // 경마공원
        37.435088 to 127.006177, // 대공원
        37.433074 to 126.996958, // 과천
        37.426471 to 126.989750, // 정부과천청사
        37.401632 to 126.976966, // 인덕원
        37.394480 to 126.964754, // 평촌
        37.389913 to 126.950910, // 범계
        37.372307 to 126.943397, // 금정
        37.358047 to 126.933046, // 산본
        37.350540 to 126.925599, // 수리산
        37.327987 to 126.916896, // 대야미
        37.311858 to 126.903713, // 반월
        37.302813 to 126.866778, // 상록수
        37.309882 to 126.853826, // 한대앞
        37.316030 to 126.838529, // 중앙
        37.316860 to 126.823022, // 고잔
        37.320856 to 126.805607, // 초지
        37.327047 to 126.788797, // 안산
        37.337770 to 126.767699, // 신길온천
        37.351271 to 126.741116, // 정왕
        37.361925 to 126.738494, // 오이도
    )

    fun segmentSec(a: Int, b: Int): Int {
        val lo = minOf(a, b)
        return if (lo in segmentSeconds.indices && kotlin.math.abs(a - b) == 1) segmentSeconds[lo] else 120
    }
}
