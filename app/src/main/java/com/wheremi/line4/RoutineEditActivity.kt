package com.wheremi.line4

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.UUID

/**
 * 루틴 추가/편집 (iOS 모달 스타일)
 *
 *   취소          루틴          저장
 *   ┌ 이름: 출근 ──────────────┐
 *   경로   출발 / 도착
 *   언제   (월)(화)(수)(목)(금)(토)(일) · 시작 07:30 · 종료 09:00
 *   어디서 위치 [현재 위치로 설정] · 반경 300m
 *   자동 실행 ◉
 */
class RoutineEditActivity : AppCompatActivity() {

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private lateinit var r: Routine
    private var isNew = true
    private var nameField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val prefs = Prefs(this)
        val existing = intent.getStringExtra("id")?.let { RoutineStore(this).get(it) }
        isNew = existing == null
        r = existing ?: Routine(
            id = UUID.randomUUID().toString(), name = "",
            originIdx = prefs.originIdx, destIdx = prefs.destIdx,
        ).let { it.atStation(it.originIdx) } // 기본 위치 = 출발역 근처

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.decorView.setBackgroundColor(ui.bg)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night

        content = ui.vstack {
            setPadding(ui.dp(20f), ui.dp(8f), ui.dp(20f), ui.dp(40f))
            // 열자마자 이름 칸에 포커스(키보드)가 가지 않도록 바탕이 포커스를 가져간다
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        setContentView(ui.vstack {
            fitsSystemWindows = true
            setBackgroundColor(ui.bg)
            addView(topBar())
            addView(ScrollView(this@RoutineEditActivity).apply { addView(content) }, ui.lp(h = 0, weight = 1f))
        })
        render()
        content.requestFocus()
    }

