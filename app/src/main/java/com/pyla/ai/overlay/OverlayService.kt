package com.pyla.ai.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class OverlayIcon { PLAY, PAUSE, STOP }
private enum class OverlayHit { HEADER, START, PAUSE, STOP, NONE }

/**
 * Minimal floating control surface drawn entirely with platform Canvas (no Compose), so it
 * incurs no composition cost while the game is foreground.
 *
 * Design language matches the app: deep translucent glass panel, hairline highlight, accent
 * controls, smooth expand/collapse animation, large touch targets. Icons are drawn as
 * vector paths (no asset dependencies).
 */
class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var view: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var density = 1f
    private var panelWidth = 0
    private var headerHeight = 0
    private var expandedHeight = 0
    private var anim = 1f

    private var running = false
    private var paused = false
    private var actionText = "No active action"

    private val tick = object : Runnable {
        override fun run() {
            if (!OverlayPreferences.isEnabled(this@OverlayService) || !OverlayPreferences.canDrawOverlays(this@OverlayService)) {
                stopSelf(); return
            }
            running = com.pyla.ai.engine.BotStatus.engineRunning
            paused = running && com.pyla.ai.engine.BotStatus.enginePaused
            actionText = com.pyla.ai.engine.BotStatus.lastAction.ifBlank {
                com.pyla.ai.engine.BotStatus.currentState.ifBlank { "No active action" }
            }
            view?.invalidate()
            if (view != null) handler.postDelayed(this, 500)
        }
    }

    private var animStart = 0L
    private var animFrom = 1f
    private var animTo = 1f
    private val animRun = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - animStart) / 220f).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t)
            anim = animFrom + (animTo - animFrom) * eased
            view?.invalidate()
            if (t < 1f) handler.postDelayed(this, 16)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!OverlayPreferences.isEnabled(this) || !OverlayPreferences.canDrawOverlays(this)) {
            stopSelf(); return
        }
        density = resources.displayMetrics.density
        panelWidth = (224 * density).roundToInt()
        headerHeight = (52 * density).roundToInt()
        expandedHeight = (160 * density).roundToInt()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            install()
            handler.post(tick)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "overlay install failed: ${t.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun install() {
        val p = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        val dm = resources.displayMetrics
        p.x = (dm.widthPixels - panelWidth - (14 * density).roundToInt()).coerceAtLeast(0)
        p.y = (90 * density).roundToInt()
        params = p
        val v = OverlayView(this)
        view = v
        windowManager.addView(v, p)
    }

    private fun toggle() {
        collapsed = !collapsed
        handler.removeCallbacks(animRun)
        animStart = SystemClock.uptimeMillis()
        animFrom = anim
        animTo = if (collapsed) 0f else 1f
        handler.post(animRun)
    }

    private fun startBot() {
        if (running) return
        view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        startActivity(Intent(this, com.pyla.ai.ui.MainActivity::class.java).apply {
            action = com.pyla.ai.ui.MainActivity.ACTION_REQUEST_START
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun pauseResume() {
        view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        com.pyla.ai.engine.BotEngine.instance?.let { it.setPaused(!it.isPaused()) }
    }

    private fun stopBot() {
        view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        com.pyla.ai.engine.BotEngine.instance?.stop()
        com.pyla.ai.capture.CaptureService.stop(this)
    }

    private inner class OverlayView(context: Context) : View(context) {
        init { setWillNotDraw(false) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = (headerHeight + (expandedHeight - headerHeight) * anim).roundToInt()
            setMeasuredDimension(panelWidth, h.coerceAtLeast(headerHeight))
        }

        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(248, 22, 27, 36) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * density; color = Color.argb(40, 132, 139, 153)
        }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13f * density; isFakeBoldText = true
        }
        private val status = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * density; isFakeBoldText = true }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chev = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val div = Paint(Paint.ANTI_ALIAS_FLAG)
        private val actionP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density }
        private val bgBtn = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokeBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
        private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density; isFakeBoldText = true }
        private val rad = 16f * density
        private val pad = 14f * density

        private var downX = 0f
        private var downY = 0f
        private var pStartX = 0
        private var pStartY = 0
        private var moved = false

        private fun buttonRects(): List<RectF> {
            val btnY = headerHeight + 34f * density
            val btnH = 46f * density
            val gap = 8f * density
            val bw = (panelWidth - 2 * pad - gap * 2) / 3
            val sx = pad
            return listOf(
                RectF(sx, btnY, sx + bw, btnY + btnH),
                RectF(sx + bw + gap, btnY, sx + 2 * bw + gap, btnY + btnH),
                RectF(sx + 2 * (bw + gap), btnY, sx + 3 * bw + 2 * gap, btnY + btnH),
            )
        }

        private fun hit(x: Float, y: Float): OverlayHit {
            if (y < headerHeight) return OverlayHit.HEADER
            if (anim < 0.4f) return OverlayHit.NONE
            val r = buttonRects()
            if (y in r[0].top..r[0].bottom) {
                return when {
                    x < r[1].left -> OverlayHit.START
                    x < r[2].left -> OverlayHit.PAUSE
                    else -> OverlayHit.STOP
                }
            }
            return OverlayHit.NONE
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, rad, rad, panel)
            canvas.drawRoundRect(0f, 0f, w, h, rad, rad, border)

            canvas.drawText("PYLA", pad, 31f * density, title)
            val accent = when { paused -> PAUSED; running -> GREEN; else -> MUTED }
            dot.color = accent
            canvas.drawCircle(pad + 56f * density, 25f * density, 4f * density, dot)
            status.color = accent
            canvas.drawText(when { paused -> "Paused"; running -> "Running"; else -> "Stopped" }, pad + 67f * density, 31f * density, status)

            val cx = w - pad - 6f * density
            val cy = 26f * density
            chev.color = Color.argb(200, 182, 188, 200)
            val p = Path()
            if (collapsed) {
                p.moveTo(cx - 6f * density, cy - 4f * density); p.lineTo(cx, cy + 4f * density); p.lineTo(cx + 6f * density, cy - 4f * density)
            } else {
                p.moveTo(cx - 6f * density, cy + 4f * density); p.lineTo(cx, cy - 4f * density); p.lineTo(cx + 6f * density, cy + 4f * density)
            }
            canvas.drawPath(p, chev)

            if (anim < 0.02f) return
            val a = (255 * anim).roundToInt().coerceIn(0, 255)

            div.color = Color.argb((40 * anim).roundToInt(), 132, 139, 153)
            canvas.drawRect(pad, headerHeight.toFloat(), w - pad, headerHeight.toFloat() + density, div)

            actionP.color = Color.argb((180 * anim).roundToInt(), 182, 188, 200)
            val at = if (actionText.length > 28) actionText.take(27) + "…" else actionText
            canvas.drawText(at, pad, headerHeight + 22f * density, actionP)

            val rects = buttonRects()
            drawBtn(canvas, rects[0], "Start", GREEN, !running, OverlayIcon.PLAY, a)
            drawBtn(canvas, rects[1], if (paused) "Resume" else "Pause", ACCENT, running, OverlayIcon.PAUSE, a)
            drawBtn(canvas, rects[2], "Stop", RED, running, OverlayIcon.STOP, a)
        }

        private fun drawBtn(c: Canvas, r: RectF, label: String, accentColor: Int, enabled: Boolean, icon: OverlayIcon, a: Int) {
            bgBtn.color = Color.argb((a * (if (enabled) 28 else 12)) / 255, 232, 236, 244)
            c.drawRoundRect(r, 10f * density, 10f * density, bgBtn)
            strokeBtn.color = if (enabled) Color.argb((a * 80) / 255, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                else Color.argb((a * 25) / 255, 132, 139, 153)
            c.drawRoundRect(r, 10f * density, 10f * density, strokeBtn)
            val icx = r.left + r.width() / 2
            val icy = r.top + 16f * density
            iconP.color = if (enabled) accentColor else Color.argb((a * 110) / 255, 132, 139, 153)
            drawIcon(c, icon, icx, icy, 9f * density, iconP)
            labelP.color = if (enabled) accentColor else Color.argb((a * 130) / 255, 132, 139, 153)
            labelP.textAlign = Paint.Align.CENTER
            c.drawText(label, r.left + r.width() / 2, r.bottom - 7f * density, labelP)
            labelP.textAlign = Paint.Align.LEFT
        }

        private fun drawIcon(c: Canvas, icon: OverlayIcon, cx: Float, cy: Float, r: Float, paint: Paint) {
            val p = Path()
            when (icon) {
                OverlayIcon.PLAY -> {
                    paint.style = Paint.Style.FILL
                    p.moveTo(cx - r * 0.55f, cy - r); p.lineTo(cx + r * 0.85f, cy); p.lineTo(cx - r * 0.55f, cy + r); p.close()
                    c.drawPath(p, paint); paint.style = Paint.Style.STROKE
                }
                OverlayIcon.PAUSE -> {
                    paint.style = Paint.Style.FILL
                    c.drawRoundRect(cx - r * 0.75f, cy - r, cx - r * 0.2f, cy + r, 1.5f, 1.5f, paint)
                    c.drawRoundRect(cx + r * 0.2f, cy - r, cx + r * 0.75f, cy + r, 1.5f, 1.5f, paint)
                    paint.style = Paint.Style.STROKE
                }
                OverlayIcon.STOP -> {
                    paint.style = Paint.Style.FILL
                    c.drawRoundRect(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, 2f, 2f, paint)
                    paint.style = Paint.Style.STROKE
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val par = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    pStartX = par.x; pStartY = par.y; moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > SLOP || abs(dy) > SLOP) moved = true
                    if (moved) {
                        par.x = pStartX + dx.roundToInt()
                        par.y = (pStartY + dy.roundToInt()).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(this, par) } catch (_: Throwable) {}
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        when (hit(e.x, e.y)) {
                            OverlayHit.HEADER -> toggle()
                            OverlayHit.START -> startBot()
                            OverlayHit.PAUSE -> pauseResume()
                            OverlayHit.STOP -> stopBot()
                            OverlayHit.NONE -> {}
                        }
                    }
                    return true
                }
            }
            return true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        view?.let { v -> try { windowManager.removeView(v) } catch (_: Throwable) {} }
        view = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PylaOverlay"
        private const val SLOP = 8f
        private const val ACCENT = 0xFFFFC400.toInt()
        private const val GREEN = 0xFF4CD964.toInt()
        private const val RED = 0xFFFF5A5A.toInt()
        private const val PAUSED = 0xFF9C8CFF.toInt()
        private const val MUTED = 0xFF7A8699.toInt()
        const val ACTION_STOP = "com.pyla.ai.overlay.STOP"

        fun start(context: Context): Boolean {
            if (!OverlayPreferences.isEnabled(context) || !OverlayPreferences.canDrawOverlays(context)) return false
            return try { context.startService(Intent(context, OverlayService::class.java)); true }
            catch (t: Throwable) { android.util.Log.w(TAG, "overlay start failed: ${t.message}"); false }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}