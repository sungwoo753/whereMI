package com.wheremi.line4

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.time.ZonedDateTime

/**
 * 루틴 자동 실행.
 *
 *  - 위치가 설정된 루틴마다 지오펜스(반경 r m)를 등록한다. (배터리 거의 안 씀)
 *  - 지오펜스에 들어가면(ENTER) / 1분 머물면(DWELL) GeofenceReceiver 가 깨어나
 *    요일·시간대가 맞고 오늘 아직 실행 안 했으면 안내를 시작한다.
 *  - 시간대가 시작될 때 "이미 그 근처에 있는" 경우를 위해, 시간대 시작 시각에 알람을 걸어
 *    지오펜스를 INITIAL_TRIGGER_ENTER 로 다시 등록한다 → 안에 있으면 즉시 ENTER 가 온다.
 *  - 재부팅/앱 업데이트 후에도 다시 등록한다.
 */
object RoutineScheduler {
    private const val TAG = "whereMI"

    fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    /** 루틴이 바뀌었을 때 / 부팅 후 / 알람 때 호출 */
    fun sync(ctx: Context, initialEnter: Boolean = false) {
        registerGeofences(ctx, initialEnter)
        scheduleNextWindow(ctx)
    }

    private fun geofencePending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 10, Intent(ctx, GeofenceReceiver::class.java),
        // Geofencing 이 이벤트를 extras 로 채워 넣으므로 MUTABLE 이어야 한다
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    @SuppressLint("MissingPermission")
    private fun registerGeofences(ctx: Context, initialEnter: Boolean) {
        val client = LocationServices.getGeofencingClient(ctx)
        val pending = geofencePending(ctx)
        client.removeGeofences(pending)
        val fences = RoutineStore(ctx).all().filter { it.enabled && it.hasLocation }.map {
            Geofence.Builder()
                .setRequestId(it.id)
                .setCircularRegion(it.lat!!, it.lng!!, it.radiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(60_000)
                .build()
        }
        if (fences.isEmpty() || !hasLocationPermission(ctx)) return
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(if (initialEnter) GeofencingRequest.INITIAL_TRIGGER_ENTER else 0)
            .addGeofences(fences)
            .build()
        try {
            client.addGeofences(req, pending)
                .addOnSuccessListener { Log.i(TAG, "지오펜스 ${fences.size}개 등록 (initial=$initialEnter)") }
                .addOnFailureListener { Log.w(TAG, "지오펜스 등록 실패: ${it.message}") }
        } catch (e: SecurityException) {
            Log.w(TAG, "위치 권한 없음: ${e.message}")
        }
    }

    /** 가장 가까운 루틴 시간대 시작 시각에 알람 (정확할 필요 없어 inexact window, 별도 권한 불필요) */
    private fun scheduleNextWindow(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            ctx, 11, Intent(ctx, RoutineAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pending)
        val now = ZonedDateTime.now(RoutineStore.KST)
        val next = RoutineStore(ctx).all()
            .filter { it.enabled && it.hasLocation }
            .mapNotNull { it.nextStart(now) }
            .minOrNull() ?: return
        am.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), 60_000, pending)
        Log.i(TAG, "다음 루틴 시간대 확인: $next")
    }

    /** 지오펜스 이벤트로 깨어났을 때: 조건이 맞는 루틴이면 안내 시작 */
    fun onGeofence(ctx: Context, routineIds: List<String>) {
        val store = RoutineStore(ctx)
        val now = ZonedDateTime.now(RoutineStore.KST)
        for (id in routineIds) {
            val r = store.get(id) ?: continue
            if (!r.enabled || !r.isActiveAt(now) || store.firedToday(id) || RideService.running) continue
            Log.i(TAG, "루틴 '${r.name}' 자동 실행")
            store.markFired(id)
            RideService.start(ctx, r.originIdx, r.destIdx)
            break
        }
    }
}

/** 지오펜스 진입/체류 → 루틴 확인. (지오펜스 이벤트는 백그라운드 포그라운드서비스 시작이 허용되는 예외) */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val t = event.geofenceTransition
        if (t != Geofence.GEOFENCE_TRANSITION_ENTER && t != Geofence.GEOFENCE_TRANSITION_DWELL) return
        RoutineScheduler.onGeofence(context, event.triggeringGeofences.orEmpty().map { it.requestId })
    }
}

/** 루틴 시간대 시작 시각: 이미 근처에 있으면 바로 ENTER 가 오도록 지오펜스 재등록 */
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RoutineScheduler.sync(context, initialEnter = true)
    }
}

/** 재부팅 / 앱 업데이트 후 지오펜스·알람 복구 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RoutineScheduler.sync(context)
    }
}
