package com.wheremi.line4

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * 전역을 지난 뒤 "얼마나 남았는지"를 보여주는 작은 진행 바.
 *  - 채워진 부분이 실제 진행률을 부드럽게 따라가고
 *  - 채워진 흰 막대 위로 옅은 음영이 흐르는 모션으로 "달리는 중" 느낌을 준다.
 */
class ApproachBar(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF } // 호선 색 배경 위 흰색 30%
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var target = 0f
    private var shown = 0f
    private var lastFrame = 0L

    fun setProgress(p: Float) {
        target = p.coerceIn(0f, 1f)
        if (shown == 0f) shown = target * 0.9f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((72 * density).toInt(), (8 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16 else (now - lastFrame).coerceAtMost(100)
        lastFrame = now
        // 실제 진행률을 부드럽게 따라감
        shown += (target - shown) * (dt / 600f).coerceAtMost(1f)

        val h = 5 * density
        val top = (height - h) / 2f
        val r = h / 2f
        rect.set(0f, top, width.toFloat(), top + h)
        canvas.drawRoundRect(rect, r, r, track)

        val fw = (width * shown).coerceAtLeast(h)
        rect.set(0f, top, fw, top + h)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(rect, r, r, fill)

        // 흐르는 빛 (1.4초 주기)
        val phase = (now % 1400L) / 1400f
        val cx = -fw * 0.4f + phase * fw * 1.8f
        sheen.shader = LinearGradient(
            cx - 14 * density, 0f, cx + 14 * density, 0f,
            intArrayOf(0x00000000, 0x3300588A, 0x00000000), null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, r, r, sheen)

        if (isShown) postInvalidateDelayed(33)
    }
}
