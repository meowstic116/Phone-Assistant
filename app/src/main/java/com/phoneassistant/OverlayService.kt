package com.phoneassistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Owns the overlay windows: the character, a speech bubble or thinking indicator above
 * him, and an input bar below. Panels track his position, so they follow him around.
 */
class OverlayService : Service(), PropHost {

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        /** Label of the app the user is in, refreshed every few seconds. */
        @Volatile
        var currentAppLabel: String? = null
            private set

        private var instance: OverlayService? = null
        val stopping: Boolean get() = instance?.ending == true
        internal var onPowerStateChanged: (() -> Unit)? = null

        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.phoneassistant.STOP"

        /** Where the cancel chip's last dragged position is kept. */
        private const val PREFS = "chip"

        /** How faint the chip goes while a step is acting, so that it is plainly out of play. */
        private const val DUCK_ALPHA = 0.35f

        /**
         * How long a step waits for the main thread to move the overlay's windows aside.
         * Bounded, because a main thread that has stopped running must not hang a job — and
         * short, because every step pays it twice.
         */
        private const val DUCK_WAIT_MS = 300L

        /** How long a model reply keeps the floor after it lands, over any toy line. */
        private const val REPLY_HOLD_MS = 4_000L

        /** How long a spoken line keeps him standing there before he gets on with things. */
        private const val SPEAKING_PAUSE_MS = 3_500L
        private const val BUBBLE_SPEECH_WAIT_MS = 2_500L

