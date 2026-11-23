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
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
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

    private val wakeStartup = WakeStartupPolicy()

    companion object {
        @Volatile
        var running: Boolean = false
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

        /**
         * Height, in dp, of the row a keyboard sits under: a chat's message box, a comment
         * field, a search bar. He keeps this much room between himself and any keyboard that
         * is not his own, so the box being typed into stays visible instead of being stood
         * on — clearing the keyboard alone leaves his feet on the keyboard's top edge, which
         * is exactly where that row begins.
         */
        private const val INPUT_ROW_DP = 60f

        private const val START_FROM_APP = "com.phoneassistant.START_FROM_APP"
        private const val START_FROM_BOOT = "com.phoneassistant.START_FROM_BOOT"

        internal fun startFromBoot(ctx: Context): Boolean = requestStart(ctx, START_FROM_BOOT)

        /** Called only when a Pip activity has window focus, not from a boot/unlock receiver. */
        internal fun onPipWindowFocused() {
            val service = instance ?: return
            if (!service.screenUsable()) return
            if (service.wakeStartup.appVisible(PipStartup.listenOnStart(service), service.readyForWakeStartup(),
                    PipStartup.enabled(service))) service.startListeningOnStartup(showFailure = true)
        }

        /** Only the visible Start assistant button uses this path, never the boot receiver. */
        internal fun startFromApp(activity: android.app.Activity): Boolean = requestStart(activity, START_FROM_APP)

        private fun requestStart(ctx: Context, action: String): Boolean = try {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(action)) != null
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

        internal fun heardVoice(text: String, stillListening: () -> Boolean, done: (WakeFeedback) -> Unit) {
            val service = instance
            if (service == null || service.ending || !running) done(WakeFeedback.BUSY)
            else service.handleVoice(text, stillListening, done)
        }

        internal fun discardVoiceDraft() {
            instance?.let {
                it.voiceExchange.clear()
            }
        }

        internal fun previewBubble(): Boolean {
            val service = instance ?: return false
            if (service.ending || !service.bubbleScreenUsable() || service.awaitingReply || service.voicedReply != null) return false
            service.sayAloud("This is Pip’s speech bubble.")
            return true
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
                it.voiceExchange.clear()
                it.hideThinking()
                it.hideBubble()
                it.leaveAttention()
            }
            PipVoice.stop()
        }

        /** How often the floating readout repaints. Fast enough to watch a keyboard arrive. */
        internal const val HUD_REFRESH_MS = 250L

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

    internal lateinit var windowManager: WindowManager
    private lateinit var petParams: WindowManager.LayoutParams
    private var pet: PetView? = null
    private var petHost: PetView? = null
    private var bubbleHost: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleBox: OverlayBounds.Box? = null
    private var bubbleRetryAt = 0L
    private val captionIdlePause = CaptionIdlePause()
    private val overlayLocation = IntArray(2)

    private var voicedReply: ReplyBubbleState? = null
    private lateinit var speechBubble: SpeechBubble

    private val voiceExchange = VoiceExchange()
    private var screenOffGeneration = 0L
    private var inputBar: LinearLayout? = null
    private var inputField: EditText? = null
    private var inputParams: WindowManager.LayoutParams? = null

    /** The floating diagnostics readout, while it has been asked for. */
    internal var hud: DiagHud? = null
    internal var hudParams: WindowManager.LayoutParams? = null

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
    internal var overlayDucked = false

    /** The same for the floating diagnostics panel and for the toys. */

    /** True while the diagnostics panel is being dragged: it keeps its touch then. */
    internal var hudHeld = false

    /** True while a finger is on the chip, so the ticker does not drag it back mid-drag. */
    private var chipDragging = false
    private var chipMoved = false
    private var chipDownX = 0f
    private var chipDownY = 0f
    private var chipLeft = 0
    private var chipTop = 0

    /** Where the panel was when the finger went down, so a drag follows it exactly. */
    internal var hudDownX = 0f
    internal var hudDownY = 0f
    internal var hudLeft = 0
    internal var hudTop = 0

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

    internal val handler = Handler(Looper.getMainLooper())
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



    internal var screenW = 1
    internal var screenH = 1
    internal var topInset = 0
    internal var bottomInset = 0
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
    private val shiftedPitch = mutableMapOf<Prop, DragMotion.Point>()
    private var toyApproach: ToyApproach<Prop>? = null
    private val toyWalk get() = toyApproach != null
    private data class JobWalk(val turn: Long, val gate: JobApproachGate, var path: JobApproach? = null)
    private var jobWalk: JobWalk? = null
    private var relocating = false
    private var idleUntil = 0L
    private var attentive = false
    private var paused = false

    private var petDrag: WindowDrag? = null
    private var ending = false
    private var powerAnimator: ValueAnimator? = null
    private var toyReachAnimator: ValueAnimator? = null
    private val closeAfterExit = Runnable { finishExit() }

    private val frameMs = 33L

    override fun onCreate() {
        super.onCreate()
        instance = this
        ScreenLocker.attach(this)
        running = true
        windowManager = getSystemService(WindowManager::class.java)
        speechBubble = SpeechBubble(this, limits = {
            (screenW - dpInt(12f)).coerceIn(1, dpInt(232f).coerceAtLeast(1)) to
                (screenH - topInset - bottomInset - dpInt(12f)).coerceIn(1, dpInt(280f).coerceAtLeast(1))
        }, onBoundsChanged = ::layoutSpeechBubble)

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
        speechBubble.setScreenHidden(!bubbleScreenUsable())

        // Every start, extension and early end reconciles the timer with the new deadline.
        Quiet.onChanged = { settleBreak() }
        settleBreak()

        petParams = stageWindow(petW, petH).apply {
            x = posX.toInt()
            y = posY.toInt()
        }

        val view = PetView(this)
        petDrag = createPetDrag(view)
        // Consume the gesture on Pip rather than passing it through to whichever app is under him.
        view.setOnTouchListener { v, event -> bubbleHopLocksInput() || petDrag?.onTouch(v, event) == true }
        // The screen may already be off when he starts — a restarted service, or a start from
        // the notification — and no broadcast is coming to say so, so the first answer comes
        // from the power manager rather than from the next screen event. Set before the view
        // is attached, so there is not even one frame drawn into a dark display.
        view.animating = !paused
        val entrance = canAnimatePower()
        if (entrance) view.powerPose = PipTransition.pose(entering = true, fraction = 0f)
        pet = view

        petHost = view
        val added = runCatching { windowManager.addView(view, petParams) }.isSuccess
        if (!added) {
            petHost = null
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
        readDisplayBounds()
        bubbleBox = null
        petDrag?.cancel()
        cancelToyHop()
        cancelGardenVisit()
        finishToyWalk(false)
        clearToySeat()
        shiftedPitch.clear()
        val bottom = (screenH - petH - bottomInset).toFloat()
        val right = (screenW - petW).toFloat()
        posX = OverlayBounds.clamp(posX, 0f, right)
        posY = OverlayBounds.clamp(posY, topInset.toFloat(), bottom)
        targetX = OverlayBounds.clamp(targetX, 0f, right)
        targetY = OverlayBounds.clamp(targetY, topInset.toFloat(), bottom)
        speechBubble.reflow()
        props.toList().forEach { it.moveTo(it.posX, it.posY) }
        (postOwner as? LemonadeStand)?.takePost()
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
    internal fun status(): Status = Status(
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

    /** The floating diagnostics panel repaints from here; its windows live in OverlayServiceHud.kt. */
    internal val hudTicker = object : Runnable {
        override fun run() {
            if (hud == null) return
            renderHud()
            handler.postDelayed(this, HUD_REFRESH_MS)
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
        val source = when (intent?.action) {
            START_FROM_APP -> WakeStartupPolicy.Source.APP
            START_FROM_BOOT -> WakeStartupPolicy.Source.BOOT
            else -> WakeStartupPolicy.Source.RECOVERY
        }
        // Android 11+ restricts mic access for a service started from the background.
        if (wakeStartup.start(source, PipStartup.listenOnStart(this), readyForWakeStartup(), PipStartup.enabled(this),
                bootMicAllowed = Build.VERSION.SDK_INT < 30, openAppAtBoot = PipStartup.openAppAtBoot(this))) {
            startListeningOnStartup(showFailure = source == WakeStartupPolicy.Source.APP)
        }
        if (source == WakeStartupPolicy.Source.BOOT) {
            onPowerStateChanged?.invoke()
            maybeOpenPipAfterBoot()
        }
        return if (ending || petHost == null) START_NOT_STICKY else START_STICKY
    }

    private fun maybeOpenPipAfterBoot() {
        if (!wakeStartup.hasPendingAppLaunch) return
        val optedIn = readyForWakeStartup() && PipStartup.enabled(this) && PipStartup.openAppAtBoot(this) &&
            Settings.canDrawOverlays(this) && AssistantBrain.setupComplete(this)
        if (!wakeStartup.takeAppLaunch(screenUsable(), optedIn)) return
        // Open the real app screen, not a transparent activity or a lock-screen window.
        // If the OS denies it, leave the mic deferred until the user opens Pip themselves.
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { Diag.caught("open Pip after boot", it) }
    }

    private fun readyForWakeStartup() = petHost != null && !ending

    private fun startListeningOnStartup(showFailure: Boolean) {
        if (WakeListenService.running) return
        val problem = WakeListenService.startProblem(this)
        val message = problem ?: if (WakeListenService.start(this)) null else
            "Android couldn’t start listening. Start it from Pip settings → Talk to Pip."
        if (showFailure && message != null) {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
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
        WakeListenService.stop(this)
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
        voicedReply = null
        speechBubble.clear()
        hideHud()
        props.toList().forEach(::dropProp)
        resetToIdle()
        petHost?.let { view ->
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
        voicedReply = null
        WakeListenService.stop(this)
        PipVoice.stop()
        cancelPowerAnimation()
        cancelToyReach()
        petDrag?.cancel()
        petDrag = null
        props.forEach { it.cancelDrag() }
        handler.removeCallbacksAndMessages(null)
        speechBubble.clear()
        hideSpeechWindow()
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
        petHost?.let { view -> runCatching { windowManager.removeViewImmediate(view) } }
        petHost = null
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
        return voicedReply != null || speechBubble.showingConversation || (awaitingReply && now - replyAskedAt < 15_000L) || now < replyUntil
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

    /**
     * A toy's sound. Routed through here rather than each prop holding the player, so one
     * switch covers every toy and his own voice can hush them. A toy sound never competes with
     * a spoken line: while the reply has the floor the effects wait for the next interaction.
     */
    override fun sound(effect: ToySounds.Sound, gain: Float) {
        if (replyHasFloor()) return
        ToyAudio.play(this, effect, gain)
    }

    override fun postDelayedSound(effect: ToySounds.Sound, delayMs: Long, gain: Float) {
        handler.postDelayed({ sound(effect, gain) }, delayMs.coerceIn(0L, 2_000L))
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
        cancelPetWalk(owner)
        approachPost(x, y)
    }

    override fun petIsNear(x: Float, y: Float, within: Float): Boolean =
        hypot(posX + petW / 2f - x, posY + petH / 2f - y) <= within

    override fun petOnScreen(): Boolean = pet != null

    override fun petCentreX(): Float = posX + petW / 2f

    override fun petFeetY(): Float = ToyFooting.feetY(posY.toInt().toFloat(), petH, dp(1f))

    /** Heads for the post; whatever is waiting there runs on arrival. */
    private fun approachPost(x: Float, y: Float) {
        val owner = postOwner ?: return
        if (toyWalk || !petCanPlay(owner)) return
        if (owner is LemonadeStand && !restoreCounterLayer(owner)) return
        petWalkTo(owner, x, y) { arrived ->
            if (arrived && postOwner === owner) firePostArrival()
        }
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
        postOwner?.let(::cancelPetWalk)
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
        finishToyWalk(false)
        relocating = false
        pet?.walking = false
        seatOwner = null
        pet?.sitting = false
    }

    private data class ToyHop(
        val owner: Prop, val started: Long, val duration: Long,
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val lift: Float,
        val ownerX: Float, val ownerY: Float,
        val finished: (Boolean) -> Unit,
        var groundY: Float = fromY
    )

    private var toyHop: ToyHop? = null
    private var raisedFor: Prop? = null
    private var counterBelowPip: LemonadeStand? = null

    private fun restoreCounterLayer(stand: LemonadeStand): Boolean {
        if (counterBelowPip !== stand) return true
        if (!runCatching { windowManager.removeViewImmediate(stand) }.isSuccess) return false
        if (!runCatching { windowManager.addView(stand, stand.params) }.isSuccess) {
            dropProp(stand)
            return false
        }
        counterBelowPip = null
        // Pip is no longer the top stage character. Raise him again for his next bubble hop.
        raisedFor = null
        raiseVisibleCaption()
        return true
    }

    private fun raiseVisibleCaption() {
        if (bubbleParams != null && hideSpeechWindow()) layoutSpeechBubble()
    }

    private data class GardenVisit(
        val owner: Prop, val ownerX: Float, val ownerY: Float, val started: Long, val travelMs: Long,
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val facesRight: Boolean,
        val grip: DragMotion.Point, val progress: (Float?) -> Unit, val watered: () -> Unit
    )
    private var gardenVisit: GardenVisit? = null

    override fun toyPlayPaused(): Boolean = ending || paused || powerAnimator != null ||
        inputBar != null || keyboardUp || overlayDucked || Agent.running()

    private fun toyActionBlocked(owner: Prop): Boolean = toyPlayPaused() || owner !in props || owner.inToyMotion ||
        relocating || seatOwner != null || (postOwner !== owner && postArrival != null) || standingStill()

    override fun petWaterPlant(owner: Prop, gripX: Float, gripY: Float, facesRight: Boolean,
                               progress: (Float?) -> Unit, watered: () -> Unit): Boolean {
        if (pet == null || gardenVisit != null || toyHop != null || toyWalk || toyActionBlocked(owner) ||
            owner.movingByUser || petDrag?.moving == true || !screenUsable()) return false
        if (post != null) petRelease()
        val grip = DragMotion.Point(gripX, gripY)
        val stance = WateringPose.stance(grip, facesRight, dp(1f))
        val tx = (stance.x - petW / 2f).coerceIn(0f, (screenW - petW).coerceAtLeast(0).toFloat())
        val minY = topInset.toFloat()
        val ty = OverlayBounds.clamp(ToyFooting.topForFeet(stance.y, petH, dp(1f)), minY, (screenH - petH - bottomInset).toFloat())
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
        extendToy(old.owner, 25_000L)
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
            view.gardenGrip = DragMotion.Point(visit.grip.x + (visit.owner.posX - visit.ownerX) - posX.toInt(),
                visit.grip.y + (visit.owner.posY - visit.ownerY) - posY.toInt())
            visit.progress(pour)
            if (pour >= 1f) {
                cancelGardenVisit()
                idleUntil = now + idleDelay()
                visit.watered()
            }
        }
        return true
    }

    // Chat captions/model latency aren't competing motion. Real input, jobs, and a request
    // to hold still are. An idle lemonade counter can be left, but not an unfinished pour.
    private fun bubbleHopBlocked(owner: Prop): Boolean = ending || paused || powerAnimator != null ||
        owner !in props || owner.inToyMotion || seatOwner != null || postArrival != null ||
        inputBar != null || keyboardUp || relocating || overlayDucked || Agent.running() || standingStill()

    private fun bubbleHopLocksInput(): Boolean {
        if (toyHop != null) return true
        if (gardenVisit != null || toyWalk || petDrag?.moving == true) return false
        val now = SystemClock.uptimeMillis()
        return props.any { it is BubbleWrap && it.reservesPetInput(now) && !it.movingByUser && !bubbleHopBlocked(it) }
    }

    override fun petHopOn(owner: Prop, x: Float, y: Float, finished: (Boolean) -> Unit): Boolean {
        val view = pet ?: return false
        if (toyHop != null || gardenVisit != null || toyWalk || owner.movingByUser || petDrag?.moving == true ||
            bubbleHopBlocked(owner) || !screenUsable()) return false

        if (raisedFor !== owner) {
            // Separate overlay windows are stacked in attachment order. Raise Pip once per
            // sheet so the jump and his feet are visible above the bubbles.
            val host = petHost ?: return false
            if (!runCatching { windowManager.removeViewImmediate(host) }.isSuccess) return false
            if (!runCatching { windowManager.addView(host, petParams) }.isSuccess) {
                stopSelf()
                return false
            }
            raisedFor = owner
            counterBelowPip = props.filterIsInstance<LemonadeStand>().firstOrNull()
            raiseVisibleCaption()
        }
        val tx = (x - petW / 2f).coerceIn(0f, (screenW - petW).coerceAtLeast(0).toFloat())
        val minY = topInset.toFloat()
        val ty = OverlayBounds.clamp(ToyFooting.topForFeet(y, petH, dp(1f)), minY, (screenH - petH - bottomInset).toFloat())
        val distance = hypot(tx - posX, ty - posY)
        val duration = (280f + distance / dp(1f) * 1.25f).toLong().coerceAtMost(710L)
        if (post != null) petRelease()
        toyHop = ToyHop(owner, SystemClock.uptimeMillis(), duration, posX, posY, tx, ty,
            (dp(16f) + distance * 0.18f).coerceAtMost(dp(48f)), owner.posX, owner.posY, finished)
        standStillNow()
        if (abs(tx - posX) > dp(2f)) view.facingRight = tx > posX
        return true
    }

    private fun finishToyHop(landed: Boolean) {
        val hop = toyHop ?: return
        toyHop = null
        // Feet on the sheet: the softest sound in the box, because it is the landing and not
        // the event. Only on a real landing — a cancelled hop went nowhere.
        if (landed) sound(ToySounds.Sound.TAP, 0.45f)
        pet?.apply { scaleX = 1f; scaleY = 1f }
        if (!landed) {
            // A moved sheet invalidates this target. Settle at the current horizontal spot
            // without leaving the next jump or keyboard walk starting in midair.
            posY = OverlayBounds.clamp(hop.groundY, topInset.toFloat(), (screenH - petH - bottomInset).toFloat())
            if (!ending && !paused && !overlayDucked) applyPetPosition()
        }
        hop.finished(landed)
    }

    private fun cancelToyHop() = finishToyHop(landed = false)

    private fun advanceToyHop(now: Long, view: PetView): Boolean {
        val hop = toyHop ?: return false
        // A stationary tap on a visible bubble isn't a drag and doesn't cut Pip's jump short.
        if (bubbleHopBlocked(hop.owner) || abs(hop.owner.posX - hop.ownerX) >= dp(1f) ||
            abs(hop.owner.posY - hop.ownerY) >= dp(1f)) {
            cancelToyHop()
            return false
        }
        val t = ((now - hop.started) / hop.duration.toFloat()).coerceIn(0f, 1f)
        val height = hopLift(t)
        posX = hop.fromX + (hop.toX - hop.fromX) * t
        hop.groundY = hop.fromY + (hop.toY - hop.fromY) * t
        posY = (hop.groundY - height * hop.lift).coerceAtLeast(topInset.toFloat())
        view.pivotY = ToyFooting.feetY(0f, petH, dp(1f))
        view.scaleY = 1f + height * 0.08f
        view.scaleX = 1f - height * 0.06f
        applyPetPosition()
        if (t >= 1f) {
            idleUntil = now + idleDelay()
            finishToyHop(landed = true)
        }
        return true
    }

    override fun petCanPlay(owner: Prop): Boolean =
        (toyApproach == null || toyApproach?.owner === owner) && toyHop == null && gardenVisit == null &&
            !toyActionBlocked(owner) && !owner.movingByUser && petDrag?.moving != true && screenUsable()

    private fun targetToyWalk(x: Float, y: Float) {
        targetX = OverlayBounds.clamp(x - petW / 2f, 0f, (screenW - petW).toFloat())
        targetY = OverlayBounds.clamp(y - petH / 2f, topInset + dp(ToyFooting.WALK_TOP_PADDING_DP),
            (screenH - petH - bottomInset).toFloat())
    }

    override fun petWalkTo(owner: Prop, x: Float, y: Float, finished: (Boolean) -> Unit): Boolean {
        if (toyWalk || !petCanPlay(owner)) return false
        if (postOwner != null && postOwner !== owner) petRelease()
        targetToyWalk(x, y)
        toyApproach = ToyApproach(owner, finished)
        walking = true
        return true
    }

    override fun retargetPetWalk(owner: Prop, x: Float, y: Float): Boolean {
        if (toyApproach?.owner !== owner || !petCanPlay(owner)) return false
        targetToyWalk(x, y)
        return true
    }

    override fun cancelPetWalk(owner: Prop) {
        if (toyApproach?.owner === owner) finishToyWalk(false)
    }

    private fun finishToyWalk(arrived: Boolean) {
        val walk = toyApproach ?: return
        if (!arrived) extendToy(walk.owner, 25_000L)
        toyApproach = null
        walking = false
        pet?.walking = false
        walk.finish(arrived)
    }

    private var seatOwner: Prop? = null

    override fun petSitAt(owner: Prop) {
        if (owner !in props || toyPlayPaused() || !screenUsable() || petDrag?.moving == true) return
        seatOwner = owner
        pet?.sitting = true
        attentive = true
        walking = false
        finishToyWalk(false)
        pet?.walking = false
    }

    override fun petIsSeatedAt(owner: Prop): Boolean = seatOwner === owner

    private fun clearToySeat() {
        if (seatOwner == null) return
        seatOwner = null
        pet?.sitting = false
        if (inputBar == null) attentive = false
        idleUntil = SystemClock.uptimeMillis() + 1200L
    }

    override fun petLeaveSeat(owner: Prop): Boolean {
        if (seatOwner !== owner) return false
        clearToySeat()
        return !ending && screenUsable() && !overlayDucked && !standingStill() && wanderAllowed &&
            inputBar == null && !Agent.running() && petDrag?.moving != true
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

    /** Every toy, caption, and Pip uses the same top-left origin, including in RTL. */
    private fun stageWindow(width: Int, height: Int, untouchable: Boolean = false) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else 0) or
            (if (untouchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        if (Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    override fun addProp(prop: Prop): Boolean {
        if (ending) return false
        val toy = Toy.values().firstOrNull { it.owns(prop) }
        if (toy != null && !PipSettings.toyAllowed(this, toy)) return false
        if (props.contains(prop)) return true
        val hand = toyHand(prop.centreX)
        val params = stageWindow(prop.windowW, prop.windowH,
            untouchable = overlayDucked || hand != null).apply {
            x = prop.posX.toInt()
            y = prop.posY.toInt()
        }
        prop.params = params
        if (hand != null) prop.alpha = 0f
        if (overlayDucked) params.alpha = 0f
        if (!runCatching { windowManager.addView(prop, params) }.isSuccess) return false
        val isFirstPiece = toy != null && props.none(toy::owns)
        props += prop
        if (toy != null) {
            if (isFirstPiece) Toys.rememberArrival(this, toy)
            toyLifetimes.getOrPut(toy) { ToyLifetime(SystemClock.uptimeMillis()) }
        }
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
        shiftedPitch.remove(prop)
        cancelPetWalk(prop)
        if (seatOwner === prop) clearToySeat()
        if (toyHop?.owner === prop) cancelToyHop()
        if (gardenVisit?.owner === prop) cancelGardenVisit()
        if (raisedFor === prop) raisedFor = null
        if (counterBelowPip === prop) counterBelowPip = null
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
    private fun bubbleScreenUsable() = runCatching {
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
    }.getOrDefault(false)

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

        speechBubble.onFrame()
        layoutSpeechBubble()

        keyboardWatch -= frameMs
        if (keyboardWatch <= 0L) {
            keyboardWatch = 400L
            speechBubble.setScreenHidden(!bubbleScreenUsable())
            watchKeyboard()
        }

        // Hold positions until Android has finished with the prepared gesture path.
        if (overlayDucked) {
            finishToyWalk(false)
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

        // Nothing on screen for a few minutes: he gets bored and makes his own fun.
        if (spontaneousToysAllowed && !attentive && !walking && props.isEmpty() && !still &&
            captionIdlePause.permitsIdleMotion(now)) {
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
        toyApproach?.owner?.let { if (toyActionBlocked(it) || it.movingByUser) finishToyWalk(false) }
        val station = post
        if (station != null && (postArrival != null || postReturn) && !toyWalk &&
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
                toyApproach?.owner is ScoreBoard -> dp(SoccerAi.RUN_SPEED)
                toyWalk -> speed * 2.5f
                else -> speed
            }
            val perFrame = rate * frameMs / 1000f

            if (distance <= perFrame || distance < 0.5f) {
                posX = targetX
                posY = targetY
                applyPetPosition()
                walking = false
                view.walking = false
                if (relocating) {
                    relocating = false
                    // Waiting by his own chat box is a long stay. Stepping clear of a
                    // keyboard in another app is not: he goes back to wandering shortly.
                    idleUntil = now + if (inputBar != null) 600_000L else idleDelay()
                } else {
                    idleUntil = now + idleDelay()
                    finishToyWalk(true)
                }
            } else {
                posX += dx / distance * perFrame
                posY += dy / distance * perFrame
                applyPetPosition()
                if (abs(dx) > 1f) view.facingRight = dx > 0
                view.walking = true
            }
        } else if (wanderAllowed && !still && !attentive && post == null && now > idleUntil &&
            captionIdlePause.permitsIdleMotion(now)) {
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
    }

    private fun applyPetPosition() {
        val host = petHost ?: return
        updateStageBounds(host, petParams, OverlayBounds.Box(posX.toInt(), posY.toInt(), petW, petH), "Pip position")
        layoutSpeechBubble()
        positionInputBar()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    internal fun dpInt(value: Float) = dp(value).toInt()

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
            applyPetPosition()
        },
        allowed = { userDragAllowed() && !bubbleHopLocksInput() },
        grip = { holding ->
            if (holding) {
                if (jobWalk != null) {
                    Agent.cancel()
                    hideJobBar()
                    endJobWatch()
                }
                cancelGardenVisit()
                walking = false
                finishToyWalk(false)
                relocating = false
                view.walking = false
            }
            view.setGrip(holding)
        },
        dragStarted = {
            clearToySeat()
            view.startCarry()
        },
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

    private fun updateStageBounds(view: View, params: WindowManager.LayoutParams, box: OverlayBounds.Box,
                                  diagnostic: String): Boolean {
        val old = OverlayBounds.Box(params.x, params.y, params.width, params.height)
        if (old == box) return true
        fun set(bounds: OverlayBounds.Box) {
            params.x = bounds.x
            params.y = bounds.y
            params.width = bounds.width
            params.height = bounds.height
        }
        set(box)
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (e: RuntimeException) {
            set(old)
            Diag.chatFailure(diagnostic, e)
            false
        }
    }

    /** A caption can resize or move without ever relaying out Pip's sprite. */
    private fun layoutSpeechBubble() {
        val shown = speechBubble.shown && !ending && petHost != null
        val now = SystemClock.uptimeMillis()
        captionIdlePause.visibilityChanged(shown, now)
        if (!shown) {
            bubbleBox = null
            hideSpeechWindow()
            return
        }
        val panel = speechBubble.view
        val stand = (postOwner as? LemonadeStand)?.takeIf { it.atCounter() }
        val anchor = if (stand != null) OverlayBounds.Box(stand.posX.toInt(), stand.posY.toInt(), stand.windowW, stand.windowH)
            else OverlayBounds.Box(posX.toInt(), posY.toInt(), petW, petH)
        val frame = bubbleBox?.takeIf { speechBubble.fading && it.width == panel.panelWidth && it.height == panel.panelHeight }
            ?: OverlayBounds.anchoredPanel(anchor, panel.panelWidth, panel.panelHeight,
                screenW, topInset, screenH - bottomInset, dpInt(6f), dpInt(8f))
        val attached = bubbleParams
        if (attached != null) {
            val host = bubbleHost ?: return
            if (updateStageBounds(host, attached, frame, "bubble layout")) bubbleBox = frame
            return
        }
        if (now < bubbleRetryAt) return
        val host = bubbleHost ?: FrameLayout(this).apply {
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnClickListener { hideBubble() }
            bubbleHost = this
        }
        val params = stageWindow(frame.width, frame.height).apply {
            // Private previews and ordinary replies can replace one another in the same
            // window without resizing it. Set this before attach, not after the text changes.
            flags = flags or WindowManager.LayoutParams.FLAG_SECURE
            x = frame.x
            y = frame.y
        }
        try {
            windowManager.addView(host, params)
            bubbleParams = params
            bubbleBox = frame
        } catch (e: RuntimeException) {
            bubbleRetryAt = now + 300L
            Diag.chatFailure("show bubble", e)
        }
    }

    private fun hideSpeechWindow(): Boolean {
        if (bubbleParams == null) return true
        val host = bubbleHost ?: return false
        return try {
            windowManager.removeViewImmediate(host)
            bubbleParams = null
            bubbleRetryAt = 0L
            true
        } catch (e: RuntimeException) {
            Diag.chatFailure("hide bubble", e)
            false
        }
    }

    // --------------------------------------------------------------- speech bubble

    /**
     * How long a line stays up, by how much there is to read: a two-word reply should not
     * sit there for twelve seconds, and a longer one should get longer.
     */
    private fun bubbleLifetime(text: String): Long =
        (2200L + text.length * 55L).coerceIn(3_000L, 14_000L)

    private fun captionReply(text: String, handsFree: Boolean = false) {
        val presentation = ReplyBubbleState(text.length, spoken = true)
        voicedReply = presentation

        fun onCaption(end: Int?) {
            if (voicedReply !== presentation || ending) return
            val previous = presentation.visibleEnd
            val changed = presentation.reveal(end)
            if (end == null) {
                voicedReply = null
                val unseen = text.length - previous
                val hold = if (previous > 0 && unseen <= 16) 3_000L else bubbleLifetime(text.substring(previous))
                if (changed || previous == 0) speechBubble.text(text, readMs = hold, conversation = true)
                else speechBubble.hold(hold)
                replyUntil = SystemClock.uptimeMillis() + hold
                if (previous == 0) startTalking()
            } else if (changed) {
                speechBubble.text(text, presentation.visibleEnd, conversation = true)
                idleUntil = SystemClock.uptimeMillis() + SPEAKING_PAUSE_MS
                if (previous == 0 || pet?.mood == PetView.Mood.TALKING || pet?.mood == PetView.Mood.NEUTRAL) {
                    startTalking(950L)
                }
            }
        }

        ChatRecovery.attempt(Unit, {
            Diag.chatFailure("start spoken reply", it)
            onCaption(null)
        }) { PipVoice.reply(this, text, ::onCaption, allowLocked = handsFree) }
        // The existing dots remain in the same window until actual playback or text fallback.
        if (voicedReply === presentation && presentation.waiting) speechBubble.thinking()
    }

    private fun hideBubble() {
        if (voicedReply?.waiting == true) speechBubble.clearThinking()
        voicedReply = null
        speechBubble.clearText()
    }

    private fun hideThinking() = speechBubble.clearThinking()

    // ---------------------------------------------------------------- thinking

    private fun startThinking() {
        if (ending) return
        speechBubble.setScreenHidden(!bubbleScreenUsable())
        attentive = true
        standStillNow()
        feel(PetView.Mood.THINKING, 0L)
        speechBubble.thinking()
    }

    // --------------------------------------------------------------------- chat

    private fun enterAttention(moveUp: Boolean) {
        if (ending) return
        clearToySeat()
        attentive = true
        walking = false
        finishToyWalk(false)
        relocating = false
        // Dropped like every other interruption, so a stale callback cannot fire later.
        pet?.walking = false
        if (moveUp) {
            val comfortable = topInset + screenH * 0.12f
            if (posY > comfortable) {
                targetX = posX
                targetY = comfortable
                walking = true
                relocating = true
            }
        }
    }

    private fun leaveAttention() {
        hideKeyboard()
        removeInputBar()
        attentive = false
        if (relocating) {
            relocating = false
            walking = false
            pet?.walking = false
        }
        pet?.mood = PetView.Mood.NEUTRAL
        idleUntil = SystemClock.uptimeMillis() + 1500L
    }

    private fun sayAloud(text: String, finalReply: Boolean = false, handsFree: Boolean = false) {
        // A reply can land after the overlay has been stopped, and a bubble added to a dead
        // service is a window nothing will ever take down again.
        if (ending || !running) return
        speechBubble.setScreenHidden(!bubbleScreenUsable())
        // Speaking is a pause, not attention. Attention belongs to the chat bar and to sitting
        // at his easel; claiming it here is what used to root him to the spot, because nothing
        // ever gave it back.
        //
        // A walk a toy sent him on survives the line he says on the way, so a reply cannot pull
        // him out from behind the lemonade stand. Ordinary wandering stops for the pause.
        if (!toyWalk && !relocating) {
            walking = false
            pet?.walking = false
        }
        idleUntil = SystemClock.uptimeMillis() + SPEAKING_PAUSE_MS
        voicedReply = null // Callbacks from an old utterance cannot replace the new panel.
        val line = text.ifBlank { "(silence)" }
        if (finalReply && VoiceSettings.enabled(this)) {
            captionReply(line, handsFree)
        } else {
            speechBubble.text(line, readMs = bubbleLifetime(line), conversation = finalReply)
            startTalking()
        }
    }

    private fun startTalking(durationMs: Long = 1600L) {
        // Being smug or annoyed outranks talking: he wants you to see his face.
        val mood = pet?.mood
        if (mood != PetView.Mood.ANNOYED && mood != PetView.Mood.SMUG) {
            feel(PetView.Mood.TALKING, durationMs)
        }
    }

    private fun toggleChat() {
        if (ending || bubbleHopLocksInput()) return
        if (inputBar != null) {
            closeChat()
        } else {
            // Opening the chat is an interruption: whatever he was doing with his hands
            // stops, rather than carrying on in an app behind the box he just gave you.
            Agent.cancel()
            attentive = true
            walking = false
            finishToyWalk(false)
            relocating = false
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
            gravity = Gravity.TOP or Gravity.LEFT
            // Match Pip's layout coordinates; the composer still requests and pans for the IME.
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            // The window asks for the keyboard itself, so it opens together with the bar
            // rather than depending on a request that can arrive before the IME connects.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }

        clearToySeat()
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
        val escapeY = OverlayBounds.keyboardEscapeY(posY, neededY, topInset + dp(8f)) ?: return

        targetX = posX
        targetY = escapeY
        // The keyboard wins; the toy can ask Pip to approach again later. A post is special —
        // he has to go back behind the counter once the keyboard is gone, which is what
        // postReturn is for.
        finishToyWalk(false)
        if (post != null) postReturn = true
        walking = true
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

    /** Measure the legacy stage's screen origin from Pip's stationary sprite. */
    private fun measureWindowOffset() {
        if (Build.VERSION.SDK_INT >= 30) return // Uses display coordinates by construction.
        val host = petHost ?: return
        if (walking || toyHop != null || gardenVisit != null || jobWalk != null ||
            petDrag?.moving == true || !host.isLaidOut || host.isLayoutRequested ||
            host.width != petW || host.height != petH) return
        host.getLocationOnScreen(overlayLocation)
        windowOffset = (overlayLocation[1] - petParams.y).toFloat()
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

    private fun extendToy(prop: Prop, delay: Long) {
        Toy.values().firstOrNull { it.owns(prop) }?.let {
            toyLifetimes[it]?.keepFor(SystemClock.uptimeMillis(), delay)
        }
    }

    private fun retireIfStale() {
        val now = SystemClock.uptimeMillis()
        val expired = toyLifetimes.entries.firstOrNull { (toy, lifetime) ->
            lifetime.expired(now, held = props.any { prop ->
                toy.owns(prop) && (prop.movingByUser || toyApproach?.owner === prop ||
                    gardenVisit?.owner === prop || toyHop?.owner === prop || prop.keepsToy(now))
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
            if (!under(prop) || prop.movingByUser) return@forEach
            // Moving a goal around the composer is not a player move. Restore it before play.
            if (Toy.SOCCER.owns(prop)) shiftedPitch.putIfAbsent(prop, DragMotion.Point(prop.posX, prop.posY))
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
        shiftedPitch.forEach { (prop, from) ->
            if (prop in props && !prop.movingByUser) prop.moveTo(from.x, from.y)
        }
        shiftedPitch.clear()
    }

    /** A spoken purchase: put it in the composer for the user, never run or send it. */
    private fun reviewPurchase(text: String, done: (WakeFeedback) -> Unit) {
        if (screenUsable()) {
            showInputBar()
            inputField?.let {
                it.setText(text)
                it.setSelection(it.text.length)
                android.widget.Toast.makeText(this, "Purchases stay yours. Review it and tap Send if you want help.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            PipVoice.reply(this, "Purchases stay yours. Unlock your phone to review that request.",
                allowLocked = true)
        }
        done(WakeFeedback.PURCHASE_REVIEW)
    }

    private fun handleVoice(text: String, stillListening: () -> Boolean, done: (WakeFeedback) -> Unit) {
        val command = CarCommand.parse(text)
        val task = command != null || CarCommand.looksLikeTask(text)
        if (!task && !VoiceSettings.drivingEnabled(this)) {
            handleOrdinaryVoice(text, stillListening, done, driving = false)
            return
        }
        DrivingVoice.projected(this) { projected ->
            if (instance !== this || ending || !stillListening()) { done(WakeFeedback.BUSY); return@projected }
            val enabled = VoiceSettings.drivingEnabled(this)
            val route = CarVoiceRoute.choose(enabled, projected, supported = command != null, task = task)
            Diag.carConnection(CarVoiceRoute.label(route, projected))
            fun report(result: DrivingVoice.Result) {
                // Release the listener even if the overlay is gone: a held callback would leave
                // the microphone marked busy and kill the session.
                done(when {
                    result.prompted -> WakeFeedback.CAR_PROMPT
                    result.verified -> WakeFeedback.CAR_VERIFIED
                    result.handedOff -> WakeFeedback.CAR_COMMAND
                    else -> WakeFeedback.CAR_NOT_RUN
                })
                if (instance !== this || ending) return
                Diag.handsFreeCommand(result.message)
                PipVoice.reply(this, result.message, allowLocked = true)
            }
            when (route) {
                CarVoiceRoute.EXECUTE -> {
                    if (Agent.running()) report(DrivingVoice.Result("I’m already working on another phone task. Try again in a moment."))
                    else DrivingVoice.execute(this, checkNotNull(command), ::report)
                }
                CarVoiceRoute.DISABLED -> report(DrivingVoice.Result("Hands-free driving commands are off in Pip settings. Change that only when parked."))
                CarVoiceRoute.CONNECTION_UNVERIFIED -> report(DrivingVoice.Result("I couldn’t verify an Android Auto connection. Try again when parked."))
                CarVoiceRoute.UNSUPPORTED -> report(DrivingVoice.Result("That’s not a hands-free driving command. Try a Spotify control or a Google Maps destination, or wait until parked."))
                CarVoiceRoute.ORDINARY, CarVoiceRoute.REVIEW_WHEN_PARKED -> {
                    handleOrdinaryVoice(text, stillListening, done, driving = projected == true || projected == null && enabled)
                    return@projected
                }
            }
        }
    }

    private fun handleOrdinaryVoice(text: String, stillListening: () -> Boolean, done: (WakeFeedback) -> Unit,
                                    driving: Boolean) {
        fun parkedOnly(): WakeFeedback {
            val reason = "That’s not a hands-free driving command. Try again when parked."
            Diag.carConnection("Projection connected; not a driving command")
            Diag.handsFreeCommand(reason)
            PipVoice.reply(this, reason, allowLocked = true)
            return WakeFeedback.CAR_NOT_RUN
        }
        val waiting = awaitingReply && SystemClock.uptimeMillis() - replyAskedAt < 15_000L
        if (!stillListening() || Agent.running() || waiting) {
            if (driving && stillListening() && !PipVoice.microphoneBlocked()) {
                PipVoice.reply(this, "I’m busy with another request. Try again in a moment.", allowLocked = true)
            }
            done(WakeFeedback.BUSY)
            return
        }
        if (!inputField?.text.isNullOrBlank()) {
            if (driving) PipVoice.reply(this, "I couldn’t start that request. There’s an unfinished draft on the phone. Check it when parked.", allowLocked = true)
            done(WakeFeedback.DRAFT)
            return
        }
        // Spending money is the one request that still stops for a person.
        if (PurchasePolicy.asksToPay(text)) {
            if (driving) {
                PipVoice.reply(this, "I can’t buy things while driving. Save that for when you’re parked.", allowLocked = true)
                done(WakeFeedback.PURCHASE_REVIEW)
            } else reviewPurchase(text, done)
            return
        }
        // Tapping Pip first opens an empty composer. It shouldn't swallow a spoken request.
        if (inputBar != null) closeChat()
        // Navigation questions and stops are answered from Maps' notification, so they need no
        // screen and no unlock whether or not a car is attached.
        val navigation = CarCommand.parse(text)
        if (navigation == CarCommand.StopNavigation || navigation == CarCommand.NavigationState) {
            runNavigation(navigation, done, driving)
            return
        }
        // A spoken lock request is answered by the phone itself. It needs no screen and no
        // model, and it must happen before the phone goes dark rather than after a round trip.
        ScreenLock.requested(text)?.let { match ->
            if (driving) {
                // Darting at the lock during a drive is never what the driver wants, and the
                // lock is not a hands-free command either. Say so and leave the screen alone.
                val line = "I’ll leave the screen on while you’re driving. Ask me to lock it when you park."
                Diag.screenLock("not locked · driving")
                Diag.handsFreeCommand(line)
                PipVoice.reply(this, line, allowLocked = true)
                done(WakeFeedback.CAR_NOT_RUN)
                return
            }
            runScreenLock(match)
            done(WakeFeedback.REPLIED)
            return
        }
        val local = JobProgress.routePhoneLocally(text)
        if (!driving && (local || PhoneContext.asksForAppLabel(text) || NowPlaying.requested(text) ||
                MessageNotifications.request(text) != null)) {
            runLocally(text, done)
            return
        }
        if (CarCommand.looksLikeTask(text) || PhoneContext.mayInspect(text)) {
            if (driving) { done(parkedOnly()); return }
            runVoiceTask(text, stillListening, done, unlocked = bubbleScreenUsable())
            return
        }
        val turn = ChatTurns.begin()
        val unlocked = bubbleScreenUsable()
        val screenGeneration = screenOffGeneration
        val previousVoice = voiceExchange.takeIfRecent(SystemClock.elapsedRealtime()).takeIf { unlocked }
        beginReply()
        if (screenUsable()) startThinking()
        // Ordinary conversation is read-only: the provider sees no app or screen context and
        // cannot start a phone job from a spoken turn. Work on the phone is recognized above,
        // and only a purchase stops for confirmation.
        var timedOut = false
        val deadline = Runnable {
            timedOut = true
            if (instance === this && !ending && ChatTurns.isCurrent(turn)) {
                ChatTurns.begin() // Also invalidate any queued provider work for this turn.
                awaitingReply = false
                hideThinking()
                leaveAttention()
                if (stillListening()) PipVoice.reply(this, "My chat reply took too long. You can try again.", allowLocked = true)
            }
            done(WakeFeedback.CHAT_TIMED_OUT)
        }
        handler.postDelayed(deadline, 18_000L)
        AssistantBrain.reply(this, text, turn = turn, handsFree = true, voiceContext = previousVoice) { ok, reply ->
            handler.post {
                if (timedOut) return@post
                handler.removeCallbacks(deadline)
                var result = WakeFeedback.BUSY
                try {
                    if (instance !== this || ending || !ChatTurns.isCurrent(turn)) return@post
                    awaitingReply = false
                    if (!stillListening()) { hideThinking(); leaveAttention(); return@post }
                    ChatRecovery.attempt(Unit, { Diag.chatFailure("voice chat history", it) }) {
                        Memory.append(this, "user", text)
                        if (ok) Memory.append(this, "assistant", reply)
                    }
                    if (screenUsable()) {
                        leaveAttention()
                        sayAloud(reply, finalReply = true, handsFree = true)
                    } else {
                        hideThinking()
                        leaveAttention()
                        PipVoice.reply(this, reply, allowLocked = true)
                    }
                    if (ok && unlocked && screenOffGeneration == screenGeneration && bubbleScreenUsable()) {
                        voiceExchange.remember(text, reply, SystemClock.elapsedRealtime())
                    }
                    result = if (ok) WakeFeedback.REPLIED else WakeFeedback.CHAT_FAILED
                } finally { done(result) }
            }
        }
    }

    /**
     * A navigation stop or question, answered without the screen. While driving the answer is
     * spoken with the other hands-free replies; otherwise it goes through the normal bubble.
     */
    private fun runNavigation(command: CarCommand, done: (WakeFeedback) -> Unit, driving: Boolean) {
        fun answer(result: DrivingVoice.Result) {
            Diag.handsFreeCommand(result.message)
            if (driving) {
                done(if (result.verified) WakeFeedback.CAR_VERIFIED
                    else if (result.handedOff) WakeFeedback.CAR_COMMAND
                    else WakeFeedback.CAR_NOT_RUN)
                if (instance !== this || ending) return
                PipVoice.reply(this, result.message, allowLocked = true)
            } else {
                done(if (result.verified) WakeFeedback.CAR_VERIFIED else WakeFeedback.REPLIED)
                modelSays(result.message)
            }
        }
        if (command == CarCommand.NavigationState) {
            CarNavigation.status { navigation ->
                val canInspect = PipMediaAccess.enabled(this)
                answer(DrivingVoice.Result(
                    NavVoice.message(NavVoice.Action.STATUS, navigation, canInspect)))
            }
        } else {
            CarNavigation.stop(this) { action ->
                answer(DrivingVoice.Result(NavVoice.message(action, null, canInspect = true),
                    handedOff = true, verified = action == NavVoice.Action.STOPPED_AND_VERIFIED))
            }
        }
    }

    /** Phone jobs recognized on the phone run right away, exactly like a typed message. */
    private fun runLocally(text: String, done: (WakeFeedback) -> Unit) {
        val turn = ChatTurns.begin()
        // A spoken message check is answered by its own route and reads nothing aloud to
        // anyone nearby unless the user asked for it.
        MessageNotifications.request(text)?.let { request ->
            Agent.cancel()
            hideThinking()
            awaitingReply = false
            PrivateMessageRead.present(this, PipMediaAccess.messageAnswer(this, request), turn) { reply, _ ->
                speechBubble.setScreenHidden(false)
                speechBubble.text(reply, readMs = bubbleLifetime(reply), conversation = true, privatePreview = true)
            }
            done(WakeFeedback.REPLIED)
            return
        }
        if (NowPlaying.requested(text)) {
            val reply = ChatRecovery.attempt(ChatRecovery.REPLY_FAILED, { Diag.chatFailure("now playing reply", it) }) {
                PipMediaAccess.answer(this)
            }
            modelSays(reply)
            done(WakeFeedback.REPLIED)
            return
        }
        // The job's own spoken lines carry the result.
        if (startJob(text, text, turn)) {
            voiceExchange.clear()
            done(WakeFeedback.REPLIED)
        } else {
            val line = "I couldn’t start that one. Unlock the phone and check Pip settings, then try again."
            if (screenUsable()) sayAloud(line, finalReply = true, handsFree = true)
            done(WakeFeedback.CHAT_FAILED)
        }
    }

    /** A phone task the model must hand over, run without the review step. */
    private fun runVoiceTask(text: String, stillListening: () -> Boolean, done: (WakeFeedback) -> Unit, unlocked: Boolean) {
        val turn = ChatTurns.begin()
        val screenGeneration = screenOffGeneration
        val previousVoice = voiceExchange.takeIfRecent(SystemClock.elapsedRealtime()).takeIf { unlocked }
        beginReply()
        if (screenUsable()) startThinking()
        var timedOut = false
        val deadline = Runnable {
            timedOut = true
            if (instance === this && !ending && ChatTurns.isCurrent(turn)) {
                ChatTurns.begin()
                awaitingReply = false
                hideThinking()
                leaveAttention()
                if (stillListening()) PipVoice.reply(this, "That took too long. You can try again.", allowLocked = true)
            }
            done(WakeFeedback.CHAT_TIMED_OUT)
        }
        handler.postDelayed(deadline, 18_000L)
        AssistantBrain.delegate(this, text, turn, previousVoice) { outcome ->
            handler.post {
                if (timedOut) return@post
                handler.removeCallbacks(deadline)
                try {
                    // No early return here: the wake listener stays paused until done() runs.
                    if (instance !== this || ending || !ChatTurns.isCurrent(turn)) {
                        hideThinking()
                        return@post
                    }
                    if (!stillListening()) {
                        hideThinking()
                        leaveAttention()
                        done(WakeFeedback.BUSY)
                        return@post
                    }
                    awaitingReply = false
                    hideThinking()
                    if (outcome is AssistantBrain.TaskOutcome.RefusedPurchase) {
                        leaveAttention()
                        reviewPurchase(text, done)
                        return@post
                    }
                    val task = (outcome as? AssistantBrain.TaskOutcome.Task)?.job
                    if (task == null) {
                        leaveAttention()
                        val line = "I couldn’t work out how to do that one. Try saying it another way."
                        if (screenUsable()) sayAloud(line, finalReply = true, handsFree = true)
                        else PipVoice.reply(this, line, allowLocked = true)
                        done(WakeFeedback.CHAT_FAILED)
                        return@post
                    }
                    // The model may have turned the request into something that spends money
                    // even though the user's words did not; that still needs a person.
                    if (PurchasePolicy.asksToPay(task)) {
                        leaveAttention()
                        reviewPurchase(text, done)
                        return@post
                    }
                    if (startJob(task, text, turn)) {
                        // Same guard as an ordinary reply: a turn that began before the phone
                        // was locked and unlocked again is not context for the next one, since
                        // the screen it referred to is gone. Without this, a stale "on it" from
                        // before a lock was offered back as if it described the current screen.
                        if (unlocked && screenOffGeneration == screenGeneration && bubbleScreenUsable()) {
                            voiceExchange.remember(text, "On it.", SystemClock.elapsedRealtime())
                        }
                        done(WakeFeedback.REPLIED)
                    } else {
                        leaveAttention()
                        done(WakeFeedback.CHAT_FAILED)
                    }
                } finally { /* Every path above calls done(). */ }
            }
        }
    }

    private fun sendMessage() {
        val field = inputField ?: return
        val text = field.text.toString().trim()
        if (text.isEmpty()) return

        voiceExchange.clear()
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
        MessageNotifications.request(text)?.let { request ->
            Agent.cancel()
            leaveAttention()
            hideBubble()
            hideThinking()
            awaitingReply = false
            PrivateMessageRead.present(this, PipMediaAccess.messageAnswer(this, request), turn) { reply, _ ->
                speechBubble.setScreenHidden(false)
                speechBubble.text(reply, readMs = bubbleLifetime(reply), conversation = true, privatePreview = true)
            }
            return
        }
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
        // Locking the screen is the one phone action that must not wait on a model: the screen
        // it is about goes dark, so there is no second chance to get the wording right.
        ScreenLock.requested(text)?.let { match ->
            runScreenLock(match)
            return
        }
        val event = ChatRecovery.attempt<String?>(null, { Diag.chatFailure("chat toy reaction", it) }) {
            Toys.reactTo(this, text)
        }

        AssistantBrain.reply(this, text, Toys.summary(this), event, turn) { ok, reply ->
            handler.post {
                if (instance !== this || !ChatTurns.isCurrent(turn)) return@post
                // Sometimes the answer is not a line but a job for his hands to do.
                val job = if (ok) Agent.delegated(reply) else null
                if (job != null) {
                    startJob(job, text, turn)
                    return@post
                }
                // A refusal to something that is plainly a job is a bug, not an answer: the
                // character prompt says so, and a model can still produce one. Hand it to his
                // hands rather than passing the excuse on to the person who asked.
                if (ok && ClearRows.requested(text) && ClearRows.soundsLikeRefusal(reply)) {
                    Diag.caught("housekeeping refusal", IllegalStateException("refusal sent to the hands"))
                    startJob(text, text, turn)
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

    /**
     * Locks the screen on request.
     *
     * The line is said first, while the screen is still lit: once the lock lands there is
     * nothing left to show a bubble on. The lock itself runs off the main thread because the
     * Android 8 fallback dispatches a gesture and waits for it, and Android refuses gestures
     * from the thread that owns the view tree.
     */
    private fun runScreenLock(match: ScreenLock.Match) {
        Diag.screenLock(ScreenLock.label(match))
        if (!ScreenLocker.available()) {
            Diag.screenLock("cannot lock · no accessibility")
            modelSays(ScreenLocker.message(ScreenLocker.Result.NO_ACCESS))
            return
        }
        // Everything below runs on a worker: the accessibility reads and the Android 8 gesture
        // are framework calls, and Android refuses gestures from the thread that owns the view
        // tree. Doing the availability probe here too keeps the main thread out of it.
        modelSays("Locking up.")
        Thread({
            val result = try {
                ScreenLocker.lock()
            } catch (t: Throwable) {
                Diag.caught("lock screen", t)
                ScreenLocker.Result.NO_ACCESS
            }
            handler.post {
                if (instance !== this || ending) return@post
                // Only a problem is worth saying after the fact. On success the phone is on its
                // way to sleep, and a second bubble would arrive on the lock screen.
                if (result !in setOf(ScreenLocker.Result.LOCKED, ScreenLocker.Result.ACCEPTED,
                        ScreenLocker.Result.ALREADY_LOCKED)) {
                    modelSays(ScreenLocker.message(result))
                }
            }
        }, "PipScreenLock").start()
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
        clearToySeat()
        doomscrollJob = doomscroll
        if (doomscroll) Toys.rememberArrival(this, Toy.DOOMSCROLL)
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

    internal fun setHudDucked(ducked: Boolean): Boolean {
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
        speechBubble.setDucked(overlayDucked)
        // Normally the caption detaches when ducked. Don't dispatch a touch if it couldn't.
        if (overlayDucked && !hideSpeechWindow()) ok = false
        if (!overlayDucked) applyPetPosition()
        petHost?.let { if (!duckLayout(it, petParams, overlayDucked, DUCK_ALPHA)) ok = false }
        if (!setHudDucked(hud?.let(::overlaps) == true)) ok = false
        props.forEach { prop -> if (!duckLayout(prop, prop.params, overlaps(prop))) ok = false }
        fading.forEach { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null && !duckLayout(view, params, overlaps(view), alwaysUntouchable = true)) ok = false
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
        finishToyWalk(false)
        relocating = false
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
                    screenOffGeneration++
                    voiceExchange.clear()
                    speechBubble.setScreenHidden(true)
                    if (voicedReply != null) {
                        hideBubble()
                        hideThinking()
                    }
                    PipVoice.stopUnlessHandsFree()
                    if (ending) {
                        finishExit()
                        return
                    }
                    cancelPowerAnimation()
                    finishToyFlights()
                    pet?.powerPose = null
                    cancelToyHop()
                    cancelGardenVisit()
                    finishToyWalk(false)
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
                    speechBubble.setScreenHidden(!bubbleScreenUsable())
                    pet?.animating = true
                    if (resume) handler.post(tick)
                    settleBreak()
                    maybeOpenPipAfterBoot()
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
