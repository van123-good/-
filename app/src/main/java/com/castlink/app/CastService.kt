package com.castlink.app

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
import android.os.PowerManager
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** 发射端：录屏 -> H.264 硬编码（低延时配置） -> TCP。仅视频，不含音频。 */
class CastService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_WIDTH = "w"
        const val EXTRA_HEIGHT = "h"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "cast"

        @Volatile var running = false
        @Volatile var status = "未连接"
        @Volatile var listener: ((String) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        fun publish(s: String) {
            status = s
            main.post { listener?.invoke(s) }
        }
    }

    @Volatile private var stopped = false
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var socket: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            shutdown("已停止")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForegroundNotification()
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val host = intent.getStringExtra(EXTRA_HOST)
        if (data == null || host == null) {
            shutdown("参数错误")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        stopped = false
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val w = intent.getIntExtra(EXTRA_WIDTH, 1280)
        val h = intent.getIntExtra(EXTRA_HEIGHT, 720)
        val fps = intent.getIntExtra(EXTRA_FPS, 60)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
        worker = Thread({ runCast(code, data, host, w, h, fps, bitrate) }, "cast-main").also { it.start() }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "投屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = android.app.PendingIntent.getService(
            this, 0, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val n = builder.setContentTitle("CastLink 正在投屏")
            .setContentText("点击通知栏按钮可停止")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun runCast(code: Int, data: Intent, host: String, w: Int, h: Int, fps: Int, bitrate: Int) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "castlink:cast").also {
                it.acquire(4 * 60 * 60 * 1000L)
            }

            publish("正在连接 $host ...")
            val s = Socket()
            s.tcpNoDelay = true
            s.sendBufferSize = 256 * 1024
            s.connect(InetSocketAddress(host, Protocol.PORT), 5000)
            socket = s
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            Protocol.write(out, Protocol.hello(w, h))

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    shutdown("录屏已被系统停止")
                }
            }, Handler(Looper.getMainLooper()))

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // 画面静止时重复上一帧，保证接收端持续有画面
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                if (Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                }
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // 部分设备不支持 High Profile / CBR，回退到基础参数
                format.removeKey(MediaFormat.KEY_PROFILE)
                enc.reset()
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            codec = enc
            val surface = enc.createInputSurface()
            enc.start()

            val dpi = resources.displayMetrics.densityDpi
            display = proj.createVirtualDisplay(
                "CastLink", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                surface, null, null
            )
            publish("投屏中 → $host  ${w}x$h@$fps")
            pump(enc, out)
        } catch (e: Exception) {
            if (status.startsWith("投屏中") || status.startsWith("正在连接")) publish("连接失败/中断: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            shutdown(null)
            main.post { stopSelf() }
        }
    }

    /** 读取编码输出并经有界队列交给发送线程；拥塞时丢帧并请求关键帧，避免延时累积。 */
    private fun pump(enc: MediaCodec, out: OutputStream) {
        val queue = ArrayBlockingQueue<ByteArray>(4)
        val failure = arrayOfNulls<Exception>(1)
        val sender = Thread({
            try {
                while (!stopped) {
                    val p = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    Protocol.write(out, p)
                }
            } catch (e: Exception) {
                failure[0] = e
                shutdown(null)
            }
        }, "cast-send").also { it.start() }

        val info = MediaCodec.BufferInfo()
        var waitKey = false
        while (!stopped) {
            val idx = enc.dequeueOutputBuffer(info, 100_000)
            if (idx < 0) continue
            val buf = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val type = when {
                    isConfig -> Protocol.T_CONFIG
                    isKey -> Protocol.T_KEY
                    else -> Protocol.T_FRAME
                }
                if (isKey) waitKey = false
                if (!waitKey || isConfig) {
                    val packet = Protocol.encode(type, info.presentationTimeUs, bytes)
                    if (!queue.offer(packet)) {
                        if (isConfig || isKey) {
                            queue.clear()
                            queue.offer(packet)
                        } else {
                            // 网络拥塞：丢弃，直到下一个关键帧，并立刻请求
                            queue.clear()
                            waitKey = true
                            val b = Bundle()
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            enc.setParameters(b)
                        }
                    }
                }
            }
            enc.releaseOutputBuffer(idx, false)
        }
        sender.join(500)
        failure[0]?.let { throw it }
    }

    @Synchronized
    private fun shutdown(message: String?) {
        stopped = true
        running = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        if (message != null) publish(message)
    }

    override fun onDestroy() {
        shutdown(if (status.startsWith("投屏中")) "已停止" else null)
        super.onDestroy()
    }
}
