package com.phoneassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Owns three small overlay windows: the character, a speech bubble above it and an
 * input bar below it. The character wanders the whole screen, and stops to listen
 * whenever the input bar is open.
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        /** Label of the app the user is in, refreshed every few seconds. */
        @Volatile
        var currentAppLabel: String? = null
            private set

        private var instance: OverlayService? = null

        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "assistant"
        private const val ACTION_STOP = "com.phoneassistant.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var petParams: WindowManager.LayoutParams
    private var pet: PetView? = null

    private var bubble: TextView? = null
    private var inputBar: LinearLayout? = null
    private var inputField: EditText? = null
    private var inputParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(System.currentTimeMillis())

    private var screenW = 1
    private var screenH = 1
    private var topInset = 0
    private var bottomInset = 0
    private var petW = 1
    private var petH = 1
    private var speed = 55f

    /** Sub-pixel position, so slow diagonal travel still moves. */
    private var posX = 0f
    private var posY = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var walking = false
    private var idleUntil = 0L
    private var attentive = false
    private var paused = false
    private var watchCountdown = 0L

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragged = false

    private val frameMs = 33L

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        windowManager = getSystemService(WindowManager::class.java)

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val dp = resources.displayMetrics.density
        screenW = resources.displayMetrics.widthPixels
        screenH = resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            screenW = metrics.bounds.width()
            screenH = metrics.bounds.height()
            val insets = metrics.windowInsets
            topInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            bottomInset =
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        }

        petW = (64 * dp).toInt()
        petH = (86 * dp).toInt()
        speed = 55 * dp

        posX = (screenW - petW) * 0.55f
        posY = (screenH - petH - bottomInset) * 0.62f

        petParams = WindowManager.LayoutParams(
            petW,
            petH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX.toInt()
            y = posY.toInt()
        }

        val view = PetView(this)
        view.setOnTouchListener(touchListener)
        pet = view

        val added = runCatching { windowManager.addView(view, petParams) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }

        idleUntil = SystemClock.uptimeMillis() + 2500L
        registerScreenReceiver()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        removeInputBar()
        pet?.let { view -> runCatching { windowManager.removeView(view) } }
        pet = null
        runCatching { unregisterReceiver(screenReceiver) }
        instance = null
        running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- wandering

    private val tick = object : Runnable {
        override fun run() {
            if (!paused) step()
            handler.postDelayed(this, frameMs)
        }
    }

    private fun step() {
        val view = pet ?: return
        val now = SystemClock.uptimeMillis()

        watchCountdown += frameMs
        if (watchCountdown >= 15_000L) {
            watchCountdown = 0L
            currentAppLabel =
                if (AppWatcher.hasPermission(this)) AppWatcher.currentAppLabel(this) else null
        }

        // Standing still and paying attention while the user types.
        if (attentive) return

        if (walking) {
            val dx = targetX - posX
            val dy = targetY - posY
            val distance = hypot(dx, dy)
            val perFrame = speed * frameMs / 1000f

            if (distance <= perFrame || distance < 0.5f) {
                posX = targetX
                posY = targetY
                applyPetPosition()
                walking = false
                view.walking = false
                idleUntil = now + idleDelay()
            } else {
                posX += dx / distance * perFrame
                posY += dy / distance * perFrame
                applyPetPosition()
                if (abs(dx) > 1f) view.facingRight = dx > 0
                view.walking = true
            }
        } else if (now > idleUntil) {
            pickTarget(now)
        }
    }

    /** Long pauses between short strolls — he should feel relaxed, not busy. */
    private fun idleDelay(): Long = 12_000L + rnd.nextLong(16_000L)

    private fun pickTarget(now: Long) {
        val maxX = (screenW - petW).coerceAtLeast(0).toFloat()
        val minY = topInset + dp(8f)
        val maxY = (screenH - petH - bottomInset - dp(8f)).coerceAtLeast(minY)
        val reach = dp(230f)

        val nx = (posX + rnd.nextFloat() * reach * 2 - reach).coerceIn(0f, maxX)
        val ny = (posY + rnd.nextFloat() * reach * 2 - reach).coerceIn(minY, maxY)

        if (hypot(nx - posX, ny - posY) < dp(45f)) {
            idleUntil = now + 3000L
            return
        }
        targetX = nx
        targetY = ny
        walking = true
    }

    private fun applyPetPosition() {
        petParams.x = posX.toInt()
        petParams.y = posY.toInt()
        val view = pet ?: return
        runCatching { windowManager.updateViewLayout(view, petParams) }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun dpInt(value: Float) = dp(value).toInt()

    // -------------------------------------------------------------------- touch

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragged = false
                walking = false
                pet?.walking = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downRawX) > 4 * resources.displayMetrics.density ||
                    abs(event.rawY - downRawY) > 4 * resources.displayMetrics.density
                ) {
                    dragged = true
                }
                if (dragged) {
                    posX = (posX + (event.rawX - lastRawX))
                        .coerceIn(0f, (screenW - petW).toFloat())
                    posY = (posY + (event.rawY - lastRawY))
                        .coerceIn(topInset.toFloat(), (screenH - petH - bottomInset).toFloat())
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    applyPetPosition()
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragged) toggleChat()
                targetX = posX
                targetY = posY
                true
            }

            else -> false
        }
    }

    // --------------------------------------------------------------------- chat

    private fun toggleChat() {
        if (inputBar != null) {
            closeChat()
        } else {
            attentive = true
            walking = false
            pet?.walking = false
            showInputBar()
        }
    }

    private fun closeChat() {
        hideKeyboard()
        removeInputBar()
        hideBubble()
        attentive = false
        pet?.mood = PetView.Mood.NEUTRAL
        idleUntil = SystemClock.uptimeMillis() + 1500L
    }

    private fun showInputBar() {
        if (inputBar != null) {
            inputField?.requestFocus()
            showKeyboard()
            return
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(10f), dpInt(4f), dpInt(4f), dpInt(4f))
            setBackgroundResource(R.drawable.overlay_panel)
        }

        val field = EditText(this).apply {
            hint = "Say something…"
            setSingleLine(false)
            maxLines = 3
            textSize = 14f
            setTextColor(0xFFE8ECF4.toInt())
            setHintTextColor(0xFF98A2B6.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage()
                    true
                } else {
                    false
                }
            }
        }

        val send = TextView(this).apply {
            text = "Send"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF7DD3FC.toInt())
            setPadding(dpInt(12f), dpInt(10f), dpInt(12f), dpInt(10f))
            setOnClickListener { sendMessage() }
        }

        bar.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send)

        val params = WindowManager.LayoutParams(
            dpInt(252f),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable on purpose so it can receive the IME, but not modal, so the
            // rest of the screen keeps working while it is open.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        inputParams = params
        inputBar = bar
        inputField = field
        positionInputBar()

        if (!runCatching { windowManager.addView(bar, params) }.isSuccess) {
            inputBar = null
            inputField = null
            inputParams = null
            attentive = false
            return
        }

        field.requestFocus()
        handler.postDelayed({ showKeyboard() }, 80L)
    }

    private fun positionInputBar() {
        val params = inputParams ?: return
        val bar = inputBar ?: return
        val width = params.width
        params.x = (posX + petW / 2f - width / 2f).toInt()
            .coerceIn(dpInt(6f), (screenW - width - dpInt(6f)).coerceAtLeast(dpInt(6f)))
        params.y = (posY + petH + dpInt(6f)).toInt()
            .coerceAtMost(screenH - bottomInset - dpInt(60f))
        runCatching { windowManager.updateViewLayout(bar, params) }
    }

    private fun removeInputBar() {
        inputBar?.let { bar -> runCatching { windowManager.removeView(bar) } }
        inputBar = null
        inputField = null
        inputParams = null
    }

    private fun sendMessage() {
        val field = inputField ?: return
        val text = field.text.toString().trim()
        if (text.isEmpty()) return

        field.setText("")
        hideKeyboard()
        Memory.append(this, "user", text)
        attentive = true
        pet?.mood = PetView.Mood.THINKING

        AssistantBrain.reply(this, currentAppLabel) { ok, reply ->
            handler.post {
                if (ok) Memory.append(this, "assistant", reply)
                showBubble(reply)
                pet?.mood = PetView.Mood.TALKING
                handler.postDelayed({
                    pet?.mood = PetView.Mood.NEUTRAL
                    // Stay put for a moment after speaking, then wander off again
                    // unless the user is already typing the next message.
                    handler.postDelayed({
                        val typing = inputField?.text?.isNotEmpty() == true
                        if (!typing) closeChat()
                    }, 2600L)
                }, 1400L)
            }
        }
    }

    private fun showBubble(text: String) {
        hideBubble()

        val view = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFFE8ECF4.toInt())
            setLineSpacing(dp(2f), 1f)
            setPadding(dpInt(12f), dpInt(9f), dpInt(12f), dpInt(9f))
            maxWidth = dpInt(232f)
            setBackgroundResource(R.drawable.overlay_panel)
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(dpInt(240f), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = view.measuredWidth.coerceAtLeast(dpInt(48f))
        val height = view.measuredHeight.coerceAtLeast(dpInt(26f))

        var x = (posX + petW / 2f - width / 2f).toInt()
        x = x.coerceIn(dpInt(6f), (screenW - width - dpInt(6f)).coerceAtLeast(dpInt(6f)))

        val preferred = (posY - height - dpInt(8f)).toInt()
        // Above him if there is room, otherwise below.
        val y = if (preferred >= topInset) {
            preferred
        } else {
            (posY + petH + dpInt(8f)).toInt()
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        if (runCatching { windowManager.addView(view, params) }.isSuccess) {
            bubble = view
        }
    }

    private fun hideBubble() {
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
    }

    private fun showKeyboard() {
        val field = inputField ?: return
        field.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val field = inputField ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    // ------------------------------------------------------------------- window

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            paused = intent?.action == Intent.ACTION_SCREEN_OFF
            if (paused) pet?.walking = false
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Assistant", NotificationManager.IMPORTANCE_LOW).apply {
                description = Persona.NAME + " stays on your screen"
                setShowBadge(false)
            }
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Assistant")
            .setContentText(Persona.NAME + " is on your screen")
            .setSmallIcon(R.drawable.ic_pip)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pip),
                    "Stop",
                    stopService
                ).build()
            )
            .build()
    }
}
