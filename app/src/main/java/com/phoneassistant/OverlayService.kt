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
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.abs
import kotlin.random.Random

/**
 * Keeps the character on screen as an overlay window that is exactly the size of
 * the character, so taps anywhere else still reach the app underneath.
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

        fun setMood(mood: PetView.Mood) {
            instance?.pet?.mood = mood
        }

        fun flashMood(mood: PetView.Mood, millis: Long) {
            val service = instance ?: return
            service.pet?.mood = mood
            service.handler.postDelayed({ service.pet?.mood = PetView.Mood.NEUTRAL }, millis)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var pet: PetView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(System.currentTimeMillis())

    private var screenW = 1
    private var screenH = 1
    private var speed = 0f
    private var targetX = 0f
    private var walking = false
    private var idleUntil = 0L
    private var paused = false
    private var watchCountdown = 0L

    private var downRawX = 0f
    private var lastRawX = 0f
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
        var navInset = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            screenW = metrics.bounds.width()
            screenH = metrics.bounds.height()
            navInset = metrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        }

        val petW = (64 * dp).toInt()
        val petH = (86 * dp).toInt()
        speed = 62 * dp
        val restY = screenH - petH - navInset - (4 * dp).toInt()

        params = WindowManager.LayoutParams(
            petW,
            petH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW * 0.6f).toInt()
            y = restY
        }

        val view = PetView(this)
        view.setOnTouchListener(touchListener)
        pet = view

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            // Permission was revoked between the check and here.
            stopSelf()
            return
        }

        targetX = params.x.toFloat()
        idleUntil = SystemClock.uptimeMillis() + 1200
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
        pet?.let { view -> runCatching { windowManager.removeView(view) } }
        pet = null
        runCatching { unregisterReceiver(screenReceiver) }
        instance = null
        running = false
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!paused) step()
            handler.postDelayed(this, frameMs)
        }
    }

    private fun step() {
        val view = pet ?: return
        val now = SystemClock.uptimeMillis()

        if (walking) {
            val dx = targetX - params.x
            val perFrame = speed * frameMs / 1000f
            if (abs(dx) <= perFrame) {
                params.x = targetX.toInt()
                walking = false
                view.walking = false
                idleUntil = now + 1500L + rnd.nextLong(4000L)
                push()
            } else {
                params.x = (params.x + if (dx > 0) perFrame else -perFrame).toInt()
                view.facingRight = dx > 0
                view.walking = true
                push()
            }
        } else if (now > idleUntil) {
            val maxX = (screenW - params.width).coerceAtLeast(0)
            val next = rnd.nextInt(0, maxX + 1).toFloat()
            if (abs(next - params.x) < params.width * 0.6f) {
                idleUntil = now + 1000L
            } else {
                targetX = next
                walking = true
            }
        }

        watchCountdown += frameMs
        if (watchCountdown >= 15_000L) {
            watchCountdown = 0L
            currentAppLabel = if (AppWatcher.hasPermission(this)) AppWatcher.currentAppLabel(this) else null
        }
    }

    private fun push() {
        val view = pet ?: return
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                lastRawX = event.rawX
                dragged = false
                walking = false
                pet?.walking = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downRawX) > 4 * resources.displayMetrics.density) dragged = true
                if (dragged) {
                    params.x = (params.x + (event.rawX - lastRawX)).toInt()
                    params.y = params.y
                    lastRawX = event.rawX
                    push()
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (dragged) {
                    targetX = params.x.toFloat()
                } else {
                    openChat()
                }
                true
            }

            else -> false
        }
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

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
