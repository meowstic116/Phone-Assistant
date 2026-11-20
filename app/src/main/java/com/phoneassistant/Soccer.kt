package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.random.Random

/**
 * A two-goal kickabout. Tap the ball to boot it at Pip's goal down at the bottom; he
 * walks over and boots it back at yours. First to three, or ninety seconds.
 *
 * Dragging a goal is allowed, because everything on this screen is allowed to be dragged.
 * It is also, in Pip's view, cheating.
 */
class SoccerGame(private val host: PropHost) {

    companion object {
        const val TARGET = 3
        private const val TIME_LIMIT_MS = 90_000L

        val CHEAT_LINES = listOf(
            "you moved my goal. I saw that.",
            "moved it again. I have eyes. they're enormous.",
            "one more shuffle and I'm writing a review."
        )
        val PIP_GOAL_LINES = listOf(
            "goal. *small, restrained celebration*",
            "in. mostly legs, some talent.",
            "did you see it go past? I did. smugly.",
            "goal. and I barely even planned that, which is the worrying part."
        )
        val YOUR_GOAL_LINES = listOf(
            "lucky bounce. good goal, though.",
            "*stares at the ball* that happened. I'm fine.",
            "one for you. *slow clap* I'm being gracious, loudly.",
            "that was genuinely good. don't enjoy it too much."
        )
        val PIP_KICK_LINES = listOf(
            "my turn. *scuffs it*",
            "*boots it* for all the small phones with big dreams.",
            "watch the technique. there isn't any.",
            "all legs, no plan.",
            "*winds up* this one's going over your goal. possibly my own."
        )
    }

    var youScore = 0
        private set
    var pipScore = 0
        private set

    var over = false
        private set

    lateinit var topGoal: Goal
        private set
    lateinit var bottomGoal: Goal
        private set
    lateinit var ball: Ball
        private set

    private lateinit var board: ScoreBoard

    private val ui = Handler(Looper.getMainLooper())
    private val rnd = Random(SystemClock.uptimeMillis())

    /**
     * The pitch: the stage, or only the part of it above a keyboard. Fixed for the game, so
     * a goal line cannot shift out from under a ball in flight; the one exception is the
     * re-fit below, which only happens before anyone has touched the ball.
     */
    private var pitchTop = 0f
    private var pitchBottom = 0f

    /** Where the ball sat when the game began, to prove it has not been touched since. */
    private var ballHomeX = 0f
    private var ballHomeY = 0f

    private var paused = false
    private var cheats = 0
    private var startedAt = 0L
    private var nextPipKick = 0L
    private var pipChasing = false

    fun start() {
        startedAt = SystemClock.uptimeMillis()
        nextPipKick = startedAt + 2600L

        board = ScoreBoard(host, this)
        topGoal = Goal(host, this, pipDefends = false)
        bottomGoal = Goal(host, this, pipDefends = true)
        ball = Ball(host, this)

        fitPitch()
        layOut()
        ballHomeX = ball.posX
        ballHomeY = ball.posY
        // His own chat box's keyboard may still be on its way out, which would leave the
        // pitch short. One chance to grow it back, and only if the ball is provably where
        // it was put: not kicked, not dragged, not drifting. If that cannot be shown the
        // re-fit is dropped rather than risked later.
        ui.postDelayed({
            val untouched = !ball.kicked && ball.atRest() &&
                abs(ball.posX - ballHomeX) < 1f && abs(ball.posY - ballHomeY) < 1f
            if (!over && !paused && untouched) refitPitch()
        }, 800L)

        host.say("first to three. tap the ball at my goal. I'll defend, badly.")
        host.feel(PetView.Mood.SMUG, 2600L)
    }

    /** The whole stage, or just what sits above a keyboard someone is typing on. */
    private fun fitPitch() {
        pitchTop = host.stageTop().toFloat()
        pitchBottom = host.toysFloor()
    }

    /**
     * Whether the game's windows have already been packed away. They outlive the game by the
     * length of their fade, so a timer that fires in that window must not rebuild them.
     */
    private fun packedAway(): Boolean = board.leaving || !board.isAttachedToWindow

