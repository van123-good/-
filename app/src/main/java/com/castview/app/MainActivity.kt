package com.castview.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

/** LetsView-style home: two big entry cards (cast / receive), discovered devices list, manual IP. */
class MainActivity : Activity() {
    private lateinit var deviceList: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var statusView: TextView
    private lateinit var castBtn: Button
    private lateinit var discovery: Discovery
    private var target: String? = null
    private val found = LinkedHashMap<String, String>()
    private val REQ = 100
    private val green = Color.parseColor("#2BC48A")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F4F6F9")) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(20)) }
        scroll.addView(root)

        root.addView(TextView(this).apply { text = "CastView"; textSize = 30f; setTextColor(green); setTypeface(typeface, 1) })
        root.addView(TextView(this).apply { text = "高清 · 低延时 · 稳定的屏幕镜像"; textSize = 14f; setTextColor(Color.GRAY) })

        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, dp(12)) }
        cards.addView(card("📱", "我是接收端", "在大屏设备上显示", "#2BC48A") {
            startActivity(Intent(this, ReceiverActivity::class.java))
        })
        cards.addView(card("🚀", "我是发射端", "把本机画面投出去", "#3A7BFF") { toggleCast() })
        root.addView(cards)

        statusView = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY); setPadding(dp(4), dp(4), 0, dp(8)) }
        root.addView(statusView)

        root.addView(section("附近的设备"))
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)

        root.addView(section("手动输入接收端 IP"))
        ipInput = EditText(this).apply {
            hint = "例如 192.168.1.20"; inputType = InputType.TYPE_CLASS_TEXT
            background = round(Color.WHITE, 12); setPadding(dp(16), dp(12), dp(16), dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { target = s?.toString()?.trim()?.ifEmpty { null } }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        root.addView(ipInput, LinearLayout.LayoutParams(-1, -2))

        castBtn = Button(this).apply {
            text = "开始投屏"; setTextColor(Color.WHITE); background = round(Color.parseColor("#3A7BFF"), 28)
            setOnClickListener { toggleCast() }
        }
        root.addView(castBtn, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        setContentView(scroll)

        discovery = Discovery(this)
        discovery.start { name, host -> runOnUiThread { found[host] = name; refreshDevices() } }
        SenderService.listener = { s -> statusView.text = s; refreshBtn() }
        statusView.text = SenderService.status
        refreshBtn()
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.BLACK); setTypeface(typeface, 1); setPadding(dp(4), dp(12), 0, dp(8))
    }

    private fun card(icon: String, title: String, sub: String, color: String, click: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = round(Color.parseColor(color), 20); setPadding(dp(12), dp(24), dp(12), dp(24))
            setOnClickListener { click() }
        }
        c.addView(TextView(this).apply { text = icon; textSize = 36f; gravity = Gravity.CENTER })
        c.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, 1) })
        c.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(Color.parseColor("#E6FFFFFF")); gravity = Gravity.CENTER })
        return c.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
    }

    private fun refreshDevices() {
        deviceList.removeAllViews()
        if (found.isEmpty()) {
            deviceList.addView(TextView(this).apply { text = "正在搜索同一 Wi-Fi 下的接收端…"; setTextColor(Color.GRAY) })
        }
        for ((host, name) in found) {
            deviceList.addView(TextView(this).apply {
                text = "🖥  $name   ($host)"; textSize = 16f; setTextColor(Color.BLACK)
                background = round(if (host == target) Color.parseColor("#D8F5EA") else Color.WHITE, 12)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setOnClickListener { target = host; ipInput.setText(host); refreshDevices() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun refreshBtn() { castBtn.text = if (SenderService.running) "停止投屏" else "开始投屏" }

    private fun toggleCast() {
        if (SenderService.running) {
            startService(Intent(this, SenderService::class.java).setAction(SenderService.ACTION_STOP)); return
        }
        if (target == null) { Toast.makeText(this, "请先选择设备或输入接收端 IP", Toast.LENGTH_SHORT).show(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ && res == RESULT_OK && data != null) {
            val i = Intent(this, SenderService::class.java)
                .putExtra(SenderService.EXTRA_HOST, target).putExtra(SenderService.EXTRA_CODE, res)
                .putExtra(SenderService.EXTRA_DATA, data)
            startForegroundService(i)
            SenderService.running = true; refreshBtn()
        }
    }

    override fun onDestroy() { discovery.stop(); SenderService.listener = null; super.onDestroy() }
}
