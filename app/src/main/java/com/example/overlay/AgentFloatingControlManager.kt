package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val TAG = "FloatingControls"

/**
 * وحدة مزامنة حقيقية لإيقاف المهمة مؤقتاً أو استئنافها أثناء تنفيذ الكوروتينات.
 */
class AgentExecutionController {
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    fun togglePaused(): Boolean {
        val next = !_isPaused.value
        _isPaused.value = next
        return next
    }

    fun reset() {
        _isPaused.value = false
    }

    suspend fun awaitIfPaused() {
        currentCoroutineContext().ensureActive()
        if (_isPaused.value) {
            _isPaused.first { !it }
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun delayRespectingPause(totalMs: Long) {
        var remaining = totalMs.coerceAtLeast(0L)
        val slice = 150L
        while (remaining > 0L) {
            awaitIfPaused()
            val step = minOf(remaining, slice)
            delay(step)
            remaining -= step
        }
        awaitIfPaused()
    }
}

interface FloatingControlCallbacks {
    fun onStopImmediately()
    fun onTogglePause()
    fun onTakeOverByUser()
}

/**
 * أزرار التحكم العائمة فوق التطبيقات أثناء عمل الوكيل (عبر SYSTEM_ALERT_WINDOW):
 * 1) إيقاف فوري ينهي المهمة.
 * 2) إيقاف مؤقت / استئناف.
 * 3) «أكمل بنفسي» (يتوقف الوكيل ويسلّم التحكم للمستخدم فوراً).
 * 4) تصغير (إخفاء الأزرار مع استمرار العمل، مع زر صغير قابل للسحب لإعادتها).
 */
class AgentFloatingControlManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var expandedPanel: LinearLayout? = null
    private var minimizedPill: TextView? = null
    private var statusView: TextView? = null
    private var pauseResumeButton: Button? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isMinimized: Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    suspend fun show(
        initialStatusAr: String,
        isPaused: Boolean,
        callbacks: FloatingControlCallbacks
    ) {
        if (!Settings.canDrawOverlays(context)) return
        withContext(Dispatchers.Main) {
            try {
                if (rootView != null) {
                    updateStatusInternal(initialStatusAr, isPaused)
                    return@withContext
                }

                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@withContext
                windowManager = wm
                isMinimized = false

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val expanded = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 18, 24, 18)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F00A1930"))
                        cornerRadius = 36f
                        setStroke(3, Color.parseColor("#00D4FF"))
                    }
                }

                val statusTv = TextView(context).apply {
                    text = initialStatusAr
                    setTextColor(Color.parseColor("#EAF4FF"))
                    textSize = 13f
                    setPadding(0, 0, 0, 12)
                }
                statusView = statusTv

                val row1 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val row2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 10, 0, 0)
                }

                val stopBtn = createStyledButton(
                    label = "إيقاف",
                    bgHex = "#D92D53",
                    textColor = Color.WHITE
                ) {
                    callbacks.onStopImmediately()
                }

                val pauseBtn = createStyledButton(
                    label = if (isPaused) "استئناف" else "إيقاف مؤقت",
                    bgHex = if (isPaused) "#059669" else "#2563EB",
                    textColor = Color.WHITE
                ) {
                    callbacks.onTogglePause()
                }
                pauseResumeButton = pauseBtn

                val takeOverBtn = createStyledButton(
                    label = "أكمل بنفسي",
                    bgHex = "#00D4FF",
                    textColor = Color.parseColor("#031226")
                ) {
                    callbacks.onTakeOverByUser()
                }

                val minimizeBtn = createStyledButton(
                    label = "تصغير",
                    bgHex = "#1E3A5F",
                    textColor = Color.parseColor("#EAF4FF")
                ) {
                    setMinimizedInternal(true)
                }

                row1.addView(stopBtn, createRowButtonParams(marginEnd = 10))
                row1.addView(pauseBtn, createRowButtonParams(marginEnd = 0))
                row2.addView(takeOverBtn, createRowButtonParams(marginEnd = 10))
                row2.addView(minimizeBtn, createRowButtonParams(marginEnd = 0))

                expanded.addView(statusTv)
                expanded.addView(row1)
                expanded.addView(row2)
                expandedPanel = expanded

                val pill = TextView(context).apply {
                    text = "● الوكيل (إظهار التحكم)"
                    setTextColor(Color.parseColor("#031226"))
                    textSize = 12f
                    setPadding(24, 14, 24, 14)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#00D4FF"))
                        cornerRadius = 48f
                        setStroke(3, Color.parseColor("#EAF4FF"))
                    }
                    visibility = View.GONE
                }
                minimizedPill = pill

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 24
                    y = 140
                }
                layoutParams = params

                attachDragAndClickListener(pill, params, wm) {
                    setMinimizedInternal(false)
                }
                attachDragListener(expanded, params, wm)

                root.addView(expanded)
                root.addView(pill)

                wm.addView(root, params)
                rootView = root
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unable to attach floating control panel", e)
            }
        }
    }

    suspend fun updateStatus(statusAr: String, isPaused: Boolean) {
        withContext(Dispatchers.Main) {
            updateStatusInternal(statusAr, isPaused)
        }
    }

    suspend fun dismiss() {
        withContext(Dispatchers.Main) {
            val view = rootView ?: return@withContext
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // تمت إزالة النافذة مسبقاً
            } finally {
                rootView = null
                expandedPanel = null
                minimizedPill = null
                statusView = null
                pauseResumeButton = null
                layoutParams = null
                windowManager = null
                isMinimized = false
            }
        }
    }

    private fun updateStatusInternal(statusAr: String, isPaused: Boolean) {
        statusView?.text = if (isPaused) {
            "متوقف مؤقتاً — اضغط استئناف للمتابعة أو أكمل بنفسي"
        } else {
            statusAr
        }
        pauseResumeButton?.apply {
            text = if (isPaused) "استئناف" else "إيقاف مؤقت"
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isPaused) "#059669" else "#2563EB"))
                cornerRadius = 24f
            }
        }
        minimizedPill?.text = if (isPaused) "❚❚ الوكيل متوقف مؤقتاً" else "● الوكيل يعمل (إظهار)"
    }

    private fun setMinimizedInternal(minimized: Boolean) {
        isMinimized = minimized
        expandedPanel?.visibility = if (minimized) View.GONE else View.VISIBLE
        minimizedPill?.visibility = if (minimized) View.VISIBLE else View.GONE
    }

    private fun createStyledButton(
        label: String,
        bgHex: String,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 12f
            isAllCaps = false
            minimumHeight = 96
            setPadding(22, 10, 22, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 24f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createRowButtonParams(marginEnd: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            this.marginEnd = marginEnd
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndClickListener(
        target: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        onTap: () -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { wm.updateViewLayout(rootView, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(
        target: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { wm.updateViewLayout(rootView, params) }
                    }
                    true
                }
                else -> false
            }
        }
    }
}
