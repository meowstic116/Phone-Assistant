package com.phoneassistant

/** The goal keeps its central position; only the scoreboard moves when the screen is narrow. */
internal object SoccerLayout {
    fun scoreboard(stageWidth: Float, pitchTop: Float, goalLeft: Float, goalBottom: Float,
                   boardWidth: Float, margin: Float, gap: Float): DragMotion.Point {
        val left = margin.coerceAtMost((stageWidth - boardWidth).coerceAtLeast(0f))
        val beside = left + boardWidth + gap <= goalLeft
        return DragMotion.Point(left, if (beside) pitchTop + margin else goalBottom + gap)
    }
}
