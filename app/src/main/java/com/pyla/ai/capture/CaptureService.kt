package com.pyla.ai.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.pyla.ai.engine.PylaLog as Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.pyla.ai.R
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.engine.DeviceProfile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val frameLock = ReentrantLock()
    private var latest: Frame? = null
    @Volatile private var latestTimestampMs: Long = 0
    private var rowScratch = ByteArray(0)

    private var width = 1280
    private var height = 720

    private class Frame(val width: Int, val height: Int, val rgbBuffer: IntArray)

    data class FrameCopy(
        val width: Int,
        val height: Int,
        val rgbBuffer: IntArray,
        val capturedAtMs: Long,
    )

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
                if (data != null) {
                    try {
                        startCapture(resultCode, data, w, h)
                    } catch (t: Throwable) {
                        // Observation only at the real start boundary; the original throw is preserved.
                        DiagnosticLog.failure("capture", "start", "start_threw", t)
                        throw t
                    }
                } else DiagnosticLog.failure("capture", "start", "result_data_missing")
            }
            ACTION_STOP -> {
                try {
                    com.pyla.ai.engine.BotEngine.instance?.stop("capture_stop_action")
                } catch (t: Throwable) {
                    DiagnosticLog.failure("capture", "stop", "engine_stop_threw", t)
                    Log.w(TAG, "engine stop: ${t.message}")
                }
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
        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            DiagnosticLog.failure("capture", "frame_acquire", "acquire_threw", t)
            null
        }
        if (image != null) {
            try {
                if (image.width == width && image.height == height) handleImage(image)
                else DiagnosticLog.sample("capture.frame_ignored", "reason=size_mismatch", 10_000)
            } finally { image.close() }
        }
    }

    /**
     * Current logical size of the default display in its *current* rotation.
     *
     * Read from the DisplayManager's display object: a Service is a non-visual context, and the
     * WindowManager it hands out can keep reporting the orientation the app process was started
     * in. That left the capture in portrait after the game rotated to landscape on a device where
     * the bot was started from the portrait app screen.
     */
    private fun realDisplaySize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        try {
            val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 1 && metrics.heightPixels > 1) {
                    return metrics.widthPixels to metrics.heightPixels
                }
            }
        } catch (_: Throwable) { }
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    /** Re-reads the display geometry and follows it. Safe to call repeatedly. */
    private fun syncDisplayGeometry() {
        if (projection == null) return
        val (rw, rh) = realDisplaySize()
        InputCoordinates.setScreenSize(rw, rh)
        val (cw, ch) = captureSizeFor(rw, rh)
        applySize(cw, ch)
    }

    /**
     * Safety net for devices that do not deliver (or deliver early) the display-changed callback:
     * the geometry is re-checked on a slow timer for as long as a projection is active.
     */
    private val geometryPoll = object : Runnable {
        override fun run() {
            try { syncDisplayGeometry() } catch (t: Throwable) {
                DiagnosticLog.failure("capture", "geometry_poll", "threw", t)
            }
            if (projection != null) handler.postDelayed(this, GEOMETRY_POLL_MS)
        }
    }

    private fun captureSizeFor(rw: Int, rh: Int): Pair<Int, Int> {
        val maxHeight = DeviceProfile.maxCaptureHeight.coerceAtMost(MAX_CAPTURE_HEIGHT)
        if (rh <= maxHeight) return rw to rh
        val scale = maxHeight.toDouble() / rh
        val w = (((rw * scale).toInt()) / 2) * 2
        return w.coerceAtLeast(2) to maxHeight
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            syncDisplayGeometry()
        }
    }


    private fun applySize(w: Int, h: Int) {
        if ((w == width && h == height) || projection == null) return
        Log.i(TAG, "Display rotated: capture ${width}x${height} -> ${w}x${h}")
        width = w; height = h
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener(imageListener, handler)
        try {
            // Detach the old surface before resizing. Resizing with the old-size surface still
            // attached was observed (Android 13) to leave the mirror in its previous portrait
            // layout: the landscape game then appeared as a small letterboxed strip inside the
            // new buffer and no screen was recognised for the rest of the session.
            virtualDisplay?.surface = null
            virtualDisplay?.resize(w, h, resources.displayMetrics.densityDpi)
            virtualDisplay?.surface = newReader.surface
        } catch (t: Throwable) {
            DiagnosticLog.failure("capture", "resize", "virtual_display_threw", t)
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
                override fun onStop() {
                    DiagnosticLog.event("capture.projection_stopped", "size=${width}x$height")
                    Log.w(TAG, "MediaProjection stopped")
                    try { stopCapture() } finally {
                        // A callback is observed; Android does not identify who initiated this stop.
                        DiagnosticLog.failure("capture", "projection", "projection_stopped_callback")
                    }
                }
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
        handler.removeCallbacks(geometryPoll)
        handler.postDelayed(geometryPoll, GEOMETRY_POLL_MS)
        DiagnosticLog.event("capture.started", "size=${width}x$height")
        try {
            DiagnosticLog.setDeviceProfile(
                com.pyla.ai.engine.DeviceProfile.diagnosticProfile(realW, realH, width, height))
        } catch (_: Throwable) { }
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
                frame = Frame(width, height, IntArray(expected))
                latest = frame
            }
            val out = frame.rgbBuffer
            if (rowStride == width * 4 && pixelStride == 4) {
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out, 0, expected)
            } else {
                if (rowScratch.size < rowStride) rowScratch = ByteArray(rowStride)
                var pos = 0
                for (row in 0 until height) {
                    buf.position(row * rowStride)
                    val bytesRead = rowStride.coerceAtMost(buf.remaining())
                    buf.get(rowScratch, 0, bytesRead)
                    var off = 0
                    for (x in 0 until width) {
                        if (off + 2 >= bytesRead) break
                        out[pos++] = (rowScratch[off].toInt() and 0xFF) or
                            ((rowScratch[off + 1].toInt() and 0xFF) shl 8) or
                            ((rowScratch[off + 2].toInt() and 0xFF) shl 16) or
                            (if (off + 3 < bytesRead) (rowScratch[off + 3].toInt() and 0xFF) shl 24 else -0x1000000)
                        off += pixelStride
                    }
                    if (pos >= expected) break
                }
            }
            latestTimestampMs = SystemClock.elapsedRealtime()
        }
    }

    /** Copies a complete frame while the producer lock is held, preventing torn screenshots. */
    fun copyLatestFrame(reuse: IntArray? = null): FrameCopy? = frameLock.withLock {
        val frame = latest ?: return@withLock null
        val required = frame.width * frame.height
        val destination = reuse?.takeIf { it.size == required } ?: IntArray(required)
        System.arraycopy(frame.rgbBuffer, 0, destination, 0, required)
        FrameCopy(frame.width, frame.height, destination, latestTimestampMs)
    }

    fun latestTimestampMs(): Long = latestTimestampMs

    @Synchronized
    private fun stopCapture() {
        handler.removeCallbacks(geometryPoll)
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (t: Throwable) {
            DiagnosticLog.failure("capture", "stop", "display_listener_unregister_threw", t)
        }
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        val activeProjection = projection
        projection = null
        try { activeProjection?.stop() } catch (t: Throwable) {
            DiagnosticLog.failure("capture", "stop", "projection_release_threw", t)
        }
        frameLock.withLock { latest = null; latestTimestampMs = 0 }
        DiagnosticLog.event("capture.stopped", "size=${width}x$height")
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
        // WORK_IN_PROGRESS_FEATURE is a secret feature that is going to be added soon... Hello, by countryside99 still implementing...
        const val WORK_IN_PROGRESS_FEATURE = "com.pyla.ai.WORK_IN_PROGRESS_FEATURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val MAX_CAPTURE_HEIGHT = 720
        private const val GEOMETRY_POLL_MS = 500L

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
