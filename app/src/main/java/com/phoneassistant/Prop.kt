package com.phoneassistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/** Speech bubble fade duration; toys use their own motion. */
internal const val FADE_MS = 170L

/**
 * What a prop may ask of the screen it lives on: where the edges are, how to talk, and
 * how to make Pip look smug, annoyed or pleased about something.
 */
interface PropHost {
    fun stageContext(): Context
    fun stageWidth(): Int
    fun stageHeight(): Int
    fun stageTop(): Int
    fun stageBottom(): Int

    /**
     * The lowest y a toy's top edge may sit: above the keyboard if one is up, whoever is
     * typing in it, or above the navigation strip if not.
     */
    fun toysFloor(): Float

    /** dp to pixels, for props doing their own arithmetic. */
    fun px(value: Float): Float

    /** The user poked Pip: do what a tap on him would do, from inside a toy's window. */
    fun pokePet()

    /** Pip says this in his bubble. */
    fun say(line: String)
    fun feel(mood: PetView.Mood, ms: Long)

    /**
     * A toy's own sound effect. Every call is a fresh variation, so a toy asking twice gets two
     * slightly different takes; [gain] is that toy's sense of how loud it should be, 0–1.
     * Silently does nothing when toy sounds are off or the phone is on silent.
     */
    fun sound(effect: ToySounds.Sound, gain: Float = 1f)

    /**
     * The same, later. Part of the host rather than a timer in each toy so a prop that is put
     * away mid-sequence does not leave a delayed squeak to fire at nothing.
     */
    fun postDelayedSound(effect: ToySounds.Sound, delayMs: Long, gain: Float = 1f)

    fun petWidth(): Int
    fun petHeight(): Int

    /**
     * Whether Pip's middle is within [within] pixels of (x, y), in screen pixels. Toy windows
     * are drawn over his, so a tap may be one meant for him — but only while he is actually
     * there, not in the space he would occupy if he had not walked off.
     */
    fun petIsNear(x: Float, y: Float, within: Float): Boolean

    /**
     * Whether Pip is on screen at all. Props that want to face him ask this first, because
     * [petCentreX] only means anything while he is there.
     */
    fun petOnScreen(): Boolean

    /** Pip's middle, in screen pixels. Read every frame, so it returns a plain float. */
    fun petCentreX(): Float
    fun petFeetY(): Float

    /** Leap toward a target. An accepted hop finishes once, with false if interrupted. */
    fun petHopOn(owner: Prop, x: Float, y: Float, finished: (landed: Boolean) -> Unit): Boolean

    /** Approach the can's grip in screen pixels. A null progress means the pour has ended. */
    fun petWaterPlant(owner: Prop, gripX: Float, gripY: Float, facesRight: Boolean,
                      progress: (Float?) -> Unit, watered: () -> Unit): Boolean

    /** An accepted walk calls [finished] once, including if it is interrupted. */
    fun petWalkTo(owner: Prop, x: Float, y: Float, finished: (Boolean) -> Unit = {}): Boolean
    fun retargetPetWalk(owner: Prop, x: Float, y: Float): Boolean
    fun cancelPetWalk(owner: Prop)
    fun petCanPlay(owner: Prop): Boolean
    fun toyPlayPaused(): Boolean

    /**
     * Pip takes up a post at (x, y), owned by [owner]: he walks there and then stays put
     * instead of wandering off when his idle timer runs out. [done] runs once, on arrival,
     * and [petRelease] frees him again. The owner is the prop he is serving, so it can be
     * left where it is while he is standing behind it.
     */
    fun petHoldAt(owner: Prop, x: Float, y: Float, done: () -> Unit)

    /** Ends a post taken with [petHoldAt], so he goes back to wandering. */
    fun petRelease()

    /** Whether [prop] is the prop he is currently posted at. */
    fun isPostOwner(prop: Prop): Boolean
    fun petSitAt(owner: Prop)
    fun petIsSeatedAt(owner: Prop): Boolean

    /** Release only this prop's seat; true if Pip is free to walk away. */
    fun petLeaveSeat(owner: Prop): Boolean

    fun addProp(prop: Prop): Boolean
    fun dropProp(prop: Prop)
    fun updateProp(prop: Prop)

    /**
     * The user has let go of [prop]. It was kept touchable while a step was pressing because a
     * finger was on it; now that the finger is gone it can get out of the way like the rest.
     */
    fun propLetGo(prop: Prop)
    fun userDragAllowed(): Boolean
    fun propTapped(prop: Prop)
    fun propsOnScreen(): List<Prop>
}

/** The palette every toy is painted with, so they all look like they came from Pip. */
object Ink {
    const val BG = 0xF21C202B.toInt()
    const val EDGE = 0xFF3A4356.toInt()
    const val ACCENT = 0xFF7DD3FC.toInt()
    const val TEXT = 0xFFE8ECF4.toInt()
    const val DIM = 0xFF98A2B6.toInt()
    const val LEMON = 0xFFF6C445.toInt()
    const val WOOD = 0xFFB98A5E.toInt()
    const val ALERT = 0xFFF2796B.toInt()
}

