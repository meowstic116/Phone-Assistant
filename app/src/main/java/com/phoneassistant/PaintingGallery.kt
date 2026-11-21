package com.phoneassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Images are copied into private storage, downsampled, and re-encoded without source metadata. */
internal object PaintingGallery {
    const val MAX_CUSTOM = 24
    private const val MAX_SOURCE_BYTES = 24L * 1024 * 1024
    private const val MAX_EDGE = 640
    private const val CUSTOM = "custom_"
    private val customId = Regex("custom_[a-f0-9-]{36}")
    val worker = Executors.newSingleThreadExecutor()

    data class Picture(val id: String, val label: String, val style: Painting.Style? = null) {
        val title: String get() = style?.title ?: "A picture from your gallery"
        val blurb: String get() = style?.blurb ?: "a private picture chosen from their on-device gallery"
    }

    fun pictures(ctx: Context): List<Picture> {
        val builtIn = Painting.Style.values().map { Picture("default_${it.name}", it.title, it) }
        val custom = prefs(ctx).all.mapNotNull { (key, label) ->
            if (customId.matches(key) && label is String && file(ctx, key).isFile) Picture(key, label) else null
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.id }))
        return builtIn + custom
    }

    fun enabled(ctx: Context, picture: Picture): Boolean = prefs(ctx).getBoolean("enabled_${picture.id}", true)

    fun setEnabled(ctx: Context, picture: Picture, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("enabled_${picture.id}", enabled).apply()
    }

    fun remove(ctx: Context, picture: Picture): Boolean {
        if (picture.style != null || !customId.matches(picture.id)) return false
        val stored = file(ctx, picture.id)
        if (stored.exists() && !stored.delete()) return false
        prefs(ctx).edit().remove(picture.id).remove("enabled_${picture.id}").apply()
        return true
    }

    fun bitmap(ctx: Context, picture: Picture, thumbnail: Boolean = false): Bitmap? = runCatching {
        val drawable = picture.style?.drawable
        val options = BitmapFactory.Options().apply { inSampleSize = if (thumbnail) 4 else 1 }
        when {
            drawable != null -> BitmapFactory.decodeResource(ctx.resources, drawable, options)
            picture.style == null && customId.matches(picture.id) -> BitmapFactory.decodeFile(file(ctx, picture.id).path, options)
            else -> null
        }
    }.getOrNull()

    enum class ImportResult { SAVED, FULL, INVALID }

    /** Called from the gallery worker. No persistent URI permission or network access needed. */
    fun import(ctx: Context, uri: Uri): ImportResult {
        if (pictures(ctx).count { it.style == null } >= MAX_CUSTOM) return ImportResult.FULL
        if (uri.scheme != "content") return ImportResult.INVALID
        val dir = directory(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) return ImportResult.INVALID
        val id = CUSTOM + UUID.randomUUID()
        val output = file(ctx, id)
        var temp: File? = null
        var saved = false
        return try {
            val source = File.createTempFile("pip-picture-", ".tmp", ctx.cacheDir).also { temp = it }
            val reader = ctx.contentResolver.openInputStream(uri) ?: return ImportResult.INVALID
            reader.use { input ->
                source.outputStream().buffered().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) throw IOException("Image import made no progress")
                        total += n
                        if (total > MAX_SOURCE_BYTES) throw IOException("Image exceeds input limit")
                        out.write(buffer, 0, n)
                    }
                }
            }
            val image = decode(source) ?: return ImportResult.INVALID
            try {
                @Suppress("DEPRECATION") // Same WebP encoder works on API 26 through current Android.
                val wrote = output.outputStream().use { image.compress(Bitmap.CompressFormat.WEBP, 88, it) }
                if (!wrote) return ImportResult.INVALID
            } finally {
                image.recycle()
            }
            val label = runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()?.filterNot { it.isISOControl() }?.trim()?.take(90)
                ?.takeIf { it.isNotBlank() } ?: "Imported picture"
            saved = prefs(ctx).edit().putString(id, label).commit()
            if (saved) ImportResult.SAVED else ImportResult.INVALID
        } catch (_: Exception) {
            ImportResult.INVALID
        } finally {
            temp?.delete()
            if (!saved) output.delete()
        }
    }

    private fun decode(source: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0 || w.toLong() * h > 100_000_000L) throw IOException("Invalid image size")
                val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(w, h))
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong() * options.outHeight > 100_000_000L) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > MAX_EDGE) sample *= 2
        return BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun directory(ctx: Context) = File(ctx.noBackupFilesDir, "pip_paintings")
    private fun file(ctx: Context, id: String) = File(directory(ctx), "$id.webp")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("painting_gallery", Context.MODE_PRIVATE)
}
