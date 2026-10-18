package com.castview.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Sender: captures screen (no audio), encodes H.264 at low latency and streams it over TCP. */
class SenderService : Service() {
    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        @Volatile var running = false
        @Volatile var status = ""
        var listener: ((String) -> Unit)? = null
    }

    private class Packet(val data: ByteArray, val flags: Int, val pts: Long)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var socket: Socket? = null
    @Volatile private var active = false
    private val queue = ArrayBlockingQueue<Packet>(4)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        startFg()
        val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: run { shutdown(); return START_NOT_STICKY }
        active = true; running = true
        Thread { run(host, code, data) }.start()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("cast", "投屏", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(this, 0,
            Intent(this, SenderService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "cast").setContentTitle("正在投屏")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun report(s: String) { status = s; main.post { listener?.invoke(s) } }

    private fun run(host: String, code: Int, data: Intent) {
        try {
            report("连接中…")
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.sendBufferSize = 256 * 1024
            sock.connect(InetSocketAddress(host, Protocol.PORT), 5000)
            socket = sock
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { shutdown() }
            }, main)

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            // Scale so the long side <= 1920 (1080p class); dimensions aligned to 16 for encoder friendliness.
            val scale = minOf(1f, 1920f / maxOf(dm.widthPixels, dm.heightPixels))
            val w = (dm.widthPixels * scale).toInt() / 16 * 16
            val h = (dm.heightPixels * scale).toInt() / 16 * 16

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try { enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            catch (e: Exception) { // fall back to a basic profile on picky encoders
                fmt.removeKey(MediaFormat.KEY_PROFILE); fmt.removeKey(MediaFormat.KEY_LEVEL)
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            codec = enc
            val surface = enc.createInputSurface()
            enc.start()
            display = mp.createVirtualDisplay("cast", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)

            Protocol.writeHeader(out, w, h)
            report("投屏中")
            Thread { drain(enc) }.start()

            // Writer loop (this thread).
            while (active) {
                val p = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.writeInt(p.data.size); out.writeByte(p.flags); out.writeLong(p.pts)
                out.write(p.data)
                if (queue.isEmpty()) out.flush()
            }
        } catch (e: Exception) {
            if (active) report("连接中断：${e.message}")
        } finally {
            shutdown()
        }
    }

    /** Reads encoder output; if the network can't keep up, drops stale frames to keep latency low. */
    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var needKey = false
        var config: Packet? = null
        try {
            while (active) {
                val i = enc.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val buf = enc.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset); buf.get(bytes)
                    var fl = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) fl = Protocol.FLAG_CONFIG
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) fl = fl or Protocol.FLAG_KEY
                    val p = Packet(bytes, fl, info.presentationTimeUs)
                    if (fl and Protocol.FLAG_CONFIG != 0) {
                        config = p; queue.put(p)
                    } else if (needKey && fl and Protocol.FLAG_KEY == 0) {
                        // skip until next key frame
                    } else {
                        needKey = false
                        if (!queue.offer(p)) {
                            // Congestion: discard backlog, resend config and wait for a fresh key frame.
                            queue.clear()
                            config?.let { queue.offer(it) }
                            needKey = true
                            enc.setParameters(Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            })
                        }
                    }
                }
                enc.releaseOutputBuffer(i, false)
            }
        } catch (_: Exception) {}
    }

    private fun shutdown() {
        if (!active && !running) return
        active = false; running = false
        try { socket?.close() } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; codec = null; projection = null; socket = null
        if (status == "投屏中") report("已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }
}
