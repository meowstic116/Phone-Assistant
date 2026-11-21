package com.phoneassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class PaintingGalleryActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var add: Button
    private var renderId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(page)
        })
        page.addView(label("Painting gallery", 30f))
        page.addView(label("Pip picks from the pictures you turn on. Imported pictures stay on this phone. You can add up to ${PaintingGallery.MAX_CUSTOM}.", 14f))
        add = Button(this).apply {
            text = "Add pictures"
            isAllCaps = false
            setOnClickListener { pickImages() }
        }
        page.addView(add, -1, -2)
        status = label("", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        page.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list, -1, -2)
        page.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { finish() }
        }, -1, -2)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val mine = ++renderId
        val pictures = PaintingGallery.pictures(this)
        val previews = mutableListOf<Pair<PaintingGallery.Picture, ImageView>>()
        list.removeAllViews()
        for ((title, section) in listOf("Built-in pictures" to pictures.filter { it.style != null },
            "Your pictures" to pictures.filter { it.style == null })) {
            list.addView(label(title, 19f).apply { setPadding(0, dp(16), 0, dp(4)) })
            if (section.isEmpty()) list.addView(label("No pictures added yet.", 14f))
            section.forEach { picture ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val thumbnail = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                row.addView(thumbnail, LinearLayout.LayoutParams(dp(52), dp(46)).apply { marginEnd = dp(10) })
                previews += picture to thumbnail
                row.addView(Switch(this).apply {
                    text = picture.label
                    setTextColor(getColor(R.color.text))
                    minHeight = dp(48)
                    isChecked = PaintingGallery.enabled(this@PaintingGalleryActivity, picture)
                    setOnCheckedChangeListener { _, enabled ->
                        PaintingGallery.setEnabled(this@PaintingGalleryActivity, picture, enabled)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (picture.style == null) row.addView(Button(this).apply {
                    text = "Remove"
                    contentDescription = "Remove ${picture.label}"
                    isAllCaps = false
                    setOnClickListener { confirmRemoval(picture) }
                }, -2, dp(48))
                list.addView(row, -1, -2)
            }
        }
        val ctx = applicationContext
        PaintingGallery.worker.execute {
            val decoded = previews.map { (picture, image) -> PaintingGallery.bitmap(ctx, picture, thumbnail = true) to image }
            runOnUiThread {
                if (!isDestroyed && !isFinishing && mine == renderId) decoded.forEach { (bitmap, view) -> view.setImageBitmap(bitmap) }
            }
        }
    }

    private fun confirmRemoval(picture: PaintingGallery.Picture) {
        AlertDialog.Builder(this)
            .setTitle("Remove this picture?")
            .setMessage("Pip's copy of “${picture.label}” will be deleted. The original picture won't be changed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                if (PaintingGallery.remove(this, picture)) {
                    OverlayService.removeDisplayedPicture(picture.id)
                    status.text = "Picture removed."
                    render()
                } else status.text = "Couldn't remove that picture."
            }.show()
    }

    @Suppress("DEPRECATION") // Platform picker with no extra dependencies; supported from API 26.
    private fun pickImages() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, PICK_IMAGES)
        } catch (_: ActivityNotFoundException) {
            status.text = "No Android picture picker is available."
        }
    }

    @Deprecated("The platform activity result API also supports API 26.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGES || resultCode != RESULT_OK || data == null) return
        val uris = linkedSetOf<Uri>()
        data.data?.let(uris::add)
        data.clipData?.let { clip ->
            for (i in 0 until minOf(clip.itemCount, PaintingGallery.MAX_CUSTOM + 1)) clip.getItemAt(i).uri?.let(uris::add)
        }
        if (uris.isEmpty()) return
        add.isEnabled = false
        status.text = "Adding pictures…"
        val ctx = applicationContext
        PaintingGallery.worker.execute {
            val results = uris.map { PaintingGallery.import(ctx, it) }
            val saved = results.count { it == PaintingGallery.ImportResult.SAVED }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                status.text = buildString {
                    append(if (saved == 1) "Added 1 picture." else "Added $saved pictures.")
                    if (PaintingGallery.ImportResult.FULL in results) append(" The gallery is full.")
                    if (PaintingGallery.ImportResult.INVALID in results) append(" Some pictures couldn't be read or were too large.")
                }
                add.isEnabled = true
                render()
            }
        }
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.text))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private companion object { const val PICK_IMAGES = 5 }
}