        /**
         * Height, in dp, of the row a keyboard sits under: a chat's message box, a comment
         * field, a search bar. He keeps this much room between himself and any keyboard that
         * is not his own, so the box being typed into stays visible instead of being stood
         * on — clearing the keyboard alone leaves his feet on the keyboard's top edge, which
         * is exactly where that row begins.
         */
        private const val INPUT_ROW_DP = 60f

        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) != null
        } catch (e: IllegalStateException) {
            Diag.caught("start Pip", e)
            false
        } catch (e: SecurityException) {
            Diag.caught("start Pip", e)
            false
        }

        /** Main-screen stop; the notification action reaches the same exit path. */
        fun stop(ctx: Context) {
            val service = instance
            if (service != null) service.beginExit()
            else ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        /** Show the three dots, for messages sent from the history screen. */
        fun beginThinking() {
            instance?.let { service ->
                service.beginReply()
                service.startThinking()
            }
        }

        /** History screen opened: hold still and step up clear of the input row. */
        fun beginHistoryAttention() {
            instance?.enterAttention(moveUp = true)
        }

        fun endHistoryAttention() {
            instance?.leaveAttention()
        }

        /** Say something in the overlay bubble, for replies sent from the history screen. */
        fun speak(context: Context, text: String) {
            val overlay = instance?.takeUnless { it.ending }
            if (overlay != null) overlay.modelSays(text)
            else if (VoiceSettings.enabled(context)) PipVoice.reply(context, text)
        }

        /** Main-screen buttons: put one of Pip's toys on the screen. */
        fun bringOut(toy: Toy): Boolean {
            val service = instance?.takeUnless { it.ending } ?: return false
            // Whether toys are allowed is decided in one place; this only adds his protest
            // when the reason is the break.
            val outcome = Toys.summon(service, toy)
            if (!outcome.ok && Quiet.active(service)) {
                service.sayAloud("I'm on a break. you asked.")
            }
            return outcome.ok
        }

        /** Main-thread update after a switch changes in Pip settings. */
        fun behaviorSettingsChanged() {
            instance?.applyBehaviorSettings()
        }

        fun removeDisplayedPicture(id: String) {
            val service = instance ?: return
            service.propsOnScreen().filterIsInstance<Painting>()
                .filter { it.picture.id == id }.forEach(service::dropProp)
        }

        /** What is out on the screen right now, for the prompt. Empty when nothing is. */
        fun toysOnStage(): String = instance?.let { Toys.summary(it) } ?: ""

        /** Dispatch from chat history, but never accept a late reply for an older message. */
        fun runPhoneJob(task: String, original: String, turn: Long): Boolean =
            instance?.startJob(task, original, turn) ?: false

        fun clearConversationFeedback() {
            Agent.cancel()
            instance?.let {
                it.awaitingReply = false
                it.hideThinking()
                it.hideBubble()
                it.leaveAttention()
            }
            PipVoice.stop()
        }

        /** How often the floating readout repaints. Fast enough to watch a keyboard arrive. */
        private const val HUD_REFRESH_MS = 250L

        /** How often the cancel chip checks that there is still a job to cancel. */
        private const val JOB_REFRESH_MS = 300L

        fun status(): Status? = instance?.status()

        /**
         * The compact readout, as a window of the overlay so it stays over other apps. False
         * when he is not running: the panel is one of his windows, not a separate app.
         */
        fun showDiagnosticsHud(): Boolean = instance?.let { it.showHud(); true } ?: false

        fun hideDiagnosticsHud(): Boolean = instance?.let { it.hideHud(); true } ?: false

        fun diagnosticsHudShowing(): Boolean = instance?.hud != null

        /**
         * Toy business for a message sent from the history screen, so asking him there does
         * exactly what asking him in the bubble does. Returns the note for his prompt.
         */
        fun reactTo(text: String): String? = instance?.let { Toys.reactTo(it, text) }

        /** Main-screen button: send Pip on a break of the given length. */
        fun startBreak(ms: Long): Boolean {
            val service = instance ?: return false
            Toys.clearAll(service)
            service.resetToIdle()
            Quiet.start(service, ms)
            service.feel(PetView.Mood.ANNOYED, 4000L)
            service.sayAloud(
                "break time. *puts the tools down very deliberately* I'll be here, " +
                    "being patient at you."
            )
            return true
        }

        /** Main-screen button: cut a toy break short. */
        fun endBreak(): Boolean {
            val service = instance ?: return false
            Quiet.clear(service)
            service.sayAloud("break's over. *stretches* I had ideas, actually.")
            return true
        }

        /** Put everything away. */
        fun tidyUp(): Boolean {
            val service = instance ?: return false
            Toys.clearAll(service)
            // Dropping the props does not touch his walk, so tidy up resets it itself: the
            // place he was heading for is gone with them.
            service.resetToIdle()
            return true
        }
    }

    /**
     * The live numbers for the diagnostics screen and its floating panel: what the keyboard
     * reports, where he is against it, and what is out. Null when he is not running.
     */
    data class Status(
        val imeReportedPx: Int,
        val keyboardUp: Boolean,
        val rememberedPx: Int,
        val ceilingPx: Int,
        /** The height the placement is worked out from, and the keyboard top it implies. */
        val keyboardUsedPx: Int,
        val keyboardTopPx: Int,
        /** The gap between the window's own y and the display's, as measured. */
        val windowOffsetPx: Int,
        /** Where the chat box is, top and bottom. Top is -1 when it is not open. */
        val boxTopPx: Int,
        val boxBottomPx: Int,
        val petY: Int,
        val petHeightPx: Int,
        val props: List<String>,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val bottomInsetPx: Int,
        /** Whether the character is drawing at all, and how many frames a second he really is. */
        val drawing: Boolean,
        val drawsPerSecond: Int
    )

    private lateinit var windowManager: WindowManager
    private lateinit var petParams: WindowManager.LayoutParams
    private var pet: PetView? = null

    private class VoicedReply(val text: String) {
        val presentation = ReplyBubbleState(text.length, spoken = true)
    }
    private var voicedReply: VoicedReply? = null
    private var bubble: ReplyBubbleView? = null
    private var speechBubbleFallback: Runnable? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private data class BubbleExit(val view: ReplyBubbleView, val animation: ValueAnimator)
    private var bubbleExit: BubbleExit? = null
    private var bubbleHideAt = 0L

    private var thinking: ThinkingView? = null
    private var thinkingParams: WindowManager.LayoutParams? = null

    private var inputBar: LinearLayout? = null
    private var inputField: EditText? = null
    private var inputParams: WindowManager.LayoutParams? = null

    /** The floating diagnostics readout, while it has been asked for. */
    private var hud: DiagHud? = null
    private var hudParams: WindowManager.LayoutParams? = null

    /** The cancel chip, while a phone job is running. */
    private var jobBar: JobBar? = null
    private var jobBarParams: WindowManager.LayoutParams? = null

    /**
     * Where the chip was left after a drag, as a fraction of the room it has to move in. Null
     * means it has never been moved and sits in its corner. Stored as a fraction rather than
     * pixels so the same spot means the same corner after a rotation.
     */
    private var chipSpot: Pair<Float, Float>? = null

    /**
     * True while a step is touching the phone. The chip is not touchable then, so a press
     * meant for the app underneath can never land on the way out of the job instead.
     */

    /** The same for Pip himself: he is a touchable window too, and stands in a corner. */

    /** True for the moment a step is pressing, so anything created meanwhile starts aside. */
    private var overlayDucked = false

    /** The same for the floating diagnostics panel and for the toys. */

    /** True while the diagnostics panel is being dragged: it keeps its touch then. */
    private var hudHeld = false

    /** True while a finger is on the chip, so the ticker does not drag it back mid-drag. */
    private var chipDragging = false
    private var chipMoved = false
    private var chipDownX = 0f
    private var chipDownY = 0f
    private var chipLeft = 0
    private var chipTop = 0

    /** Where the panel was when the finger went down, so a drag follows it exactly. */
    private var hudDownX = 0f
    private var hudDownY = 0f
    private var hudLeft = 0
    private var hudTop = 0

    /** Measured height of the keyboard in pixels, 0 until it has been seen. */
    private var keyboardHeightPx = 0

    /** True while a keyboard is showing, in this app or any other. */
    private var keyboardUp = false
    private var keyboardWatch = 0L

    /**
     * When a hold on standing still ends, kept here rather than read from [Still] every frame:
     * this is checked by the walk several times a second, and a SharedPreferences read at that
     * rate is a pointless cost. 0 means he is free to wander.
     */
    private var stillUntil = 0L

    /** Bumped by every mood, so only the newest one's timer puts his face back to neutral. */
    private var moodSeq = 0

    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(System.currentTimeMillis())

    /**
     * A reused copy of [props] for the frame loop. A toy can take itself off the screen from
     * inside its own frame, so the loop cannot walk the live list — but a fresh list of them
     * every frame is a list a second at thirty frames a second, for nothing.
     */
    private val frameScratch = ArrayList<Prop>(8)

    /** Counts down to the next look for a toy that has outstayed its welcome. */
    private var retireCheck = 1_000L

    /** Everything Pip has put on the screen: stands, paintings, games, plants. */
    private val props = mutableListOf<Prop>()

    /** Windows that have left the stage and are still fading off the screen. */
    private val fading = mutableListOf<View>()

    /** Called when he finishes a walk he was sent on by a toy. */
    private var arrive: (() -> Unit)? = null

    /** A spot a toy wants him to stay at, or null when he is free to wander. */
    private var post: Pair<Float, Float>? = null

    /** The prop he is serving: it keeps its place even when the chat box is in the way. */
    private var postOwner: Prop? = null

    /**
     * He was moved off his post by a keyboard and has to go back to it, even when there is
     * no work left owed there. Separate from [postArrival], which means a pour is waiting.
     */
    private var postReturn = false

    /** A model reply is expected, or has just landed. Its words outrank a toy's. */
    private var awaitingReply = false
    private var replyAskedAt = 0L
    private var replyUntil = 0L

    /**
     * One-shot work waiting at that post — a lemonade to pour. Kept here rather than on the
     * walk, because a walk can be interrupted (a tap, a chat) and the promise survives it.
     */
    private var postArrival: (() -> Unit)? = null

    /** How long until he gets bored and starts something by himself. */
    private var initiativeIn = 200_000L
    private var wanderAllowed = true
    private var spontaneousToysAllowed = true



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

    /** True during a walk a toy sent him on, which a spoken line must not cancel. */
    private var toyWalk = false
    private data class JobWalk(val turn: Long, val gate: JobApproachGate, var path: JobApproach? = null)
    private var jobWalk: JobWalk? = null
    private var relocating = false
    private var idleUntil = 0L
    private var attentive = false
    private var paused = false
    private var watchCountdown = 0L

    private var petDrag: WindowDrag? = null
    private var ending = false
    private var powerAnimator: ValueAnimator? = null
    private var toyReachAnimator: ValueAnimator? = null
    private val closeAfterExit = Runnable { finishExit() }

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

        readDisplayBounds()
        keyboardHeightPx = Keyboard.rememberedPx(this)
        stillUntil = Still.until(this)
        chipSpot = loadChipSpot()

        posX = (screenW - petW) * 0.55f
        posY = (screenH - petH - bottomInset) * 0.62f

        // A restarted service should know whether anyone is actually looking.
        paused = getSystemService(PowerManager::class.java)?.isInteractive != true

        // Every start, extension and early end reconciles the timer with the new deadline.
        Quiet.onChanged = { settleBreak() }
        settleBreak()

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
        petDrag = createPetDrag(view)
        view.setOnTouchListener(petDrag)
        // The screen may already be off when he starts — a restarted service, or a start from
        // the notification — and no broadcast is coming to say so, so the first answer comes
        // from the power manager rather than from the next screen event. Set before the view
        // is attached, so there is not even one frame drawn into a dark display.
        view.animating = !paused
        val entrance = canAnimatePower()
        if (entrance) view.powerPose = PipTransition.pose(entering = true, fraction = 0f)
        pet = view

        val added = runCatching { windowManager.addView(view, petParams) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }

        if (entrance) playPowerAnimation(view, entering = true)
        onPowerStateChanged?.invoke()
        idleUntil = SystemClock.uptimeMillis() + 2500L
        applyBehaviorSettings()
        registerScreenReceiver()
        ImeWatch.onChange = { ime -> handler.post { onImeChanged(ime) } }
        Still.onChanged = { handler.post { refreshStill() } }
        if (!paused) handler.post(tick)
    }

    private fun readDisplayBounds() {
        val density = resources.displayMetrics.density
        screenW = resources.displayMetrics.widthPixels
        screenH = resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            screenW = metrics.bounds.width()
            screenH = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topInset = insets.top
            bottomInset = insets.bottom
        }
        petW = (64 * density).toInt().coerceAtLeast(1)
        petH = (86 * density).toInt().coerceAtLeast(1)
        speed = 55 * density
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ending || pet == null) return
        clearBubbleExit()
        readDisplayBounds()
        petDrag?.cancel()
        cancelToyHop()
        cancelGardenVisit()
        val bottom = (screenH - petH - bottomInset).toFloat()
        val right = (screenW - petW).toFloat()
        posX = OverlayBounds.clamp(posX, 0f, right)
        posY = OverlayBounds.clamp(posY, topInset.toFloat(), bottom)
        targetX = OverlayBounds.clamp(targetX, 0f, right)
        targetY = OverlayBounds.clamp(targetY, topInset.toFloat(), bottom)
        petParams.width = petW
        petParams.height = petH
        props.toList().forEach { it.moveTo(it.posX, it.posY) }
        applyPetPosition()
        positionJobBar()
        watchKeyboard()
    }

    /**
     * Height of the keyboard, whether it is ours or another app's. Zero when none is up.
     *
     * The accessibility service is the one trusted source. A floating overlay is only ever
     * sent an IME inset for a keyboard it raised itself, so nothing inside this service can
     * see another app's typing, and a probe window that pretends otherwise is just a guess.
     * His own box uses it too, rather than the height remembered from last time: the box is
     * the one thing that cannot be behind the keyboard, and a remembered figure is a figure
     * from a different keyboard.
     */
    private fun imeHeight(): Int = ImeWatch.heightPx

    /** Read by the diagnostics screen; every number here is one the app actually uses. */
    private fun status(): Status = Status(
        imeReportedPx = imeHeight(),
        keyboardUp = keyboardUp,
        rememberedPx = keyboardHeightPx,
        ceilingPx = keyboardCeiling().toInt(),
        keyboardUsedPx = keyboardPx(),
        keyboardTopPx = (screenH - keyboardPx()).coerceAtLeast(0),
        windowOffsetPx = windowOffset.toInt(),
        // In the display's own coordinates, so they can be read against the keyboard's top.
        boxTopPx = inputParams?.y?.plus(windowOffset.toInt()) ?: -1,
        boxBottomPx = inputParams?.y
            ?.plus(windowOffset.toInt())
            ?.plus(inputBar?.height ?: 0) ?: -1,
        petY = posY.toInt(),
        petHeightPx = petH,
        props = props.toList().map { prop -> prop.javaClass.simpleName },
        screenWidthPx = screenW,
        screenHeightPx = screenH,
        bottomInsetPx = bottomInset,
        // Measured inside the view's own onDraw, not inferred from the mood or the screen
        // state: the point of showing it is to check what actually happens.
        drawing = pet?.animating == true,
        drawsPerSecond = pet?.drawsPerSecond ?: 0
    )

    // ------------------------------------------------------- floating diagnostics

    /**
     * Small, live, and over everything. The Diagnostics screen is an activity, so it can only
     * be read while it is in front — and the keyboard it is describing closes as soon as you
     * leave the app that owns it. This panel is a window of the overlay itself, so it keeps
     * reading while you type somewhere else.
     */
    private fun showHud() {
        if (hud != null) return
        val view = DiagHud(this).apply { onClose = { hideHud() } }
        val width = dpInt(190f)
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable so it can be dragged, but never focusable: it must not take the
            // keyboard away from the app whose keyboard it is measuring. A panel opened
            // while a step is pressing starts out of the way like everything else.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (overlayDucked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Top right, where it covers the least of a keyboard in progress.
            x = (screenW - width - dpInt(10f)).coerceAtLeast(0)
            y = topInset + dpInt(10f)
        }
        view.setOnTouchListener(hudTouch)
        if (!runCatching { windowManager.addView(view, params) }.isSuccess) return
        hud = view
        hudParams = params
        if (overlayDucked) setHudDucked(true)
        hudHeld = false
        // Painted straight away, so it never appears hollow for a quarter of a second.
        renderHud()
        handler.postDelayed(hudTicker, HUD_REFRESH_MS)
    }

    private fun hideHud() {
        handler.removeCallbacks(hudTicker)
        hud?.let { view -> runCatching { windowManager.removeView(view) } }
        hud = null
        hudParams = null
        // The next panel is a new window with new params, so it starts unducked.
        hudHeld = false
    }

    private val hudTicker = object : Runnable {
        override fun run() {
            if (hud == null) return
            renderHud()
            handler.postDelayed(this, HUD_REFRESH_MS)
        }
    }

    private fun renderHud() {
        hud?.render(status(), ImeWatch.enabled(this), ImeWatch.lastSeenPx, ImeWatch.lastSeenAt)
    }

    /**
     * Dragging is measured from where the finger went down rather than by adding each move, so
     * a dropped frame cannot make the panel drift away from the finger that is holding it. A
     * tap that goes nowhere is not a click: the only clickable part is the × in the header.
     */
    private val hudTouch = View.OnTouchListener { _, event ->
        val params = hudParams ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hudDownX = event.rawX
                hudDownY = event.rawY
                hudLeft = params.x
                hudTop = params.y
                hudHeld = true
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (overlayDucked) return@OnTouchListener true
                // The window has no height until it is laid out; its real one is only known
                // after the first frame.
                val height = hud?.height?.takeIf { it > 0 } ?: params.height
                params.x = (hudLeft + (event.rawX - hudDownX)).toInt()
                    .coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                params.y = (hudTop + (event.rawY - hudDownY)).toInt()
                    .coerceIn(topInset, (screenH - height - bottomInset).coerceAtLeast(topInset))
                hud?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hudHeld = false
                true
            }

            else -> false
        }
    }

    /** Called when the accessibility service sees a keyboard appear, move or go. */
    private fun onImeChanged(ime: Int) {
        if (ending) return
        keyboardUp = ime > 0
        if (ime == 0) {
            // The keyboard has gone, so the box can go back down to his feet.
            positionInputBar()
            return
        }
        if (abs(ime - keyboardHeightPx) > dpInt(2f)) {
            keyboardHeightPx = ime
            Keyboard.remember(this, ime)
        }
        // Placed again against the new height, in case he is already high enough to stay put.
        positionInputBar()
        walkClearOfKeyboard()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) beginExit()
        return if (ending) START_NOT_STICKY else START_STICKY
    }

    private fun canAnimatePower(): Boolean = screenUsable() && ValueAnimator.areAnimatorsEnabled()

    private fun playPowerAnimation(view: PetView, entering: Boolean) {
        cancelPowerAnimation()
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (entering) PipTransition.ENTRANCE_MS else PipTransition.EXIT_MS
            interpolator = LinearInterpolator()
            addUpdateListener { view.powerPose = PipTransition.pose(entering, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    if (powerAnimator !== animator) return
                    powerAnimator = null
                    if (entering) view.powerPose = null else finishExit()
                }
            })
        }
        powerAnimator = animation
        animation.start()
    }

    private fun cancelPowerAnimation() {
        val old = powerAnimator
        powerAnimator = null
        old?.cancel()
    }

    private fun beginExit() {
        if (ending) return
        ending = true
        onPowerStateChanged?.invoke()
        // Work stops immediately, not after the decorative exit has finished.
        PipVoice.stop()
        Agent.cancel()
        finishJobWalk(false)
        ChatTurns.begin()
        petDrag?.cancel()
        cancelToyHop()
        cancelGardenVisit()
        prepareExitUi()
        val view = pet
        if (view == null || !view.isAttachedToWindow || !canAnimatePower()) {
            finishExit()
            return
        }
        // A long system animation scale or a missing completion callback must not keep
        // the foreground service around. The screen-off receiver also finishes at once.
        handler.postDelayed(closeAfterExit, 850L)
        playPowerAnimation(view, entering = false)
    }

    private fun prepareExitUi() {
        handler.removeCallbacks(tick)
        finishToyFlights()
        hideKeyboard()
        removeInputBar()
        hideJobBar()
        doomscrollJob = false
        automaticDoomscrollJob = false
        pet?.showDoomscrollCue(DoomscrollMotion.Cue.CLEAR)
        hideThinking()
        hideBubble()
        hideHud()
        props.toList().forEach(::dropProp)
        resetToIdle()
        pet?.let { view ->
            if (!::petParams.isInitialized) return@let
            petParams.flags = petParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            petParams.alpha = 1f
            runCatching { windowManager.updateViewLayout(view, petParams) }
        }
    }

    private fun finishExit() {
        handler.removeCallbacks(closeAfterExit)
        cancelPowerAnimation()
        pet?.powerPose = PipTransition.pose(entering = false, fraction = 1f)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        PipVoice.stop()
        cancelPowerAnimation()
        cancelToyReach()
        petDrag?.cancel()
        petDrag = null
        props.forEach { it.cancelDrag() }
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        hideThinking()
        removeInputBar()
        hideJobBar()
        // The panel is its own window and nothing else owns it, so it goes with the service.
        hud?.let { view -> runCatching { windowManager.removeView(view) } }
        hud = null
        hudParams = null
        props.toList().forEach { prop -> runCatching { windowManager.removeView(prop) } }
        props.clear()
        fading.forEach { view -> runCatching { windowManager.removeView(view) } }
        fading.clear()
        // Nothing survives the service, including work a toy left at his post.
        postOwner = null
        post = null
        postArrival = null
        postReturn = false
        // A job outlives no service: its next step would be acting on a screen nothing is
        // watching any more.
        Agent.cancel()
        finishJobWalk(false)
        ChatTurns.begin()
        ImeWatch.onChange = null
        keyboardUp = false
        pet?.let { view -> runCatching { windowManager.removeView(view) } }
        pet = null
        runCatching { unregisterReceiver(screenReceiver) }
        Quiet.onChanged = null
        Still.onChanged = null
        instance = null
        running = false
        onPowerStateChanged?.invoke()
        super.onDestroy()
    }

    // ------------------------------------------------------------------- props

    override fun stageContext(): Context = this
    override fun stageWidth(): Int = screenW
    override fun stageHeight(): Int = screenH
    override fun stageTop(): Int = topInset
    override fun stageBottom(): Int = bottomInset

    override fun toysFloor(): Float {
        // Measured here rather than from the last tick: a toy placed a moment after the
        // keyboard appeared would otherwise land under it.
        val ime = imeHeight()
        val bottom =
            if (ime > bottomInset + dpInt(80f)) (screenH - ime).toFloat()
            else (screenH - bottomInset).toFloat()
        return bottom - dp(8f)
    }
    override fun px(value: Float): Float = dp(value)
    override fun petWidth(): Int = petW
    override fun petHeight(): Int = petH
    override fun propsOnScreen(): List<Prop> = props.toList()

    /** Same thing a tap on him does, for toys whose windows cover him. */
    override fun pokePet() = toggleChat()

    /**
     * A line from a toy. These are the fallback: while his own answer is on the way, or has
     * just landed, the toys keep quiet, so the two never talk over each other.
     */
    override fun say(line: String) {
        if (replyHasFloor()) return
        sayAloud(line)
    }

    /** True while the model is the one who should be speaking. */
    private fun replyHasFloor(): Boolean {
        val now = SystemClock.uptimeMillis()
        // The wait has a ceiling: a request that never answers must not mute the toys all day.
        return voicedReply != null || (awaitingReply && now - replyAskedAt < 15_000L) || now < replyUntil
    }

    /** From here until the reply lands, only the model speaks. */
    private fun beginReply() {
        hideBubble()
        awaitingReply = true
        replyAskedAt = SystemClock.uptimeMillis()
    }

    private fun modelSays(text: String) {
        awaitingReply = false
        replyUntil = SystemClock.uptimeMillis() + REPLY_HOLD_MS
        sayAloud(text, finalReply = true)
    }

    override fun feel(mood: PetView.Mood, ms: Long) {
        val view = pet ?: return
        view.mood = mood
        val mine = ++moodSeq
        if (ms > 0L) {
            // Only the newest mood may clear itself: a smug 2.5 s stretch followed by an
            // annoyed 4 s one used to be cut short by the first one's timer.
            handler.postDelayed({ if (mine == moodSeq) view.mood = PetView.Mood.NEUTRAL }, ms)
        }
    }

    override fun petHoldAt(owner: Prop, x: Float, y: Float, done: () -> Unit) {
        postOwner = owner
        post = x to y
        postArrival = done
        // A new post is a walk he is starting, not one he owes.
        postReturn = false
        approachPost(x, y)
    }

    override fun petIsNear(x: Float, y: Float, within: Float): Boolean =
        hypot(posX + petW / 2f - x, posY + petH / 2f - y) <= within

    override fun petOnScreen(): Boolean = pet != null

    override fun petCentreX(): Float = posX + petW / 2f

    /** Heads for the post; whatever is waiting there runs on arrival. */
    private fun approachPost(x: Float, y: Float) {
        petWalkTo(x, y) { firePostArrival() }
    }

    /**
     * Does the work waiting at his post, once, on arrival. The work is taken before it runs,
     * so a second arrival cannot pour the same glass twice — and being at the post already is
     * not a separate route to the pour, just a walk of one step.
     */
    private fun firePostArrival() {
        // He is at the post now, so there is nothing left to walk back for.
        postReturn = false
        val work = postArrival ?: return
        postArrival = null
        work()
    }

    override fun isPostOwner(prop: Prop): Boolean = prop === postOwner

    override fun petRelease() {
        postOwner = null
        post = null
        postArrival = null
        postReturn = false
        idleUntil = SystemClock.uptimeMillis() + 1500L
    }

    /**
     * Puts him back on his own feet with nothing outstanding: no post, no walk, no arrival
     * still owed to a toy, not sitting, not paying attention. Clearing the screen has to do
     * this itself, because a dropped prop takes its window with it but leaves every one of
     * those exactly as it found them.
     *
     * [petRelease] sets the idle timer too, so the only target that matters is the next one:
     * every walk writes its own before anything reads it.
     */
    private fun resetToIdle() {
        petRelease()
        // A chat bar he opened outranks this: it holds his attention itself, and clears it
        // when it closes.
        if (inputBar == null) attentive = false
        walking = false
        toyWalk = false
        relocating = false
        arrive = null
        pet?.walking = false
        pet?.sitting = false
    }

    private data class ToyHop(
        val owner: Prop, val started: Long, val duration: Long,
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val lift: Float,
        val landed: () -> Unit
    )

    private var toyHop: ToyHop? = null
    private var raisedFor: Prop? = null

    private data class GardenVisit(
        val owner: Prop, val ownerX: Float, val ownerY: Float, val started: Long, val travelMs: Long,
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val facesRight: Boolean,
        val grip: DragMotion.Point, val progress: (Float?) -> Unit, val watered: () -> Unit
    )
    private var gardenVisit: GardenVisit? = null

    private fun toyActionBlocked(owner: Prop): Boolean = owner.movingByUser || owner !in props || petDrag?.moving == true || attentive ||
        relocating || post != null || inputBar != null || keyboardUp || overlayDucked || Agent.running() || standingStill()

    override fun petWaterPlant(owner: Prop, gripX: Float, gripY: Float, facesRight: Boolean,
                               progress: (Float?) -> Unit, watered: () -> Unit): Boolean {
        if (pet == null || gardenVisit != null || toyHop != null || toyWalk || toyActionBlocked(owner) || !screenUsable()) return false
        val grip = DragMotion.Point(gripX, gripY)
        val stance = WateringPose.stance(grip, facesRight, dp(1f))
        val tx = (stance.x - petW / 2f).coerceIn(0f, (screenW - petW).coerceAtLeast(0).toFloat())
        val minY = topInset.toFloat()
        val ty = (stance.y - petH + dp(2f)).coerceIn(minY, (screenH - petH - bottomInset).toFloat().coerceAtLeast(minY))
        val distanceDp = hypot(tx - posX, ty - posY) / dp(1f)
        val travel = if (distanceDp < 3f) 0L else (distanceDp * 5.4f).toLong().coerceIn(190L, 2_100L)
        gardenVisit = GardenVisit(owner, owner.posX, owner.posY, SystemClock.uptimeMillis(), travel,
            posX, posY, tx, ty, facesRight, grip, progress, watered)
        standStillNow()
        return true
    }

    private fun cancelGardenVisit() {
        val old = gardenVisit ?: return
        gardenVisit = null
        pet?.apply { gardenLean = 0f; gardenGrip = null; walking = false }
        old.progress(null)
    }

    private fun advanceGardenVisit(now: Long, view: PetView): Boolean {
        val visit = gardenVisit ?: return false
        if (toyActionBlocked(visit.owner) || abs(visit.owner.posX - visit.ownerX) > dp(1f) ||
            abs(visit.owner.posY - visit.ownerY) > dp(1f)) {
            cancelGardenVisit()
            return false
        }
        val elapsed = (now - visit.started).coerceAtLeast(0L)
        val travel = if (visit.travelMs == 0L) 1f else (elapsed / visit.travelMs.toFloat()).coerceAtMost(1f)
        val position = travel * travel * (3f - 2f * travel)
        posX = visit.fromX + (visit.toX - visit.fromX) * position
        posY = visit.fromY + (visit.toY - visit.fromY) * position
        view.walking = travel < 1f
        view.facingRight = if (travel < 1f && abs(visit.toX - visit.fromX) > dp(2f)) visit.toX > visit.fromX else visit.facesRight
        applyPetPosition()
        if (travel == 1f) {
            val pour = ((elapsed - visit.travelMs) / PlantGrowth.PIP_POUR_DURATION_MS.toFloat()).coerceIn(0f, 1f)
            view.gardenLean = kotlin.math.sin(pour * Math.PI).toFloat()
            // Layout params are rounded to whole pixels; use the same origin as the window.
            view.gardenGrip = DragMotion.Point(visit.grip.x + (visit.owner.posX - visit.ownerX) - petParams.x,
                visit.grip.y + (visit.owner.posY - visit.ownerY) - petParams.y)
            visit.progress(pour)
            if (pour >= 1f) {
                cancelGardenVisit()
                idleUntil = now + idleDelay()
                visit.watered()
            }
        }
        return true
    }

    override fun petHopOn(owner: Prop, x: Float, y: Float, landed: () -> Unit): Boolean {
        val view = pet ?: return false
        if (toyHop != null || gardenVisit != null || toyWalk || toyActionBlocked(owner) || !screenUsable()) return false

        if (raisedFor !== owner) {
            // Separate overlay windows are stacked in attachment order. Raise Pip once per
            // sheet so the jump and his feet are visible above the bubbles.
            if (!runCatching { windowManager.removeViewImmediate(view) }.isSuccess) return false
            if (!runCatching { windowManager.addView(view, petParams) }.isSuccess) {
                stopSelf()
                return false
            }
            raisedFor = owner
        }
        val tx = (x - petW / 2f).coerceIn(0f, (screenW - petW).coerceAtLeast(0).toFloat())
        val minY = topInset.toFloat()
        val ty = (y - petH + dp(2f)).coerceIn(minY, (screenH - petH - bottomInset).toFloat().coerceAtLeast(minY))
        val distance = hypot(tx - posX, ty - posY)
        val duration = (280f + distance / dp(1f) * 1.25f).toLong().coerceAtMost(710L)
        toyHop = ToyHop(owner, SystemClock.uptimeMillis(), duration, posX, posY, tx, ty,
            (dp(16f) + distance * 0.18f).coerceAtMost(dp(48f)), landed)
        standStillNow()
        if (abs(tx - posX) > dp(2f)) view.facingRight = tx > posX
        return true
    }

    private fun cancelToyHop() {
        if (toyHop == null) return
        toyHop = null
        pet?.apply { scaleX = 1f; scaleY = 1f }
    }

    private fun advanceToyHop(now: Long, view: PetView): Boolean {
        val hop = toyHop ?: return false
        if (toyActionBlocked(hop.owner)) {
            cancelToyHop()
            return false
        }
        val t = ((now - hop.started) / hop.duration.toFloat()).coerceIn(0f, 1f)
        val height = hopLift(t)
        posX = hop.fromX + (hop.toX - hop.fromX) * t
        val ground = hop.fromY + (hop.toY - hop.fromY) * t
        posY = (ground - height * hop.lift).coerceAtLeast(topInset.toFloat())
        view.pivotY = (petH - dp(2f))
        view.scaleY = 1f + height * 0.08f
        view.scaleX = 1f - height * 0.06f
        applyPetPosition()
        if (t >= 1f) {
            cancelToyHop()
            idleUntil = now + idleDelay()
            hop.landed()
        }
        return true
    }

    /** Called by toys only, so every walk from here is one he has been sent on. */
    override fun petWalkTo(x: Float, y: Float, done: (() -> Unit)?) {
        cancelToyHop()
        cancelGardenVisit()
        targetX = OverlayBounds.clamp(x - petW / 2f, 0f, (screenW - petW).toFloat())
        targetY = OverlayBounds.clamp(y - petH / 2f, topInset + dp(8f), (screenH - petH - bottomInset).toFloat())
        arrive = done
        walking = true
        toyWalk = true
        relocating = false
        attentive = false
    }

    override fun petSit(sitting: Boolean) {
        pet?.sitting = sitting
        if (sitting) {
            attentive = true
            walking = false
            toyWalk = false
            arrive = null
            pet?.walking = false
            idleUntil = SystemClock.uptimeMillis() + 600_000L
        } else {
            attentive = false
            idleUntil = SystemClock.uptimeMillis() + 1200L
        }
    }

    override fun updateProp(prop: Prop) {
        runCatching { windowManager.updateViewLayout(prop, prop.params) }
    }

    override fun propLetGo(prop: Prop) = Unit

    override fun userDragAllowed(): Boolean = !ending && !paused && !overlayDucked && powerAnimator == null

    private val toyLifetimes = mutableMapOf<Toy, ToyLifetime>()

    override fun propTapped(prop: Prop) {
        if (prop !in props) return
        Toy.values().firstOrNull { it.owns(prop) }?.let { toyLifetimes[it]?.tapped(SystemClock.uptimeMillis()) }
    }

    private fun toyHand(towardX: Float): DragMotion.Point? {
        val view = pet ?: return null
        if (ending || powerAnimator != null || overlayDucked || Agent.running() ||
            petDrag?.moving == true || toyHop != null || gardenVisit != null ||
            !view.isAttachedToWindow || !canAnimatePower()) return null
        val side = if (towardX < petCentreX()) -1f else 1f
        return DragMotion.Point(petCentreX() + side * dp(25f), posY + petH - dp(34f))
    }

    private fun reachForToy(toy: Prop, hand: DragMotion.Point, entering: Boolean, linger: Long) {
        val view = pet ?: return
        cancelToyReach()
        view.toyReachColor = toy.motion.color
        view.toyReachSide = if (hand.x < petCentreX()) -1f else 1f
        idleUntil = maxOf(idleUntil, SystemClock.uptimeMillis() + linger)
        if (walking && !toyWalk && !relocating) standStillNow()
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = linger
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val reach = if (entering) minOf(t * 5f, (1f - t) * 1.65f)
                    else minOf(t * 4f, (1f - t) * 3.5f)
                view.toyReach = reach.coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (toyReachAnimator === animation) {
                        toyReachAnimator = null
                        view.toyReach = 0f
                    }
                }
            })
        }
        toyReachAnimator = animation
        animation.start()
    }

    private fun cancelToyReach() {
        val old = toyReachAnimator
        toyReachAnimator = null
        old?.removeAllListeners()
        old?.cancel()
        pet?.toyReach = 0f
    }

    /** Snap to final positions before Android gestures, screen-off, or shutdown. */
    private fun finishToyFlights() {
        cancelToyReach()
        props.toList().forEach(Prop::completeToyMotion)
        fading.filterIsInstance<Prop>().forEach(Prop::completeToyMotion)
    }

    override fun addProp(prop: Prop): Boolean {
        if (ending) return false
        val toy = Toy.values().firstOrNull { it.owns(prop) }
        if (toy != null && !PipSettings.toyAllowed(this, toy)) return false
        if (props.contains(prop)) return true
        val hand = toyHand(prop.centreX)
        val params = WindowManager.LayoutParams(
            prop.windowW,
            prop.windowH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable and draggable, but never focusable: a toy must not steal the
            // keyboard or the taps meant for the app underneath. A toy put down while a step
            // is pressing starts out of the way for that step, as all of them do.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (overlayDucked || hand != null) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prop.posX.toInt()
            y = prop.posY.toInt()
        }
        prop.params = params
        if (hand != null) prop.alpha = 0f
        if (overlayDucked) params.alpha = 0f
        if (!runCatching { windowManager.addView(prop, params) }.isSuccess) return false
        props += prop
        if (toy != null) toyLifetimes.getOrPut(toy) { ToyLifetime(SystemClock.uptimeMillis()) }
        if (hand != null) {
            val delay = when (prop) {
                is Goal -> if (prop.pipDefends) 78L else 32L
                is Ball -> 106L
                else -> 0L
            }
            if (delay == 0L) reachForToy(prop, hand, true, prop.motion.duration + 100L)
            prop.arriveFrom(hand, delay) {
                if (!overlayDucked && prop in props) {
                    prop.params.flags = duckFlags(prop.params.flags, false)
                    updateProp(prop)
                }
            }
        }
        keepBarClear()
        return true
    }

    override fun dropProp(prop: Prop) {
        if (!props.remove(prop)) return
        if (toyHop?.owner === prop) cancelToyHop()
        if (gardenVisit?.owner === prop) cancelGardenVisit()
        if (raisedFor === prop) raisedFor = null
        Toy.values().firstOrNull { it.owns(prop) }?.let { toy ->
            if (props.none(toy::owns)) toyLifetimes.remove(toy)
        }
        if (prop === postOwner) petRelease()
        val hand = toyHand(prop.centreX)
        if (hand != null && toyReachAnimator == null) reachForToy(prop, hand, false, 390L)
        prop.params.flags = prop.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        updateProp(prop)
        fading += prop
        prop.leaveFor(hand) {
            if (fading.remove(prop)) runCatching { windowManager.removeView(prop) }
        }
    }

    // --------------------------------------------------------------- toy break

    /** Fires once, at the deadline of the break that was running when it was posted. */
    private val breakAlarm = Runnable { settleBreak() }

    /**
     * Reconciles the stored break with the clock, and is the only place that does: arm a
     * timer for whatever is left, or pay the line for a deadline that has passed. The
     * deadline on disk is the single source of truth, so nothing here needs a flag — a
     * restart, or a phone that slept through the end, is just another call.
     *
     * Called on creation, on every change to the break, when the alarm fires, and when the
     * screen comes back.
     */
    private fun settleBreak() {
        if (ending) return
        handler.removeCallbacks(breakAlarm)
        val left = Quiet.remaining(this)
        if (left > 0L) {
            handler.postDelayed(breakAlarm, left + 500L)
            return
        }
        // Owed, but a bubble said to a dark or locked phone would expire unseen.
        if (!Quiet.owed(this) || !screenUsable()) return
        announceBreakOver()
    }

    /** Whether anything said right now would actually be seen. */
    private fun screenUsable(): Boolean {
        if (paused) return false
        return runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false }.getOrDefault(false)
    }

    private fun announceBreakOver() {
        // Marked first: a restart mid-sentence must not repeat him forever.
        Quiet.markSaid(this)
        sayAloud(Lines.pick(Toys.BREAK_OVER_LINES))
        feel(PetView.Mood.HAPPY, 3000L)
        // Give it a moment before he bounces into something on his own.
        initiativeIn = 90_000L + rnd.nextLong(90_000L)
    }

    // ---------------------------------------------------------------- wandering

    private val tick = object : Runnable {
        override fun run() {
            if (paused || ending) return
            step()
            if (!paused && !ending) handler.postDelayed(this, frameMs)
        }
    }

    private fun step() {
        if (ending || powerAnimator != null) return
        val view = pet ?: return
        val now = SystemClock.uptimeMillis()

        // A hold that has run out ends here rather than on a timer of its own: the deadline on
        // disk is the truth, so a phone that slept through the end of one is just another
        // reading of it, and nothing has to be re-armed after a restart.
        if (stillDue()) endStill()
        val still = standingStill()

        if (bubble != null && bubbleParams?.alpha != 0f && now >= bubbleHideAt) fadeBubble()

        keyboardWatch -= frameMs
        if (keyboardWatch <= 0L) {
            keyboardWatch = 400L
            watchKeyboard()
        }

        // Hold positions until Android has finished with the prepared gesture path.
        if (overlayDucked) {
            cancelToyHop()
            cancelGardenVisit()
            return
        }

        if (advanceJobWalk(view)) return

        // Toys animate and run their own physics whether or not he is paying attention.
        if (props.isNotEmpty()) {
            // Caught rather than left to crash the tick, but recorded: a toy throwing every
            // frame used to freeze that toy silently, with nothing anywhere saying why.
            frameScratch.clear()
            frameScratch.addAll(props)
            frameScratch.forEach { prop ->
                if (prop.inToyMotion) return@forEach
                try {
                    prop.onFrame()
                } catch (t: Throwable) {
                    Diag.caught(prop.javaClass.simpleName + ".onFrame", t)
                }
            }
            // Check idle clocks once a second, not on every animation frame.
            retireCheck -= frameMs
            if (retireCheck <= 0L) {
                retireCheck = 1_000L
                retireIfStale()
            }
        }

        watchCountdown += frameMs
        if (watchCountdown >= 15_000L) {
            watchCountdown = 0L
            currentAppLabel = AppWatcher.currentAppLabel(this)
        }

        // Nothing on screen for a few minutes: he gets bored and makes his own fun.
        if (spontaneousToysAllowed && !attentive && !walking && props.isEmpty() && !still) {
            initiativeIn -= frameMs
            if (initiativeIn <= 0L) {
                initiativeIn = 180_000L + rnd.nextLong(140_000L)
                Toys.spontaneous(this)
            }
        }

        // Work left at his post outlives an interrupted walk. If a chat or a tap stopped
        // him halfway there, he heads back once he is free and does it on arrival — never
        // before, so a long crossing never pours the glass early. Being at the post already
        // is not a second route to it: the walk is simply one step long. A post he was
        // already keeping is walked back to the same way when a keyboard moved him off it,
        // which is all postReturn means — there may be nothing left to do there.
        if (advanceToyHop(now, view) || advanceGardenVisit(now, view) || petDrag?.moving == true) return
        val station = post
        if (station != null && (postArrival != null || postReturn) && !attentive && !walking &&
            !still && !inKeyboard(station.second)
        ) {
            approachPost(station.first, station.second)
        }

        // Standing still and paying attention, unless he is finishing a walk he was
        // already on (a toy sent him, or he is repositioning for the input bar).
        if (attentive && !walking && !relocating) return

        if (walking) {
            val dx = targetX - posX
            val dy = targetY - posY
            val distance = hypot(dx, dy)
            // Errands and getting clear of the keyboard are urgent, so he hurries: a toy he
            // was asked for should be done with, not a slow stroll away.
            val rate = when {
                relocating -> speed * 4f
                toyWalk -> speed * 2.5f
                else -> speed
            }
            val perFrame = rate * frameMs / 1000f

            if (distance <= perFrame || distance < 0.5f) {
                posX = targetX
                posY = targetY
                applyPetPosition()
                walking = false
                toyWalk = false
                view.walking = false
                if (relocating) {
                    relocating = false
                    // Waiting by his own chat box is a long stay. Stepping clear of a
                    // keyboard in another app is not: he goes back to wandering shortly.
                    idleUntil = now + if (inputBar != null) 600_000L else idleDelay()
                } else {
                    idleUntil = now + idleDelay()
                    val arrival = arrive
                    arrive = null
                    arrival?.invoke()
                }
            } else {
                posX += dx / distance * perFrame
                posY += dy / distance * perFrame
                applyPetPosition()
                if (abs(dx) > 1f) view.facingRight = dx > 0
                view.walking = true
            }
        } else if (wanderAllowed && !still && !attentive && post == null && now > idleUntil) {
            // A post outranks being bored: he keeps station until the toy lets him go.
            pickTarget(now)
        }
    }

    private fun applyBehaviorSettings() {
        val wasWandering = wanderAllowed
        wanderAllowed = PipSettings.enabled(this, PipSettings.Option.WANDER)
        if (!wanderAllowed) standStillNow()
        else if (!wasWandering) idleUntil = SystemClock.uptimeMillis() + idleDelay()

        val forbidden = Toy.values().filterNot { PipSettings.toyAllowed(this, it) }
        val spontaneous = PipSettings.enabled(this, PipSettings.Option.SPONTANEOUS)
        spontaneousToysAllowed = Toy.values().any { it !in forbidden } && spontaneous
        if (Toy.DOOMSCROLL in forbidden || !PipSettings.enabled(this, PipSettings.Option.ACCESSIBILITY) ||
            automaticDoomscrollJob && !spontaneous) {
            stopDoomscrollToy()
        }
        propsOnScreen().filter { prop -> forbidden.any { it.owns(prop) } }.forEach(::dropProp)

        if (!PipSettings.enabled(this, PipSettings.Option.ACCESSIBILITY) && jobBar != null && !Agent.running()) {
            hideJobBar()
            endJobWatch()
        }
    }

    /** Long pauses between short strolls — he should feel relaxed, not busy. */
    private fun idleDelay(): Long = 12_000L + rnd.nextLong(16_000L)

    // ----------------------------------------------------------- standing still

    /** Whether a hold on standing still is on him at this moment. */
    private fun standingStill(): Boolean =
        stillUntil == Still.FOREVER || stillUntil > System.currentTimeMillis()

    /** Whether a timed hold has just run out, and is waiting to be ended and paid off. */
    private fun stillDue(): Boolean =
        stillUntil != 0L && stillUntil != Still.FOREVER && System.currentTimeMillis() > stillUntil

    /**
     * Re-reads the stored hold after the user sets one or takes it away. Coming out of a hold
     * restarts his idle timer, so he wanders off on his own schedule rather than the instant
     * the hold is lifted.
     */
    private fun refreshStill() {
        val was = standingStill()
        stillUntil = Still.until(this)
        if (was && !standingStill()) idleUntil = SystemClock.uptimeMillis() + idleDelay()
    }

    /**
     * A timed hold has run out. It is only cleared once he can actually be seen to move again:
     * a line said to a locked phone is a line nobody hears, and a hold dropped behind a lock
     * screen would be the one bit of this that happened silently.
     */
    private fun endStill() {
        if (!screenUsable()) return
        Still.clear(this)
        sayAloud(Lines.pick(Still.DONE_LINES))
    }

    /**
     * Stands him where he is. A stroll of his own stops dead; a walk a toy sent him on, or one
     * getting him out from under a keyboard, is left to finish — something is owed at the end of
     * the first, and the second is moving him out of the user's way.
     */
    fun standStillNow() {
        if (toyWalk || relocating) return
        walking = false
        pet?.walking = false
    }

    private fun pickTarget(now: Long) {
        val maxX = (screenW - petW).coerceAtLeast(0).toFloat()
        val minY = topInset + dp(8f)
        // Nowhere in the keyboard, whoever is typing in it, and nowhere on the box above it.
        val floor = if (keyboardUp) {
            keyboardCeiling() - keyboardAbove()
        } else {
            (screenH - bottomInset).toFloat()
        }
        val maxY = (floor - petH - dp(8f)).coerceAtLeast(minY)
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
        toyWalk = false
    }

    private fun applyPetPosition() {
        petParams.x = posX.toInt()
        petParams.y = posY.toInt()
        pet?.let { view -> runCatching { windowManager.updateViewLayout(view, petParams) } }
        repositionPanels()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun dpInt(value: Float) = dp(value).toInt()

    // -------------------------------------------------------------------- touch

    private fun createPetDrag(view: PetView) = WindowDrag(
        view,
        position = { DragMotion.Point(posX, posY) },
        moveTo = { x, y ->
            val minY = topInset.toFloat()
            val oldX = posX
            posX = x.coerceIn(0f, (screenW - petW).coerceAtLeast(0).toFloat())
            posY = y.coerceIn(minY, (screenH - petH - bottomInset).toFloat().coerceAtLeast(minY))
            view.pullPickup((posX - oldX) / dp(16f))
            if (petParams.x != posX.toInt() || petParams.y != posY.toInt()) applyPetPosition()
        },
        allowed = ::userDragAllowed,
        grip = { holding ->
            if (holding) {
                if (jobWalk != null) {
                    Agent.cancel()
                    hideJobBar()
                    endJobWatch()
                }
                cancelToyHop()
                cancelGardenVisit()
                walking = false
                toyWalk = false
                relocating = false
                arrive = null
                view.walking = false
            }
            view.setGrip(holding)
        },
        dragStarted = view::startCarry,
        dragFinished = {
            if (!toyWalk) {
                targetX = posX
                targetY = posY
            }
            idleUntil = SystemClock.uptimeMillis() + idleDelay()
            walkClearOfKeyboard()
        },
        tapped = { _, _ -> toggleChat() }
    )

    // ------------------------------------------------------------ anchored panels

    /** Places a panel above him, or below when there is no room at the top. */
    private fun placePanel(width: Int, height: Int, params: WindowManager.LayoutParams) {
        var x = (posX + petW / 2f - width / 2f).toInt()
        x = x.coerceIn(dpInt(6f), (screenW - width - dpInt(6f)).coerceAtLeast(dpInt(6f)))

        val y = OverlayBounds.panelY(posY.toInt(), petH, height, dpInt(8f), topInset, screenH - bottomInset)

        params.x = x
        params.y = y
    }

    private fun repositionPanels() {
        bubble?.let { view ->
            bubbleParams?.let { params ->
                placePanel(params.width, params.height, params)
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        thinking?.let { view ->
            thinkingParams?.let { params ->
                placePanel(params.width, params.height, params)
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        positionInputBar()
    }

    // --------------------------------------------------------------- speech bubble

    /**
     * How long a line stays up, by how much there is to read: a two-word reply should not
     * sit there for twelve seconds, and a longer one should get longer.
     */
    private fun bubbleLifetime(text: String): Long =
        (2200L + text.length * 55L).coerceIn(3_000L, 14_000L)

    private fun openBubble(text: String, end: Int = text.length) {
        ChatRecovery.attempt(Unit, { Diag.chatFailure("reply bubble", it) }) { attachBubble(text, end) }
    }

    private fun attachBubble(text: String, end: Int) {
        clearBubbleExit()
        val widthLimit = (screenW - dpInt(12f)).coerceIn(1, dpInt(232f).coerceAtLeast(1))
        val view = ReplyBubbleView(this, text, widthLimit)
        view.measure(View.MeasureSpec.makeMeasureSpec(widthLimit, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val width = view.measuredWidth.coerceIn(dpInt(48f).coerceAtMost(widthLimit), widthLimit)
        val height = view.measuredHeight.coerceAtLeast(dpInt(26f))
        view.reveal(end)

        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        placePanel(width, height, params)
        if (overlayDucked) params.alpha = 0f

        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
        bubbleHideAt = SystemClock.uptimeMillis() + bubbleLifetime(text)

        // Enter at full opacity. If Android drops the animation (for example during a
        // window relayout), the bubble is still visible instead of being left transparent.
        if (ValueAnimator.areAnimatorsEnabled()) {
            val above = params.y + height <= posY
            view.pivotX = (posX + petW / 2f - params.x).coerceIn(0f, width.toFloat())
            view.pivotY = if (above) height.toFloat() else 0f
            view.scaleX = 0.96f
            view.scaleY = 0.96f
            view.translationY = dp(if (above) 6f else -6f)
            view.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(185L).setInterpolator(DecelerateInterpolator(1.65f)).start()
        }
    }

    private fun cancelSpeechBubbleFallback() {
        speechBubbleFallback?.let(handler::removeCallbacks)
        speechBubbleFallback = null
    }

    private fun captionReply(text: String) {
        val reply = VoicedReply(text)
        val presentation = reply.presentation
        voicedReply = reply
        openBubble(text, presentation.visibleEnd)
        bubbleHideAt = Long.MAX_VALUE

        fun render() {
            val view = bubble
            if (view != null) view.reveal(presentation.visibleEnd)
            else openBubble(text, presentation.visibleEnd)
        }

        fun holdText(ms: Long) {
            bubbleHideAt = SystemClock.uptimeMillis() + ms
            replyUntil = bubbleHideAt
        }

        fun onCaption(end: Int?) {
            if (voicedReply !== reply || ending) return
            val previous = presentation.visibleEnd
            val changed = presentation.reveal(end)
            if (end == null) {
                cancelSpeechBubbleFallback()
                voicedReply = null
                if (changed || previous == 0) render()
                if (previous < text.length) {
                    val unseen = text.length - previous
                    holdText(if (previous > 0 && unseen <= 16) 3_000L else bubbleLifetime(text.substring(previous)))
                } else if (bubbleHideAt == Long.MAX_VALUE) holdText(3_000L)
            } else if (changed) {
                cancelSpeechBubbleFallback()
                render()
                bubbleHideAt = Long.MAX_VALUE
                idleUntil = SystemClock.uptimeMillis() + SPEAKING_PAUSE_MS
                if (pet?.mood == PetView.Mood.TALKING || pet?.mood == PetView.Mood.NEUTRAL) {
                    feel(PetView.Mood.TALKING, 950L)
                }
            }
        }

        speechBubbleFallback = Runnable {
            speechBubbleFallback = null
            if (voicedReply === reply && !ending && presentation.showTextIfWaiting()) {
                render()
                holdText(bubbleLifetime(text))
            }
        }.also { handler.postDelayed(it, BUBBLE_SPEECH_WAIT_MS) }
        ChatRecovery.attempt(Unit, {
            Diag.chatFailure("start spoken reply", it)
            onCaption(null)
        }) { PipVoice.reply(this, text, ::onCaption) }
    }

    private fun fadeBubble() {
        val view = bubble ?: return
        clearBubbleExit()
        bubble = null
        bubbleParams = null
        bubbleHideAt = 0L
        view.animate().cancel()
        // Fix the entry transform before exiting. The exit only changes opacity; shrinking
        // or translating here made the text jump when Pip started walking again.
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(view) }
            return
        }
        val animation = ValueAnimator.ofFloat(view.alpha, 0f).apply {
            duration = 180L
            interpolator = LinearInterpolator()
            addUpdateListener { view.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bubbleExit?.view !== view) return
                    bubbleExit = null
                    runCatching { windowManager.removeViewImmediate(view) }
                }
            })
        }
        bubbleExit = BubbleExit(view, animation)
        animation.start()
    }

    private fun clearBubbleExit() {
        val outgoing = bubbleExit ?: return
        bubbleExit = null
        outgoing.animation.cancel()
        runCatching { windowManager.removeViewImmediate(outgoing.view) }
    }

    private fun hideBubble() {
        clearBubbleExit()
        cancelSpeechBubbleFallback()
        voicedReply = null
        bubble?.let { view ->
            view.animate().cancel()
            runCatching { windowManager.removeView(view) }
        }
        bubble = null
        bubbleParams = null
        bubbleHideAt = 0L
    }

    // ---------------------------------------------------------------- thinking

    private fun startThinking() {
        if (ending) return
        attentive = true
        walking = false
        toyWalk = false
        relocating = false
        arrive = null
        pet?.walking = false
        feel(PetView.Mood.THINKING, 0L)
        showThinking()
    }

    private fun showThinking() {
        hideThinking()
        val width = dpInt(52f)
        val height = dpInt(26f)
        val view = ThinkingView(this)

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
        }
        placePanel(width, height, params)

        if (overlayDucked) params.alpha = 0f
        if (runCatching { windowManager.addView(view, params) }.isSuccess) {
            thinking = view
            thinkingParams = params
            view.alpha = 0f
            view.scaleX = 0.9f
            view.scaleY = 0.9f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    private fun hideThinking() {
        thinking?.let { view -> runCatching { windowManager.removeView(view) } }
        thinking = null
        thinkingParams = null
    }

    // --------------------------------------------------------------------- chat

    private fun enterAttention(moveUp: Boolean) {
        if (ending) return
        attentive = true
        walking = false
        toyWalk = false
        relocating = false
        // Dropped like every other interruption, so a stale callback cannot fire later.
        arrive = null
        pet?.walking = false
        if (moveUp) {
            val comfortable = topInset + screenH * 0.12f
            if (posY > comfortable) {
                posY = comfortable
                applyPetPosition()
            }
        }
    }

    private fun leaveAttention() {
        hideKeyboard()
        removeInputBar()
        attentive = false
        pet?.mood = PetView.Mood.NEUTRAL
        idleUntil = SystemClock.uptimeMillis() + 1500L
    }

    private fun sayAloud(text: String, finalReply: Boolean = false) {
        // A reply can land after the overlay has been stopped, and a bubble added to a dead
        // service is a window nothing will ever take down again.
        if (ending || !running) return
        hideThinking()
        // Speaking is a pause, not attention. Attention belongs to the chat bar and to sitting
        // at his easel; claiming it here is what used to root him to the spot, because nothing
        // ever gave it back.
        //
        // A walk a toy sent him on survives the line he says on the way, so a reply cannot pull
        // him out from behind the lemonade stand. Ordinary wandering stops for the pause.
        if (!toyWalk) {
            walking = false
            pet?.walking = false
        }
        idleUntil = SystemClock.uptimeMillis() + SPEAKING_PAUSE_MS
        hideBubble()
        if (finalReply && VoiceSettings.enabled(this)) captionReply(text)
        else openBubble(text)
        // Being smug or annoyed outranks talking: he wants you to see his face.
        val mood = pet?.mood
        if (mood != PetView.Mood.ANNOYED && mood != PetView.Mood.SMUG) {
            feel(PetView.Mood.TALKING, 1600L)
        }
    }

    private fun toggleChat() {
        if (ending) return
        if (inputBar != null) {
            closeChat()
        } else {
            // Opening the chat is an interruption: whatever he was doing with his hands
            // stops, rather than carrying on in an app behind the box he just gave you.
            Agent.cancel()
            attentive = true
            walking = false
            toyWalk = false
            relocating = false
            arrive = null
            pet?.walking = false
            showInputBar()
        }
    }

    private fun closeChat() {
        hideKeyboard()
        removeInputBar()
        attentive = false
        relocating = false
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

        val field = PipEditText(this).apply {
            onBackPressed = {
                closeChat()
                true
            }
            hint = "Say something…"
            filters = arrayOf(android.text.InputFilter.LengthFilter(Memory.MAX_MESSAGE_CHARS))
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
                    // Finish the IME callback before removing its focused overlay window.
                    val editor = this
                    handler.post { if (inputField === editor) sendMessage() }
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
            // The window asks for the keyboard itself, so it opens together with the bar
            // rather than depending on a request that can arrive before the IME connects.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
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
        showKeyboard()
        // Steps clear by the remembered height straight away; the watcher refines it.
        walkClearOfKeyboard()
    }

    // ------------------------------------------------------------ keyboard size

    /**
     * A safety net under the accessibility service: that pushes each change as it happens,
     * but this re-reads the height while the overlay runs, so a service that has been killed
     * cannot leave him avoiding a keyboard that went away long ago.
     */
    private fun watchKeyboard() {
        // The offset first, so the walk below is worked out with the current one.
        measureWindowOffset()
        // Then measure the keyboard rather than re-reading the last answer. A keyboard
        // measured while it was still rising keeps that height for good, and a height that is
        // too small is exactly what leaves him standing in the keyboard with his box behind it.
        ImeWatch.probe()
        onImeChanged(imeHeight())
    }

    /**
     * If the bar would sit under the keyboard, or he would be standing on the box above it,
     * he walks straight up until both are clear. He keeps his column: the bar follows him and
     * is kept on screen on its own, so there is nothing to be gained by sliding sideways —
     * and re-targeting on every probe sample used to drag him into a corner.
     */
    private fun walkClearOfKeyboard() {
        // Only while a keyboard is actually on screen. The height is remembered between
        // keyboards so he can step clear the instant one opens, but the area it describes is
        // nobody's business when the keyboard is not there — taken as a floor it kept him out
        // of the bottom of the screen the whole time, keyboard or no keyboard.
        if (!keyboardShowing()) return
        val neededY = keyboardCeiling() - keyboardAbove() - petH - dp(6f)
        if (neededY >= posY) return

        targetX = posX
        targetY = neededY.coerceAtLeast(topInset + dp(8f))
        if (abs(targetY - posY) < 1f) return
        // He drops whatever a toy sent him on: the keyboard wins. A post is the exception —
        // he has to go back behind the counter once the keyboard is gone, which is what
        // postReturn is for.
        arrive = null
        if (post != null) postReturn = true
        walking = true
        toyWalk = false
        relocating = true
    }

    /**
     * How much of the screen above the keyboard he has to keep clear as well as the keyboard
     * itself, because clearing only the keyboard leaves his feet on its top edge — which is
     * where the row above it starts.
     *
     * With his own chat box open that row is his box, and he stands on top of it. On anybody
     * else's keyboard it is their message box, comment field or search bar: the one thing on
     * that screen the user is actually looking at while they type, and the one thing he must
     * not be sitting on. A row's worth of room above the keyboard clears it.
     */
    private fun keyboardAbove(): Float {
        val bar = inputBar ?: return dp(INPUT_ROW_DP)
        // The bar has no height on the frame it is added, so its usual size is the floor.
        return bar.height.toFloat().takeIf { it > 0f } ?: dp(62f)
    }

    /**
     * Whether standing with his middle at [y] would put him in the keyboard. Without this
     * the post would walk him back into the keyboard that just moved him off it, and the two
     * would trade places every frame.
     */
    private fun inKeyboard(y: Float): Boolean =
        keyboardUp && y + petH / 2f > keyboardCeiling()

    /**
     * The keyboard's height, from the accessibility service wherever it will say.
     *
     * That service is the only thing on the phone that can see a keyboard at all, so its
     * figure is the one to place from. The remembered height is only what it left behind last
     * time, and a number remembered from another keyboard, or from a smaller font, is exactly
     * the sort of thing that leaves his box tucked behind the keyboard in front of him.
     */
    private fun keyboardPx(): Int = imeHeight().takeIf { it > 0 } ?: keyboardHeightPx

    /**
     * The gap between this window's own y and the display's.
     *
     * A TOP-gravity overlay window is placed below the status bar unless it has been given the
     * whole display, so his "y" and the keyboard's top are not the same measurement: one
     * starts at the status bar's lower edge and the other at the top of the screen. That gap
     * is small enough not to look like the fault, and it is about the height of his box, which
     * is how "the box is just behind the keyboard" happens.
     */
    private var windowOffset = 0f

    /**
     * Measures that gap from a window that is standing still, and keeps it.
     *
     * Read while he is walking, it is the difference between where he is heading and where the
     * window still is: a different number every frame, and none of them the offset. A settled
     * window gives the one answer, and it does not change while the phone stays upright.
     */
    private fun measureWindowOffset() {
        val view = pet ?: return
        if (walking || !view.isLaidOut) return
        val onScreen = IntArray(2)
        view.getLocationOnScreen(onScreen)
        windowOffset = (onScreen[1] - petParams.y).toFloat()
    }

    /**
     * Whether a keyboard is on screen at this moment: another app's, which the accessibility
     * service reports, or the one his own box raised, which needs no reporting because the box
     * is the thing that asked for it and is up before its keyboard finishes rising.
     */
    private fun keyboardShowing(): Boolean = keyboardUp || inputBar != null

    /**
     * Top of the keyboard in his own coordinates, less the room he keeps above it. With no
     * keyboard on screen this is simply the bottom of the screen: there is no area to keep out
     * of, and he is free to stand where he likes.
     *
     * The keyboard's top is measured from the top of the display, so the window's own offset
     * comes off it first. After that, nearly a fifth of the keyboard's height is held back on
     * top of its edge, and the box he carries under his feet has its own room reserved above
     * that. A keyboard that reports itself short puts his box behind it, and standing a little
     * too high for the seconds a chat lasts costs nothing — so this errs that way on purpose
     * rather than landing exactly on the line.
     */
    private fun keyboardCeiling(): Float {
        val px = keyboardPx()
        if (px <= 0 || !keyboardShowing()) return (screenH - bottomInset).toFloat()
        return (screenH - px).toFloat() - windowOffset - px * 0.18f - dp(10f)
    }

    private fun positionInputBar() {
        val params = inputParams ?: return
        val bar = inputBar ?: return
        val width = params.width
        params.x = (posX + petW / 2f - width / 2f).toInt()
            .coerceIn(dpInt(6f), (screenW - width - dpInt(6f)).coerceAtLeast(dpInt(6f)))
        // Under his feet, and moved only by him moving. The box is not given a place of its
        // own: it belongs under the character, and keeping it clear of a keyboard is his walk's
        // business rather than a second thing for the box to work out and get wrong.
        val barHeight = bar.height.toFloat().takeIf { it > 0f } ?: dp(62f)
        params.y = minOf(posY + petH + dpInt(6f), screenH - bottomInset - barHeight).toInt()
            .coerceAtLeast(topInset + dpInt(4f))
        runCatching { windowManager.updateViewLayout(bar, params) }
        keepBarClear()
    }

    private fun retireIfStale() {
        val now = SystemClock.uptimeMillis()
        val expired = toyLifetimes.entries.firstOrNull { (toy, lifetime) ->
            lifetime.expired(now, held = props.any { prop ->
                (prop.movingByUser && toy.owns(prop)) || (toy == Toy.BUBBLES && prop is BubbleWrap && prop.playingThrough(now)) ||
                    (toy == Toy.PLANT && prop is Plant && (gardenVisit?.owner === prop || prop.tendingThrough(now)))
            })
        }?.key ?: return
        Toys.retire(this, expired)
    }

    /**
     * Nothing may sit on top of the chat box. Props that end up under it are nudged clear,
     * to whichever side of the box they are already nearest; each nudge resolves the
     * overlap, so this settles instead of shoving things around every frame.
     */
    private fun keepBarClear() {
        val params = inputParams ?: return
        // The bar has no height until it is laid out, and this can be the only pass it gets
        // if he stands still afterwards, so the usual size is the floor.
        val barHeight = (inputBar?.height ?: 0).coerceAtLeast(dpInt(62f))
        val left = params.x.toFloat()
        val right = left + params.width
        val top = params.y.toFloat()
        val bottom = top + barHeight

        fun under(prop: Prop): Boolean =
            prop.posX < right && prop.posX + prop.windowW > left &&
                prop.posY < bottom && prop.posY + prop.windowH > top

        props.toList().forEach { prop ->
            // The prop he is serving stays put: nudging it away would leave him behind a
            // counter that is no longer there, and chasing it again would never settle.
            if (prop === postOwner) return@forEach
            if (!under(prop)) return@forEach
            val above = top - prop.windowH - dp(10f)
            val below = bottom + dp(10f)
            val preferAbove = prop.centreY < top + barHeight / 2f
            prop.moveTo(prop.posX, if (preferAbove) above else below)
            if (under(prop)) {
                prop.moveTo(prop.posX, if (preferAbove) below else above)
            }
        }
    }

    private fun removeInputBar() {
        handler.removeCallbacks(showKeyboardNudge)
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
        // The bar goes away the moment he has the message; he answers in the bubble.
        removeInputBar()
        relocating = false

        if (voicedReply != null) {
            hideBubble()
            hideThinking()
        }
        PipVoice.stop()
        val turn = ChatTurns.begin()
        if (NowPlaying.requested(text)) {
            Agent.cancel()
            val reply = ChatRecovery.attempt(ChatRecovery.REPLY_FAILED, { Diag.chatFailure("now playing reply", it) }) {
                PipMediaAccess.answer(this)
            }
            modelSays(reply)
            handler.postDelayed({
                if (ChatTurns.isCurrent(turn) && inputBar == null && !Agent.running()) closeChat()
            }, 4000L)
            return
        }
        ChatRecovery.attempt(Unit, { Diag.chatFailure("chat history", it) }) {
            Memory.append(this, "user", text)
        }
        Agent.cancel()
        ChatRecovery.attempt<String?>(null, { Diag.chatFailure("chat preferred name", it) }) {
            Facts.saveNameRequest(this, text)
        }?.let { reply ->
            ChatRecovery.attempt(Unit, { Diag.chatFailure("chat history", it) }) {
                Memory.append(this, "assistant", reply)
            }
            modelSays(reply)
            handler.postDelayed({
                if (ChatTurns.isCurrent(turn) && inputBar == null && !Agent.running()) closeChat()
            }, 4000L)
            return
        }
        // His answer takes priority from this moment on, so the toys' own lines stay quiet.
        beginReply()
        // Thinking comes before the toys act. The other way round, thinking cancelled the
        // walk a toy had just started — and with it the arrival callback — so a pour or a
        // painting never began at all.
        startThinking()
        // Some things are done, not said: stands get opened, games get started, and
        // criticism gets taken personally.
        if (JobProgress.routePhoneLocally(text)) {
            startJob(text, text, turn)
            return
        }
        val event = ChatRecovery.attempt<String?>(null, { Diag.chatFailure("chat toy reaction", it) }) {
            Toys.reactTo(this, text)
        }

        AssistantBrain.reply(this, currentAppLabel, Toys.summary(this), event, turn) { ok, reply ->
            handler.post {
                if (instance !== this || !ChatTurns.isCurrent(turn)) return@post
                // Sometimes the answer is not a line but a job for his hands to do.
                val job = if (ok) Agent.delegated(reply) else null
                if (job != null) {
                    startJob(job, text, turn)
                    return@post
                }
                if (ok) ChatRecovery.attempt(Unit, { Diag.chatFailure("chat history", it) }) {
                    Memory.append(this, "assistant", reply)
                }
                modelSays(reply)
                handler.postDelayed({
                    if (ChatTurns.isCurrent(turn) && inputBar == null && !Agent.running()) closeChat()
                }, 4000L)
            }
        }
    }

    // ------------------------------------------------------------------ phone jobs

    /**
     * Runs a job the model handed over: one action at a time, reading the screen between
     * them, through the accessibility service. The thinking dots stay up until his first
     * line replaces them, so there is never a stretch where nothing says he is working.
     *
     * The job speaks and finishes on its own thread, so both callbacks come back to the main
     * looper before they touch a window.
     */
    private var doomscrollJob = false
    private var automaticDoomscrollJob = false

    internal fun canStartDoomscrollAtHome(): Boolean = !ending && !awaitingReply && !Agent.running() &&
        !attentive && !walking && inputBar == null && !keyboardUp && !overlayDucked &&
        petDrag?.moving != true && props.isEmpty() && !standingStill() && screenUsable() &&
        PipSettings.toyAllowed(this, Toy.DOOMSCROLL) && PipSettings.enabled(this, PipSettings.Option.SPONTANEOUS) &&
        !Quiet.active(this) && Screen.available() &&
        packageManager.getLaunchIntentForPackage(DoomscrollMotion.INSTAGRAM_PACKAGE) != null && Screen.clearHomeScreen(this)

    internal fun startDoomscrollToy(automatic: Boolean = false): Boolean {
        if (ending || instance !== this || Agent.running()) return false
        // The random sampler just checked Home. The feed worker independently checks again
        // at the launch, in case the user opened something after the sample.
        if (!automatic) closeChat()
        val turn = if (automatic) ChatTurns.current() else ChatTurns.begin()
        return startJob(DoomscrollMotion.COMMAND, DoomscrollMotion.COMMAND, turn, fromClearHome = automatic)
    }

    internal fun stopDoomscrollToy() {
        if (!doomscrollJob) return
        if (Agent.running()) stopJob() else endJobWatch()
    }

    private fun startJob(job: String, original: String, turn: Long, fromClearHome: Boolean = false): Boolean {
        if (ending || instance !== this || !ChatTurns.isCurrent(turn)) return false
        if (!screenUsable()) {
            if (!fromClearHome) modelSays("Unlock your phone before asking me to work another app.")
            return false
        }
        val doomscroll = JobProgress.isDoomscrollRequest(original)
        if (doomscroll && (!PipSettings.toyAllowed(this, Toy.DOOMSCROLL) || Quiet.active(this))) {
            sayAloud(if (Quiet.active(this)) "I'm on a break. you asked." else "Doomscroll is turned off in Pip settings.")
            return false
        }
        // The chat model has answered, so nothing is waiting on it any more: the toys may
        // talk again while the job runs.
        awaitingReply = false
        val approach = JobApproachGate()
        val started = Agent.start(
            this,
            job,
            onSay = { id, line ->
                handler.post {
                    if (instance === this && ChatTurns.isCurrent(turn) && Agent.isCurrent(id)) sayAloud(line)
                }
            },
            onDone = { id, line ->
                handler.post {
                    if (instance !== this || !ChatTurns.isCurrent(turn) || !Agent.isCurrent(id)) return@post
                    // The chip belongs to the job and the job has just ended, so it goes now
                    // rather than at the next tick: a finished job must not still offer Stop.
                    // Guarded on a job being live, so a job started in the meantime keeps its
                    // own chip.
                    if (!Agent.running()) {
                        hideJobBar()
                        endJobWatch()
                    }
                    if (line.isNotBlank()) {
                        if (!fromClearHome) {
                            Memory.append(this, "assistant", line)
                        }
                        sayAloud(line, finalReply = !fromClearHome)
                        feel(PetView.Mood.HAPPY, 2500L)
                    }
                }
            },
            // For as long as the phone is being touched, his own windows are inert: they
            // step out of the way of the press instead of catching it. This call does not
            // return until they really are out of the way — a gesture dispatched before the
            // window was updated is exactly the press the duck is there to protect.
            onGesture = { footprint -> duckOverlay(footprint) },
            approach = approach,
            onFeedMotion = { id, cue ->
                handler.post {
                    if (instance === this && !ending && !paused && ChatTurns.isCurrent(turn) &&
                        (Agent.isActive(id) || cue == DoomscrollMotion.Cue.CLEAR && Agent.isCurrent(id))) {
                        val side = if (petCentreX() >= screenW / 2f) -1f else 1f
                        pet?.showDoomscrollCue(cue, side)
                    }
                }
            },
            originalRequest = original,
            fromClearHome = fromClearHome
        )
        if (!started) {
            if (!Agent.running()) hideJobBar()
            if (fromClearHome) return false
            sayAloud(
                when {
                    Agent.running() -> "I'm already in the middle of something."
                    !PipSettings.enabled(this, PipSettings.Option.ACCESSIBILITY) ->
                        "Accessibility use is turned off in Pip settings."
                    else -> Agent.NO_HANDS
                }
            )
            return false
        }
        // His hands are busy: he stands out of the way of the app he is working in, and the
        // way out of the job appears at the bottom of the screen.
        doomscrollJob = doomscroll
        automaticDoomscrollJob = fromClearHome
        pet?.showDoomscrollCue(DoomscrollMotion.Cue.CLEAR)
        beginJobWatch(turn, approach)
        showJobBar()
        return true
    }

    // --------------------------------------------------------------------- job bar

    /**
     * The cancel chip, for as long as a job is running. It is its own window so it stays over
     * the app being worked on, and it sits at the bottom because that is where a stop belongs
     * and where it covers the least of what he is doing.
     */
    private fun showJobBar() {
        if (jobBar != null) return
        // The listener is for TalkBack, which activates the chip without a finger: a real
        // touch goes through the service's own listener so it can become a drag.
        val view = JobBar(this) { stopJob() }
        view.setOnTouchListener(jobBarTouch)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        val width = view.measuredWidth.coerceAtLeast(dpInt(130f))
        val height = view.measuredHeight.coerceAtLeast(dpInt(32f))
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable for its one button, but never focusable: it must not take the keyboard
            // away from the field a job is typing into.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        jobBarParams = params
        jobBar = view
        positionJobBar()
        if (!runCatching { windowManager.addView(view, params) }.isSuccess) {
            jobBar = null
            jobBarParams = null
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(140L).start()
        handler.postDelayed(jobTicker, JOB_REFRESH_MS)
    }

    private fun hideJobBar() {
        handler.removeCallbacks(jobTicker)
        jobBar?.let { view -> runCatching { windowManager.removeView(view) } }
        jobBar = null
        jobBarParams = null
        chipDragging = false
        chipMoved = false
    }

    /**
     * Gets the chip out of the way of a press, or puts it back afterwards.
     *
     * FLAG_NOT_TOUCHABLE is the part that matters: while a step is acting, a touch at the
     * chip's own place goes to the app underneath — where the job aimed it — instead of
     * ending the job. The dimming is only so the moment it cannot be tapped is visible; Stop
     * is back the instant the step has finished.
     */
    private fun setChipDucked(ducked: Boolean): Boolean {
        val view = jobBar ?: return true
        val params = jobBarParams ?: return false
        if (!duckLayout(view, params, ducked, DUCK_ALPHA)) return false
        return true
    }

    private fun setHudDucked(ducked: Boolean): Boolean {
        val view = hud ?: return true
        val params = hudParams ?: return false
        if (ducked && hudHeld) return false
        if (!duckLayout(view, params, ducked)) return false
        return true
    }

    private fun duckFlags(flags: Int, ducked: Boolean): Int = if (ducked) {
        flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    } else {
        flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    }

    /** Android 12+ measures obscuring opacity on the window, not on the View. */
    private fun duckLayout(
        view: View, params: WindowManager.LayoutParams, ducked: Boolean, alpha: Float = 0f,
        alwaysUntouchable: Boolean = false
    ): Boolean {
        val oldFlags = params.flags
        val oldAlpha = params.alpha
        params.flags = duckFlags(oldFlags, ducked || alwaysUntouchable)
        params.alpha = if (ducked) alpha else 1f
        if (params.flags == oldFlags && params.alpha == oldAlpha) return true
        return runCatching { windowManager.updateViewLayout(view, params) }.onFailure {
            params.flags = oldFlags
            params.alpha = oldAlpha
            Diag.caught("move overlay aside", it)
        }.isSuccess
    }

    /** Refuse a touch if any overlay couldn't move aside rather than press through it. */
    private fun applyDuck(path: Rect?): Boolean {
        if (ending || instance !== this) return false
        fun overlaps(view: View): Boolean {
            if (path == null) return false
            if (!view.isLaidOut) return true // it could finish layout before the gesture lands
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return path.intersects(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        if (path != null && (inputBar != null ||
                (chipDragging && jobBar?.let(::overlaps) == true) ||
                (hudHeld && hud?.let(::overlaps) == true) ||
                (petDrag?.held == true && pet?.let(::overlaps) == true) || props.any { it.held && overlaps(it) })
        ) return false
        overlayDucked = path != null
        if (overlayDucked) {
            petDrag?.cancel()
            props.forEach { it.cancelDrag() }
            finishToyFlights()
        }
        var ok = setChipDucked(jobBar?.let(::overlaps) == true)
        pet?.let { if (!duckLayout(it, petParams, overlaps(it), DUCK_ALPHA)) ok = false }
        if (!setHudDucked(hud?.let(::overlaps) == true)) ok = false
        props.forEach { prop -> if (!duckLayout(prop, prop.params, overlaps(prop))) ok = false }
        (fading + listOfNotNull(bubble, bubbleExit?.view, thinking)).forEach { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                val wasHidden = params.alpha == 0f
                if (!duckLayout(view, params, overlaps(view), alwaysUntouchable = true)) ok = false
                if (view === bubble && wasHidden && params.alpha > 0f && bubbleHideAt != Long.MAX_VALUE) {
                    bubbleHideAt = maxOf(bubbleHideAt, SystemClock.uptimeMillis() + 3_000L)
                }
            }
        }
        return ok
    }

    private fun duckOverlay(path: Rect?): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return applyDuck(path)
        val done = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + DUCK_WAIT_MS
        var clear = false
        if (!handler.post {
                try {
                    if (path == null || SystemClock.uptimeMillis() < deadline) clear = applyDuck(path)
                } catch (e: Exception) {
                    Diag.caught("move overlay aside", e)
                } finally {
                    done.countDown()
                }
            }
        ) return false
        return runCatching { done.await(DUCK_WAIT_MS, TimeUnit.MILLISECONDS) && clear }.getOrDefault(false)
    }

    /**
     * The bottom left corner, or wherever the chip was last dragged to, and above the keyboard
     * when one is up so the way out of a job is never behind the thing the job opened.
     *
     * The corner is the point: a job presses what it needs pressed all over the screen, and the
     * middle of the bottom edge is the one strip a list row, a keyboard's space bar or a game's
     * control is most likely to be in. A corner is small, out of the way of the app's own
     * controls, and — with the drag — somewhere the user can put out of the way entirely.
     */
    private fun positionJobBar() {
        val view = jobBar ?: return
        val params = jobBarParams ?: return
        val spanX = (screenW - params.width).coerceAtLeast(0)
        val spanY = (screenH - params.height - topInset).coerceAtLeast(0)
        val spot = chipSpot

        params.x = if (spot == null) {
            dpInt(10f)
        } else {
            (spot.first * spanX).toInt().coerceIn(0, spanX)
        }

        var y = if (spot == null) {
            (screenH - bottomInset - params.height - dpInt(14f))
        } else {
            (topInset + spot.second * spanY).toInt().coerceIn(topInset, topInset + spanY)
        }

        // A keyboard takes the bottom of the screen. The chip stays visible above it instead of
        // hiding under it: a Stop nobody can reach is the same as no Stop.
        val floor = if (keyboardUp) keyboardCeiling() else (screenH - bottomInset).toFloat()
        if (y + params.height > floor - dpInt(4f)) {
            y = (floor - params.height - dpInt(14f)).toInt()
        }

        params.y = y.coerceIn(topInset, (screenH - params.height).coerceAtLeast(topInset))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * One finger, one target: still means stop, moving means put the chip down somewhere else.
     * The measured distance is from where the finger went down rather than added per frame, so
     * a dropped frame cannot make the chip lag behind the finger holding it.
     */
    private val jobBarTouch = View.OnTouchListener { _, event ->
        val params = jobBarParams ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                chipDownX = event.rawX
                chipDownY = event.rawY
                chipLeft = params.x
                chipTop = params.y
                chipMoved = false
                chipDragging = true
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - chipDownX
                val dy = event.rawY - chipDownY
                if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) chipMoved = true
                if (chipMoved && !overlayDucked) {
                    params.x = (chipLeft + dx).toInt()
                        .coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                    params.y = (chipTop + dy).toInt()
                        .coerceIn(topInset, (screenH - params.height).coerceAtLeast(topInset))
                    jobBar?.let { view ->
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                chipDragging = false
                if (chipMoved) {
                    // Kept, so the chip stays where it was put for the whole job and for the
                    // next one: a place the user chose is better than a place guessed at.
                    rememberChipSpot(params)
                } else {
                    stopJob()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                chipDragging = false
                // A press the system took away is not a stop: the chip just goes back to where
                // it belongs.
                if (chipMoved) rememberChipSpot(params) else if (!overlayDucked) positionJobBar()
                true
            }

            else -> false
        }
    }

    /** Saves the chip's place, relative to the room it has to move in. */
    private fun rememberChipSpot(params: WindowManager.LayoutParams) {
        val spanX = (screenW - params.width).coerceAtLeast(1)
        val spanY = (screenH - params.height - topInset).coerceAtLeast(1)
        val spot = (params.x.toFloat() / spanX).coerceIn(0f, 1f) to
            ((params.y - topInset).toFloat() / spanY).coerceIn(0f, 1f)
        chipSpot = spot
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("chip_x", spot.first)
            .putFloat("chip_y", spot.second)
            .apply()
    }

    /** Where the chip was left last time, or null if it has never been dragged. */
    private fun loadChipSpot(): Pair<Float, Float>? {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("chip_x") || !prefs.contains("chip_y")) return null
        return prefs.getFloat("chip_x", 0f) to prefs.getFloat("chip_y", 0f)
    }

    /**
     * The chip belongs to the job, so it goes the moment there is no job — finished, cancelled
     * here, cancelled by a new message, or ended by the service going away. Checking the one
     * source of truth covers every one of those without each caller having to remember.
     */
    private val jobTicker = object : Runnable {
        override fun run() {
            if (jobBar == null) return
            if (!Agent.running()) {
                hideJobBar()
                endJobWatch()
                return
            }
            // A chip being held follows the finger; only one that is resting is put back in
            // its place (or lifted above a keyboard that has just arrived).
            if (!chipDragging && !overlayDucked) positionJobBar()
            handler.postDelayed(this, JOB_REFRESH_MS)
        }
    }

    /**
     * He moves out of the way of the app he is working in: the bottom right, low down, where a
     * header, a list and most keyboards keep clear. He stays there until the job is over
     * instead of wandering across the screen being watched.
     */
    private fun beginJobWatch(turn: Long, approach: JobApproachGate) {
        finishJobWalk(false)
        petDrag?.cancel()
        cancelToyHop()
        cancelGardenVisit()
        attentive = true
        toyWalk = false
        relocating = false
        arrive = null
        walking = false
        jobWalk = JobWalk(turn, approach)
    }

    private fun advanceJobWalk(view: PetView): Boolean {
        val walk = jobWalk ?: return false
        if (!Agent.running() || !ChatTurns.isCurrent(walk.turn) || !screenUsable() || !view.isAttachedToWindow) {
            finishJobWalk(false)
            return false
        }
        val cornerX = (screenW - petW - dpInt(10f)).coerceAtLeast(0).toFloat()
        val cornerY = (screenH - bottomInset - petH - dpInt(10f)).toFloat()
        val floor = if (keyboardShowing()) keyboardCeiling() - keyboardAbove() - petH - dp(6f) else cornerY
        val destination = DragMotion.Point(cornerX, minOf(cornerY, floor).coerceAtLeast(topInset.toFloat()))
        // Replan from the current position if a keyboard or rotation moves the parking spot.
        val path = walk.path?.takeIf { it.destination == destination }
            ?: JobApproach(DragMotion.Point(posX, posY), destination, dp(1f)).also { walk.path = it }
        val next = path.advance(frameMs)
        if (abs(destination.x - posX) > 1f) view.facingRight = destination.x > posX
        posX = next.x
        posY = next.y
        applyPetPosition()
        view.walking = !path.arrived
        if (path.arrived) finishJobWalk(true)
        return true
    }

    private fun finishJobWalk(arrived: Boolean) {
        val walk = jobWalk ?: return
        jobWalk = null
        walking = false
        relocating = false
        targetX = posX
        targetY = posY
        pet?.walking = false
        walk.gate.finish(arrived)
    }

    /** Job over: he may wander again, once the line he says has had its moment. */
    private fun endJobWatch() {
        finishJobWalk(false)
        doomscrollJob = false
        automaticDoomscrollJob = false
        pet?.showDoomscrollCue(DoomscrollMotion.Cue.CLEAR)
        // A job that ended in the middle of a step — cancelled, or the service going away —
        // must not leave his windows unable to be tapped afterwards.
        // Screen restores any overlapping windows when the in-flight Android gesture ends.
        // Don't restore them here: Stop may arrive in the middle of a swipe.
        // An open chat holds his attention itself and clears it when it closes.
        if (inputBar != null) return
        attentive = false
        idleUntil = SystemClock.uptimeMillis() + 1500L
    }

    /** The button at the bottom: stop where he is, and say so. */
    private fun stopJob() {
        hideJobBar()
        if (!Agent.running()) {
            endJobWatch()
            return
        }
        Agent.cancel()
        endJobWatch()
        sayAloud("Stopped.")
    }

    /**
     * The window already declares SOFT_INPUT_STATE_ALWAYS_VISIBLE, so the keyboard comes
     * up with the bar; this single nudge only covers an IME that connects late.
     */
    private fun showKeyboard() {
        handler.removeCallbacks(showKeyboardNudge)
        handler.postDelayed(showKeyboardNudge, 120L)
    }

    private val showKeyboardNudge = object : Runnable {
        override fun run() {
            val field = inputField ?: return
            field.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        handler.removeCallbacks(showKeyboardNudge)
        val token = inputField?.windowToken ?: return
        ChatRecovery.attempt(Unit, { Diag.chatFailure("chat keyboard", it) }) {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
        }
    }

    // ------------------------------------------------------------------- window

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (voicedReply != null) {
                        hideBubble()
                        hideThinking()
                    }
                    PipVoice.stop()
                    if (ending) {
                        finishExit()
                        return
                    }
                    cancelPowerAnimation()
                    finishToyFlights()
                    pet?.powerPose = null
                    cancelToyHop()
                    cancelGardenVisit()
                    paused = true
                    handler.removeCallbacks(tick)
                    if (Agent.running() || doomscrollJob || jobWalk != null) {
                        Agent.cancel()
                        hideJobBar()
                        endJobWatch()
                    }
                    petDrag?.cancel()
                    props.forEach { it.cancelDrag() }
                    pet?.walking = false
                    // Nothing is looking at him: stop repainting the idle sway.
                    pet?.animating = false
                }
                // Awake or unlocked: check the real deadline, since the timer sleeps with
                // the phone. A locked one is skipped here and caught by USER_PRESENT.
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (ending) return
                    val resume = paused
                    paused = false
                    pet?.animating = true
                    if (resume) handler.post(tick)
                    settleBreak()
                }
            }
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
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
            NotificationChannel(PipPermissions.STATUS_CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW).apply {
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

        return Notification.Builder(this, PipPermissions.STATUS_CHANNEL)
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
