package com.phoneassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot

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

    fun petWidth(): Int
    fun petHeight(): Int

    /**
     * Whether Pip's middle is within [within] pixels of (x, y), in screen pixels. Toy windows
     * are drawn over his, so a tap may be one meant for him — but only while he is actually
     * there, not in the space he would occupy if he had not walked off.
     */
    fun petIsNear(x: Float, y: Float, within: Float): Boolean

    /**
     * Pip's middle in screen pixels, or null when he is not on screen. Props that want to
     * face him read this rather than assuming where he is.
     */
    fun petCentre(): Pair<Float, Float>?

    /** Pip walks until his centre sits on (x, y), then runs [done]. */
    fun petWalkTo(x: Float, y: Float, done: (() -> Unit)?)

    /**
     * Pip takes up a post at (x, y), owned by [owner]: he walks there and then stays put
     * instead of wandering off when his idle timer runs out. [done] runs once, on arrival,
     * and [petRelease] frees him again. The owner is the prop he is serving, so it can be
     * left where it is while he is standing behind it.
     */
    fun petHoldAt(owner: Prop, x: Float, y: Float, done: () -> Unit)

    /** Ends a post taken with [petHoldAt], so he goes back to wandering. */
    fun petRelease()
    fun petSit(sitting: Boolean)

    fun addProp(prop: Prop): Boolean
    fun dropProp(prop: Prop)
    fun updateProp(prop: Prop)
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

    /** When this prop appeared, so a toy can outstay its welcome and be packed away. */
    val bornAt: Long = SystemClock.uptimeMillis()

    private val density = resources.displayMetrics.density
    protected fun dp(v: Float) = v * density

    val windowW: Int get() = (boxW * density).toInt()
    val windowH: Int get() = (boxH * density).toInt()

    val centreX: Float get() = posX + windowW / 2f
    val centreY: Float get() = posY + windowH / 2f

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var travelled = false

    private val touch = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                travelled = false
                lift(true)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downRawX) > dp(7f) ||
                    abs(event.rawY - downRawY) > dp(7f)
                ) {
                    travelled = true
                }
                if (travelled) {
                    moveTo(posX + event.rawX - lastRawX, posY + event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                lift(false)
                if (travelled) onDragFinished() else onTap(event.x, event.y)
                travelled = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                lift(false)
                travelled = false
                true
            }

            else -> false
        }
    }

    init {
        setOnTouchListener(touch)
    }

    /** Move the window, keeping it on the screen. Safe to call before the window exists. */
    fun moveTo(x: Float, y: Float) {
        posX = x.coerceIn(0f, (host.stageWidth() - windowW).coerceAtLeast(0).toFloat())
        val minY = host.stageTop().toFloat()
        val maxY = (host.stageHeight() - windowH - host.stageBottom()).toFloat()
            .coerceAtLeast(minY)
        posY = y.coerceIn(minY, maxY)
        if (!::params.isInitialized) return
        // The window is placed from these params, so they have to carry the new spot:
        // asking for a layout update without them just re-applies where it already was.
        params.x = posX.toInt()
        params.y = posY.toInt()
        host.updateProp(this)
    }

    /** Position it and add it to the screen in one step. */
    fun bring(x: Float, y: Float): Boolean {
        moveTo(x, y)
        return host.addProp(this)
    }

    /**
     * A quick pop as a prop arrives, so it lands on the screen instead of just being there.
     * The slight undershoot on the way in is what makes it read as placed rather than drawn.
     */
    fun appear() {
        if (!isAttachedToWindow) return
        alpha = 0f
        scaleX = 0.84f
        scaleY = 0.84f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()
    }

    /** Shrinks and fades away, for a prop that has already left the stage. */
    fun fadeAway() {
        if (!isAttachedToWindow) return
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(150L)
            .setInterpolator(AccelerateInterpolator())
            .start()
    }

    /** Grows a touch while held, so a prop feels picked up rather than scraped along. */
    private fun lift(held: Boolean) {
        animate().cancel()
        animate()
            .alpha(1f)
            .scaleX(if (held) 1.045f else 1f)
            .scaleY(if (held) 1.045f else 1f)
            .setDuration(if (held) 90L else 130L)
            .start()
    }

    /** Called for a tap that was not a drag. Coordinates are inside this prop. */
    protected open fun onTap(x: Float, y: Float) {}

    /** Called when the user finishes dragging this prop somewhere else. */
    protected open fun onDragFinished() {}

    /** Called about 30 times a second, for toys with physics or things to grow. */
    open fun onFrame() {}

    /** One line for Pip's prompt describing what this is. */
    open fun describe(): String = ""

    /** How far this prop has been moved from where it started, in dp. */
    protected fun drift(startX: Float, startY: Float): Float =
        hypot(abs(posX - startX), abs(posY - startY)) / density

    protected fun fill(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    protected fun stroke(color: Int, widthDp: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = dp(widthDp)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    protected fun text(color: Int = Ink.TEXT, sizeDp: Float = 10f, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = dp(sizeDp)
            isFakeBoldText = bold
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
