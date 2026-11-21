package com.wheremi.line4

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * 애플(iOS 설정 앱) 느낌의 작은 UI 키트.
 *  - 연회색 배경 위 흰 스쿼클 카드, 카드 안은 행(row) + 들여쓴 구분선
 *  - 여백: 화면 좌우 20dp, 카드 안 16dp, 섹션 사이 28dp
 */
class Ui(private val ctx: Context) {

    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
    fun color(id: Int) = ContextCompat.getColor(ctx, id)

    val bg get() = color(R.color.bg)
    val card get() = color(R.color.card)
    val label get() = color(R.color.label)
    val label2 get() = color(R.color.label2)
    val accent get() = color(R.color.line4)

    fun text(s: String, sp: Float, c: Int = label, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(c)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
    }

    fun vstack(block: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        block()
    }

    fun hstack(block: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
           top: Float = 0f, bottom: Float = 0f, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    /** 흰 스쿼클 카드 */
    fun card(block: LinearLayout.() -> Unit = {}) = vstack {
        background = SquircleDrawable(card, n = 4f, radiusPx = dp(14f).toFloat())
        clipToOutline = true
        block()
    }

    /** 섹션 제목 (작은 회색 글씨) */
    fun header(s: String) = text(s, 13f, label2).apply {
        setPadding(dp(16f), 0, dp(16f), dp(8f))
    }

    /** 카드 안 구분선 (왼쪽 16dp 들여쓰기) */
    fun separator() = View(ctx).apply {
        setBackgroundColor(color(R.color.separator))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f)))
            .apply { marginStart = dp(16f) }
    }

    /** "제목 ……… 값 ›" 행 */
    fun row(title: String, value: String = "", chevron: Boolean = true, valueColor: Int = label2,
            onClick: (() -> Unit)? = null): Pair<View, TextView> {
        val v = text(value, 16f, valueColor).apply { gravity = Gravity.END }
        val row = hstack {
            minimumHeight = dp(48f)
            setPadding(dp(16f), dp(12f), dp(14f), dp(12f))
            addView(text(title, 16f), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
            addView(v, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            if (chevron) addView(text("›", 20f, label2).apply { setPadding(dp(8f), 0, 0, dp(2f)) })
            onClick?.let { c -> setOnClickListener { c() }; background = ripple() }
        }
        return row to v
    }

    /** 스위치 행 */
    fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View = hstack {
        minimumHeight = dp(48f)
        setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
        addView(text(title, 16f), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        addView(switch(checked, onChange))
    }

    fun switch(checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(ctx).apply {
        isChecked = checked
        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.line4), color(R.color.fill)),
        )
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    /** 꽉 찬 스쿼클 버튼 */
    fun primaryButton(s: String, fill: Int = accent, textColor: Int = 0xFFFFFFFF.toInt(), onClick: () -> Unit) =
        text(s, 17f, textColor, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52f)
            background = SquircleDrawable(fill, n = 4f, radiusPx = dp(14f).toFloat())
            foreground = ripple()
            setOnClickListener { onClick() }
        }

    fun ripple() = TypedValue().let {
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        ContextCompat.getDrawable(ctx, it.resourceId)
    }

    /** 역 고르기 (목록 다이얼로그) */
    fun pickStation(activity: Activity, title: String, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(Line4.stations.toTypedArray(), current) { d, which ->
                onPick(which); d.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
