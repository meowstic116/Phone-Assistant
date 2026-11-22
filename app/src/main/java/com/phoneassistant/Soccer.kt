package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
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

    private val clock = ToyPlayClock()
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
    private var nextPipKick = 750L
    private var nextDecision = 0L
    private var lastUserPlay: Long? = null
    private var lastQuip = -4_000L
    private var nextRoundAt: Long? = null
    private var clearAt: Long? = null
    private var stageWidth = 0
    private var stageHeight = 0
    private var pipChasing = false

    fun start(): Boolean {
        board = ScoreBoard(host, this)
        topGoal = Goal(host, this, pipDefends = false)
        bottomGoal = Goal(host, this, pipDefends = true)
        ball = Ball(host, this)

        fitPitch()
        if (!layOut()) {
            clear()
            return false
        }
        ballHomeX = ball.posX
        ballHomeY = ball.posY
        host.say("first to three. tap the ball at my goal. I'll defend, badly.")
        host.feel(PetView.Mood.SMUG, 2600L)
        return true
    }

    /** The whole stage, or just what sits above a keyboard someone is typing on. */
    private fun fitPitch() {
        stageWidth = host.stageWidth()
        stageHeight = host.stageHeight()
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
        if (!layOut()) clear()
    }

    /** The pitch's edges, for the ball's own physics. */
    fun pitchTopY(): Float = pitchTop
    fun pitchFloorY(): Float = pitchBottom

    private fun layOut(): Boolean {
        val w = host.stageWidth().toFloat()
        val top = pitchTop
        val bottom = pitchBottom
        if (w < maxOf(topGoal.windowW, bottomGoal.windowW, board.windowW) ||
            bottom - top < topGoal.windowH + bottomGoal.windowH + ball.windowH + host.px(8f)) return false

        val goalLeft = (w - topGoal.windowW) / 2f
        val goalTop = top + host.px(4f)
        val score = SoccerLayout.scoreboard(w, top, goalLeft, goalTop + topGoal.windowH,
            board.windowW.toFloat(), margin = host.px(10f), gap = host.px(8f))
        if (!board.bring(score.x, score.y) || !topGoal.bring(goalLeft, goalTop) || !bottomGoal.bring(
            w / 2f - bottomGoal.windowW / 2f,
            bottom - bottomGoal.windowH - host.px(4f)
        ) || !ball.bring(w / 2f - ball.windowW / 2f, (top + bottom) / 2f - ball.windowH / 2f)) return false
        topGoal.rememberOrigin()
        bottomGoal.rememberOrigin()
        return true
    }

    fun isPaused(): Boolean = paused || over || host.toyPlayPaused()

    private fun cancelChase() = host.cancelPetWalk(board)

    fun userPlayed() {
        lastUserPlay = clock.elapsed
        nextDecision = clock.elapsed
    }

    private fun petFeet() = DragMotion.Point(host.petCentreX(), host.petFeetY())

    private fun DragMotion.Point.dpPoint(): DragMotion.Point = DragMotion.Point(x / host.px(1f), y / host.px(1f))
    private fun canRespond() = !isPaused() && !ball.inToyMotion && host.petCanPlay(board)

    private fun pipKick(at: DragMotion.Point, save: Boolean) {
        cancelChase() // Plant his feet instead of running on past the contact point.
        val behindGoal = at.y <= topGoal.lineY
        val aimY = if (behindGoal) (pitchTop + pitchBottom) / 2f else topGoal.lineY
        val aim = SoccerAi.aim(at.dpPoint(), topGoal.centreX / host.px(1f), aimY / host.px(1f),
            rnd.nextFloat() * 0.42f - 0.21f)
        nextPipKick = clock.elapsed + SoccerAi.KICK_COOLDOWN_MS
        nextDecision = clock.elapsed + SoccerAi.THINK_MS
        val power = when {
            behindGoal -> SoccerBallTuning.CLEAR_KICK
            save -> SoccerBallTuning.SAVE_KICK
            else -> SoccerBallTuning.PIP_KICK
        }
        ball.kick(up = !behindGoal, aim = aim, power = host.px(power))
        host.sound(ToySounds.Sound.KICK, if (save) 0.95f else 0.75f)
        if (clock.elapsed - lastQuip >= 3_600L) {
            lastQuip = clock.elapsed
            host.feel(PetView.Mood.SMUG, 1200L)
            host.say(if (save) "not past me. well, not this time." else Lines.pick(PIP_KICK_LINES))
        }
    }

    /** A save requires actual contact with Pip before the ball crosses his goal line. */
    internal fun trySave(from: DragMotion.Point, to: DragMotion.Point, goalCrossingX: Float?): Boolean {
        if (clock.elapsed < nextPipKick || to.y <= from.y || from.y >= bottomGoal.lineY) return false
        val feet = petFeet()
        val end = if (goalCrossingX == null) to else DragMotion.Point(goalCrossingX, bottomGoal.lineY)
        val hit = SoccerAi.kickContact(feet.dpPoint(), from.dpPoint(), end.dpPoint()) ?: return false
        if (!canRespond()) return false
        val point = DragMotion.Point(host.px(hit.x), host.px(hit.y))
        ball.moveTo(point.x - ball.windowW / 2f, point.y - ball.windowH / 2f)
        pipKick(point, save = true)
        return true
    }

    fun scored(pipPoint: Boolean) {
        if (isPaused()) return
        paused = true
        cancelChase()

        if (pipPoint) pipScore++ else youScore++
        board.invalidate()

        host.feel(if (pipPoint) PetView.Mood.SMUG else PetView.Mood.ANNOYED, 1800L)
        // It is a goal either way. His goal is slightly louder, because he would insist.
        host.sound(ToySounds.Sound.GOAL, if (pipPoint) 1f else 0.8f)
        host.postDelayedSound(ToySounds.Sound.WHISTLE, 620L, 0.55f)
        host.say(
            Lines.pick(if (pipPoint) PIP_GOAL_LINES else YOUR_GOAL_LINES)
        )

        nextRoundAt = clock.elapsed + 1_500L
    }

    fun goalMoved() {
        if (over) return
        cheats++
        host.feel(PetView.Mood.ANNOYED, 2800L)
        host.sound(ToySounds.Sound.BOING, 0.7f)
        if (cheats >= 3) {
            endRound(after = 3_400L)
            host.say("three goal moves. that's the limit. *flops* rematch later.")
        } else {
            host.say(Lines.pick(CHEAT_LINES))
        }
    }

    /** Runs only from the match's own scoreboard, never from a detached timer. */
    fun referee(now: Long) {
        if (packedAway()) return
        val blocked = host.toyPlayPaused()
        val elapsed = clock.frame(now, blocked)
        if (blocked) return
        if (over) {
            if (clearAt?.let { elapsed >= it } == true) clear()
            return
        }
        if (host.stageWidth() != stageWidth || host.stageHeight() != stageHeight) {
            // Rotation invalidates old goal coordinates, not either player's score.
            cancelChase()
            fitPitch()
            if (!layOut()) { clear(); return }
            ball.reset()
        }
        nextRoundAt?.let { next ->
            if (elapsed < next) return
            nextRoundAt = null
            if (youScore >= TARGET || pipScore >= TARGET) { finish(); return }
            ball.reset()
            paused = false
            nextPipKick = elapsed + 750L
            nextDecision = elapsed
            lastUserPlay = null
        }
        if (elapsed > TIME_LIMIT_MS) { timeUp(); return }
        if (!paused && elapsed >= 800L && !pipChasing && ball.atRest() && !ball.movingByUser &&
            ball.posX == ballHomeX && ball.posY == ballHomeY && ball.restingSince == 0L && cheats == 0) refitPitch()
        if (!canRespond()) return
        val feet = petFeet().dpPoint()
        val state = ball.motion()
        val held = ball.movingByUser
        val pressing = held || lastUserPlay?.let { elapsed - it < 1_500L } == true
        if (elapsed < 800L && !pressing) return
        val scale = host.px(1f)
        // At the top edge Pip can't stand any higher. Let him head a stranded ball
        // only if it actually touches his body; don't let him shoot it from a distance.
        val walkTop = host.stageTop() + host.px(ToyFooting.WALK_TOP_PADDING_DP)
        val edge = ToyFooting.feetY(walkTop.toInt().toFloat(), host.petHeight(), scale) / scale
        val head = DragMotion.Point(feet.x, feet.y - host.petHeight() / scale + 17f)
        val header = SoccerAi.headerContact(state.centre, head, feet, minimumFeetY = edge)
        if (!held && elapsed >= nextPipKick && state.vy >= -160f &&
            (header || SoccerAi.kickContact(feet, state.centre, state.centre) != null)) {
            pipKick(DragMotion.Point(ball.centreX, ball.centreY), save = state.vy > 80f)
            return
        }
        if (elapsed < nextDecision) return
        nextDecision = elapsed + SoccerAi.THINK_MS
        val radius = ball.windowW / 2f
        val pitch = SoccerAi.Pitch(radius / scale, (stageWidth - radius) / scale,
            (pitchTop + radius) / scale, (pitchBottom - radius) / scale,
            bottomGoal.mouthLeft / scale, bottomGoal.mouthRight / scale, bottomGoal.lineY / scale)
        val plan = SoccerAi.plan(feet, state, pitch, pressing)
        if (held && plan.role != SoccerAi.Role.GUARD) {
            cancelChase()
            return
        }
        val x = host.px(plan.feet.x)
        val y = ToyFooting.centreForFeet(host.px(plan.feet.y), host.petHeight(), scale)
        if (host.petIsNear(x, y, host.px(4f))) {
            cancelChase()
        } else if (!host.retargetPetWalk(board, x, y)) {
            pipChasing = host.petWalkTo(board, x, y) { pipChasing = false }
        }
    }

    private fun endRound(after: Long) {
        over = true
        paused = true
        cancelChase()
        clearAt = clock.elapsed + after
    }

    private fun timeUp() {
        endRound(after = 3_800L)
        val line = when {
            youScore > pipScore -> "time. you're ahead. I want a recount."
            pipScore > youScore -> "time. I win. being this good is tiring."
            else -> "time. level. we're both bad at this."
        }
        host.feel(if (youScore > pipScore) PetView.Mood.ANNOYED else PetView.Mood.SMUG, 5000L)
        host.say(line)
    }

    private fun finish() {
        if (over || packedAway()) return
        endRound(after = 4_500L)
        val youWon = youScore > pipScore
        host.feel(if (youWon) PetView.Mood.ANNOYED else PetView.Mood.SMUG, 6000L)
        host.say(
            if (youWon) {
                "you won. *sits down* that pitch is cursed. noted."
            } else {
                "$pipScore to $youScore. I'll take a trophy, or a snack."
            }
        )
    }

    /** Packs the whole game away. */
    fun clear() {
        // Over first: nothing queued may rebuild a pitch that is being packed away.
        over = true
        cancelChase()
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

    override val boxW = SoccerBallTuning.BALL_SIZE_DP
    override val boxH = SoccerBallTuning.BALL_SIZE_DP

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

    internal fun motion(): SoccerAi.Ball = SoccerAi.Ball(DragMotion.Point(centreX / dp(1f), centreY / dp(1f)),
        vx / dp(1f), vy / dp(1f))

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
        game.userPlayed()
        val aim = ((x / width.toFloat()) - 0.5f) * 2f
        kick(up = false, aim = aim, power = dp(SoccerBallTuning.PLAYER_KICK))
        if (scan == 0f) {
            scan = 1f
            host.feel(PetView.Mood.SMUG, 1200L)
        }
    }

    override fun onDragStarted() {
        game.userPlayed()
        vx = 0f
        vy = 0f
        moving = false
    }

    override fun onDragFinished() {
        game.userPlayed()
        restingSince = SystemClock.uptimeMillis()
    }

    override fun onFrame() {
        if (game.isPaused() || movingByUser) return

        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastAt).coerceIn(0L, 60L)).toFloat() / 1000f
        lastAt = now

        if (!moving) return
        if (abs(vx) < dp(SoccerBallTuning.STOP_SPEED) && abs(vy) < dp(SoccerBallTuning.STOP_SPEED)) {
            moving = false
            vx = 0f
            vy = 0f
            restingSince = now
            return
        }

        val retention = SoccerBallTuning.retainedSpeed(dt)
        vx *= retention
        vy *= retention

        var nx = posX + vx * dt
        var ny = posY + vy * dt

        val maxX = (host.stageWidth() - windowW).toFloat()
        if (nx <= 0f) {
            nx = 0f
            vx = -vx * SoccerBallTuning.REBOUND
        }
        if (nx >= maxX) {
            nx = maxX
            vx = -vx * SoccerBallTuning.REBOUND
        }

        val from = DragMotion.Point(centreX, centreY)
        val to = DragMotion.Point(nx + windowW / 2f, ny + windowH / 2f)
        val top = game.topGoal
        val bottom = game.bottomGoal
        val topCrossing = if (vy < 0f) SoccerAi.crossingX(from, to, top.lineY) else null
        val bottomCrossing = if (vy > 0f) SoccerAi.crossingX(from, to, bottom.lineY) else null

        if (topCrossing != null && topCrossing in top.mouthLeft..top.mouthRight) {
            game.scored(pipPoint = true)
            return
        }
        if (game.trySave(from, to, bottomCrossing)) return
        if (bottomCrossing != null && bottomCrossing in bottom.mouthLeft..bottom.mouthRight) {
            game.scored(pipPoint = false)
            return
        }

        val topEdge = game.pitchTopY()
        val floorY = game.pitchFloorY() - windowH
        if (ny <= topEdge) {
            ny = topEdge
            vy = -vy * SoccerBallTuning.REBOUND
        }
        if (ny >= floorY) {
            ny = floorY
            vy = -vy * SoccerBallTuning.REBOUND
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
        val cy = height / 2f - dp(SoccerBallTuning.DRAW_LIFT_DP)
        val radius = cx - dp(SoccerBallTuning.DRAW_INSET_DP)
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

    internal override fun keepsToy(now: Long): Boolean = true // The match owns its full clock and cleanup.

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
