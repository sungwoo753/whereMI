package com.wheremi.line4

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * 메인 화면 (iOS 설정 앱 스타일)
 *
 *   whereMI
 *   4호선 탑승 안내
 *
 *   지금 출발
 *   ┌───────────────────────┐
 *   │ 출발              사당 › │
 *   │ 도착              혜화 › │
 *   └───────────────────────┘
 *                    ⇅ 바꾸기
 *   [        안내 시작        ]
 *
 *   루틴
 *   ┌───────────────────────┐
 *   │ 출근            ▶  ◉  │
 *   │ 사당 → 혜화             │
 *   │ 평일 07:30–09:00 · 위치 │
 *   └───────────────────────┘
 *   ┌ + 루틴 추가 ───────────┐
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.decorView.setBackgroundColor(ui.bg)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night

        content = ui.vstack { setPadding(ui.dp(20f), ui.dp(28f), ui.dp(20f), ui.dp(40f)) }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(ui.bg)
            addView(content)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleRideIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRideIntent(intent)
    }

    // ------------------------------------------------------------------ 화면 그리기

    private fun render() {
        content.removeAllViews()

        // 큰 제목
        content.addView(ui.text("whereMI", 34f, bold = true))
        content.addView(ui.text("4호선 탑승 안내", 15f, ui.label2), ui.lp(top = 6f))

        // 지금 출발
        content.addView(ui.header("지금 출발"), ui.lp(top = 32f))
        content.addView(ui.card {
            addView(ui.row("출발", Line4.stations[prefs.originIdx]) {
                ui.pickStation(this@MainActivity, "출발역", prefs.originIdx) { prefs.originIdx = it; render() }
            }.first)
            addView(ui.separator())
            addView(ui.row("도착", Line4.stations[prefs.destIdx]) {
                ui.pickStation(this@MainActivity, "도착역", prefs.destIdx) { prefs.destIdx = it; render() }
            }.first)
        })
        content.addView(ui.text("⇅  바꾸기", 14f, ui.accent).apply {
            gravity = Gravity.END
            setPadding(ui.dp(8f), ui.dp(10f), ui.dp(8f), ui.dp(4f))
            setOnClickListener {
                val o = prefs.originIdx
                prefs.originIdx = prefs.destIdx
                prefs.destIdx = o
                render()
            }
        })
        val running = RideService.running
        content.addView(
            if (running) ui.primaryButton("안내 종료", fill = ui.color(R.color.fill), textColor = ui.label) {
                RideService.stop(this); content.postDelayed({ render() }, 300)
            } else ui.primaryButton("안내 시작") { startRide(prefs.originIdx, prefs.destIdx) },
            ui.lp(top = 8f),
        )

        // 루틴
        content.addView(ui.header("루틴"), ui.lp(top = 36f))
        val routines = RoutineStore(this).all()
        routines.forEach { r -> content.addView(routineCard(r), ui.lp(bottom = 10f)) }
        content.addView(ui.card {
            addView(ui.row("+  루틴 추가", chevron = false) {
                startActivity(Intent(this@MainActivity, RoutineEditActivity::class.java))
            }.first.also { row -> ((row as LinearLayout).getChildAt(0) as TextView).setTextColor(ui.accent) })
        })
        content.addView(
            ui.text(
                "루틴은 정해 둔 요일·시간대에 저장한 위치 근처에 들어가면 안내를 자동으로 시작해요. " +
                    "하루에 한 번만 실행돼요.",
                13f, ui.label2,
            ).apply { setPadding(ui.dp(16f), 0, ui.dp(16f), 0); setLineSpacing(0f, 1.25f) },
            ui.lp(top = 10f),
        )
    }

    private fun routineCard(r: Routine) = ui.card {
        addView(ui.hstack {
            setPadding(ui.dp(16f), ui.dp(14f), ui.dp(12f), ui.dp(14f))
            background = ui.ripple()
            setOnClickListener {
                startActivity(Intent(this@MainActivity, RoutineEditActivity::class.java).putExtra("id", r.id))
            }
            addView(ui.vstack {
                addView(ui.text(r.name.ifBlank { "루틴" }, 17f, bold = true))
                addView(
                    ui.text("${Line4.shortName(r.originIdx)} → ${Line4.shortName(r.destIdx)}", 15f),
                    ui.lp(top = 6f),
                )
                val where = if (r.hasLocation) "${r.placeLabel()} ${r.radiusM}m" else "위치 미설정"
                addView(ui.text("${r.daysLabel()} ${r.timeLabel()} · $where", 13f, ui.label2), ui.lp(top = 6f))
            }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
            // 바로 시작
            addView(ui.text("▶", 13f, 0xFFFFFFFF.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                background = SquircleDrawable(ui.accent, radiusFraction = 0.5f, n = 3f)
                setOnClickListener { startRide(r.originIdx, r.destIdx) }
            }, LinearLayout.LayoutParams(ui.dp(34f), ui.dp(34f)).apply { marginEnd = ui.dp(12f) })
            // 자동 실행
            addView(ui.switch(r.enabled) { on ->
                RoutineStore(this@MainActivity).save(r.copy(enabled = on))
                RoutineScheduler.sync(this@MainActivity)
            })
        })
    }

    // ------------------------------------------------------------------ 안내 시작

    /**
     * ACTION_RIDE + origin/dest 인덱스로 열리면 화면 조작 없이 바로 안내 시작.
     * 예) adb shell am start -n com.wheremi.line4/.MainActivity -a com.wheremi.line4.RIDE --ei origin 27 --ei dest 14
     */
    private fun handleRideIntent(i: Intent?) {
        if (i?.action != ACTION_RIDE) return
        val o = i.getIntExtra(RideService.EXTRA_ORIGIN, -1)
        val d = i.getIntExtra(RideService.EXTRA_DEST, -1)
        if (o !in Line4.stations.indices || d !in Line4.stations.indices) return
        startRide(o, d)
    }

    private fun startRide(o: Int, d: Int) {
        if (o == d) {
            Toast.makeText(this, "출발역과 도착역이 같아요", Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.apiKey.isBlank()) {
            Toast.makeText(this, "local.properties 에 SEOUL_API_KEY 를 넣고 다시 빌드하세요", Toast.LENGTH_LONG).show()
            return
        }
        prefs.originIdx = o
        prefs.destIdx = d
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "‘다른 앱 위에 표시’를 허용해 주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        RideService.start(this, o, d)
        moveTaskToBack(true)
    }

    companion object {
        const val ACTION_RIDE = "com.wheremi.line4.RIDE"
    }
}
