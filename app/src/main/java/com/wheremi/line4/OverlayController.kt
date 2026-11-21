package com.wheremi.line4

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 시계(상태바) 바로 아래 좌측에 떠 있는 작은 알약.
 *
 *   ( 선바위 | 3역 전 )            ← 2역 이상 남음 (배경은 스쿼클 모양)
 *   ( 선바위 | ━━━━━━──── )      ← 도착지가 다음 역 (흐르는 진행 바)
 *
 * 대기 중 탈 열차가 곧 도착하면 배경만 부드럽게 점멸하고, 그때 위젯을 탭하면 "탑승"으로 인식한다.
 *
 * 배경을 호선 색(4호선 하늘색)으로 채우고, 글자는 흰색 기본 글씨로만 표시한다.
 * 상태바·렌즈홀 영역 위로는 올라가지 않으며, 좌우/아래로 드래그해 옮길 수 있다.
 * "다른 앱 위에 표시" 권한이 있으면 유튜브 전체화면 위에도 보인다.
 */
class OverlayController(
    private val context: Context,
    private val prefs: Prefs,
    /** 위젯의 "탑승" 버튼을 눌렀을 때 */
    private val onBoardTapped: () -> Unit,
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private lateinit var mainText: TextView
    private lateinit var subText: TextView
    private lateinit var divider: View
    private lateinit var bar: ApproachBar
    private lateinit var background: SquircleDrawable
    /** 탈 열차가 곧 도착 → 배경 점멸 + 탭하면 탑승 */
    private var boardable = false
    private var pulse: android.animation.ValueAnimator? = null
    private lateinit var params: WindowManager.LayoutParams

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    ).toInt()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** 상태바 + 렌즈홀 중 더 아래쪽까지의 높이 */
    private fun topSafeInset(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        var top = if (id > 0) context.resources.getDimensionPixelSize(id) else dp(28f)
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            top = maxOf(top, insets.top)
        }
        return top
    }

    /** 시계(상태바) 바로 밑. 상태바·렌즈홀과 겹치지 않을 만큼만 띄운다. */
    private fun minY() = topSafeInset() + dp(2f)

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else context.resources.displayMetrics.widthPixels

    /** 위젯 오른쪽 끝이 화면 밖으로 나가지 않도록 x 를 당긴다 (좌우 8dp 여백) */
    private fun clampX(x: Int, width: Int): Int {
        val margin = dp(8f)
        val maxX = screenWidth() - width - margin
        return x.coerceAtMost(maxX).coerceAtLeast(minOf(margin, maxX.coerceAtLeast(0)))
    }

    fun show() {
        if (root != null || !canShow()) return
        val view = buildPill()
        try {
            wm.addView(view, params)
            root = view
        } catch (e: Exception) {
            root = null
        }
    }

    // ---------------------------------------------------------------- 공통 요소

    /** 하늘색 배경 위 흰 글씨가 잘 보이도록 얇은 검정 테두리 느낌의 그림자 */
    private fun TextView.outline() {
        setShadowLayer(dp(1.6f).toFloat(), 0f, dp(0.4f).toFloat(), 0x99000000.toInt())
    }

    private fun buildTexts(startPadDp: Float) {
        mainText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(110f)
            outline()
            setPadding(dp(startPadDp), 0, 0, 0)
        }
        divider = View(context).apply {
            setBackgroundColor(0x66FFFFFF)
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(11f)).apply {
                marginStart = dp(7f); marginEnd = dp(7f)
            }
        }
        subText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            outline()
            includeFontPadding = false
            maxLines = 1
        }
        bar = ApproachBar(context).apply { visibility = View.GONE }
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // ---------------------------------------------------------------- 알약

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPill(): LinearLayout {
        buildTexts(startPadDp = 0f)
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 얇은 스쿼클: 위아래 4dp, 좌우 12dp, 모서리는 애플식 연속 곡률
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            this@OverlayController.background = SquircleDrawable(Line4.COLOR, radiusFraction = 0.5f, n = 3f)
            background = this@OverlayController.background
            elevation = dp(4f).toFloat()
            addView(mainText)
            addView(divider)
            addView(subText)
            addView(bar)
        }
        params = baseParams().apply {
            x = prefs.overlayX.takeIf { it >= 0 } ?: dp(14f)
            y = maxOf(prefs.overlayY, minY())
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = clampX((startX + (e.rawX - downX)).toInt(), view.width)
                    params.y = (startY + (e.rawY - downY)).toInt().coerceAtLeast(minY())
                    root?.let { wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    val moved = kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop
                    if (!moved && boardable) {
                        // 점멸 중 탭 = "탑승했어요"
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        onBoardTapped()
                    } else {
                        prefs.overlayX = params.x; prefs.overlayY = params.y
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    prefs.overlayX = params.x; prefs.overlayY = params.y
                    true
                }
                else -> false
            }
        }
        // 글자 길이가 바뀌어 위젯 폭이 달라지면 화면 안으로 다시 맞춘다
        view.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val fixed = clampX(params.x, r - l)
            if (fixed != params.x && root != null) {
                params.x = fixed
                v.post { root?.let { runCatching { wm.updateViewLayout(it, params) } } }
            }
        }
        return view
    }

    // ---------------------------------------------------------------- 갱신

    fun update(pill: RideText.Pill) {
        if (root == null) return
        if (mainText.text != pill.main) mainText.text = pill.main
        val showBar = pill.progress != null
        val showSub = !showBar && !pill.sub.isNullOrEmpty()
        if (showSub && subText.text != pill.sub) subText.text = pill.sub
        subText.visibility = if (showSub) View.VISIBLE else View.GONE
        bar.visibility = if (showBar) View.VISIBLE else View.GONE
        divider.visibility = if (showSub || showBar) View.VISIBLE else View.GONE
        pill.progress?.let { bar.setProgress(it) }
        setBoardable(pill.boardable)
    }

    /** 배경만 부드럽게 점멸 (글자는 그대로). 1.8초 주기로 진하게 ↔ 옅게 */
    private fun setBoardable(on: Boolean) {
        if (on == boardable) return
        boardable = on
        pulse?.cancel()
        pulse = null
        if (on) {
            pulse = android.animation.ValueAnimator.ofInt(255, 95).apply {
                duration = 900
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    background.alpha = it.animatedValue as Int
                    root?.invalidateOutline()
                }
                start()
            }
        } else {
            background.alpha = 255
            root?.invalidateOutline()
        }
    }

    fun hide() {
        pulse?.cancel(); pulse = null; boardable = false
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}
