package com.wheremi.line4

/**
 * RideView → 표시 문자열.
 *
 * 규칙
 *  - 목표 역(대기 중: 출발역 / 탑승 중: 도착역)까지 1역 이상 남음 → "n역 전"
 *  - 목표 역이 바로 다음 역 (전역 정차 중 포함) → 흰 진행 바(progress)
 *  - 대기 중 열차가 출발역에 정차         → "도착"
 */
object RideText {

    /** 오버레이 한 줄: 역 이름 + (보조 문구 또는 진행 바) */
    data class Pill(
        val main: String,
        val sub: String?,
        val progress: Float?,
        /** "탑승" 버튼 표시: 대기 중 + 탈 열차가 출발역 바로 전 역~출발역 정차 중 */
        val boardable: Boolean = false,
    )

    private fun minutes(sec: Int) = ((sec + 30) / 60).coerceAtLeast(1)

    private fun soon(sec: Int?): String = if (sec == null || sec <= 40) "곧 도착" else "${minutes(sec)}분 뒤 도착"

    fun pill(v: RideView): Pill {
        val name = Line4.shortName(v.targetIdx)
        val boardable = v.phase == Phase.WAITING && v.trainNo != null && !v.stale &&
            (v.atTarget || v.progress != null)
        return when {
            v.phase == Phase.ARRIVED -> Pill(name, "도착", null)
            boardable && v.atTarget -> Pill(name, "도착", null, boardable = true)
            boardable -> Pill(name, null, v.progress, boardable = true)
            v.trainNo == null -> Pill(name, "열차 찾는 중", null)
            v.stale -> Pill(name, "위치 확인 중", null)
            v.atTarget -> Pill(name, "도착", null)
            v.progress != null -> Pill(name, null, v.progress)
            else -> Pill(name, "${v.stopsBefore}역 전", null)
        }
    }

    /** 시계 옆 상태바 칩(Android 16+ 실시간 정보)에 들어갈 아주 짧은 문구. */
    fun chip(v: RideView): String = when {
        v.phase == Phase.ARRIVED -> "도착"
        v.trainNo == null -> "4호선"
        v.atTarget -> "도착"
        v.progress != null -> if ((v.etaSec ?: 0) <= 40) "곧" else "${minutes(v.etaSec!!)}분"
        else -> "${v.stopsBefore}역 전"
    }

    fun title(v: RideView): String {
        val name = Line4.shortName(v.targetIdx)
        return when {
            v.phase == Phase.ARRIVED -> "$name 도착"
            v.trainNo == null -> "$name · 다가오는 열차를 찾는 중"
            v.phase == Phase.WAITING && v.atTarget -> "$name · 열차가 도착했어요"
            v.progress != null -> "$name · ${soon(v.etaSec)}"
            else -> "$name · ${v.stopsBefore}역 전"
        }
    }

    fun body(v: RideView, destIdx: Int): String {
        val parts = mutableListOf<String>()
        when (v.phase) {
            Phase.WAITING -> parts += "${Line4.shortName(destIdx)}행 탑승 대기"
            Phase.RIDING -> {
                v.nextIdx?.takeIf { it != v.targetIdx }?.let { parts += "다음 ${Line4.shortName(it)}" }
                if (v.progress != null || v.stopsBefore == 1) parts += "이번 역에서 내리세요"
                if (v.stale) parts += "위치 정보 지연"
            }
            Phase.ARRIVED -> Unit
        }
        v.trainNo?.let { parts += "${it}열차" }
        if (v.estimated) parts += "실시간 정보 지연 · 시간표로 추정"
        v.shortTerminalIdx?.let { parts += "⚠ ${Line4.shortName(it)}행" }
        return parts.joinToString(" · ")
    }
}