/**
 * Something Pip has put down on the screen: a stand, a painting, a goal, a ball. Each prop
 * owns one overlay window, paints itself in code, can be dragged wherever the user likes,
 * and runs its own little mechanic when tapped.
 */
abstract class Prop(protected val host: PropHost) : View(host.stageContext()) {

    /** Window size in dp. */
    abstract val boxW: Float
    abstract val boxH: Float

    var posX = 0f
        private set
    var posY = 0f
        private set

    lateinit var params: WindowManager.LayoutParams

    /**
     * True from the moment this prop has left the stage, while its window is still fading.
     * Treat it as gone now rather than waiting for its exit animation to detach the window.
     */
    var leaving = false
        private set

    /** Start time for visual effects that animate relative to this prop's arrival. */
    val bornAt: Long = SystemClock.uptimeMillis()

    private val density = resources.displayMetrics.density
    protected fun dp(v: Float) = v * density

    val windowW: Int get() = (boxW * density).toInt()
    val windowH: Int get() = (boxH * density).toInt()

    val centreX: Float get() = posX + windowW / 2f
    val centreY: Float get() = posY + windowH / 2f

    private val drag = WindowDrag(
        this,
        position = { DragMotion.Point(posX, posY) },
        moveTo = ::moveTo,
        allowed = { !leaving && !inToyMotion && host.userDragAllowed() },
        grip = { holding ->
            if (!holding) host.propLetGo(this)
            if (!leaving) lift(holding)
        },
        dragStarted = ::onDragStarted,
        dragFinished = ::onDragFinished,
        tapped = { x, y ->
            host.propTapped(this)
            onTap(x, y)
        }
    )

    val held: Boolean get() = drag.held
    internal val movingByUser: Boolean get() = drag.moving
    internal fun cancelDrag() = drag.cancel()

    init { setOnTouchListener(drag) }

    override fun onDetachedFromWindow() {
        drag.cancel()
        cancelToyMotion()
        super.onDetachedFromWindow()
    }

    /** Move the window, keeping it on the screen. Safe to call before the window exists. */
    fun moveTo(x: Float, y: Float) {
        posX = x.coerceIn(0f, (host.stageWidth() - windowW).coerceAtLeast(0).toFloat())
        val minY = host.stageTop().toFloat()
        val maxY = (host.stageHeight() - windowH - host.stageBottom()).toFloat()
            .coerceAtLeast(minY)
        posY = y.coerceIn(minY, maxY)
        if (!inToyMotion) positionWindow(posX, posY)
    }

    /** Position it and add it to the screen in one step. */
    fun bring(x: Float, y: Float): Boolean {
        moveTo(x, y)
        return host.addProp(this)
    }

    private var toyAnimator: ValueAnimator? = null
    private var afterMotion: (() -> Unit)? = null
    internal val inToyMotion: Boolean get() = toyAnimator != null

    internal val motion: ToyMotion get() = when (this) {
        is LemonadeStand -> ToyMotion.STAND
        is LemonadeGlass -> ToyMotion.CUP
        is Painting -> ToyMotion.CANVAS
        is Goal -> ToyMotion.GOAL
        is Ball -> ToyMotion.BALL
        is ScoreBoard -> ToyMotion.SCORE
        is BubbleWrap -> ToyMotion.WRAP
        is Plant -> ToyMotion.PLANT
        else -> ToyMotion.CANVAS
    }

    private fun positionWindow(x: Float, y: Float) {
        if (!::params.isInitialized) return
        val newX = x.toInt()
        val newY = y.toInt()
        if (params.x == newX && params.y == newY) return
        params.x = newX
        params.y = newY
        host.updateProp(this)
    }

    internal fun arriveFrom(origin: DragMotion.Point, delayMs: Long, settled: () -> Unit) {
        transit(origin.x - windowW / 2f, origin.y - windowH / 2f, posX, posY,
            entering = true, delayMs = delayMs) {
            positionWindow(posX, posY)
            settled()
        }
    }

    internal fun leaveFor(hand: DragMotion.Point?, done: () -> Unit) {
        leaving = true
        drag.cancel()
        setOnTouchListener(null)
        animate().cancel()
        cancelToyMotion()
        if (hand == null || !isAttachedToWindow) {
            done()
            return
        }
        transit(params.x.toFloat(), params.y.toFloat(), hand.x - windowW / 2f, hand.y - windowH / 2f,
            entering = false, delayMs = 0L, done = done)
    }

