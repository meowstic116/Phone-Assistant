package com.phoneassistant

import android.graphics.Canvas
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
            "did you just *move* my goal? put that back. I saw that.",
            "you moved it again. I have eyes, they're enormous, they see everything.",
            "one more goalpost shuffle and I'm taking my ball home."
        )
        val PIP_GOAL_LINES = listOf(
            "GOAL. *tiny victory dance* that's how it's done.",
            "in! the net! I'm basically a professional and I have the legs for it.",
            "hah! did you even see it go past you? because I did. smugly."
        )
        val YOUR_GOAL_LINES = listOf(
            "fine. FINE. that one was lucky and we both know it.",
            "*stares at the ball* ... okay. okay. that happened.",
            "ooh, one for you. *slow, tiny clap* look at me being gracious. I'm great at this."
        )
        val PIP_KICK_LINES = listOf(
            "my turn. *scuffs it*",
            "*boots it* this one's for all the phones shaped like me.",
            "watch the technique. there isn't any.",
            "here comes the Pip express."
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

        layOut()

        host.say(
            "first to three. tap the ball to boot it at my goal, down there. " +
                "I'll be defending. badly, probably."
        )
        host.feel(PetView.Mood.SMUG, 2600L)
    }

    private fun layOut() {
        val w = host.stageWidth().toFloat()
        val top = host.stageTop().toFloat()
        val bottom = (host.stageHeight() - host.stageBottom()).toFloat()

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
        host.say(if (pipPoint) PIP_GOAL_LINES.random() else YOUR_GOAL_LINES.random())

        if (youScore >= TARGET || pipScore >= TARGET) {
            ui.postDelayed({ finish() }, 1500L)
        } else {
            ui.postDelayed({
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
            host.say(
                "that's three goalpost moves, which is the legal limit. I'm calling it. " +
                    "*flops* rematch later, once I've emotionally recovered."
            )
            ui.postDelayed({ clear() }, 3400L)
        } else {
            host.say(CHEAT_LINES[(cheats - 1).coerceAtMost(CHEAT_LINES.size - 1)])
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
        if (!ball.atRest()) return
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
            if (over || paused) return@petWalkTo
            // If they booted it away while he was plodding over, he kicks at nothing.
            if (abs(ball.centreX - aimX) > host.px(26f)) {
                host.say("...it moved. that's fine. I meant to do that.")
                return@petWalkTo
            }
            host.feel(PetView.Mood.SMUG, 1600L)
            ball.kick(up = true, aim = rnd.nextFloat() * 1.8f - 0.9f, power = host.px(560f))
            host.say(PIP_KICK_LINES.random())
            nextPipKick = SystemClock.uptimeMillis() + 2600L
        }
    }

    private fun timeUp() {
        over = true
        paused = true
        val line = when {
            youScore > pipScore -> "time's up and you're ahead. I demand a recount, and a rematch, and a snack."
            pipScore > youScore -> "time's up. I win. *flops over dramatically* being this good is exhausting."
            else -> "time's up and we're level. we're both bad at this. especially you."
        }
        host.feel(if (youScore > pipScore) PetView.Mood.ANNOYED else PetView.Mood.SMUG, 5000L)
        host.say(line)
        ui.postDelayed({ clear() }, 3800L)
    }

    private fun finish() {
        if (over) return
        over = true
        paused = true
        val youWon = youScore > pipScore
        host.feel(if (youWon) PetView.Mood.ANNOYED else PetView.Mood.SMUG, 6000L)
        host.say(
            if (youWon) {
                "you won. *sits down heavily* I'd like it noted that this pitch is " +
                    "obviously cursed and that I let you, for morale reasons."
            } else {
                "$pipScore to $youScore. I win. I'd like a trophy, or at least a snack. " +
                    "you can be the snack. I mean you can get the snack."
            }
        )
        ui.postDelayed({ clear() }, 4500L)
    }

    /** Packs the whole game away. */
    fun clear() {
        host.propsOnScreen().forEach { prop ->
            if (prop is Goal || prop is Ball || prop is ScoreBoard) host.dropProp(prop)
        }
    }
}

/** One of the two goals. Move it and Pip will have opinions. */
class Goal(host: PropHost, private val game: SoccerGame, val pipDefends: Boolean) : Prop(host) {

    override val boxW = 142f
    override val boxH = 50f

    private var originX = 0f
    private var originY = 0f

    private val glass = fill(0x22FFFFFF)
    private val netPaint = stroke(0x55FFFFFF, 0.7f)
    private val postPaint = stroke(if (pipDefends) Ink.ALERT else Ink.ACCENT, 3.2f)
    private val label = text(if (pipDefends) Ink.ALERT else Ink.ACCENT, 7.5f, true)

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
        host.say(
            if (pipDefends) "that's my goal. it stays where I put it. that's the rule I just invented."
            else "your goal. it's not supposed to move. that's not me being a sore loser, that's the law."
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inner = RectF(dp(8f), dp(8f), w - dp(8f), h - dp(8f))

        canvas.drawRect(inner, glass)
        canvas.save()
        canvas.clipRect(inner)
        var x = inner.left
        while (x < inner.right) {
            canvas.drawLine(x, inner.top, x + h, inner.bottom, netPaint)
            x += dp(9f)
        }
        var y = inner.top
        while (y < inner.bottom) {
            canvas.drawLine(inner.left, y, inner.right, y, netPaint)
            y += dp(8f)
        }
        canvas.restore()

        canvas.drawRect(inner, postPaint)
        if (pipDefends) {
            canvas.drawLine(inner.left, inner.top, inner.right, inner.top, postPaint)
            centred(canvas, "PIP'S GOAL", inner.centerX(), inner.bottom - dp(7f), label)
        } else {
            canvas.drawLine(inner.left, inner.bottom, inner.right, inner.bottom, postPaint)
            centred(canvas, "YOUR GOAL", inner.centerX(), inner.top + dp(8f), label)
        }
    }

    override fun describe(): String =
        if (pipDefends) "Pip's goal at the bottom of the screen" else "your goal at the top"
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

    var restingSince = 0L
        private set

    private val leather = fill(0xFFF3F5F8.toInt())
    private val seam = stroke(0xFF5C6675.toInt(), 1.2f)
    private val patch = fill(0xFF232936.toInt())

    fun atRest(): Boolean = !moving

    fun kick(up: Boolean, aim: Float, power: Float) {
        vy = if (up) -power else power
        vx = aim * power * 0.6f
        moving = true
        lastAt = SystemClock.uptimeMillis()
    }

    fun reset() {
        moveTo(
            host.stageWidth() / 2f - windowW / 2f,
            (host.stageTop() + host.stageHeight() - host.stageBottom()) / 2f - windowH / 2f
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

    override fun onFrame() {
        if (game.isPaused()) return

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

        val topEdge = host.stageTop().toFloat()
        val floorY = (host.stageHeight() - host.stageBottom() - windowH).toFloat()
        if (ny <= topEdge) {
            ny = topEdge
            vy = -vy * 0.7f
        }
        if (ny >= floorY) {
            ny = floorY
            vy = -vy * 0.7f
        }

        spin += vx * dt * 0.5f
        moveTo(nx, ny)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        canvas.save()
        canvas.rotate(spin, r, r)
        canvas.drawCircle(r, r, r - dp(1.5f), leather)
        canvas.drawCircle(r, r, r - dp(1.5f), seam)
        canvas.drawCircle(r, r, dp(5.5f), patch)
        for (i in 0 until 5) {
            val angle = Math.toRadians((i * 72).toDouble())
            val px = r + (r * 0.62f) * Math.cos(angle).toFloat()
            val py = r + (r * 0.62f) * Math.sin(angle).toFloat()
            canvas.drawCircle(px, py, dp(3.2f), patch)
        }
        canvas.restore()
    }

    override fun describe(): String = "the soccer ball"
}

/** The little score pill, which is also the referee's clock. */
class ScoreBoard(host: PropHost, private val game: SoccerGame) : Prop(host) {

    override val boxW = 124f
    override val boxH = 48f

    private val body = fill(Ink.BG)
    private val edge = stroke(Ink.EDGE, 1f)
    private val scorePaint = text(Ink.TEXT, 11f, true)
    private val rulePaint = text(Ink.DIM, 6.8f)

    override fun onFrame() {
        game.referee(SystemClock.uptimeMillis())
    }

    override fun onTap(x: Float, y: Float) {
        host.say(
            "PIP ${game.pipScore} — ${game.youScore} YOU. first to three. " +
                "and moving goals is cheating, which I know because I made the rules."
        )
    }

    override fun onDraw(canvas: Canvas) {
        panel(canvas, RectF(dp(1f), dp(1f), width - dp(1f), height - dp(1f)), 12f, edge, body)
        centred(
            canvas,
            "PIP ${game.pipScore}   ·   ${game.youScore} YOU",
            width / 2f,
            height * 0.42f,
            scorePaint
        )
        centred(canvas, "first to ${SoccerGame.TARGET}", width / 2f, height * 0.76f, rulePaint)
    }

    override fun describe(): String =
        "a soccer scoreboard showing you ${game.youScore}, Pip ${game.pipScore}"
}