    private fun topBar() = ui.hstack {
        setPadding(ui.dp(12f), ui.dp(10f), ui.dp(12f), ui.dp(10f))
        addView(ui.text("취소", 17f, ui.accent).apply {
            setPadding(ui.dp(8f), ui.dp(8f), ui.dp(8f), ui.dp(8f))
            setOnClickListener { finish() }
        })
        addView(ui.text(if (isNew) "새 루틴" else "루틴", 17f, bold = true).apply { gravity = Gravity.CENTER },
            ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        addView(ui.text("저장", 17f, ui.accent, bold = true).apply {
            setPadding(ui.dp(8f), ui.dp(8f), ui.dp(8f), ui.dp(8f))
            setOnClickListener { save() }
        })
    }

    private fun render() {
        r = r.copy(name = nameField?.text?.toString() ?: r.name)
        content.removeAllViews()

        // 이름
        nameField = EditText(this).apply {
            setText(r.name)
            hint = "이름 (예: 출근, 퇴근)"
            setTextColor(ui.label)
            setHintTextColor(ui.label2)
            textSize = 17f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setPadding(ui.dp(16f), ui.dp(14f), ui.dp(16f), ui.dp(14f))
        }
        content.addView(ui.card { addView(nameField) }, ui.lp(top = 12f))

        // 경로
        content.addView(ui.header("경로"), ui.lp(top = 28f))
        content.addView(ui.card {
            addView(ui.row("출발", Line4.stations[r.originIdx]) {
                ui.pickStation(this@RoutineEditActivity, "출발역", r.originIdx) { r = r.withOrigin(it); render() }
            }.first)
            addView(ui.separator())
            addView(ui.row("도착", Line4.stations[r.destIdx]) {
                ui.pickStation(this@RoutineEditActivity, "도착역", r.destIdx) { r = r.copy(destIdx = it); render() }
            }.first)
        })

        // 언제
        content.addView(ui.header("언제"), ui.lp(top = 28f))
        content.addView(ui.card {
            addView(dayChips())
            addView(ui.separator())
            addView(ui.row("시작", Routine.hm(r.startMin)) { pickTime(r.startMin) { r = r.copy(startMin = it); render() } }.first)
            addView(ui.separator())
            addView(ui.row("종료", Routine.hm(r.endMin)) { pickTime(r.endMin) { r = r.copy(endMin = it); render() } }.first)
        })

        // 어디서
        content.addView(ui.header("어디서"), ui.lp(top = 28f))
        content.addView(ui.card {
            addView(ui.row("위치", r.placeLabel(), valueColor = if (r.hasLocation) ui.label2 else ui.accent) {
                pickPlace()
            }.first)
            addView(ui.separator())
            addView(ui.row("반경", "${r.radiusM}m") {
                val options = intArrayOf(200, 300, 500, 800)
                AlertDialog.Builder(this@RoutineEditActivity)
                    .setTitle("반경")
                    .setSingleChoiceItems(options.map { "${it}m" }.toTypedArray(), options.indexOf(r.radiusM)) { d, w ->
                        r = r.copy(radiusM = options[w]); d.dismiss(); render()
                    }
                    .show()
            }.first)
        })
        content.addView(
            ui.text("출발역 근처로 두면 역 좌표를 써서, 지금 그 역에 없어도 설정할 수 있어요.", 13f, ui.label2)
                .apply { setPadding(ui.dp(16f), 0, ui.dp(16f), 0) },
            ui.lp(top = 8f),
        )

        // 자동 실행
        content.addView(ui.card {
            addView(ui.switchRow("자동 실행", r.enabled) { r = r.copy(enabled = it) })
        }, ui.lp(top = 28f))
        content.addView(
            ui.text("요일·시간대가 맞고 이 위치 근처에 들어가면 안내가 자동으로 시작돼요.", 13f, ui.label2)
                .apply { setPadding(ui.dp(16f), 0, ui.dp(16f), 0); setLineSpacing(0f, 1.25f) },
            ui.lp(top = 8f),
        )

        if (!isNew) {
            content.addView(ui.card {
                addView(ui.row("루틴 삭제", chevron = false) {
                    RoutineStore(this@RoutineEditActivity).delete(r.id)
                    RoutineScheduler.sync(this@RoutineEditActivity)
                    finish()
                }.first.also { row -> ((row as LinearLayout).getChildAt(0) as TextView).setTextColor(0xFFFF3B30.toInt()) })
            }, ui.lp(top = 36f))
        }
    }

    /** 월~일 동그란 스쿼클 토글 */
    private fun dayChips() = ui.hstack {
        setPadding(ui.dp(12f), ui.dp(12f), ui.dp(12f), ui.dp(12f))
        "월화수목금토일".forEachIndexed { i, ch ->
            val day = i + 1
            val on = day in r.days
            addView(ui.text(ch.toString(), 15f, if (on) 0xFFFFFFFF.toInt() else ui.label, bold = on).apply {
                gravity = Gravity.CENTER
                background = SquircleDrawable(if (on) ui.accent else ui.color(R.color.fill), radiusFraction = 0.5f, n = 3f)
                setOnClickListener {
                    r = r.copy(days = if (on) r.days - day else r.days + day)
                    render()
                }
            }, LinearLayout.LayoutParams(0, ui.dp(38f), 1f).apply {
                marginStart = ui.dp(3f); marginEnd = ui.dp(3f)
            })
        }
    }

    private fun pickTime(min: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPick(h * 60 + m) }, min / 60, min % 60, true).show()
    }

    // ------------------------------------------------------------------ 위치

    /** 위치 정하기: 출발역 근처(역 좌표) / 다른 역 근처 / 현재 위치 */
    private fun pickPlace() {
        val origin = Line4.shortName(r.originIdx)
        val items = arrayOf("${origin}역 근처 (출발역)", "다른 역 근처…", "현재 위치로 설정")
        AlertDialog.Builder(this)
            .setTitle("위치")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { r = r.atStation(r.originIdx); render() }
                    1 -> ui.pickStation(this, "어느 역 근처?", r.placeStationIdx ?: r.originIdx) {
                        r = r.atStation(it); render()
                    }
                    2 -> captureLocation()
                }
            }
            .show()
    }

    private fun captureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_FINE,
            )
            return
        }
        fetchLocation()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        Toast.makeText(this, "현재 위치 확인 중…", Toast.LENGTH_SHORT).show()
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    Toast.makeText(this, "위치를 가져오지 못했어요. 잠시 후 다시 시도해 주세요", Toast.LENGTH_LONG).show()
                } else {
                    r = r.copy(lat = loc.latitude, lng = loc.longitude, placeStationIdx = null)
                    Toast.makeText(this, "위치를 저장했어요 (오차 약 ${loc.accuracy.toInt()}m)", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "위치 오류: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FINE -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) fetchLocation()
            REQ_BG -> finishSave()
        }
    }

    // ------------------------------------------------------------------ 저장

    private fun save() {
        r = r.copy(name = nameField?.text?.toString()?.trim().orEmpty())
        if (r.originIdx == r.destIdx) {
            Toast.makeText(this, "출발역과 도착역이 같아요", Toast.LENGTH_SHORT).show(); return
        }
        if (r.days.isEmpty()) {
            Toast.makeText(this, "요일을 하나 이상 골라 주세요", Toast.LENGTH_SHORT).show(); return
        }
        RoutineStore(this).save(r)

        // 자동 실행에는 "항상 허용" 위치 권한이 필요
        val needBg = r.enabled && r.hasLocation && Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        if (needBg) {
            AlertDialog.Builder(this)
                .setTitle("위치 ‘항상 허용’이 필요해요")
                .setMessage("앱을 열지 않아도 루틴 위치에 들어가면 안내를 시작하려면, 다음 화면에서 위치 권한을 ‘항상 허용’으로 바꿔 주세요.")
                .setPositiveButton("설정") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
                }
                .setNegativeButton("나중에") { _, _ -> finishSave() }
                .show()
        } else {
            finishSave()
        }
    }

    private fun finishSave() {
        RoutineScheduler.sync(this)
        finish()
    }

    companion object {
        private const val REQ_FINE = 21
        private const val REQ_BG = 22
    }
}
