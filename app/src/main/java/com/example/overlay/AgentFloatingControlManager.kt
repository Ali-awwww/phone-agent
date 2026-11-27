package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.R
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
 * وحدة مزامنة حقيقية لإيقاف المهمة مؤقتاً أو تسليم التحكم للمستخدم أو استئنافها.
 */
class AgentExecutionController {
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isHandedOver = MutableStateFlow(false)
    val isHandedOver: StateFlow<Boolean> = _isHandedOver.asStateFlow()

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
        if (!paused) {
            _isHandedOver.value = false
        }
    }

    fun setHandedOver(handedOver: Boolean) {
        _isHandedOver.value = handedOver
        _isPaused.value = handedOver
    }

    fun togglePaused(): Boolean {
        val next = !_isPaused.value
        _isPaused.value = next
        if (!next) {
            _isHandedOver.value = false
        }
        return next
    }

    fun reset() {
        _isPaused.value = false
        _isHandedOver.value = false
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
    fun onResumeAgent() {}
    fun onMiniChatSubmit(command: String) {}
    fun onMiniChatMicClick() {}
}

enum class FloatingOverlayMode {
    PILL,
    BUBBLE,
    MINI_CHAT
}

/**
 * سطح التحكم العائم بنمط iPhone (يُعرض فقط عندما يكون التطبيق المستهدف خارج حزمة وكيل الهاتف):
 * 1) وضع الحبّة (Pill): كبسولة أفقية واحدة مستديرة داكنة شبه شفافة (~72% + تمويه Blur على أندرويد 12+)
 *    تحتوي باتجاه RTL على نص الحالة الأبيض مع نقاط متحركة، ثم 3 أزرار دائرية 40dp
 *    (إيقاف مؤقت رمادي داكن، إيقاف أحمر مصمت، يد/تسليم رمادي داكن).
 * 2) وضع الفقاعة (Bubble): فقاعة دائرية 56dp على حافة الشاشة مع أيقونة الوكيل (وزر «استئناف الوكيل» عند التسليم).
 * 3) شريط الدردشة المصغّر (Mini Chat Bar): صف رد الوكيل + صف الإدخال (حقل نص + ميكروفون + إرسال أزرق #007AFF)
 *    مع إبقاء أزرار التحكم الثلاثة المصغّرة في أعلى الشريط.
 */
class AgentFloatingControlManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: OutsideTouchLinearLayout? = null
    private var pillContainer: LinearLayout? = null
    private var bubbleRow: LinearLayout? = null
    private var miniChatContainer: LinearLayout? = null

    private var statusTextView: TextView? = null
    private var baseStatusAr: String = "يفكر في الحركة التالية"
    private var isPausedState: Boolean = false
    private var isHandedOverState: Boolean = false
    private var isHostVisible: Boolean = true
    private var currentMode: FloatingOverlayMode = FloatingOverlayMode.PILL

    // أزرار الحبّة
    private var pillPauseBtn: ImageButton? = null
    private var pillStopBtn: ImageButton? = null
    private var pillHandBtn: ImageButton? = null

    // عناصر الفقاعة
    private var bubbleImageBtn: ImageButton? = null
    private var bubbleResumeBtn: TextView? = null

    // عناصر شريط الدردشة المصغّر
    private var miniPauseBtn: ImageButton? = null
    private var miniStopBtn: ImageButton? = null
    private var miniHandBtn: ImageButton? = null
    private var miniReplyBubble: TextView? = null
    private var miniInputEditText: EditText? = null
    private var miniMicBtn: ImageButton? = null
    private var miniSendBtn: ImageButton? = null

    private var layoutParams: WindowManager.LayoutParams? = null
    private var activeCallbacks: FloatingControlCallbacks? = null

    private var dotStep = 0
    private val dotAnimator = object : Runnable {
        override fun run() {
            if (rootView != null && !isPausedState && currentMode == FloatingOverlayMode.PILL && isHostVisible) {
                dotStep = (dotStep + 1) % 4
                val dots = when (dotStep) {
                    1 -> "."
                    2 -> ".."
                    3 -> "..."
                    else -> "…"
                }
                statusTextView?.text = "${baseStatusAr.trimEnd('.', '…')}$dots"
            }
            mainHandler.postDelayed(this, 450L)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    suspend fun show(
        initialStatusAr: String,
        isPaused: Boolean,
        callbacks: FloatingControlCallbacks
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show floating controls: SYSTEM_ALERT_WINDOW permission not granted.")
            return false
        }
        return withContext(Dispatchers.Main) {
            try {
                activeCallbacks = callbacks
                baseStatusAr = initialStatusAr.ifBlank { "يفكر في الحركة التالية" }
                isPausedState = isPaused

                if (rootView != null) {
                    isHostVisible = true
                    rootView?.visibility = View.VISIBLE
                    updateStatusInternal(baseStatusAr, isPaused)
                    return@withContext true
                }

                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@withContext false
                windowManager = wm
                isHostVisible = true
                currentMode = FloatingOverlayMode.PILL

                val screenWidthPx = context.resources.displayMetrics.widthPixels
                val pillWidthPx = minOf(screenWidthPx - dp(24), dp(370))

                val root = OutsideTouchLinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    onOutsideTouch = {
                        if (currentMode == FloatingOverlayMode.MINI_CHAT) {
                            switchModeInternal(FloatingOverlayMode.PILL)
                        }
                    }
                }

                // 1) الحبّة الأفقية (Pill)
                val pill = buildPillView(pillWidthPx, callbacks)
                pillContainer = pill

                // 2) الفقاعة الدائرية 56dp + زر «استئناف الوكيل» عند التسليم
                val bubble = buildBubbleRow(callbacks)
                bubble.visibility = View.GONE
                bubbleRow = bubble

                // 3) شريط الأوامر المصغّر للدردشة (Mini Chat Bar)
                val miniChat = buildMiniChatBar(pillWidthPx, callbacks)
                miniChat.visibility = View.GONE
                miniChatContainer = miniChat

                root.addView(pill)
                root.addView(bubble)
                root.addView(miniChat)

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
                    baseNonFocusableFlags(),
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = getBottomNavClearancePx()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        blurBehindRadius = dp(24)
                    }
                }
                layoutParams = params

                attachDragAndTapListener(
                    target = pill,
                    params = params,
                    wm = wm,
                    snapToHorizontalEdge = false
                ) {
                    switchModeInternal(FloatingOverlayMode.BUBBLE)
                }

                attachDragAndTapListener(
                    target = bubbleImageBtn!!,
                    params = params,
                    wm = wm,
                    snapToHorizontalEdge = true,
                    onLongPress = {
                        switchModeInternal(FloatingOverlayMode.MINI_CHAT)
                    }
                ) {
                    switchModeInternal(FloatingOverlayMode.PILL)
                }

                wm.addView(root, params)
                rootView = root
                updateStatusInternal(baseStatusAr, isPaused)

                mainHandler.removeCallbacks(dotAnimator)
                mainHandler.postDelayed(dotAnimator, 450L)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unable to attach floating control panel", e)
                false
            }
        }
    }

    private fun buildPillView(
        widthPx: Int,
        callbacks: FloatingControlCallbacks
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = widthPx
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(184, 20, 20, 24)) // ~72% عتامة داكنة
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), Color.argb(48, 255, 255, 255))
            }

            // زر صغير في بداية الحبّة لفتح شريط الدردشة المصغّر
            val chatTrigger = createCircularIconButton(
                sizeDp = 34,
                bgColor = Color.argb(65, 255, 255, 255),
                iconRes = R.drawable.ic_pill_chat,
                contentDesc = "فتح شريط أوامر الدردشة المصغّر"
            ) {
                switchModeInternal(FloatingOverlayMode.MINI_CHAT)
            }
            addView(
                chatTrigger,
                LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginEnd = dp(8)
                }
            )

            val statusTv = TextView(context).apply {
                text = "يفكر في الحركة التالية…"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textDirection = View.TEXT_DIRECTION_RTL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            statusTextView = statusTv
            addView(
                statusTv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            )

            // 3 أزرار دائرية 40dp (إيقاف مؤقت رمادي داكن، إيقاف أحمر مصمت، يد/تسليم رمادي داكن)
            val pauseBtn = createCircularIconButton(
                sizeDp = 40,
                bgColor = Color.parseColor("#3A3A3C"),
                iconRes = R.drawable.ic_pill_pause,
                contentDesc = "إيقاف مؤقت"
            ) {
                callbacks.onTogglePause()
            }
            pillPauseBtn = pauseBtn

            val stopBtn = createCircularIconButton(
                sizeDp = 40,
                bgColor = Color.parseColor("#FF3B30"),
                iconRes = R.drawable.ic_pill_stop,
                contentDesc = "إيقاف"
            ) {
                callbacks.onStopImmediately()
            }
            pillStopBtn = stopBtn

            val handBtn = createCircularIconButton(
                sizeDp = 40,
                bgColor = Color.parseColor("#3A3A3C"),
                iconRes = R.drawable.ic_pill_hand,
                contentDesc = "أكمل بنفسي"
            ) {
                callbacks.onTakeOverByUser()
            }
            pillHandBtn = handBtn

            addView(pauseBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })
            addView(stopBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })
            addView(handBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
    }

    private fun buildBubbleRow(callbacks: FloatingControlCallbacks): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL

            val avatarBtn = ImageButton(context).apply {
                contentDescription = "إعادة فتح شريط الوكيل"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(220, 24, 24, 28))
                    setStroke(dp(2), Color.parseColor("#0A84FF"))
                }
                val circularBitmap = createCircularAgentDrawable()
                if (circularBitmap != null) {
                    setImageDrawable(circularBitmap)
                } else {
                    setImageResource(R.drawable.ic_pill_chat)
                }
            }
            bubbleImageBtn = avatarBtn
            addView(avatarBtn, LinearLayout.LayoutParams(dp(56), dp(56)))

            val resumeBtn = TextView(context).apply {
                text = "استئناف الوكيل"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#007AFF"))
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.argb(70, 255, 255, 255))
                }
                visibility = View.GONE
                setOnClickListener {
                    callbacks.onResumeAgent()
                }
            }
            bubbleResumeBtn = resumeBtn
            addView(
                resumeBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(8)
                }
            )
        }
    }

    private fun buildMiniChatBar(
        widthPx: Int,
        callbacks: FloatingControlCallbacks
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(184, 20, 20, 24)) // ~72% عتامة داكنة شبه شفافة
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(52, 255, 255, 255))
            }

            // أعلى الشريط: سطح تحكم واحد بالأيقونات الدائرية الثلاثة مصغّرة + طي للحبّة
            val topControlRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }

            val titleTv = TextView(context).apply {
                text = "وكيل الهاتف"
                setTextColor(Color.parseColor("#AEAEB2"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    switchModeInternal(FloatingOverlayMode.PILL)
                }
            }
            topControlRow.addView(
                titleTv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            val mPause = createCircularIconButton(
                sizeDp = 30,
                bgColor = Color.parseColor("#3A3A3C"),
                iconRes = R.drawable.ic_pill_pause,
                contentDesc = "إيقاف مؤقت"
            ) { callbacks.onTogglePause() }
            miniPauseBtn = mPause

            val mStop = createCircularIconButton(
                sizeDp = 30,
                bgColor = Color.parseColor("#FF3B30"),
                iconRes = R.drawable.ic_pill_stop,
                contentDesc = "إيقاف"
            ) { callbacks.onStopImmediately() }
            miniStopBtn = mStop

            val mHand = createCircularIconButton(
                sizeDp = 30,
                bgColor = Color.parseColor("#3A3A3C"),
                iconRes = R.drawable.ic_pill_hand,
                contentDesc = "أكمل بنفسي"
            ) { callbacks.onTakeOverByUser() }
            miniHandBtn = mHand

            topControlRow.addView(mPause, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
            topControlRow.addView(mStop, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
            topControlRow.addView(mHand, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(topControlRow)

            // الصف العلوي: رد الوكيل في فقاعة داكنة صغيرة محاذاة لليمين
            val replyContainer = FrameLayout(context).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val replyTv = TextView(context).apply {
                text = "أكتب أو تحدّث لأوجّه الوكيل فوراً على هذه الشاشة"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C2C2E"))
                    cornerRadius = dp(14).toFloat()
                }
            }
            miniReplyBubble = replyTv
            replyContainer.addView(
                replyTv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START
                )
            )
            addView(
                replyContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            )

            // الصف السفلي باتجاه RTL: حقل نص مستدير داكن + زر ميكروفون دائري + زر إرسال دائري أزرق #007AFF
            val inputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
            }

            val inputEt = EditText(context).apply {
                hint = "اكتب أمرك للوكيل…"
                setHintTextColor(Color.parseColor("#AEAEB2"))
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 2
                isSingleLine = false
                imeOptions = EditorInfo.IME_ACTION_SEND
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C1C1E"))
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.argb(40, 255, 255, 255))
                }
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        val txt = text?.toString()?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            setText("")
                            hideKeyboard(this)
                            callbacks.onMiniChatSubmit(txt)
                        }
                        true
                    } else {
                        false
                    }
                }
            }
            miniInputEditText = inputEt

            val micBtn = createCircularIconButton(
                sizeDp = 38,
                bgColor = Color.parseColor("#3A3A3C"),
                iconRes = R.drawable.ic_pill_mic,
                contentDesc = "تحدث بالصوت"
            ) {
                callbacks.onMiniChatMicClick()
            }
            miniMicBtn = micBtn

            val sendBtn = createCircularIconButton(
                sizeDp = 38,
                bgColor = Color.parseColor("#007AFF"),
                iconRes = R.drawable.ic_pill_send,
                contentDesc = "إرسال"
            ) {
                val txt = miniInputEditText?.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty()) {
                    miniInputEditText?.setText("")
                    miniInputEditText?.let { hideKeyboard(it) }
                    callbacks.onMiniChatSubmit(txt)
                }
            }
            miniSendBtn = sendBtn

            inputRow.addView(
                inputEt,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            )
            inputRow.addView(
                micBtn,
                LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    marginEnd = dp(6)
                }
            )
            inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(inputRow)
        }
    }

    suspend fun updateStatus(statusAr: String, isPaused: Boolean) {
        withContext(Dispatchers.Main) {
            updateStatusInternal(statusAr, isPaused)
        }
    }

    suspend fun setHostVisible(visible: Boolean) {
        withContext(Dispatchers.Main) {
            isHostVisible = visible
            val root = rootView ?: return@withContext
            if (!visible) {
                if (currentMode == FloatingOverlayMode.MINI_CHAT) {
                    switchModeInternal(FloatingOverlayMode.PILL)
                }
                root.visibility = View.GONE
            } else {
                root.visibility = View.VISIBLE
            }
        }
    }

    suspend fun collapseToHandOverBubble() {
        withContext(Dispatchers.Main) {
            isHandedOverState = true
            isPausedState = true
            switchModeInternal(FloatingOverlayMode.BUBBLE)
            updateButtonIconsForPause(true)
            bubbleResumeBtn?.visibility = View.VISIBLE
            snapParamsToEdge()
        }
    }

    suspend fun resumeFromHandOver() {
        withContext(Dispatchers.Main) {
            isHandedOverState = false
            isPausedState = false
            bubbleResumeBtn?.visibility = View.GONE
            switchModeInternal(FloatingOverlayMode.PILL)
            updateStatusInternal("يفكر في الحركة التالية", isPaused = false)
        }
    }

    suspend fun showMiniChatReplyAndFold(replyOneLine: String, delayBeforeFoldMs: Long = 1600L) {
        withContext(Dispatchers.Main) {
            miniReplyBubble?.text = replyOneLine
        }
        delay(delayBeforeFoldMs)
        withContext(Dispatchers.Main) {
            if (currentMode == FloatingOverlayMode.MINI_CHAT) {
                switchModeInternal(FloatingOverlayMode.PILL)
            }
        }
    }

    suspend fun setMiniChatVoiceState(isListening: Boolean, partialText: String? = null) {
        withContext(Dispatchers.Main) {
            miniMicBtn?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (isListening) "#FF3B30" else "#3A3A3C"))
            }
            if (!partialText.isNullOrBlank()) {
                miniInputEditText?.setText(partialText)
                miniInputEditText?.setSelection(partialText.length)
            }
            if (isListening) {
                miniReplyBubble?.text = "أستمع إليك الآن…"
            }
        }
    }

    suspend fun dismiss() {
        withContext(Dispatchers.Main) {
            mainHandler.removeCallbacks(dotAnimator)
            val view = rootView ?: return@withContext
            try {
                miniInputEditText?.let { hideKeyboard(it) }
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // تمت إزالة النافذة مسبقاً
            } finally {
                rootView = null
                pillContainer = null
                bubbleRow = null
                miniChatContainer = null
                statusTextView = null
                pillPauseBtn = null
                pillStopBtn = null
                pillHandBtn = null
                bubbleImageBtn = null
                bubbleResumeBtn = null
                miniPauseBtn = null
                miniStopBtn = null
                miniHandBtn = null
                miniReplyBubble = null
                miniInputEditText = null
                miniMicBtn = null
                miniSendBtn = null
                layoutParams = null
                windowManager = null
                activeCallbacks = null
                isHandedOverState = false
                isPausedState = false
                isHostVisible = true
                currentMode = FloatingOverlayMode.PILL
            }
        }
    }

    private fun updateStatusInternal(statusAr: String, isPaused: Boolean) {
        isPausedState = isPaused
        baseStatusAr = if (isPaused) {
            "متوقف مؤقتاً • اضغط استئناف"
        } else {
            statusAr.ifBlank { "يفكر في الحركة التالية" }
        }
        statusTextView?.text = if (isPaused) baseStatusAr else "$baseStatusAr…"
        updateButtonIconsForPause(isPaused)
    }

    private fun updateButtonIconsForPause(isPaused: Boolean) {
        val iconRes = if (isPaused) R.drawable.ic_pill_play else R.drawable.ic_pill_pause
        val desc = if (isPaused) "استئناف" else "إيقاف مؤقت"
        val bgHex = if (isPaused) "#0A84FF" else "#3A3A3C"

        pillPauseBtn?.apply {
            setImageResource(iconRes)
            contentDescription = desc
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bgHex))
            }
        }
        miniPauseBtn?.apply {
            setImageResource(iconRes)
            contentDescription = desc
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bgHex))
            }
        }
    }

    private fun switchModeInternal(target: FloatingOverlayMode) {
        currentMode = target
        pillContainer?.visibility = if (target == FloatingOverlayMode.PILL) View.VISIBLE else View.GONE
        bubbleRow?.visibility = if (target == FloatingOverlayMode.BUBBLE) View.VISIBLE else View.GONE
        miniChatContainer?.visibility = if (target == FloatingOverlayMode.MINI_CHAT) View.VISIBLE else View.GONE

        bubbleResumeBtn?.visibility = if (target == FloatingOverlayMode.BUBBLE && isHandedOverState) {
            View.VISIBLE
        } else {
            View.GONE
        }

        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val root = rootView ?: return

        if (target == FloatingOverlayMode.MINI_CHAT) {
            params.x = 0
            params.y = getBottomNavClearancePx()
            params.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = dp(24)
            }
            runCatching { wm.updateViewLayout(root, params) }
            miniInputEditText?.requestFocus()
        } else {
            miniInputEditText?.let { hideKeyboard(it) }
            params.flags = baseNonFocusableFlags()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = dp(24)
            }
            if (target == FloatingOverlayMode.PILL) {
                params.x = 0
                params.y = getBottomNavClearancePx()
            }
            runCatching { wm.updateViewLayout(root, params) }
        }
    }

    private fun snapParamsToEdge() {
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val root = rootView ?: return
        val dm = context.resources.displayMetrics
        val halfScreen = dm.widthPixels / 2
        val targetOffset = (halfScreen - dp(44)).coerceAtLeast(0)
        params.x = if (params.x >= 0) targetOffset else -targetOffset
        params.y = clampYOutsideMiddleBand(params.y, dm.heightPixels)
        runCatching { wm.updateViewLayout(root, params) }
    }

    private fun clampYOutsideMiddleBand(yPx: Int, screenHeightPx: Int): Int {
        val minY = getBottomNavClearancePx()
        val maxY = (screenHeightPx - dp(120)).coerceAtLeast(minY)
        val clamped = yPx.coerceIn(minY, maxY)
        val bottomBandMax = maxOf(minY, (screenHeightPx * 0.24f).toInt())
        val topBandMin = minOf(maxY, (screenHeightPx * 0.72f).toInt())
        return if (clamped in (bottomBandMax + 1) until topBandMin) {
            val distBottom = clamped - bottomBandMax
            val distTop = topBandMin - clamped
            if (distBottom <= distTop) bottomBandMax else topBandMin
        } else {
            clamped
        }
    }

    private fun baseNonFocusableFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun createCircularIconButton(
        sizeDp: Int,
        bgColor: Int,
        iconRes: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp((sizeDp * 0.25f).toInt().coerceAtLeast(6))
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createCircularAgentDrawable(): BitmapDrawable? {
        return runCatching {
            val src = BitmapFactory.decodeResource(context.resources, R.drawable.ic_agent_glyph_1795550154766)
                ?: return null
            val size = minOf(src.width, src.height)
            val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val r = size / 2f
            canvas.drawCircle(r, r, r, paint)
            BitmapDrawable(context.resources, out)
        }.getOrNull()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTapListener(
        target: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        snapToHorizontalEdge: Boolean,
        onLongPress: (() -> Unit)? = null,
        onTap: () -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        var longPressed = false
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                onLongPress?.invoke()
            }
        }

        target.setOnTouchListener { _, event ->
            val dm = context.resources.displayMetrics
            val maxHorizontalOffset = (dm.widthPixels / 2 - dp(32)).coerceAtLeast(0)
            val maxVerticalOffset = (dm.heightPixels - dp(120)).coerceAtLeast(dp(80))

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    longPressed = false
                    if (onLongPress != null) {
                        mainHandler.postDelayed(longPressRunnable, 480L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        params.x = (initialX + dx).coerceIn(-maxHorizontalOffset, maxHorizontalOffset)
                        // مع Gravity.BOTTOM: السحب للأعلى (dy سالب) يزيد المسافة عن الأسفل
                        params.y = (initialY - dy).coerceIn(dp(12), maxVerticalOffset)
                        rootView?.let { runCatching { wm.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        onTap()
                    } else if (moved) {
                        params.y = clampYOutsideMiddleBand(params.y, dm.heightPixels)
                        if (snapToHorizontalEdge) {
                            snapParamsToEdge()
                        } else {
                            rootView?.let { runCatching { wm.updateViewLayout(it, params) } }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun hideKeyboard(view: View) {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun getBottomNavClearancePx(): Int {
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navHeight = if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(24)
        return navHeight + dp(12)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private class OutsideTouchLinearLayout(context: Context) : LinearLayout(context) {
        var onOutsideTouch: (() -> Unit)? = null

        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            if (ev?.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch?.invoke()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }
}
