package com.castlink.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** 发射端界面：发现/输入接收端 -> 选择画质 -> 开始投屏。 */
class SenderActivity : Activity() {

    private val reqProjection = 100
    private lateinit var ipEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var resSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitSpinner: Spinner
    private lateinit var listView: ListView
    private val found = LinkedHashMap<String, String>() // name -> host
    private val names = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val heights = intArrayOf(720, 1080, 1440)
    private val resLabels = arrayOf("720p 流畅", "1080p 高清", "1440p 超清")
    private val fpsValues = intArrayOf(30, 60)
    private val bitrates = intArrayOf(4, 8, 16, 24)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Ui.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(p, p * 2, p, p)
        }
        root.addView(Ui.text(this, "发射端", 28f))
        root.addView(Ui.text(this, "附近的接收端（点击选择）", 14f, 0xAAFFFFFF.toInt()).apply {
            setPadding(0, p, 0, 8)
        })
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView = ListView(this).apply {
            this.adapter = this@SenderActivity.adapter
            background = Ui.round(Ui.CARD, Ui.dp(this@SenderActivity, 12).toFloat())
            setOnItemClickListener { _, _, pos, _ ->
                found[names[pos]]?.let { ipEdit.setText(it) }
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(-1, Ui.dp(this, 120)))

        ipEdit = EditText(this).apply {
            hint = "或手动输入接收端 IP，如 192.168.1.5"
            setHintTextColor(0x66FFFFFF)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_PHONE
            setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("ip", ""))
        }
        root.addView(ipEdit)

        resSpinner = spinner(resLabels)
        resSpinner.setSelection(1)
        fpsSpinner = spinner(arrayOf("30 FPS", "60 FPS"))
        fpsSpinner.setSelection(1)
        bitSpinner = spinner(bitrates.map { "$it Mbps" }.toTypedArray())
        bitSpinner.setSelection(1)
        root.addView(label("分辨率")); root.addView(resSpinner)
        root.addView(label("帧率")); root.addView(fpsSpinner)
        root.addView(label("码率")); root.addView(bitSpinner)

        statusView = Ui.text(this, CastService.status, 15f, 0xFF90CAF9.toInt()).apply {
            setPadding(0, p, 0, p)
        }
        root.addView(statusView)
        startBtn = Ui.button(this, "开始投屏").apply { setOnClickListener { toggle() } }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, Ui.dp(this, 56)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG); isFillViewport = true; addView(root)
        })
        updateButton()
    }

    private fun label(s: String) = Ui.text(this, s, 13f, 0xAAFFFFFF.toInt()).apply {
        setPadding(0, Ui.dp(this@SenderActivity, 10), 0, 0)
    }

    private fun spinner(items: Array<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@SenderActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun updateButton() {
        startBtn.text = if (CastService.running) "停止投屏" else "开始投屏"
        startBtn.background = Ui.round(
            if (CastService.running) 0xFFE53935.toInt() else Ui.ACCENT, Ui.dp(this, 12).toFloat()
        )
    }

    private fun toggle() {
        if (CastService.running) {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
            return
        }
        val host = ipEdit.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, "请选择或输入接收端 IP", Toast.LENGTH_SHORT).show(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqProjection) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未授权录屏", Toast.LENGTH_SHORT).show(); return
        }
        val host = ipEdit.text.toString().trim()
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("ip", host).apply()

        val dm = resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val targetShort = minOf(heights[resSpinner.selectedItemPosition], shortSide)
        val scale = targetShort.toFloat() / shortSide
        val longOut = align16((longSide * scale).toInt())
        val shortOut = align16(targetShort)
        val portrait = dm.heightPixels >= dm.widthPixels
        val w = if (portrait) shortOut else longOut
        val h = if (portrait) longOut else shortOut

        val i = Intent(this, CastService::class.java)
            .putExtra(CastService.EXTRA_CODE, resultCode)
            .putExtra(CastService.EXTRA_DATA, data)
            .putExtra(CastService.EXTRA_HOST, host)
            .putExtra(CastService.EXTRA_WIDTH, w)
            .putExtra(CastService.EXTRA_HEIGHT, h)
            .putExtra(CastService.EXTRA_FPS, fpsValues[fpsSpinner.selectedItemPosition])
            .putExtra(CastService.EXTRA_BITRATE, bitrates[bitSpinner.selectedItemPosition] * 1_000_000)
        CastService.running = true
        CastService.status = "正在连接 $host ..."
        statusView.text = CastService.status
        updateButton()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun align16(v: Int) = maxOf(16, (v + 8) / 16 * 16)

    override fun onStart() {
        super.onStart()
        CastService.listener = { s ->
            statusView.text = s
            updateButton()
        }
        statusView.text = CastService.status
        updateButton()
        startDiscovery()
    }

    override fun onStop() {
        CastService.listener = null
        stopDiscovery()
        super.onStop()
    }

    private fun startDiscovery() {
        try {
            val m = getSystemService(NSD_SERVICE) as NsdManager
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onServiceFound(s: NsdServiceInfo) {
                    runOnUiThread { resolveQueue.add(s); resolveNext() }
                }
                override fun onServiceLost(s: NsdServiceInfo) {
                    runOnUiThread {
                        found.remove(s.serviceName)
                        refreshList()
                    }
                }
            }
            m.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            discovery = l
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val s = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd?.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, e: Int) {
                    runOnUiThread { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    runOnUiThread {
                        si.host?.hostAddress?.let { found[si.serviceName] = it }
                        refreshList()
                        resolving = false
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun refreshList() {
        names.clear(); names.addAll(found.keys); adapter.notifyDataSetChanged()
    }

    private fun stopDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
        resolveQueue.clear(); resolving = false
    }
}
