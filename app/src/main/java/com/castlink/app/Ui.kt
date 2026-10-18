package com.castlink.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF101820.toInt()
    const val CARD = 0xFF1C2833.toInt()
    const val ACCENT = 0xFF1E88E5.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun round(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    fun text(c: Context, s: String, size: Float, color: Int = Color.WHITE) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color)
    }

    fun button(c: Context, s: String, color: Int = ACCENT) = Button(c).apply {
        text = s; setTextColor(Color.WHITE); textSize = 16f
        background = round(color, dp(c, 12).toFloat())
        isAllCaps = false
    }

    fun card(c: Context, title: String, sub: String, color: Int, onClick: () -> Unit): View {
        val l = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(color, dp(c, 20).toFloat())
            setPadding(dp(c, 24), dp(c, 28), dp(c, 24), dp(c, 28))
            isClickable = true
            setOnClickListener { onClick() }
        }
        l.addView(text(c, title, 24f))
        l.addView(text(c, sub, 14f, 0xCCFFFFFF.toInt()))
        return l
    }
}
