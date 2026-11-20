package com.phoneassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** A catalog, kept separate from the app's permissions and setup. */
class ToysActivity : Activity() {
    private data class Art(val description: Int, val action: Int, val icon: Int, val badge: Int)
    private data class Card(val action: Button, val hint: TextView)
    private lateinit var collectionProgress: TextView
    private val collectionChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateCollectionProgress()
    }

    private lateinit var status: TextView
    private lateinit var tidy: Button
    private lateinit var rest: Button
    private val cards = mutableMapOf<Toy, Card>()
    private val powerChanged: () -> Unit = { refresh() }
    private var onBreak = false
    private val countdown = object : Runnable {
        override fun run() {
            val left = Quiet.remaining(this@ToysActivity)
            if ((left > 0L) != onBreak) refresh() else if (onBreak) showStatus(left)
            status.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = column(20)
        setContentView(ScrollView(this).apply {
            id = R.id.toyMenuScroll
            isFillViewport = true
            setBackgroundColor(getColor(R.color.bg))
            addView(page)
        })

        page.addView(pair(
            button(R.string.toys_back) { finish() },
            button(R.string.toys_settings) {
                startActivity(Intent(this, PipSettingsActivity::class.java)
                    .putExtra(PipSettingsActivity.EXTRA_SHOW_TOYS, true))
            }
        ))
        page.addView(label(32f, R.color.text, heading = true).apply { setText(R.string.toys_title) }, row(12))
        page.addView(label(15f).apply { setText(R.string.toys_intro) }, row(4))

        val collectionLink = column(12).apply { background = rounded(getColor(R.color.surface), 16) }
        collectionLink.addView(button(R.string.plant_collection_title) {
            startActivity(Intent(this, PlantCollectionActivity::class.java))
        }, row())
        collectionProgress = label(13f, R.color.accent).apply { gravity = Gravity.CENTER }
        collectionLink.addView(collectionProgress, row(top = 2))
        page.addView(collectionLink, row(14))

        val controls = column(12).apply { background = rounded(getColor(R.color.surface), 16) }
        status = label(14f, R.color.accent)
        controls.addView(status, row(bottom = 6))
        tidy = button(R.string.toys_tidy) {
            if (!OverlayService.tidyUp()) Toast.makeText(this, R.string.toys_off, Toast.LENGTH_SHORT).show()
            refresh()
        }
        rest = button(R.string.toys_rest) {
            if (Quiet.active(this)) {
                if (!OverlayService.endBreak()) Quiet.clear(this)
                refresh()
            } else askBreakLength()
        }
        controls.addView(pair(tidy, rest))
        page.addView(controls, row(16))

        page.addView(label(12f, R.color.accent, heading = true).apply {
            letterSpacing = 0.12f
            setText(R.string.toys_catalog_heading)
        }, row(23, 2))
        Toy.values().forEach { addCard(page, it) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        OverlayService.onPowerStateChanged = powerChanged
        PlantCollection.preferences(this).registerOnSharedPreferenceChangeListener(collectionChanges)
        updateCollectionProgress()
        refresh()
        status.postDelayed(countdown, 1_000L)
    }

    override fun onPause() {
        PlantCollection.preferences(this).unregisterOnSharedPreferenceChangeListener(collectionChanges)
        if (OverlayService.onPowerStateChanged === powerChanged) OverlayService.onPowerStateChanged = null
        status.removeCallbacks(countdown)
        super.onPause()
    }

    private fun updateCollectionProgress() {
        val (discovered, visible) = PlantSelection.collectionProgress(PlantCollection.counts(this))
        collectionProgress.text = getString(R.string.plant_collection_summary, discovered, visible)
    }

    private fun addCard(page: LinearLayout, toy: Toy) {
        val art = artFor(toy)
        val card = column(14).apply { background = rounded(getColor(R.color.surface), 16) }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ImageView(this).apply {
            setImageResource(art.icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = rounded(art.badge, 13)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        titleRow.addView(label(20f, R.color.text, heading = true).apply { text = toy.label },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        card.addView(titleRow, row())
        card.addView(label(15f).apply { setText(art.description) }, row(8))
        val hint = label(13f, R.color.accent)
        card.addView(hint, row(8))
        val action = button(art.action, primary = true) {
            if (!OverlayService.bringOut(toy)) Toast.makeText(this, R.string.toys_busy, Toast.LENGTH_SHORT).show()
            refresh()
        }.apply { contentDescription = getString(R.string.toys_action_accessibility, toy.label, text) }
        card.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        page.addView(card, row(10))
        cards[toy] = Card(action, hint)
    }

    private fun refresh() {
        val left = Quiet.remaining(this)
        onBreak = left > 0L
        showStatus(left)
        val toysAllowed = PipSettings.enabled(this, PipSettings.Option.TOYS)
        val reelAccess = Screen.available()
        val instagramAvailable = packageManager.getLaunchIntentForPackage(DoomscrollMotion.INSTAGRAM_PACKAGE) != null
        val canPlay = !onBreak && OverlayService.running && !OverlayService.stopping && toysAllowed
        tidy.isEnabled = OverlayService.running && !OverlayService.stopping
        rest.setText(if (onBreak) R.string.toys_end_rest else R.string.toys_rest)
        for ((toy, card) in cards) {
            val issue = when {
                !toysAllowed -> null // already explained above the catalog
                !PipSettings.toySelected(this, toy) -> R.string.toy_disabled
                toy == Toy.PAINTING && PaintingGallery.pictures(this).none { PaintingGallery.enabled(this, it) } ->
                    R.string.toy_canvas_disabled
                toy == Toy.DOOMSCROLL && !reelAccess -> R.string.toy_reels_access
                toy == Toy.DOOMSCROLL && !instagramAvailable -> R.string.toy_reels_instagram
                else -> null
            }
            card.hint.visibility = if (issue == null) View.GONE else View.VISIBLE
            if (issue != null) card.hint.setText(issue)
            card.action.isEnabled = canPlay && issue == null
        }
    }

    private fun showStatus(left: Long) {
        val message = when {
            left > 0L -> {
                val total = (left + 999L) / 1_000L
                val hours = total / 3_600L
                val minutes = total / 60L % 60L
                val seconds = total % 60L
                val clock = if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
                    else "%d:%02d".format(minutes, seconds)
                getString(R.string.toys_rest_status, clock)
            }
            OverlayService.stopping -> getString(R.string.toys_stopping)
            !PipSettings.enabled(this, PipSettings.Option.TOYS) -> getString(R.string.toys_disabled)
            !OverlayService.running -> getString(R.string.toys_off)
            else -> null
        }
        if (message != null) status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun askBreakLength() {
        val minutes = longArrayOf(15, 30, 60, 120)
        AlertDialog.Builder(this)
            .setTitle(R.string.toys_rest_prompt)
            .setItems(R.array.toys_rest_lengths) { _, which ->
                val duration = minutes[which] * 60_000L
                if (!OverlayService.startBreak(duration)) Quiet.start(this, duration)
                refresh()
            }
            .show()
    }

    private fun artFor(toy: Toy): Art = when (toy) {
        Toy.LEMONADE -> Art(R.string.toy_lemonade_description, R.string.toy_lemonade_action,
            R.drawable.ic_toy_lemonade, 0xFFF6E4AE.toInt())
        Toy.PAINTING -> Art(R.string.toy_canvas_description, R.string.toy_canvas_action,
            R.drawable.ic_toy_canvas, 0xFFEBE0FA.toInt())
        Toy.SOCCER -> Art(R.string.toy_soccer_description, R.string.toy_soccer_action,
            R.drawable.ic_toy_soccer, 0xFFD3EFF9.toInt())
        Toy.BUBBLES -> Art(R.string.toy_bubbles_description, R.string.toy_bubbles_action,
            R.drawable.ic_toy_bubbles, 0xFFFFE9F3.toInt())
        Toy.PLANT -> Art(R.string.toy_plant_description, R.string.toy_plant_action,
            R.drawable.ic_toy_plant, 0xFFDAF3D9.toInt())
        Toy.DOOMSCROLL -> Art(R.string.toy_doomscroll_description, R.string.toy_doomscroll_action,
            R.drawable.ic_toy_doomscroll, 0xFFFFE2CC.toInt())
    }

    private fun pair(first: Button, second: Button) = LinearLayout(this).apply {
        addView(first, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
        addView(second, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun button(title: Int, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        setText(title)
        textSize = 14f
        isAllCaps = false
        minHeight = dp(48)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        backgroundTintList = ColorStateList(states, intArrayOf(getColor(R.color.surfaceAlt),
            getColor(if (primary) R.color.accent else R.color.surfaceAlt)))
        setTextColor(ColorStateList(states, intArrayOf(getColor(R.color.textDim),
            getColor(if (primary) R.color.onAccent else R.color.text))))
        setOnClickListener { click() }
    }

    private fun label(size: Float, color: Int = R.color.textDim, heading: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(getColor(color))
        setLineSpacing(dp(2).toFloat(), 1f)
        if (heading) {
            setTypeface(typeface, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    }

    private fun column(padding: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    private fun row(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