    /** Grows the pitch back to the full stage if the keyboard that shortened it has gone. */
    private fun refitPitch() {
        // layOut() re-brings every prop, which would put a game that has been tidied away
        // back on the screen.
        if (over || packedAway()) return
        val floor = host.toysFloor()
        if (floor <= pitchBottom) return
        pitchBottom = floor
        layOut()
    }

    /** The pitch's edges, for the ball's own physics. */
    fun pitchTopY(): Float = pitchTop
    fun pitchFloorY(): Float = pitchBottom

    private fun layOut() {
        val w = host.stageWidth().toFloat()
        val top = pitchTop
        val bottom = pitchBottom

        board.bring(host.px(10f), top + host.px(10f))
        topGoal.bring(w / 2f - topGoal.windowW / 2f, top + host.px(4f))
        bottomGoal.bring(
            w / 2f - bottomGoal.windowW / 2f,
            bottom - bottomGoal.windowH - host.px(4f)
        )
        ball.bring(w / 2f - ball.windowW / 2f, (top + bottom) / 2f - ball.windowH / 2f)
        topGoal.rememberOrigin()
        bottomGoal.rememberOrigin()
    }

    fun isPaused(): Boolean = paused || over

    fun scored(pipPoint: Boolean) {
        if (over) return
        paused = true

        if (pipPoint) pipScore++ else youScore++
        board.invalidate()

        host.feel(if (pipPoint) PetView.Mood.SMUG else PetView.Mood.ANNOYED, 1800L)
        host.say(
            Lines.pick(if (pipPoint) PIP_GOAL_LINES else YOUR_GOAL_LINES)
        )

        if (youScore >= TARGET || pipScore >= TARGET) {
            ui.postDelayed({ finish() }, 1500L)
        } else {
            ui.postDelayed({
                if (over || packedAway()) return@postDelayed
                ball.reset()
                paused = false
                nextPipKick = SystemClock.uptimeMillis() + 2200L
            }, 1500L)
        }
    }

    fun goalMoved() {
        if (over) return
        cheats++
        host.feel(PetView.Mood.ANNOYED, 2800L)
        if (cheats >= 3) {
            over = true
            paused = true
            host.say("three goal moves. that's the limit. *flops* rematch later.")
            ui.postDelayed({ clear() }, 3400L)
        } else {
            host.say(Lines.pick(CHEAT_LINES))
        }
    }

    /** Runs about thirty times a second: Pip's turn, and the clock. */
    fun referee(now: Long) {
        if (over || paused) return
        if (now - startedAt > TIME_LIMIT_MS) {
            timeUp()
            return
        }
        if (pipChasing) return
        if (!ball.atRest() || ball.movingByUser) return
        if (now - ball.restingSince < 1000L) return
        if (now < nextPipKick) return

        pipChasing = true
        nextPipKick = now + 3400L
        val aimX = ball.centreX
        host.petWalkTo(
            aimX,
            ball.centreY + host.petHeight() / 2f + host.px(6f)
        ) {
            pipChasing = false
            if (over || paused || packedAway() || ball.movingByUser) return@petWalkTo
            // If they booted it away while he was plodding over, he kicks at nothing.
            if (abs(ball.centreX - aimX) > host.px(26f)) {
                host.say("...it moved. that's fine. I meant to do that.")
                return@petWalkTo
            }
            host.feel(PetView.Mood.SMUG, 1600L)
            ball.kick(up = true, aim = rnd.nextFloat() * 1.8f - 0.9f, power = host.px(560f))
            host.say(Lines.pick(PIP_KICK_LINES))
            nextPipKick = SystemClock.uptimeMillis() + 2600L
        }
    }

    private fun timeUp() {
        over = true
        paused = true
        val line = when {
            youScore > pipScore -> "time. you're ahead. I want a recount."
            pipScore > youScore -> "time. I win. being this good is tiring."
            else -> "time. level. we're both bad at this."
        }
        host.feel(if (youScore > pipScore) PetView.Mood.ANNOYED else PetView.Mood.SMUG, 5000L)
        host.say(line)
        ui.postDelayed({ clear() }, 3800L)
    }

