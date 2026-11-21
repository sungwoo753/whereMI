package com.wheremi.line4

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 스쿼클(애플식 "연속 곡률" 모서리) 배경.
 *
 * 각 모서리를 원호 대신 초타원(superellipse) |x|^n + |y|^n = 1 의 1/4 조각으로 그린다.
 * 초타원은 끝점에서 곡률이 0 이라, 직선 변에서 모서리로 넘어갈 때 꺾임 없이 부드럽게 이어진다.
 *
 * @param radiusFraction 모서리 크기 = 짧은 변 x 이 값 (0.5 면 양 끝이 통째로 스쿼클)
 * @param radiusPx 모서리 크기를 px 로 고정 (카드 등). 주면 radiusFraction 대신 사용
 * @param n 초타원 지수. 2 = 원, 클수록 각진 느낌 (애플 아이콘 ≈ 4~5)
 */
class SquircleDrawable(
    color: Int,
    private val radiusFraction: Float = 0.5f,
    private val n: Float = 4f,
    private val radiusPx: Float? = null,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        buildPath(bounds)
    }

    private fun buildPath(b: Rect) {
        path.reset()
        if (b.isEmpty) return
        val r = radiusPx?.let { min(it, min(b.width(), b.height()) / 2f) }
            ?: (min(b.width(), b.height()) * radiusFraction)
        val steps = 24
        val e = 2f / n
        // 모서리 중심(cx, cy)과 사분면 방향(sx, sy). 시계 방향: 우상 → 우하 → 좌하 → 좌상
        val corners = listOf(
            Triple(b.right - r, b.top + r, floatArrayOf(1f, -1f)),
            Triple(b.right - r, b.bottom - r, floatArrayOf(1f, 1f)),
            Triple(b.left + r, b.bottom - r, floatArrayOf(-1f, 1f)),
            Triple(b.left + r, b.top + r, floatArrayOf(-1f, -1f)),
        )
        var first = true
        corners.forEachIndexed { ci, (cx, cy, s) ->
            for (i in 0..steps) {
                // 각 모서리마다 세로축 → 가로축(또는 반대) 순서로 진행해 경로가 한 바퀴 이어지게
                val t = (Math.PI / 2 * i / steps).let { if (ci % 2 == 0) Math.PI / 2 - it else it }
                val c = cos(t).toFloat()
                val sn = sin(t).toFloat()
                val x = cx + s[0] * r * abs(c).pow(e)
                val y = cy + s[1] * r * abs(sn).pow(e)
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
        }
        path.close()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    /** elevation 그림자도 스쿼클 모양을 따르도록 */
    override fun getOutline(outline: Outline) {
        outline.alpha = paint.alpha / 255f // 점멸로 옅어질 때 그림자도 같이 옅게
        if (Build.VERSION.SDK_INT >= 30) outline.setPath(path)
        else @Suppress("DEPRECATION") outline.setConvexPath(path)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
