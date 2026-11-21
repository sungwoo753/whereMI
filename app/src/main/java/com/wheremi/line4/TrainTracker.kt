package com.wheremi.line4

import kotlin.math.sign

enum class Phase { WAITING, RIDING, ARRIVED }

/** 화면(알림/오버레이)에 그릴 현재 상태. */
data class RideView(
    val phase: Phase,
    val trainNo: String?,
    /** 지금 신경 쓰는 역: 대기 중엔 출발역, 탑승 중엔 도착역 */
    val targetIdx: Int,
    /** targetIdx 까지 남은 역 수 ("n역 전"). 전역을 지나 마지막 구간이면 0 */
    val stopsBefore: Int?,
    /** 전역을 지난 뒤 targetIdx 까지의 진행률 0..1. 마지막 구간이 아니면 null */
    val progress: Float?,
    /** 열차가 targetIdx 에 정차 중 */
    val atTarget: Boolean,
    /** targetIdx 까지 대략 남은 초 */
    val etaSec: Int?,
    /** 열차의 다음 정차역 */
    val nextIdx: Int?,
    /** 열차가 내 도착역 전에 종착하는 경우 그 종착역 */
    val shortTerminalIdx: Int?,
    /** 위치 정보가 오래됨 */
    val stale: Boolean,
    /** API 가 멈춰 시간표로 추정 중 */
    val estimated: Boolean = false,
)

/**
 * 출발역/도착역을 받아, 실시간 위치 목록으로부터
 *  1) 내가 탈(탄) 열차를 고르고
 *  2) 그 열차의 다음 역과 남은 시간을 추정한다.
 *
 * 노선상 위치는 "진행 방향 좌표" u 로 표현: u = 역인덱스 * dir + 상태 보정.
 * u 가 클수록 진행 방향으로 더 나아간 상태.
 */
