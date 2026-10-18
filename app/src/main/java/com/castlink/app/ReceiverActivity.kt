package com.castlink.app

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

/** 接收端：监听 TCP，解码 H.264 并直接渲染到 Surface（无缓冲低延时）。 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var info: TextView
    @Volatile private var surface: android.view.Surface? = null
    private val surfaceLock = Object()
    @Volatile private var closed = false
    private var server: ServerSocket? = null
    @Volatile private var current: Socket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(info, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemUi()
        showWaiting()
        startServer()
        registerNsd()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun showWaiting() {
        val ips = Protocol.localAddresses().joinToString("\n").ifEmpty { "(未连接 Wi-Fi)" }
        info.text = "接收端已就绪\n等待发射端连接…\n\n本机 IP（可手动输入）:\n$ips\n端口 ${Protocol.PORT}"
        info.visibility = View.VISIBLE
    }

    private fun startServer() {
        Thread({
            try {
                val ss = ServerSocket(Protocol.PORT)
                ss.reuseAddress = true
                server = ss
                while (!closed) {
                    val s = ss.accept()
                    // 新连接顶替旧连接
                    try { current?.close() } catch (_: Exception) {}
                    current = s
                    Thread({ session(s) }, "recv-session").start()
                }
            } catch (e: Exception) {
                if (!closed) runOnUiThread { info.text = "监听失败: ${e.message}" }
            }
        }, "recv-accept").start()
    }

    private fun session(s: Socket) {
        var codec: MediaCodec? = null
        var renderer: Thread? = null
        try {
            s.tcpNoDelay = true
            s.receiveBufferSize = 512 * 1024
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            val hello = Protocol.read(input)
            if (hello.type != Protocol.T_HELLO || hello.data.size < 12) return
            val hb = java.nio.ByteBuffer.wrap(hello.data)
            if (hb.getInt() != Protocol.MAGIC) return
            val w = hb.getInt()
            val h = hb.getInt()

            val sf = synchronized(surfaceLock) {
                var waited = 0
                while (surface == null && !closed && waited < 50) { surfaceLock.wait(100); waited++ }
                surface
            } ?: return

            runOnUiThread { fitSurface(w, h); info.visibility = View.GONE }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= 23) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            }
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(format, sf, null, 0)
            dec.start()
            codec = dec

            renderer = Thread({
                val bi = MediaCodec.BufferInfo()
                try {
                    while (!closed && current === s) {
                        val i = dec.dequeueOutputBuffer(bi, 10_000)
                        if (i >= 0) dec.releaseOutputBuffer(i, true) // 立即渲染
                    }
                } catch (_: Exception) {
                }
            }, "recv-render").also { it.start() }

            var gotKey = false
            while (!closed && current === s) {
                val p = Protocol.read(input)
                if (p.type == Protocol.T_KEY) gotKey = true
                if (p.type == Protocol.T_FRAME && !gotKey) continue
                val flags = if (p.type == Protocol.T_CONFIG) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                val idx = dec.dequeueInputBuffer(100_000)
                if (idx < 0) continue
                val ib = dec.getInputBuffer(idx) ?: continue
                ib.clear()
                ib.put(p.data)
                dec.queueInputBuffer(idx, 0, p.data.size, p.pts, flags)
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            val wasCurrent = current === s
            if (wasCurrent) current = null
            try { renderer?.join(300) } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            if (wasCurrent && !closed) runOnUiThread { showWaiting() }
        }
    }

    private fun fitSurface(vw: Int, vh: Int) {
        val root = surfaceView.parent as View
        val rw = root.width
        val rh = root.height
        if (rw == 0 || rh == 0) return
        val scale = minOf(rw.toFloat() / vw, rh.toFloat() / vh)
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        lp.width = (vw * scale).toInt()
        lp.height = (vh * scale).toInt()
        surfaceView.layoutParams = lp
    }

    private fun registerNsd() {
        try {
            val m = getSystemService(NSD_SERVICE) as NsdManager
            val si = NsdServiceInfo().apply {
                serviceName = "CastLink-" + Build.MODEL
                serviceType = Protocol.SERVICE_TYPE
                port = Protocol.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
                override fun onServiceRegistered(s: NsdServiceInfo) {}
                override fun onServiceUnregistered(s: NsdServiceInfo) {}
            }
            m.registerService(si, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            regListener = l
        } catch (_: Exception) {
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surface = holder.surface; surfaceLock.notifyAll() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surface = null }
        try { current?.close() } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        closed = true
        try { regListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { current?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
