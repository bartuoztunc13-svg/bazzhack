package com.clipforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import kotlin.math.max
import kotlin.math.min

enum class CaptionStyle(val label: String) {
    BOLD("Bold white"), YELLOW("Yellow"), BOXED("Boxed"), MINIMAL("Minimal")
}

/**
 * Draws one caption line (with the spoken word highlighted) on a strip that sits
 * in the lower part of the video. A new bitmap is made only when the text changes.
 */
@OptIn(UnstableApi::class)
class CaptionOverlay(
    private val lines: List<CapLine>,
    private val frameW: Int,
    private val frameH: Int,
    private val style: CaptionStyle,
    private val uppercase: Boolean,
    private val highlight: Boolean,
) : BitmapOverlay() {

    private val vertical = frameH > frameW
    // Full-frame transparent layer: default overlay placement then lines up 1:1 with the video.
    private val centerY = frameH * if (vertical) 0.72f else 0.88f
    private val empty: Bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
    private var baseUs = -1L
    private var lastKey = -1L
    private var lastBitmap: Bitmap = empty

    // Show a line until the next one starts when the gap is short, so captions don't flicker.
    private val shown: List<Pair<Double, Double>> = lines.mapIndexed { i, l ->
        val next = lines.getOrNull(i + 1)?.start
        l.start to if (next != null && next - l.end < 0.4) next else l.end
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        // Frames may be timed from 0 or from the clip start depending on the device; use the first one as 0.
        if (baseUs < 0) baseUs = presentationTimeUs
        val t = (presentationTimeUs - baseUs) / 1_000_000.0
        val li = shown.indexOfFirst { t >= it.first && t < it.second }
        if (li < 0) return remember(-1L) { empty }
        val line = lines[li]
        val wi = if (highlight) max(0, line.words.indexOfLast { it.start <= t }) else -1
        return remember(li * 1000L + wi + 1) { draw(line, wi) }
    }

    private inline fun remember(key: Long, make: () -> Bitmap): Bitmap {
        if (key != lastKey) {
            lastKey = key
            lastBitmap = make()
        }
        return lastBitmap
    }

    private fun draw(line: CapLine, hi: Int): Bitmap {
        val words = line.words.map { if (uppercase) it.text.uppercase() else it.text }
        val (fill, accent) = when (style) {
            CaptionStyle.YELLOW -> Color.rgb(255, 229, 0) to Color.WHITE
            else -> Color.WHITE to Color.rgb(255, 229, 0)
        }
        val base = min(frameW, frameH) * when (style) {
            CaptionStyle.MINIMAL -> 0.06f
            else -> if (vertical) 0.085f else 0.075f
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = base
        }
        val space = paint.measureText(" ")
        var total = words.sumOf { paint.measureText(it).toDouble() }.toFloat() + space * (words.size - 1)
        val maxW = frameW * 0.9f
        if (total > maxW) {
            paint.textSize = base * maxW / total
            total = maxW
        }
        val space2 = paint.measureText(" ")
        val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fm = paint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        var x = (frameW - total) / 2f

        if (style == CaptionStyle.BOXED) {
            val pad = paint.textSize * 0.35f
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
            c.drawRoundRect(RectF(x - pad, baseline + fm.ascent - pad / 2, x + total + pad, baseline + fm.descent + pad / 2),
                pad, pad, box)
        }
        if (style == CaptionStyle.MINIMAL) paint.setShadowLayer(paint.textSize * 0.15f, 0f, 2f, Color.argb(200, 0, 0, 0))

        words.forEachIndexed { i, w ->
            if (style == CaptionStyle.BOLD || style == CaptionStyle.YELLOW) {
                val stroke = Paint(paint).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = paint.textSize * 0.14f
                    strokeJoin = Paint.Join.ROUND
                    color = Color.BLACK
                }
                c.drawText(w, x, baseline, stroke)
            }
            paint.color = if (i == hi) accent else fill
            paint.style = Paint.Style.FILL
            c.drawText(w, x, baseline, paint)
            x += paint.measureText(w) + space2
        }
        return bmp
    }
}
