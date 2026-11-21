package com.wheremi.line4

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wheremi", Context.MODE_PRIVATE)

    /** 인증키는 빌드 시 local.properties 의 SEOUL_API_KEY 에서 들어온다. */
    val apiKey: String get() = BuildConfig.SEOUL_API_KEY

    var originIdx: Int
        get() = sp.getInt("origin", Line4.stations.indexOf("사당"))
        set(v) = sp.edit().putInt("origin", v).apply()

    var destIdx: Int
        get() = sp.getInt("dest", Line4.stations.indexOf("혜화"))
        set(v) = sp.edit().putInt("dest", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var overlayX: Int
        get() = sp.getInt("ox4", -1)
        set(v) = sp.edit().putInt("ox4", v).apply()

    var overlayY: Int
        get() = sp.getInt("oy4", -1)
        set(v) = sp.edit().putInt("oy4", v).apply()

    /** 오늘(KST) API 호출 수를 1 올리고 반환. 하루 1,000회 한도 확인용. */
    fun countApiCall(): Int {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
        val n = if (sp.getString("callDay", null) == today) sp.getInt("callCount", 0) + 1 else 1
        sp.edit().putString("callDay", today).putInt("callCount", n).apply()
        return n
    }
}
