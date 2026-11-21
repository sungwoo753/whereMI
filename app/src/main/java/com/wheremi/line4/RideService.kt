package com.wheremi.line4

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 탑승 중 계속 도는 포그라운드 서비스.
 *  - 15초마다 실시간 위치 API 조회
 *  - 1초마다 오버레이의 남은 시간 갱신
 *  - 진행 중 알림(상태바 좌측 아이콘에 남은 분 표시, Android 16 에선 상태바 "실시간 정보" 칩)
 */
class RideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var tickJob: Job? = null

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private var tracker: TrainTracker? = null
    private var api: SubwayApi? = null
    private var lastError: String? = null
    private var lastNotifKey: String? = null
    private var alertedNextStop = false
    private var alertedArrival = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = OverlayController(this, prefs) { onBoardConfirmed() }
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val o = intent.getIntExtra(EXTRA_ORIGIN, -1)
                val d = intent.getIntExtra(EXTRA_DEST, -1)
                startForegroundCompat()
                if (o < 0 || d < 0 || o == d) { stopRide(); return START_NOT_STICKY }
                startRide(o, d)
            }
            ACTION_SKIP -> { tracker?.skipTrain(); alertedNextStop = false; render(force = true) }
            ACTION_BOARDED -> onBoardConfirmed()
            ACTION_STOP -> stopRide()
            else -> stopRide() // 시스템 재시작 등: 상태가 없으니 종료
        }
        return START_NOT_STICKY
    }

    private fun startRide(origin: Int, dest: Int) {
        tracker = TrainTracker(origin, dest)
        api = SubwayApi(prefs.apiKey)
        lastError = null
        lastNotifKey = null
        alertedNextStop = false
        alertedArrival = false
        running = true
        if (prefs.overlayEnabled) overlay.show()

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                poll()
                val t = tracker
                if (t == null || t.phase == Phase.ARRIVED) break
                delay(nextPollDelay(t))
            }
        }
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                render()
                delay(1_000)
            }
        }
    }

    /** "탑승했어요" (위젯 버튼 또는 알림 버튼): 곧 도착할 열차로 탑승 확정 */
    private fun onBoardConfirmed() {
        val t = tracker ?: return
        if (t.confirmBoarded(System.currentTimeMillis())) {
            alertedNextStop = false
            lastNotifKey = null
            render(force = true)
            // 탑승 직후엔 위치를 바로 한 번 더 확인해 다음 주기를 다시 계산
            pollJob?.cancel()
            pollJob = scope.launch {
                while (isActive) {
                    poll()
                    val tt = tracker
                    if (tt == null || tt.phase == Phase.ARRIVED) break
                    delay(nextPollDelay(tt))
                }
            }
        }
    }

    /** 목표 역이 가까운지 (1역 전 / 마지막 구간 / 열차 정차 중) */
    private fun isNear(v: RideView) = v.trainNo != null && (v.progress != null || v.atTarget || (v.stopsBefore ?: 99) <= 1)

    /**
     * 하루 1,000회 호출 한도를 고려한 적응형 조회 주기.
     *   3역 이상: 30초 / 2역: 15초 / 1역 이내: 8초 / 오류: 60초
     * → 1시간 이동 시 약 230회 (출퇴근 2시간 ≈ 460회)
     */
    private fun nextPollDelay(t: TrainTracker): Long {
        if (lastError != null) return POLL_ERROR_MS
        val v = t.view(System.currentTimeMillis())
        return when {
            v.trainNo == null -> POLL_MID_MS
            isNear(v) -> POLL_NEAR_MS
            (v.stopsBefore ?: 99) <= 2 -> POLL_MID_MS
            else -> POLL_FAR_MS
        }
    }

    private fun countCall() {
        val n = prefs.countApiCall()
        android.util.Log.i("whereMI", "API 호출 오늘 ${n}회")
    }

    private suspend fun poll() {
        val t = tracker ?: return
        val a = api ?: return
        try {
            countCall()
            val positions = withContext(Dispatchers.IO) { a.fetchPositions() }
            t.onPositions(positions, System.currentTimeMillis())
            lastError = null
            // 도착정보 API 로 ETA 보정은 목표 역이 가까울 때만 (호출 수 절약, 실패해도 무시)
            // 대기 중: 다가오는 열차의 출발역 도착 시각 / 탑승 중: 도착역 도착 시각
            t.hintTarget()?.takeIf { isNear(t.view(System.currentTimeMillis())) }?.let { (no, station) ->
                countCall()
                val hint = runCatching {
                    withContext(Dispatchers.IO) { a.fetchArrivalHint(station, no) }
                }.getOrNull()
                t.onArrivalHint(hint)
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
        render(force = true)
    }

    private fun render(force: Boolean = false) {
        val t = tracker ?: return
        val v = t.view(System.currentTimeMillis())
        val pill = if (lastError != null && v.phase != Phase.RIDING) RideText.Pill(Line4.shortName(v.targetIdx), "연결 오류", null) else RideText.pill(v)
        val compact = RideText.chip(v)
        overlay.update(pill)

        // 알림은 내용이 바뀔 때만 갱신 (매초 갱신하면 배터리/깜빡임)
        val title = RideText.title(v)
        val body = lastError?.let { "API 오류: $it" } ?: RideText.body(v, t.destIdx)
        val key = "$title|$body|$compact"
        if (force || key != lastNotifKey) {
            lastNotifKey = key
            nm().notify(NOTIF_ID, buildNotification(v, title, body, compact))
        }

        if (v.phase == Phase.RIDING && !alertedNextStop &&
            (v.progress != null || (v.stopsBefore ?: 9) <= 1)
        ) {
            alertedNextStop = true
            alert("이번 역 ${Line4.shortName(t.destIdx)}에서 내리세요", "곧 도착합니다")
        }
        // 도착하면 알림/오버레이를 모두 바로 닫는다
        if (v.phase == Phase.ARRIVED && !alertedArrival) {
            alertedArrival = true
            nm().cancel(ALERT_ID)
            stopRide()
        }
    }

    private fun buildNotification(v: RideView?, title: String, body: String, compact: String) =
        NotificationCompat.Builder(this, CH_RIDE)
            .setSmallIcon(statusIcon(v))
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("4호선")
            .setColor(Line4.COLOR)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(activityIntent())
            .apply {
                v?.progress?.let { setProgress(100, (it * 100).toInt(), false) }
                if (v?.phase == Phase.WAITING) {
                    addAction(0, "탑승했어요", serviceIntent(ACTION_BOARDED, 1))
                }
                if (v?.phase == Phase.WAITING || v?.phase == Phase.RIDING) {
                    addAction(0, "다른 열차", serviceIntent(ACTION_SKIP, 2))
                }
                addAction(0, "종료", serviceIntent(ACTION_STOP, 3))
                // Android 16+ "실시간 정보(Live Update)": 상태바 좌측에 짧은 칩으로 표시 요청.
                // 문자열 키로 넣어서 구버전 SDK 로도 빌드되며, 미지원 기기에서는 무시된다.
                addExtras(android.os.Bundle().apply {
                    putBoolean("android.requestPromotedOngoing", true)
                    putString("android.shortCriticalText", compact)
                })
            }
            .build()

    /** 상태바(시계 옆)와 실시간 정보 칩에 쓰이는 아이콘: 진행 링 + 열차 */
    @Suppress("UNUSED_PARAMETER")
    private fun statusIcon(v: RideView?): IconCompat = IconCompat.createWithResource(this, R.drawable.ic_stat_ride)

    private fun alert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(Line4.COLOR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setContentIntent(activityIntent())
            .build()
        nm().notify(ALERT_ID, n)
    }

    private fun startForegroundCompat() {
        val n = buildNotification(null, "4호선 안내 준비 중", "열차 정보를 불러오는 중", "4호선")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun stopRide() {
        pollJob?.cancel(); tickJob?.cancel()
        tracker = null
        running = false
        overlay.hide()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 회전/폴드 화면 전환 시 렌즈홀 위치가 바뀌므로 다시 배치
        if (running && prefs.overlayEnabled) { overlay.hide(); overlay.show(); lastNotifKey = null; render(force = true) }
    }

    override fun onDestroy() {
        running = false
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 상태바 아이콘 우선순위를 높이기 위해 DEFAULT 중요도 + 무음/무진동
        nm().deleteNotificationChannel("ride")
        nm().createNotificationChannel(
            NotificationChannel(CH_RIDE, "탑승 안내 (진행 중)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "남은 역과 도착 진행 상황을 조용히 표시"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm().createNotificationChannel(
            NotificationChannel(CH_ALERT, "하차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "도착역 직전/도착 시 진동 알림"
                enableVibration(true)
            }
        )
    }

    private fun activityIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, RideService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "com.wheremi.line4.START"
        const val ACTION_STOP = "com.wheremi.line4.STOP"
        const val ACTION_SKIP = "com.wheremi.line4.SKIP"
        const val ACTION_BOARDED = "com.wheremi.line4.BOARDED"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_DEST = "dest"

        private const val CH_RIDE = "ride_v2"
        private const val CH_ALERT = "alert"
        private const val NOTIF_ID = 4
        private const val ALERT_ID = 44
        private const val POLL_FAR_MS = 30_000L
        private const val POLL_MID_MS = 15_000L
        private const val POLL_NEAR_MS = 8_000L
        private const val POLL_ERROR_MS = 60_000L

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context, origin: Int, dest: Int) {
            val i = Intent(context, RideService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ORIGIN, origin)
                .putExtra(EXTRA_DEST, dest)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            if (!running) return
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