    private fun finish() {
        if (over || packedAway()) return
        over = true
        paused = true
        val youWon = youScore > pipScore
        host.feel(if (youWon) PetView.Mood.ANNOYED else PetView.Mood.SMUG, 6000L)
        host.say(
            if (youWon) {
                "you won. *sits down* that pitch is cursed. noted."
            } else {
                "$pipScore to $youScore. I'll take a trophy, or a snack."
            }
        )
        ui.postDelayed({ clear() }, 4500L)
    }

    /** Packs the whole game away. */
    fun clear() {
        // Over first: nothing queued may rebuild a pitch that is being packed away.
        over = true
        // Old match callbacks must never remove the next match's windows.
        listOf(board, topGoal, bottomGoal, ball).forEach(host::dropProp)
    }
}

/** One of the two goals. Move it and Pip will have opinions. */
/** What a goal says when tapped, so a second tap is not the same sentence twice. */
private val MY_GOAL_TAP = listOf(
    "that's my goal. it stays. I wrote that rule.",
    "my goal. hands off. my rules."
)
private val YOUR_GOAL_TAP = listOf(
    "your goal. moving it is against the rules I wrote.",
    "your goal. it stays put. my rules."
)
private val SCORE_TAP = listOf(
    "first to three. moving goals is cheating. I wrote that too.",
    "still first to three, and I'm still refereeing."
)

class Goal(host: PropHost, private val game: SoccerGame, val pipDefends: Boolean) : Prop(host) {

    override val boxW = 142f
    override val boxH = 50f

    private var originX = 0f
    private var originY = 0f

    private val ink = stroke(0xFF242146.toInt(), 6.5f)
    private val frame = stroke(if (pipDefends) 0xFFFF9C76.toInt() else 0xFF76E8CE.toInt(), 3.8f)
    private val net = stroke(0x997FEBD7.toInt(), 0.9f)
    private val netBack = fill(0xDD343366.toInt())
    private val badge = fill(if (pipDefends) 0xFFFFBC96.toInt() else 0xFF9DF6D9.toInt())
    private val stripe = stroke(0xFFFFFAE8.toInt(), 1.8f)
    private val label = text(0xFF242146.toInt(), 7.5f, true)
    private val cage = Path()

    /** Where the ball has to cross to count, in screen pixels. */
    val lineY: Float get() = if (pipDefends) posY + dp(13f) else posY + windowH - dp(13f)

    val mouthLeft: Float get() = posX + dp(12f)
    val mouthRight: Float get() = posX + windowW - dp(12f)

    fun rememberOrigin() {
        originX = posX
        originY = posY
    }

    override fun onDragFinished() {
        if (drift(originX, originY) > 18f) {
            rememberOrigin()
            game.goalMoved()
        }
    }

    override fun onTap(x: Float, y: Float) {
        host.say(Lines.pick(if (pipDefends) MY_GOAL_TAP else YOUR_GOAL_TAP))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val h = height.toFloat()
        val left = dp(12f)
        val right = width - dp(12f)
        val mouth = if (pipDefends) dp(13f) else h - dp(13f)
        val back = if (pipDefends) h - dp(6f) else dp(6f)
        val taper = dp(18f)
        cage.reset()
        cage.moveTo(left, mouth)
        cage.lineTo(left + taper, back)
        cage.lineTo(right - taper, back)
        cage.lineTo(right, mouth)
        cage.close()
        canvas.drawPath(cage, netBack)
        canvas.save()
        canvas.clipPath(cage)
        for (i in 0..10) {
            val f = i / 10f
            canvas.drawLine(left + (right - left) * f, mouth,
                left + taper + (right - left - 2 * taper) * f, back, net)
        }
        for (i in 1..4) {
            val f = i / 5f
            val y = mouth + (back - mouth) * f
            canvas.drawLine(left, y, right, y, net)
        }
        canvas.restore()
        canvas.drawPath(cage, ink)
        canvas.drawPath(cage, frame)
        // Two candy-striped posts sit on the actual scoring line.
        for (i in 0..6) {
            val x = left + dp(8f) + i * (right - left - dp(16f)) / 6f
            canvas.drawLine(x - dp(1f), mouth + dp(1f), x + dp(1f), mouth - dp(1f), stripe)
        }
        val badgeY = if (pipDefends) back - dp(6f) else back + dp(6f)
        canvas.drawRoundRect(cx - dp(27f), badgeY - dp(6f), cx + dp(27f), badgeY + dp(6f), dp(5f), dp(5f), badge)
        centred(canvas, if (pipDefends) "PIP'S GOAL" else "YOUR GOAL", cx, badgeY, label)
    }

