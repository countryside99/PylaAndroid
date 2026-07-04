package com.pyla.ai.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.pyla.ai.R
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val frameLock = ReentrantLock()
    private var latest: Frame? = null
    private var latestTimestampMs: Long = 0

    private var width = 1280
    private var height = 720

    inner class Frame(val width: Int, val height: Int) {
        @Volatile var rgbBuffer: IntArray? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val w = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val h = intent.getIntExtra(EXTRA_HEIGHT, 720)
                startForegroundCompat()
                if (data != null) startCapture(resultCode, data, w, h)
            }
            ACTION_STOP -> {
                try { com.pyla.ai.engine.BotEngine.instance?.stop() } catch (t: Throwable) { Log.w(TAG, "engine stop: ${t.message}") }
                stopCapture()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, getString(R.string.capture_stop_action), stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image? = try { reader.acquireLatestImage() } catch (t: Throwable) { null }
        if (image != null) {
            try {
                if (image.width == width && image.height == height) handleImage(image)
            } finally { image.close() }
        }
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    private fun captureSizeFor(rw: Int, rh: Int): Pair<Int, Int> {
        if (rh <= MAX_CAPTURE_HEIGHT) return rw to rh
        val scale = MAX_CAPTURE_HEIGHT.toDouble() / rh
        val w = (((rw * scale).toInt()) / 2) * 2
        return w.coerceAtLeast(2) to MAX_CAPTURE_HEIGHT
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (rw, rh) = realDisplaySize()
            InputCoordinates.setScreenSize(rw, rh)
            val (cw, ch) = captureSizeFor(rw, rh)
            applySize(cw, ch)
        }
    }


    private fun applySize(w: Int, h: Int) {
        if ((w == width && h == height) || projection == null) return
        Log.i(TAG, "Display rotated: capture ${width}x${height} -> ${w}x${h}")
        width = w; height = h
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener(imageListener, handler)
        try {
            virtualDisplay?.resize(w, h, resources.displayMetrics.densityDpi)
            virtualDisplay?.surface = newReader.surface
        } catch (t: Throwable) {
            Log.w(TAG, "virtual display resize failed: ${t.message}")
        }
        imageReader?.close()
        imageReader = newReader
        frameLock.withLock { latest = null; latestTimestampMs = 0 }
        InputCoordinates.setCaptureSize(w, h)
    }

    private fun startCapture(resultCode: Int, data: Intent, w: Int, h: Int) {
        stopCapture()
        val (rw, rh) = realDisplaySize()
        val realW = if (rw > 1) rw else w
        val realH = if (rh > 1) rh else h
        InputCoordinates.setScreenSize(realW, realH)
        val (cw, ch) = captureSizeFor(realW, realH)
        width = cw
        height = ch
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        projection = mgr.getMediaProjection(resultCode, data).also { p ->
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Log.w(TAG, "MediaProjection stopped"); stopCapture() }
            }, null)
        }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener(imageListener, handler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "PylaCapture", width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        InputCoordinates.setCaptureSize(width, height)
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, handler)
        Log.i(TAG, "Capture started ${width}x${height}")
    }

    private fun handleImage(image: Image) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val expected = width * height
        frameLock.withLock {
            var frame = latest
            if (frame == null || frame.width != width || frame.height != height) {
                frame = Frame(width, height)
                frame.rgbBuffer = IntArray(width * height + width * 4)
                latest = frame
            }
            val out = frame.rgbBuffer ?: return@withLock
            if (rowStride == width * 4 && pixelStride == 4) {

                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out, 0, expected)
            } else {
                var pos = 0
                val rowBytes = ByteArray(rowStride)
                for (row in 0 until height) {
                    buf.position(row * rowStride)
                    buf.get(rowBytes, 0, rowStride.coerceAtMost(buf.remaining()))
                    var off = 0
                    for (x in 0 until width) {
                        out[pos++] = (rowBytes[off].toInt() and 0xFF) or
                                ((rowBytes[off + 1].toInt() and 0xFF) shl 8) or
                                ((rowBytes[off + 2].toInt() and 0xFF) shl 16) or
                                ((rowBytes[off + 3].toInt() and 0xFF) shl 24)
                        off += pixelStride
                    }
                    if (pos >= expected) break
                }
            }
            latestTimestampMs = SystemClock.elapsedRealtime()
        }
    }

    fun latestFrame(): Frame? = frameLock.withLock { latest }
    fun latestTimestampMs(): Long = latestTimestampMs

    private fun stopCapture() {
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {}
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        frameLock.withLock { latest = null; latestTimestampMs = 0 }
        Log.i(TAG, "Capture stopped")
    }

    override fun onDestroy() {
        stopCapture()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PylaCapture"
        @Volatile var instance: CaptureService? = null
        const val CHANNEL_ID = "pyla_capture"
        const val NOTIF_ID = 0xA01
        const val ACTION_START = "com.pyla.ai.START_CAPTURE"
        const val ACTION_STOP = "com.pyla.ai.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val MAX_CAPTURE_HEIGHT = 720

        private val handler by lazy {
            android.os.Handler(android.os.HandlerThread("pyla-image-reader").apply { start() }.looper)
        }

        fun start(context: Context, resultCode: Int, data: Intent, w: Int, h: Int) {
            val i = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_WIDTH, w)
                putExtra(EXTRA_HEIGHT, h)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}