    /** The temporary window position does not affect the toy's logical position or physics. */
    private fun transit(x0: Float, y0: Float, x1: Float, y1: Float, entering: Boolean,
                        delayMs: Long, done: () -> Unit) {
        val baseX = if (entering) 1f else scaleX
        val baseY = if (entering) 1f else scaleY
        val baseAlpha = if (entering) 1f else alpha
        val baseRotation = if (entering) 0f else rotation
        val direction = if (x1 >= x0) 1f else -1f
        val profile = motion
        fun frame(t: Float) {
            val pose = profile.pose(entering, t)
            val endX = if (entering) posX else x1
            val endY = if (entering) posY else y1
            positionWindow(x0 + pose.progress * (endX - x0), y0 + pose.progress * (endY - y0) - dp(pose.lift))
            alpha = pose.alpha * baseAlpha
            scaleX = pose.xScale * baseX
            scaleY = pose.yScale * baseY
            rotation = baseRotation * (1f - t) + pose.turn * direction
        }
        afterMotion = done
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delayMs
            duration = if (entering) profile.duration else 365L
            interpolator = LinearInterpolator()
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (toyAnimator === animation) completeToyMotion()
                }
            })
        }
        toyAnimator = animator
        frame(0f)
        animator.start()
    }

    internal fun completeToyMotion() {
        val done = afterMotion ?: return
        cancelToyMotion()
        if (!leaving) {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            rotation = 0f
        }
        done()
    }

    private fun cancelToyMotion() {
        afterMotion = null
        val old = toyAnimator
        toyAnimator = null
        old?.removeAllListeners()
        old?.cancel()
    }

    /** Grows a touch while held, so a prop feels picked up rather than scraped along. */
    private fun lift(held: Boolean) {
        // Every toy in the box answers a pickup with the same small rubbery note, so they feel
        // like objects rather than windows. Quiet: the user is doing this, not the toy.
        sound(if (held) ToySounds.Sound.BOING else ToySounds.Sound.TAP, 0.35f)
        animate().cancel()
        animate()
            .alpha(1f)
            .scaleX(if (held) PickupMotion.SCALE else 1f)
            .scaleY(if (held) PickupMotion.SCALE else 1f)
            .setDuration(if (held) PickupMotion.LIFT_MS else PickupMotion.RELEASE_MS)
            .start()
    }

    /** This toy's own sound, at its own sense of loudness. */
    protected fun sound(effect: ToySounds.Sound, gain: Float = 0.8f) = host.sound(effect, gain)

    /** Called for a tap that was not a drag. Coordinates are inside this prop. */
    protected open fun onTap(x: Float, y: Float) {}

    protected open fun onDragStarted() {}

    /** Called when the user finishes dragging this prop somewhere else. */
    protected open fun onDragFinished() {}

    /** Called about 30 times a second, for toys with physics or things to grow. */
    open fun onFrame() {}

    /** A toy with work still in progress gets to use its own cleanup rules. */
    internal open fun keepsToy(now: Long): Boolean = false

    /** One line for Pip's prompt describing what this is. */
    open fun describe(): String = ""

    /** How far this prop has been moved from where it started, in dp. */
    protected fun drift(startX: Float, startY: Float): Float =
        hypot(abs(posX - startX), abs(posY - startY)) / density

    /**
     * Paints, made once and kept.
     *
     * Every prop used to build a fresh Paint for every shape it drew, and a canvas like the
     * painting has forty of them — redrawn thirty times a second, that is well over a
     * thousand Paint objects a second for colours that never change. The same colour at the
     * same width is the same paint, so each prop keeps its own; nothing here mutates a paint
     * it was handed, which is what makes sharing safe.
     */
    private val fills = HashMap<Long, Paint>()
    private val strokes = HashMap<Long, Paint>()
    private val texts = HashMap<Long, Paint>()

    protected fun fill(color: Int): Paint =
        fills.getOrPut(color.toLong()) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        }

    protected fun stroke(color: Int, widthDp: Float): Paint {
        // A tenth of a dp is finer than any width used, and keeps the key an integer. The
        // colour is masked to its 32 bits first: a negative Int widens to a long with the sign
        // bits on top, and a shift would drop them — two colours differing only in alpha would
        // then share one paint, and painting with the wrong alpha is invisible until it is not.
        val key = ((color.toLong() and 0xFFFFFFFFL) shl 16) or
            (widthDp * 10f).toLong().coerceIn(0L, 0xFFFFL)
        return strokes.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = dp(widthDp)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }
    }

    protected fun text(color: Int = Ink.TEXT, sizeDp: Float = 10f, bold: Boolean = false): Paint {
        val key = ((color.toLong() and 0xFFFFFFFFL) shl 24) or
            ((sizeDp * 16f).toLong().coerceIn(0L, 0xFFFFL) shl 1) or
            (if (bold) 1L else 0L)
        return texts.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = dp(sizeDp)
                isFakeBoldText = bold
            }
        }
    }

    protected fun panel(canvas: Canvas, rect: RectF, radiusDp: Float, edge: Paint, body: Paint) {
        canvas.drawRoundRect(rect, dp(radiusDp), dp(radiusDp), body)
        canvas.drawRoundRect(rect, dp(radiusDp), dp(radiusDp), edge)
    }

    protected fun centred(canvas: Canvas, value: String, cx: Float, cy: Float, paint: Paint) {
        val w = paint.measureText(value)
        val baseline = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(value, cx - w / 2f, baseline, paint)
    }
}