    override fun describe(): String =
        if (pipDefends) "Pip's goal at the bottom of the pitch" else "your goal at the top"
}

/** The ball. Tap to boot it downwards at Pip's goal; he kicks it back up at yours. */
class Ball(host: PropHost, private val game: SoccerGame) : Prop(host) {

    override val boxW = 42f
    override val boxH = 42f

    private var vx = 0f
    private var vy = 0f
    private var moving = false
    private var lastAt = SystemClock.uptimeMillis()
    private var spin = 0f

    /** True once anyone has kicked it: the pitch must not move under a ball in play. */
    var kicked = false
        private set

    var restingSince = 0L
        private set

    private val leather = fill(0xFFFFFAEC.toInt())
    private val shade = fill(0xFFA8D7FF.toInt())
    private val seam = stroke(0xFF2B264D.toInt(), 1.15f)
    private val outline = stroke(0xFF242146.toInt(), 1.9f)
    private val patch = fill(0xFF4B438E.toInt())
    private val patchAlt = fill(0xFF5F58A8.toInt())
    private val highlight = fill(0xC9FFFFFF.toInt())
    private val shadow = fill(0x66312654)
    private val panelPath = Path()
    private val cosines = FloatArray(5) { kotlin.math.cos(Math.toRadians((it * 72 - 90).toDouble())).toFloat() }
    private val sines = FloatArray(5) { kotlin.math.sin(Math.toRadians((it * 72 - 90).toDouble())).toFloat() }

    fun atRest(): Boolean = !moving

    fun kick(up: Boolean, aim: Float, power: Float) {
        kicked = true
        vy = if (up) -power else power
        vx = aim * power * 0.6f
        moving = true
        lastAt = SystemClock.uptimeMillis()
    }

    fun reset() {
        moveTo(
            host.stageWidth() / 2f - windowW / 2f,
            (game.pitchTopY() + game.pitchFloorY()) / 2f - windowH / 2f
        )
        vx = 0f
        vy = 0f
        moving = false
        restingSince = SystemClock.uptimeMillis()
        scan = 0f
        invalidate()
    }

    private var scan = 0f

    override fun onTap(x: Float, y: Float) {
        if (game.isPaused()) return
        val aim = ((x / width.toFloat()) - 0.5f) * 2f
        kick(up = false, aim = aim, power = host.px(1150f))
        if (scan == 0f) {
            scan = 1f
            host.feel(PetView.Mood.SMUG, 1200L)
        }
    }

    override fun onDragStarted() {
        vx = 0f
        vy = 0f
        moving = false
    }

    override fun onDragFinished() {
        restingSince = SystemClock.uptimeMillis()
    }

