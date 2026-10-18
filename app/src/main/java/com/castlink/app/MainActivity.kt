package com.castlink.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Ui.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(p, p * 2, p, p)
        }
        root.addView(Ui.text(this, "CastLink 投屏", 32f))
        root.addView(Ui.text(this, "低延时 · 高清 · 稳定（仅画面）", 14f, 0xAAFFFFFF.toInt()).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 24))
        })
        fun lp() = LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = p }
        root.addView(
            Ui.card(this, "发射端", "把本机屏幕投到另一台设备", Ui.ACCENT) {
                startActivity(Intent(this, SenderActivity::class.java))
            }, lp()
        )
        root.addView(
            Ui.card(this, "接收端", "在本机上显示其他设备的屏幕", 0xFF43A047.toInt()) {
                startActivity(Intent(this, ReceiverActivity::class.java))
            }, lp()
        )
        root.addView(Ui.text(this, "两台设备需处于同一 Wi-Fi 局域网", 12f, 0x88FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
        })
        setContentView(root)
    }
}
