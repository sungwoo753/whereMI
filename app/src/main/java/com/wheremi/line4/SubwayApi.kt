package com.wheremi.line4

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 실시간 열차 위치 한 건. */
data class TrainPos(
    val trainNo: String,
    val stationIdx: Int,
    /** 0: 진입, 1: 도착, 2: 출발, 3: 전역출발 */
    val status: Int,
    /** 0: 상행(진접 방향), 1: 하행(오이도 방향) */
    val updn: Int,
    /** 이 상태가 기록된 시각 (epoch ms) */
    val recordedAt: Long,
    /** 종착역 인덱스 (알 수 없으면 null) */
    val terminalIdx: Int?,
    /** API 가 멈춰 있어 시간표로 추정한 위치인지 */
    val estimated: Boolean = false,
)

/** 도착정보 API 에서 얻은 "이 열차가 이 역에 언제 도착하는지" 힌트. */
data class ArrivalHint(val trainNo: String, val stationIdx: Int, val arriveAt: Long)

class ApiException(message: String) : Exception(message)

/**
 * 서울 열린데이터광장 실시간 지하철 API (swopenapi.seoul.go.kr).
 * 주의: 이 API 는 HTTPS 를 지원하지 않아 network_security_config 에서 cleartext 허용이 필요.
 */
class SubwayApi(private val apiKey: String) {

    private fun get(path: String): JSONObject {
        val key = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val url = URL("http://swopenapi.seoul.go.kr/api/subway/$key/json/$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun checkError(json: JSONObject) {
        // 정상 응답: errorMessage.code == "INFO-000". 오류 응답은 최상위에 code/message.
        val err = json.optJSONObject("errorMessage") ?: json
        val code = err.optString("code")
        if (code.isNotEmpty() && code != "INFO-000") {
            throw ApiException("$code ${err.optString("message")}")
        }
    }

    /** 4호선 전체 열차 위치. 같은 열차가 여러 번 오면 가장 최근 기록만 남긴다. */
    fun fetchPositions(): List<TrainPos> {
        val line = URLEncoder.encode(Line4.LINE_NAME, "UTF-8")
        val json = get("realtimePosition/0/200/$line")
        checkError(json)
        val arr = json.optJSONArray("realtimePositionList") ?: return emptyList()
        val byTrain = HashMap<String, TrainPos>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pos = parsePosition(o) ?: continue
            val prev = byTrain[pos.trainNo]
            if (prev == null || pos.recordedAt >= prev.recordedAt) byTrain[pos.trainNo] = pos
        }
        return byTrain.values.toList()
    }

    /** 특정 역의 도착 예정 정보에서 trainNo 열차의 도착 예정 시각을 찾는다. */
    fun fetchArrivalHint(stationIdx: Int, trainNo: String): ArrivalHint? {
        val name = URLEncoder.encode(Line4.arrivalApiName(stationIdx), "UTF-8")
        val json = get("realtimeStationArrival/0/30/$name")
        checkError(json)
        val arr = json.optJSONArray("realtimeArrivalList") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("subwayId") != Line4.SUBWAY_ID) continue
            if (o.optString("btrainNo").trimStart('0') != trainNo.trimStart('0')) continue
            val sec = o.optString("barvlDt").toIntOrNull() ?: continue
            if (sec <= 0) return null // 0 이면 정보 없음
            val base = parseTime(o.optString("recptnDt")) ?: System.currentTimeMillis()
            return ArrivalHint(trainNo, stationIdx, base + sec * 1000L)
        }
        return null
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun parseTime(s: String?): Long? = try {
            if (s.isNullOrBlank()) null
            else LocalDateTime.parse(s.substring(0, 19), FMT).atZone(KST).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }

        fun parsePosition(o: JSONObject): TrainPos? {
            if (o.optString("subwayId") != Line4.SUBWAY_ID) return null
            val idx = Line4.indexOf(o.optString("statnId"), o.optString("statnNm")) ?: return null
            val trainNo = o.optString("trainNo").ifBlank { return null }
            return TrainPos(
                trainNo = trainNo,
                stationIdx = idx,
                status = o.optString("trainSttus").toIntOrNull() ?: return null,
                updn = o.optString("updnLine").toIntOrNull() ?: return null,
                recordedAt = parseTime(o.optString("recptnDt")) ?: System.currentTimeMillis(),
                terminalIdx = Line4.indexOf(o.optString("statnTid"), o.optString("statnTnm")),
            )
        }
    }
}