    override fun onFrame() {
        if (game.isPaused() || movingByUser) return

        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastAt).coerceIn(0L, 60L)).toFloat() / 1000f
        lastAt = now

        if (!moving) return
        if (abs(vx) < 2f && abs(vy) < 2f) {
            moving = false
            vx = 0f
            vy = 0f
            restingSince = now
            return
        }

        vx *= 0.985f
        vy *= 0.985f

        var nx = posX + vx * dt
        var ny = posY + vy * dt

        val maxX = (host.stageWidth() - windowW).toFloat()
        if (nx <= 0f) {
            nx = 0f
            vx = -vx * 0.7f
        }
        if (nx >= maxX) {
            nx = maxX
            vx = -vx * 0.7f
        }

        val cx = nx + windowW / 2f
        val cy = ny + windowH / 2f
        val top = game.topGoal
        val bottom = game.bottomGoal

        if (cx in top.mouthLeft..top.mouthRight && cy < top.lineY) {
            game.scored(pipPoint = true)
            return
        }
        if (cx in bottom.mouthLeft..bottom.mouthRight && cy > bottom.lineY) {
            game.scored(pipPoint = false)
            return
        }

        val topEdge = game.pitchTopY()
        val floorY = game.pitchFloorY() - windowH
        if (ny <= topEdge) {
            ny = topEdge
            vy = -vy * 0.7f
        }
        if (ny >= floorY) {
            ny = floorY
            vy = -vy * 0.7f
        }

        spin = (spin + (vx + vy * 0.30f) * dt * 0.5f) % 360f
        moveTo(nx, ny)
        invalidate()
    }

    private fun pentagon(canvas: Canvas, cx: Float, cy: Float, radius: Float, alternate: Boolean) {
        panelPath.reset()
        panelPath.moveTo(cx + radius * cosines[0], cy + radius * sines[0])
        for (i in 1..4) panelPath.lineTo(cx + radius * cosines[i], cy + radius * sines[i])
        panelPath.close()
        canvas.drawPath(panelPath, if (alternate) patchAlt else patch)
        canvas.drawPath(panelPath, seam)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - dp(1f)
        val radius = cx - dp(3f)
        canvas.drawOval(cx - dp(14f), cy + dp(11f), cx + dp(16f), cy + dp(20f), shadow)
        canvas.drawCircle(cx, cy, radius, shade)
        canvas.drawCircle(cx - dp(1.2f), cy - dp(1.6f), radius - dp(2f), leather)
        canvas.save()
        canvas.rotate(spin, cx, cy)
        for (i in 0..4) {
            val px = cx + dp(12.5f) * cosines[i]
            val py = cy + dp(12.5f) * sines[i]
            canvas.drawLine(cx, cy, px, py, seam)
            pentagon(canvas, px, py, dp(3.7f), i % 2 == 0)
        }
        pentagon(canvas, cx, cy, dp(6.4f), false)
        canvas.restore()
        canvas.drawOval(cx - dp(11f), cy - dp(12f), cx - dp(4f), cy - dp(9f), highlight)
        canvas.drawCircle(cx, cy, radius, outline)
    }

    override fun describe(): String = "the soccer ball"
}

/** The little score pill, which is also the referee's clock. */
class ScoreBoard(host: PropHost, private val game: SoccerGame) : Prop(host) {

    override val boxW = 124f
    override val boxH = 48f

    private val body = fill(0xFF31305C.toInt())
    private val edge = stroke(0xFFFFEAC1.toInt(), 1.6f)
    private val pipTile = fill(0xFFFFAA85.toInt())
    private val playerTile = fill(0xFF91F1D9.toInt())
    private val scorePaint = text(0xFF272248.toInt(), 15f, true)
    private val teamPaint = text(0xFF272248.toInt(), 8f, true)
    private val rulePaint = text(0xFFFFEAC1.toInt(), 7.6f, true)
    private val border = RectF()
    private val pipScores = (0..SoccerGame.TARGET).map(Int::toString)

    override fun onFrame() {
        game.referee(SystemClock.uptimeMillis())
    }

    override fun onTap(x: Float, y: Float) {
        host.say(
            "PIP ${game.pipScore} — ${game.youScore} YOU. " + Lines.pick(SCORE_TAP)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val half = width / 2f
        border.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        panel(canvas, border, 11f, edge, body)
        canvas.drawRoundRect(dp(6f), dp(6f), half - dp(2f), dp(31f), dp(8f), dp(8f), pipTile)
        canvas.drawRoundRect(half + dp(2f), dp(6f), width - dp(6f), dp(31f), dp(8f), dp(8f), playerTile)
        centred(canvas, "PIP", dp(24f), dp(18f), teamPaint)
        centred(canvas, "YOU", width - dp(24f), dp(18f), teamPaint)
        centred(canvas, pipScores[game.pipScore.coerceIn(0, SoccerGame.TARGET)], half - dp(13f), dp(18f), scorePaint)
        centred(canvas, pipScores[game.youScore.coerceIn(0, SoccerGame.TARGET)], half + dp(13f), dp(18f), scorePaint)
        centred(canvas, "★  FIRST TO THREE  ★", half, dp(38f), rulePaint)
    }

    override fun describe(): String =
        "a soccer scoreboard showing you ${game.youScore}, Pip ${game.pipScore}"
}
