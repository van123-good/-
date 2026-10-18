package com.castview.app

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
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

/** Receiver: listens for a sender, decodes H.264 with low-latency settings and renders full screen. */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var hint: TextView
    private var server: ServerSocket? = null
    private var client: Socket? = null
    @Volatile private var alive = true
    @Volatile private var surfaceReady = false
    private val lock = Object()
    private lateinit var discovery: Discovery

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        hint = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 20f; gravity = Gravity.CENTER
            text = "等待投屏…\n本机 IP：${Protocol.localIp() ?: "未连接 Wi-Fi"}\n名称：${Build.MODEL}"
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        discovery = Discovery(this); discovery.register(Build.MODEL)
        Thread { acceptLoop() }.start()
    }

    private fun setHint(s: String?) = runOnUiThread {
        hint.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s != null) hint.text = s
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(Protocol.PORT).also { server = it }
            while (alive) {
                val s = ss.accept()
                try { client?.close() } catch (_: Exception) {}
                client = s
                try { play(s) } catch (_: Exception) {}
                try { s.close() } catch (_: Exception) {}
                if (alive) setHint("等待投屏…\n本机 IP：${Protocol.localIp() ?: "-"}")
            }
        } catch (e: Exception) {
            if (alive) setHint("无法监听端口 ${Protocol.PORT}：${e.message}")
        }
    }

    private fun play(s: Socket) {
        s.tcpNoDelay = true
        s.receiveBufferSize = 512 * 1024
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
        val (w, h) = Protocol.readHeader(inp)
        synchronized(lock) { while (!surfaceReady && alive) lock.wait(200) }
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
        }
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        dec.configure(fmt, surfaceView.holder.surface, null, 0)
        dec.start()
        setHint(null)
        val info = MediaCodec.BufferInfo()
        try {
            while (alive) {
                val len = inp.readInt()
                require(len in 1..Protocol.MAX_FRAME) { "bad frame" }
                val flags = inp.readUnsignedByte()
                val pts = inp.readLong()
                val data = ByteArray(len); inp.readFully(data)
                var idx = dec.dequeueInputBuffer(100_000)
                while (idx < 0 && alive) { drain(dec, info); idx = dec.dequeueInputBuffer(10_000) }
                if (idx < 0) break
                dec.getInputBuffer(idx)!!.apply { clear(); put(data) }
                val f = if (flags and Protocol.FLAG_CONFIG != 0) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                dec.queueInputBuffer(idx, 0, len, pts, f)
                drain(dec, info)
            }
        } finally {
            try { dec.stop() } catch (_: Exception) {}
            dec.release()
        }
    }

    /** Render immediately, never wait for presentation time -> minimal latency. */
    private fun drain(dec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val o = dec.dequeueOutputBuffer(info, 0)
            if (o >= 0) dec.releaseOutputBuffer(o, true) else break
        }
    }

    override fun surfaceCreated(h: SurfaceHolder) { synchronized(lock) { surfaceReady = true; lock.notifyAll() } }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        try { client?.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        alive = false
        discovery.stop()
        try { client?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