class TrainTracker(
    val originIdx: Int,
    val destIdx: Int,
) {
    init {
        require(originIdx != destIdx) { "출발역과 도착역이 같습니다" }
    }

    val dir: Int = (destIdx - originIdx).sign
    /** 이 방향의 API updnLine 값. 하행(인덱스 증가) = 1 */
    val updn: Int = if (dir > 0) 1 else 0

    var phase: Phase = Phase.WAITING
        private set
    var trainNo: String? = null
        private set

    private var candidateNo: String? = null
    private val skipped = mutableSetOf<String>()
    private var lastPos: TrainPos? = null
    private var lastSeenAt: Long = 0
    private var hint: ArrivalHint? = null
    private var latest: List<TrainPos> = emptyList()

    private fun u(p: TrainPos): Double = p.stationIdx * dir + when (p.status) {
        3 -> -0.6 // 전역 출발: 이 역으로 오는 중
        0 -> -0.2 // 진입
        1 -> 0.0  // 도착(정차)
        2 -> 0.2  // 출발
        else -> 0.0
    }

    private val originU get() = (originIdx * dir).toDouble()
    private val destU get() = (destIdx * dir).toDouble()

    private companion object {
        const val DWELL = 30          // 역 정차 시간(초)
        const val DWELL_APPROACH = 30 // 진입 → 정차까지(초)
    }

    private fun sameDirection(p: TrainPos) = p.updn == updn

    /** 새 위치 목록이 도착했을 때 호출. */
    fun onPositions(raw: List<TrainPos>, now: Long) {
        val trains = raw.map { extrapolate(it, now) }
        latest = trains
        when (phase) {
            Phase.WAITING -> updateWaiting(trains, now)
            Phase.RIDING -> updateRiding(trains, now)
            Phase.ARRIVED -> Unit
        }
    }

    private fun updateWaiting(trains: List<TrainPos>, now: Long) {
        // 이전에 점찍어 둔 열차가 출발역을 떠났으면 → 그 열차에 탄 것으로 본다.
        candidateNo?.let { no ->
            val c = trains.firstOrNull { it.trainNo == no }
            if (c != null && u(c) > originU) {
                board(c, now)
                updateRiding(trains, now)
                return
            }
        }
        candidateNo = trains
            .filter { sameDirection(it) && it.trainNo !in skipped }
            .filter { u(it) <= originU && u(it) >= originU - 8 }
            .maxByOrNull { u(it) }
            ?.trainNo
    }

    private fun board(p: TrainPos, now: Long) {
        phase = Phase.RIDING
        trainNo = p.trainNo
        lastPos = p
        lastSeenAt = now
        candidateNo = null
        hint = null
    }

    private fun updateRiding(trains: List<TrainPos>, now: Long) {
        val p = trains.firstOrNull { it.trainNo == trainNo } ?: return
        lastPos = p
        lastSeenAt = now
        val arrived = (p.stationIdx == destIdx && p.status in setOf(1, 2)) || u(p) > destU
        if (arrived) phase = Phase.ARRIVED
    }

    /** "이 열차 말고 다음 열차" : 현재 후보/탑승 열차를 제외하고 다시 기다린다. */
    fun skipTrain() {
        (trainNo ?: candidateNo)?.let { skipped += it }
        phase = Phase.WAITING
        trainNo = null
        candidateNo = null
        lastPos = null
        hint = null
        updateWaiting(latest, System.currentTimeMillis())
    }

    /**
     * "탑승했어요" 버튼.
     *  1) 다가오는 열차가 출발역 바로 전 역~출발역 정차 중이면 → 그 열차 (곧 도착할 열차)
     *  2) 아니면 방금 출발역을 떠난 열차(1역 이내)가 있으면 → 그 열차 (늦게 누른 경우)
     *  3) 그것도 없으면 → 다가오는 열차
     */
    fun confirmBoarded(now: Long): Boolean {
        if (phase != Phase.WAITING) return false
        val c = latest.firstOrNull { it.trainNo == candidateNo }
        if (c != null) {
            val a = approach(c, originIdx, now)
            if (a.atTarget || a.progress != null) { board(c, now); return true }
        }
        val departed = latest
            .filter { sameDirection(it) && it.trainNo !in skipped && u(it) > originU && u(it) <= originU + 1.2 }
            .minByOrNull { u(it) }
        if (departed != null) { board(departed, now); return true }
        if (c != null) { board(c, now); return true }
        return false
    }

    fun onArrivalHint(h: ArrivalHint?) {
        if (h == null) return
        // 응답이 늦게 와서 이미 지나간 역/다른 열차에 대한 힌트면 버린다
        val target = hintTarget() ?: return
        if (h.trainNo == target.first && h.stationIdx == target.second) hint = h
    }

    /**
     * 도착정보 API 로 조회할 (열차번호, 역).
     * 대기 중: 다가오는 열차가 출발역에 언제 오는지 / 탑승 중: 도착역에 언제 오는지.
     */
    fun hintTarget(): Pair<String, Int>? = when (phase) {
        Phase.WAITING -> candidateNo?.let { it to originIdx }
        Phase.RIDING -> trainNo?.let { it to destIdx }
        Phase.ARRIVED -> null
    }

    /** 열차 p 의 "다음 정차역"과 그 역까지 남은 초 (시간표 기반). */
    private fun nextStop(p: TrainPos, now: Long): Pair<Int, Int> {
        val elapsed = ((now - p.recordedAt) / 1000).toInt().coerceAtLeast(0)
        val i = p.stationIdx
        return when (p.status) {
            0 -> i to (DWELL_APPROACH - elapsed)
            1 -> (i + dir) to (DWELL + Line4.segmentSec(i, i + dir) - elapsed)
            3 -> i to (Line4.segmentSec(i - dir, i) - elapsed)
            else -> (i + dir) to (Line4.segmentSec(i, i + dir) - elapsed)
        }
    }

    /** 열차 p 가 target 역에 도착하기까지 남은 초 (이미 지났거나 정차 중이면 0). */
    private fun etaTo(p: TrainPos, target: Int, now: Long): Int {
        if (p.stationIdx == target && p.status == 1) return 0
        val (next, firstEta) = nextStop(p, now)
        if ((target - next) * dir < 0) return 0
        var total = firstEta.coerceAtLeast(0)
        var k = next
        while (k != target) {
            total += DWELL + Line4.segmentSec(k, k + dir)
            k += dir
        }
        return total
    }

    private fun hintEta(stationIdx: Int, no: String?, now: Long): Int? =
        hint?.takeIf { it.trainNo == no && it.stationIdx == stationIdx && now - it.arriveAt < 60_000 }
            ?.let { ((it.arriveAt - now) / 1000).toInt().coerceAtLeast(0) }

    /**
     * API 데이터가 멈춰 있을 때(실제로 과천선 구간에서 몇 분씩 갱신이 안 되는 경우가 있음)
     * 마지막 상태에서 시간표대로 열차를 앞으로 굴려 현재 위치를 추정한다.
     *  - 마지막 상태가 정상 소요시간 x1.5 + 20초 이상 그대로면 "멈춘 데이터"로 본다.
     *  - 그때는 예정대로 진행했다고 보고 진입→정차→출발→다음역 순으로 이어 붙인다.
     *  - API 가 새 상태를 보내면 그 값을 그대로 다시 쓴다.
     */
    internal fun extrapolate(p: TrainPos, now: Long): TrainPos {
        val d = if (p.updn == 1) 1 else -1
        fun durSec(st: Int, s: Int): Int = when (s) {
            0 -> DWELL_APPROACH
            1 -> DWELL
            2 -> Line4.segmentSec(st, st + d)
            3 -> Line4.segmentSec(st - d, st)
            else -> DWELL
        }
        val grace = (durSec(p.stationIdx, p.status) * 1.5 + 20).toLong() * 1000
        if (now - p.recordedAt < grace) return p

        var st = p.stationIdx
        var s = p.status
        var t = p.recordedAt
        repeat(200) {
            val endOfLine = st == p.terminalIdx || st + d !in Line4.stations.indices
            if (s == 1 && endOfLine) return@repeat // 종착역에서 정차
            val dur = durSec(st, s) * 1000L
            if (now < t + dur) return p.copy(stationIdx = st, status = s, recordedAt = t, estimated = true)
            t += dur
            when (s) {
                0, 3 -> s = 1
                1 -> s = 2
                else -> { st += d; s = 1 }
            }
        }
        return p.copy(stationIdx = st, status = s, recordedAt = t, estimated = true)
    }

    private data class Approach(
        val stopsBefore: Int, val progress: Float?, val atTarget: Boolean, val eta: Int, val next: Int,
    )

    /**
     * 열차 p 가 target 역에 대해 어디쯤인지.
     *  - target 정차 중            → atTarget
     *  - target 이 바로 다음 역 (전역 정차 중 포함) → stopsBefore=0, progress=0..1
     *  - 그 외                      → stopsBefore=n ("n역 전")
     */
    private fun approach(p: TrainPos, target: Int, now: Long): Approach {
        val (next, firstEta) = nextStop(p, now)
        if (p.stationIdx == target && p.status == 1) return Approach(0, 1f, true, 0, next)
        val hinted = hintEta(target, p.trainNo, now)
        if (next == target) {
            // 목표 역이 바로 다음 역. 전체 = 전역 정차 + 주행 → 정차 중 0% 에서 시작해
            // 출발 후에도 되돌아가지 않고 이어서 차오른다.
            val seg = DWELL + Line4.segmentSec(target - dir, target)
            val remain = (hinted ?: firstEta).coerceAtLeast(0)
            val total = maxOf(seg.toFloat(), remain.toFloat())
            return Approach(0, (1f - remain / total).coerceIn(0f, 1f), false, remain, next)
        }
        val stops = if (p.status == 1) (target - p.stationIdx) * dir else (target - next) * dir + 1
        return Approach(stops.coerceAtLeast(0), null, false, hinted ?: etaTo(p, target, now), next)
    }

    fun view(now: Long): RideView = when (phase) {
        Phase.WAITING -> {
            val c = latest.firstOrNull { it.trainNo == candidateNo }
            if (c == null) RideView(Phase.WAITING, null, originIdx, null, null, false, null, null, null, false)
            else viewFor(Phase.WAITING, c, originIdx, now, stale = false).copy(estimated = c.estimated)
        }
        Phase.RIDING -> {
            val p = lastPos
            if (p == null) RideView(Phase.RIDING, trainNo, destIdx, null, null, false, null, null, null, true)
            else viewFor(Phase.RIDING, p, destIdx, now, stale = now - lastSeenAt > 180_000)
                .copy(estimated = p.estimated)
        }
        Phase.ARRIVED -> RideView(Phase.ARRIVED, trainNo, destIdx, 0, 1f, true, 0, destIdx, null, false)
    }

    private fun viewFor(phase: Phase, p: TrainPos, target: Int, now: Long, stale: Boolean): RideView {
        val a = approach(p, target, now)
        return RideView(
            phase = phase,
            trainNo = p.trainNo,
            targetIdx = target,
            stopsBefore = a.stopsBefore,
            progress = a.progress,
            atTarget = a.atTarget,
            etaSec = a.eta,
            nextIdx = a.next.coerceIn(0, Line4.stations.lastIndex),
            shortTerminalIdx = shortTerminal(p),
            stale = stale,
        )
    }

    private fun shortTerminal(p: TrainPos): Int? {
        val t = p.terminalIdx ?: return null
        return if (t * dir < destU) t else null
    }
}
