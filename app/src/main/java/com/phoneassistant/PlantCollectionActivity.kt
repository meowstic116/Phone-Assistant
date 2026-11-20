package com.phoneassistant

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Collectible bloom progress lives in its own screen, separate from toy controls. */
class PlantCollectionActivity : Activity() {
    private data class PlantRow(val icon: BloomIcon, val name: TextView, val count: TextView)
    private val plantRows = mutableMapOf<PlantSpecies, PlantRow>()
    private lateinit var plantProgress: TextView
    private lateinit var goldenSection: LinearLayout
    private lateinit var goldenHint: TextView
    private val plantChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshCollection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = column(20)
        setContentView(ScrollView(this).apply {
            id = R.id.plantCollectionScroll
            isFillViewport = true
            setBackgroundColor(getColor(R.color.bg))
            addView(page)
        })
        page.addView(Button(this).apply {
            setText(R.string.plant_collection_back)
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2))
        page.addView(label(28f, R.color.text, heading = true).apply {
            setText(R.string.plant_collection_title)
        }, row(top = 12))
        page.addView(buildPlantCollection(), row(top = 12))
    }

    override fun onResume() {
        super.onResume()
        PlantCollection.preferences(this).registerOnSharedPreferenceChangeListener(plantChanges)
        refreshCollection()
    }

    override fun onPause() {
        PlantCollection.preferences(this).unregisterOnSharedPreferenceChangeListener(plantChanges)
        super.onPause()
    }

    private fun buildPlantCollection(): View {
        val section = column(14).apply { background = rounded(getColor(R.color.surface), 16) }
        plantProgress = label(13f, R.color.accent)
        section.addView(plantProgress, row(top = 4, bottom = 4))
        goldenSection = column(0).apply { visibility = View.GONE }
        goldenSection.addView(label(12f, R.color.accent, heading = true).apply {
            setText(R.string.plant_collection_bonus)
            letterSpacing = 0.08f
        }, row(top = 12))
        for (species in PlantSpecies.values()) {
            val icon = BloomIcon(species)
            val name = label(14f).apply { text = species.display }
            val count = label(13f).apply { gravity = Gravity.END }
            val tile = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = rounded(getColor(R.color.surfaceAlt), 10)
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(8)
                    marginEnd = dp(5)
                })
                addView(count, LinearLayout.LayoutParams(-2, -2))
            }
            val parent = if (species == PlantSpecies.GOLDEN) goldenSection else section
            parent.addView(tile, row(top = 5))
            plantRows[species] = PlantRow(icon, name, count)
        }
        goldenHint = label(13f)
        goldenSection.addView(goldenHint, row(top = 6))
        section.addView(goldenSection, row())
        refreshCollection()
        return section
    }

    private fun refreshCollection() {
        val counts = PlantCollection.counts(this)
        val regular = PlantSelection.regular
        plantProgress.text = getString(R.string.plant_collection_regular_progress,
            regular.count { counts.getValue(it) > 0L }, regular.size)
        val revealGolden = PlantSelection.goldenEligible(counts)
        goldenSection.visibility = if (revealGolden) View.VISIBLE else View.GONE
        if (revealGolden) {
            goldenHint.setText(if (counts.getValue(PlantSpecies.GOLDEN) > 0L) R.string.plant_collection_golden_found
                else R.string.plant_collection_golden_eligible)
        }
        for ((species, entry) in plantRows) {
            val times = counts.getValue(species)
            val unlocked = times > 0L
            entry.icon.unlocked = unlocked
            entry.name.setTextColor(if (unlocked) species.discoveryColor else getColor(R.color.textDim))
            entry.count.setTextColor(getColor(if (unlocked) R.color.text else R.color.textDim))
            entry.count.text = getString(R.string.plant_collection_count, times)
            entry.count.contentDescription = if (unlocked) entry.count.text else getString(R.string.plant_collection_locked)
        }
    }

    private inner class BloomIcon(private val species: PlantSpecies) : View(this@PlantCollectionActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        var unlocked = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            val x = width / 2f
            val y = height / 2f
            for (i in 0 until species.petals) {
                paint.color = if (!unlocked) 0xFF697181.toInt() else if (i % 2 == 0) species.main else species.light
                BloomArt.petal(canvas, species.shape, x + r * 0.49f * species.petalX[i],
                    y + r * 0.49f * species.petalY[i], r * 0.32f, species.angle(i), paint, path)
            }
            paint.color = if (unlocked) 0xFFFFDB7D.toInt() else 0xFF969EAD.toInt()
            canvas.drawCircle(x, y, r * 0.35f, paint)
        }
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
