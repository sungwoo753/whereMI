package com.wheremi.line4

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 자주 가는 경로 + 자동 실행 조건.
 *  "평일 18:00~19:30 사이에 회사(사당역) 근처 300m 안에 들어가면 사당→혜화 안내 시작"
 */
data class Routine(
    val id: String,
    val name: String,
    val originIdx: Int,
    val destIdx: Int,
    /** 1=월 … 7=일 */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** 하루 중 분 (0..1439). end < start 면 자정을 넘는 시간대 */
    val startMin: Int = 7 * 60 + 30,
    val endMin: Int = 9 * 60,
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Int = 300,
    /** 위치 출처: 역 인덕스(역 좌표 사용) 또는 null(현재 위치로 직접 설정) */
    val placeStationIdx: Int? = null,
    /** 위치·시간 조건으로 자동 실행 */
    val enabled: Boolean = true,
) {
    val hasLocation get() = lat != null && lng != null

    /** 역 좌표를 위치로 사용 */
    fun atStation(idx: Int): Routine {
        val (la, ln) = Line4.coords[idx]
        return copy(lat = la, lng = ln, placeStationIdx = idx)
    }

    /** 출발역을 바꿀 때: 위치가 "출발역 위치"였다면 새 출발역으로 따라간다 */
    fun withOrigin(idx: Int): Routine {
        val moved = copy(originIdx = idx)
        return if (placeStationIdx != null && placeStationIdx == originIdx) moved.atStation(idx) else moved
    }

    fun placeLabel(): String = when {
        !hasLocation -> "설정 안 됨"
        placeStationIdx != null -> "${Line4.shortName(placeStationIdx)}역 근처"
        else -> "저장한 위치"
    }

    /** now 가 이 루틴의 요일·시간대 안인지 */
    fun isActiveAt(now: ZonedDateTime): Boolean {
        val m = now.hour * 60 + now.minute
        return if (startMin <= endMin) {
            now.dayOfWeek.value in days && m in startMin until endMin
        } else {
            // 자정을 넘는 시간대: 시작일 기준 요일
            (m >= startMin && now.dayOfWeek.value in days) ||
                (m < endMin && now.minusDays(1).dayOfWeek.value in days)
        }
    }

    /** now 이후 가장 가까운 시간대 시작 시각 (요일이 비어 있으면 null) */
    fun nextStart(now: ZonedDateTime): ZonedDateTime? {
        if (days.isEmpty()) return null
        for (d in 0..7) {
            val day = now.toLocalDate().plusDays(d.toLong())
            if (day.dayOfWeek.value !in days) continue
            val t = day.atStartOfDay(now.zone).plusMinutes(startMin.toLong())
            if (t.isAfter(now)) return t
        }
        return null
    }

    fun daysLabel(): String = when (days) {
        setOf(1, 2, 3, 4, 5) -> "평일"
        setOf(6, 7) -> "주말"
        (1..7).toSet() -> "매일"
        else -> days.sorted().joinToString("") { "월화수목금토일"[it - 1].toString() }
    }

    fun timeLabel(): String = "${hm(startMin)}–${hm(endMin)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("o", originIdx); put("d", destIdx)
        put("days", JSONArray(days.sorted())); put("s", startMin); put("e", endMin)
        lat?.let { put("lat", it) }; lng?.let { put("lng", it) }
        put("r", radiusM); put("on", enabled)
        placeStationIdx?.let { put("ps", it) }
    }

    companion object {
        fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

        fun fromJson(o: JSONObject): Routine {
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
            return Routine(
                id = o.getString("id"), name = o.optString("name"),
                originIdx = o.getInt("o"), destIdx = o.getInt("d"), days = days,
                startMin = o.optInt("s"), endMin = o.optInt("e"),
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lng = if (o.has("lng")) o.getDouble("lng") else null,
                radiusM = o.optInt("r", 300), enabled = o.optBoolean("on", true),
                placeStationIdx = if (o.has("ps")) o.getInt("ps") else null,
            )
        }
    }
}

/** 루틴 저장소 (SharedPreferences 에 JSON) */
class RoutineStore(context: Context) {
    private val sp = context.getSharedPreferences("routines", Context.MODE_PRIVATE)

    fun all(): List<Routine> = runCatching {
        val a = JSONArray(sp.getString("list", "[]"))
        (0 until a.length()).map { Routine.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun get(id: String): Routine? = all().firstOrNull { it.id == id }

    fun save(r: Routine) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list += r
        write(list)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(list: List<Routine>) {
        sp.edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    /** 같은 날 두 번 자동 실행되지 않도록 */
    fun firedToday(id: String): Boolean = sp.getString("fired_$id", null) == today()
    fun markFired(id: String) = sp.edit().putString("fired_$id", today()).apply()

    private fun today() = LocalDate.now(KST).toString()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
