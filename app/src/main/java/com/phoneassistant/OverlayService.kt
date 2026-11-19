package com.phoneassistant

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

        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "assistant"
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

        /** How long a toy stays out before he has had enough of it. */
        private const val TOY_LIFE_MS = 4 * 60_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
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
        fun speak(text: String) {
            instance?.modelSays(text)
        }

        /** Main-screen buttons: put one of Pip's toys on the screen. */
        fun bringOut(toy: Toy): Boolean {
            val service = instance ?: return false
            // Whether toys are allowed is decided in one place; this only adds his protest
            // when the reason is the break.
            val outcome = Toys.summon(service, toy)
            if (!outcome.ok && Quiet.active(service)) {
                service.sayAloud("I'm on a break. you asked.")
            }
            return outcome.ok
        }

        /** What is out on the screen right now, for the prompt. Empty when nothing is. */
        fun toysOnStage(): String = instance?.let { Toys.summary(it) } ?: ""

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
            service.sayAloud("break time. *puts the tools down* I'll be here, being patient.")
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
        val petY: Int,
        val petHeightPx: Int,
        val props: List<String>,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val bottomInsetPx: Int
    )

    private lateinit var windowManager: WindowManager
    private lateinit var petParams: WindowManager.LayoutParams
    private var pet: PetView? = null

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
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
    private var chipDucked = false

    /** The same for Pip himself: he is a touchable window too, and stands in a corner. */
    private var petDucked = false

    /** True for the moment a step is pressing, so anything created meanwhile starts aside. */
    private var overlayDucked = false

    /** The same for the floating diagnostics panel and for the toys. */
    private var hudDucked = false
    private var propsDucked = false

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

    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(System.currentTimeMillis())

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
    private var relocating = false
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
        keyboardHeightPx = Keyboard.rememberedPx(this)
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
        view.setOnTouchListener(touchListener)
        pet = view

        val added = runCatching { windowManager.addView(view, petParams) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }

        idleUntil = SystemClock.uptimeMillis() + 2500L
        registerScreenReceiver()
        ImeWatch.onChange = { ime -> handler.post { onImeChanged(ime) } }
        handler.post(tick)
    }

    /**
     * Height of the keyboard, whether it is ours or another app's. Zero when none is up.
     *
     * The accessibility service is the one trusted source. A floating overlay is only ever
     * sent an IME inset for a keyboard it raised itself, so nothing inside this service can
     * see another app's typing, and a probe window that pretends otherwise is just a guess.
     * His own box does not need this: it steps clear as soon as it opens, using the height
     * remembered from the last time it was measured.
     */
    private fun imeHeight(): Int = ImeWatch.heightPx

    /** Read by the diagnostics screen; every number here is one the app actually uses. */
    private fun status(): Status = Status(
        imeReportedPx = imeHeight(),
        keyboardUp = keyboardUp,
        rememberedPx = keyboardHeightPx,
        ceilingPx = keyboardCeiling().toInt(),
        petY = posY.toInt(),
        petHeightPx = petH,
        props = props.toList().map { prop -> prop.javaClass.simpleName },
        screenWidthPx = screenW,
        screenHeightPx = screenH,
        bottomInsetPx = bottomInset
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
        hudDucked = overlayDucked
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
        hudDucked = false
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
                // A step that began while the panel was held left it alone; let go, it steps
                // aside for the rest of that step instead of staying in the way.
                if (overlayDucked) setHudDucked(true)
                true
            }

            else -> false
        }
    }

    /** Called when the accessibility service sees a keyboard appear, move or go. */
    private fun onImeChanged(ime: Int) {
        keyboardUp = ime > 0
        if (ime == 0) return
        if (abs(ime - keyboardHeightPx) > dpInt(2f)) {
            keyboardHeightPx = ime
            Keyboard.remember(this, ime)
        }
        walkClearOfKeyboard()
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
        ImeWatch.onChange = null
        keyboardUp = false
        pet?.let { view -> runCatching { windowManager.removeView(view) } }
        pet = null
        runCatching { unregisterReceiver(screenReceiver) }
        Quiet.onChanged = null
        instance = null
        running = false
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
        return (awaitingReply && now - replyAskedAt < 15_000L) || now < replyUntil
    }

    /** From here until the reply lands, only the model speaks. */
    private fun beginReply() {
        awaitingReply = true
        replyAskedAt = SystemClock.uptimeMillis()
    }

    private fun modelSays(text: String) {
        awaitingReply = false
        replyUntil = SystemClock.uptimeMillis() + REPLY_HOLD_MS
        sayAloud(text)
    }

    override fun feel(mood: PetView.Mood, ms: Long) {
        val view = pet ?: return
        view.mood = mood
        if (ms > 0L) handler.postDelayed({ view.mood = PetView.Mood.NEUTRAL }, ms)
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

    /** Called by toys only, so every walk from here is one he has been sent on. */
    override fun petWalkTo(x: Float, y: Float, done: (() -> Unit)?) {
        targetX = (x - petW / 2f).coerceIn(0f, (screenW - petW).toFloat())
        targetY = (y - petH / 2f)
            .coerceIn(topInset + dp(8f), (screenH - petH - bottomInset).toFloat())
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

    /**
     * A toy has been put down. While a step is pressing, a held toy is left touchable — taking
     * its touch away would end the drag it is in the middle of — but the moment it is let go
     * that reason is gone, so it steps aside at once, exactly as the diagnostics panel does.
     */
    override fun propLetGo(prop: Prop) {
        if (!overlayDucked) return
        if (prop.leaving) return
        prop.params.flags = duckFlags(prop.params.flags, true)
        runCatching { windowManager.updateViewLayout(prop, prop.params) }
    }

    override fun addProp(prop: Prop): Boolean {
        if (props.contains(prop)) return true
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
                (if (overlayDucked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prop.posX.toInt()
            y = prop.posY.toInt()
        }
        prop.params = params
        if (!runCatching { windowManager.addView(prop, params) }.isSuccess) return false
        props += prop
        prop.appear()
        keepBarClear()
        return true
    }

    override fun dropProp(prop: Prop) {
        if (!props.remove(prop)) return
        // Released here rather than when the window finally detaches: the fade takes 170ms,
        // and a new stand summoned in that window would have its post wiped by the old one
        // leaving.
        if (prop === postOwner) petRelease()
        // Gone from the logic at once, so nothing reacts to a toy that has left, but off the
        // screen gently: it shrinks away instead of blinking out.
        prop.fadeAway()
        fadeOut(prop)
    }

    /**
     * Removes a window once it has faded. It is tracked until then, so a service that stops
     * in the middle of a fade cannot leave the window behind.
     */
    private fun fadeOut(view: View) {
        fading += view
        handler.postDelayed({
            fading -= view
            runCatching { windowManager.removeView(view) }
        }, FADE_MS)
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
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard?.isKeyguardLocked != true
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
            if (!paused) step()
            handler.postDelayed(this, frameMs)
        }
    }

    private fun step() {
        val view = pet ?: return
        val now = SystemClock.uptimeMillis()

        if (bubble != null && now >= bubbleHideAt) fadeBubble()

        keyboardWatch -= frameMs
        if (keyboardWatch <= 0L) {
            keyboardWatch = 400L
            watchKeyboard()
        }

        // Toys animate and run their own physics whether or not he is paying attention.
        if (props.isNotEmpty()) {
            // Caught rather than left to crash the tick, but recorded: a toy throwing every
            // frame used to freeze that toy silently, with nothing anywhere saying why.
            props.toList().forEach { prop ->
                try {
                    prop.onFrame()
                } catch (t: Throwable) {
                    Diag.caught(prop.javaClass.simpleName + ".onFrame", t)
                }
            }
            retireIfStale()
        }

        watchCountdown += frameMs
        if (watchCountdown >= 15_000L) {
            watchCountdown = 0L
            currentAppLabel =
                if (AppWatcher.hasPermission(this)) AppWatcher.currentAppLabel(this) else null
        }

        // Nothing on screen for a few minutes: he gets bored and makes his own fun.
        if (!attentive && !walking && props.isEmpty()) {
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
        val station = post
        if (station != null && (postArrival != null || postReturn) && !attentive && !walking &&
            !inKeyboard(station.second)
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
        } else if (!attentive && post == null && now > idleUntil) {
            // A post outranks being bored: he keeps station until the toy lets him go.
            pickTarget(now)
        }
    }

    /** Long pauses between short strolls — he should feel relaxed, not busy. */
    private fun idleDelay(): Long = 12_000L + rnd.nextLong(16_000L)

    private fun pickTarget(now: Long) {
        val maxX = (screenW - petW).coerceAtLeast(0).toFloat()
        val minY = topInset + dp(8f)
        // Nowhere in the keyboard, whoever is typing in it.
        val floor = if (keyboardUp) keyboardCeiling() else (screenH - bottomInset).toFloat()
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

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragged = false
                walking = false
                toyWalk = false
                relocating = false
                arrive = null
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

    // ------------------------------------------------------------ anchored panels

    /** Places a panel above him, or below when there is no room at the top. */
    private fun placePanel(width: Int, height: Int, params: WindowManager.LayoutParams) {
        var x = (posX + petW / 2f - width / 2f).toInt()
        x = x.coerceIn(dpInt(6f), (screenW - width - dpInt(6f)).coerceAtLeast(dpInt(6f)))

        val above = (posY - height - dpInt(8f)).toInt()
        val y = if (above >= topInset) above else (posY + petH + dpInt(8f)).toInt()

        params.x = x
        params.y = y
    }

    private fun repositionPanels() {
        bubble?.let { view ->
            bubbleParams?.let { params ->
                placePanel(view.width, view.height, params)
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        thinking?.let { view ->
            thinkingParams?.let { params ->
                placePanel(view.width, view.height, params)
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

        if (runCatching { windowManager.addView(view, params) }.isSuccess) {
            bubble = view
            bubbleParams = params
            bubbleHideAt = SystemClock.uptimeMillis() + bubbleLifetime(text)
            // It grows into place rather than appearing, which is most of what makes a line
            // feel spoken instead of printed.
            view.alpha = 0f
            view.scaleX = 0.9f
            view.scaleY = 0.9f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    /** The bubble has had its time: it leaves the way it arrived, only quicker. */
    private fun fadeBubble() {
        val view = bubble ?: return
        bubble = null
        bubbleParams = null
        bubbleHideAt = 0L
        view.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(130L)
            .start()
        fadeOut(view)
    }

    private fun hideBubble() {
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        bubbleParams = null
        bubbleHideAt = 0L
    }

    // ---------------------------------------------------------------- thinking

    private fun startThinking() {
        attentive = true
        walking = false
        toyWalk = false
        relocating = false
        arrive = null
        pet?.walking = false
        pet?.mood = PetView.Mood.THINKING
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

    private fun sayAloud(text: String) {
        // A reply can land after the overlay has been stopped, and a bubble added to a dead
        // service is a window nothing will ever take down again.
        if (!running) return
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
        showBubble(text)
        // Being smug or annoyed outranks talking: he wants you to see his face.
        val mood = pet?.mood
        if (mood != PetView.Mood.ANNOYED && mood != PetView.Mood.SMUG) {
            pet?.mood = PetView.Mood.TALKING
            handler.postDelayed({ pet?.mood = PetView.Mood.NEUTRAL }, 1600L)
        }
    }

    private fun toggleChat() {
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
        onImeChanged(imeHeight())
    }

    /**
     * If the bar would sit under the keyboard, he walks straight up until the box clears
     * it. He keeps his column: the bar follows him and is kept on screen on its own, so
     * there is nothing to be gained by sliding sideways — and re-targeting on every probe
     * sample used to drag him into a corner.
     */
    private fun walkClearOfKeyboard() {
        val ceiling = keyboardCeiling()
        // With his own box open the box has to clear the keyboard; on somebody else's
        // keyboard he only has to keep himself out of it. The bar has no height on the
        // frame it is added, so fall back to its usual size rather than under-clearing.
        val barHeight = if (inputBar == null) {
            dp(8f)
        } else {
            inputBar?.height?.toFloat()?.takeIf { it > 0f } ?: dp(62f)
        }
        val neededY = ceiling - petH - dp(6f) - barHeight
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
     * Whether standing with his middle at [y] would put him in the keyboard. Without this
     * the post would walk him back into the keyboard that just moved him off it, and the two
     * would trade places every frame.
     */
    private fun inKeyboard(y: Float): Boolean =
        keyboardUp && y + petH / 2f > keyboardCeiling()

    /** Top of the keyboard, or a sensible guess before it has ever been measured. */
    private fun keyboardCeiling(): Float =
        if (keyboardHeightPx > 0) {
            (screenH - keyboardHeightPx).toFloat() - dp(10f)
        } else {
            screenH * 0.52f
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
        keepBarClear()
    }

    /**
     * He gets bored of things he put out himself. The oldest toy on screen, once it has been
     * there its full welcome, is packed away with a line about it.
     */
    private fun retireIfStale() {
        val oldest = props.minByOrNull { it.bornAt } ?: return
        if (SystemClock.uptimeMillis() - oldest.bornAt < TOY_LIFE_MS) return
        val toy = Toy.values().firstOrNull { it.owns(oldest) } ?: return
        Toys.retire(this, toy)
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

        Memory.append(this, "user", text)
        // A new message outranks whatever he was doing with his hands: half-finished work in
        // another app would be acting on an instruction the user has already replaced.
        Agent.cancel()
        // His answer takes priority from this moment on, so the toys' own lines stay quiet.
        beginReply()
        // Thinking comes before the toys act. The other way round, thinking cancelled the
        // walk a toy had just started — and with it the arrival callback — so a pour or a
        // painting never began at all.
        startThinking()
        // Some things are done, not said: stands get opened, games get started, and
        // criticism gets taken personally.
        val event = Toys.reactTo(this, text)

        AssistantBrain.reply(this, currentAppLabel, Toys.summary(this), event) { ok, reply ->
            handler.post {
                // Sometimes the answer is not a line but a job for his hands to do.
                val job = if (ok) Agent.delegated(reply) else null
                if (job != null) {
                    startJob(job)
                    return@post
                }
                if (ok) Memory.append(this, "assistant", reply)
                modelSays(reply)
                handler.postDelayed({
                    pet?.mood = PetView.Mood.NEUTRAL
                    handler.postDelayed({
                        if (!typing()) closeChat()
                    }, 2600L)
                }, 1400L)
            }
        }
    }

    private fun typing(): Boolean = inputField?.text?.isNotEmpty() == true

    // ------------------------------------------------------------------ phone jobs

    /**
     * Runs a job the model handed over: one action at a time, reading the screen between
     * them, through the accessibility service. The thinking dots stay up until his first
     * line replaces them, so there is never a stretch where nothing says he is working.
     *
     * The job speaks and finishes on its own thread, so both callbacks come back to the main
     * looper before they touch a window.
     */
    private fun startJob(job: String) {
        // The chat model has answered, so nothing is waiting on it any more: the toys may
        // talk again while the job runs.
        awaitingReply = false
        val started = Agent.start(
            this,
            job,
            onSay = { line -> handler.post { if (running) sayAloud(line) } },
            onDone = { line ->
                handler.post {
                    if (!running) return@post
                    Memory.append(this, "assistant", line)
                    sayAloud(line)
                    feel(PetView.Mood.HAPPY, 2500L)
                }
            },
            // For as long as the phone is being touched, his own windows are inert: they
            // step out of the way of the press instead of catching it. This call does not
            // return until they really are out of the way — a gesture dispatched before the
            // window was updated is exactly the press the duck is there to protect.
            onAct = { touching -> duckOverlay(touching) }
        )
        if (!started) {
            hideJobBar()
            sayAloud(
                if (Screen.available()) {
                    "I'm already in the middle of something."
                } else {
                    Agent.NO_HANDS
                }
            )
            return
        }
        // His hands are busy: he stands out of the way of the app he is working in, and the
        // way out of the job appears at the bottom of the screen.
        beginJobWatch()
        showJobBar()
    }

    // --------------------------------------------------------------------- job bar

    /**
     * The cancel chip, for as long as a job is running. It is its own window so it stays over
     * the app being worked on, and it sits at the bottom because that is where a stop belongs
     * and where it covers the least of what he is doing.
     */
    private fun showJobBar() {
        if (jobBar != null) return
        chipDucked = false
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
        chipDucked = false
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
    private fun setChipDucked(ducked: Boolean) {
        if (jobBar == null) return
        if (chipDucked == ducked) return
        val view = jobBar ?: return
        val params = jobBarParams ?: return
        chipDucked = ducked
        params.flags = duckFlags(params.flags, ducked)
        // The fade-in from showJobBar would otherwise bring it back to full while it is
        // still stepping aside, so the animation is stopped before the alpha is set.
        view.animate().cancel()
        view.alpha = if (ducked) DUCK_ALPHA else 1f
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Pip gets out of the way too. He is a touchable window and he stands in a corner for the
     * whole job, so a step pressing a row that happens to be under him would drag him about
     * instead of pressing the row. He is not dimmed — he is not a way out of anything, so
     * there is nothing to warn the user about — he just stops taking the touch for a moment.
     */
    private fun setPetDucked(ducked: Boolean) {
        if (petDucked == ducked) return
        val view = pet ?: return
        petDucked = ducked
        petParams.flags = duckFlags(petParams.flags, ducked)
        runCatching { windowManager.updateViewLayout(view, petParams) }
    }

    /**
     * The floating diagnostics panel. It is touchable so it can be dragged, which means it can
     * catch a press meant for the app underneath, exactly as the chip can.
     */
    private fun setHudDucked(ducked: Boolean) {
        val view = hud ?: return
        val params = hudParams ?: return
        if (hudDucked == ducked) return
        // A panel being dragged keeps its touch: taking it away mid-drag would end the drag
        // the user is in the middle of, which is worse than the press it might miss.
        if (ducked && hudHeld) return
        hudDucked = ducked
        params.flags = duckFlags(params.flags, ducked)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * The toys. Each one is a touchable window the user can put down anywhere, so any of them
     * can be sitting exactly where a step wants to press; they all step aside together.
     *
     * The one being dragged is left alone — it is in a hand, and taking the touch away would
     * drop it — so it is ducked at the next step instead of this one.
     */
    private fun setPropsDucked(ducked: Boolean) {
        if (propsDucked == ducked) return
        propsDucked = ducked
        props.forEach { prop ->
            if (ducked && prop.held) return@forEach
            prop.params.flags = duckFlags(prop.params.flags, ducked)
            runCatching { windowManager.updateViewLayout(prop, prop.params) }
        }
    }

    /** Adds or removes the one flag that decides whether a window can catch a press. */
    private fun duckFlags(flags: Int, ducked: Boolean): Int =
        if (ducked) {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }

    /**
     * Every window of the overlay that can take a touch, aside for a step or back again.
     *
     * The bubble and the thinking dots are already FLAG_NOT_TOUCHABLE for their whole lives, so
     * a job's press has always gone straight through them. Everything else — the chip, the
     * character, the floating diagnostics panel and every toy — takes fingers and has to move.
     */
    private fun applyDuck(ducked: Boolean) {
        overlayDucked = ducked
        setChipDucked(ducked)
        setPetDucked(ducked)
        setHudDucked(ducked)
        setPropsDucked(ducked)
    }

    /**
     * [applyDuck] on the main looper, waited for.
     *
     * A step calls this from the worker thread and then injects its gesture, so returning
     * before the windows have been updated would leave the press able to land on them — the
     * whole point is that the windows are out of play first. The wait is bounded, so a main
     * thread that is not running cannot hang the job: the answer is then false, and a step
     * that gets false presses nothing at all.
     */
    private fun duckOverlay(ducked: Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyDuck(ducked)
            return true
        }
        val done = CountDownLatch(1)
        val posted = handler.post {
            runCatching { applyDuck(ducked) }
            done.countDown()
        }
        if (!posted) return false
        return runCatching { done.await(DUCK_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
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
                if (chipMoved) {
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
                if (chipMoved) rememberChipSpot(params) else positionJobBar()
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
            if (!chipDragging) positionJobBar()
            handler.postDelayed(this, JOB_REFRESH_MS)
        }
    }

    /**
     * He moves out of the way of the app he is working in: the bottom right, low down, where a
     * header, a list and most keyboards keep clear. He stays there until the job is over
     * instead of wandering across the screen being watched.
     */
    private fun beginJobWatch() {
        attentive = true
        toyWalk = false
        relocating = false
        arrive = null
        targetX = (screenW - petW - dpInt(10f)).toFloat().coerceAtLeast(0f)
        targetY = (screenH - bottomInset - petH - dpInt(10f)).toFloat()
            .coerceAtLeast(topInset.toFloat())
        walking = true
        pet?.walking = true
    }

    /** Job over: he may wander again, once the line he says has had its moment. */
    private fun endJobWatch() {
        // A job that ended in the middle of a step — cancelled, or the service going away —
        // must not leave his windows unable to be tapped afterwards.
        duckOverlay(false)
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
        val field = inputField ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    // ------------------------------------------------------------------- window

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    paused = true
                    pet?.walking = false
                }
                // Awake or unlocked: check the real deadline, since the timer sleeps with
                // the phone. A locked one is skipped here and caught by USER_PRESENT.
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    paused = false
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
