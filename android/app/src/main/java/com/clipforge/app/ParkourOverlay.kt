package com.clipforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Block Runner": an original blocky character doing endless parkour across floating
 * platforms. Fills the bottom half of a split-screen Short. Everything is generated
 * from the timestamp, so it is the same every time and needs no assets.
 *
 * @param timeOffsetSec where in the run this segment starts (keeps the run continuous across ranking segments)
 */
@OptIn(UnstableApi::class)
class ParkourOverlay(
    private val w: Int,
    private val h: Int,
    private val timeOffsetSec: Double = 0.0,
    seed: Int = 7,
) : BitmapOverlay() {

    private val block = h / 12f                    // one cube
    private val speed = block * 5.2f               // world pixels per second
    private val runnerX = w * 0.3f                 // runner stays here on screen
    private val jumpLead = block * 0.6f            // take off this far before an edge

    private class Plat(val x0: Float, val x1: Float, val top: Float)

    private val plats = ArrayList<Plat>()
    private val rnd = Random(seed)

    // Two bitmaps used in turn, so Media3 always sees a changed bitmap and re-uploads it.
    private val bufs = arrayOf(
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
    )
    private var which = 0
    private var baseUs = -1L
    private val sky: Bitmap = makeSky()
    private val settings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, -1f) // bottom edge of the video
        .setOverlayFrameAnchor(0f, -1f)    // bottom edge of this layer
        .build()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // first platform is long so the run starts on solid ground
        plats += Plat(-w.toFloat(), w * 0.9f, h * 0.7f)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (baseUs < 0) baseUs = presentationTimeUs
        val t = (presentationTimeUs - baseUs) / 1e6 + timeOffsetSec
        val bmp = bufs[which]
        which = 1 - which
        draw(Canvas(bmp), t.toFloat())
        return bmp
    }

    // ------------------------------------------------------------------ world

    private fun ensure(x: Float) {
        while (plats.last().x1 < x) {
            val last = plats.last()
            val gap = block * (1.4f + rnd.nextFloat() * 1.6f)
            val len = block * (2.5f + rnd.nextInt(5))
            val dy = block * (rnd.nextInt(5) - 2) * 0.5f
            val top = (last.top + dy).coerceIn(h * 0.55f, h * 0.8f)
            plats += Plat(last.x1 + gap, last.x1 + gap + len, top)
        }
    }

    private fun platAt(x: Float) = plats.firstOrNull { x >= it.x0 && x <= it.x1 }

    /** Runner feet height (screen y) and whether it is in the air, for world position x. */
    private fun runner(x: Float): Pair<Float, Boolean> {
        ensure(x + w)
        for (i in 0 until plats.size - 1) {
            val a = plats[i]
            val b = plats[i + 1]
            val takeoff = a.x1 - jumpLead
            val land = b.x0 + jumpLead
            if (x in takeoff..land) {
                val u = (x - takeoff) / (land - takeoff)
                val peak = min(a.top, b.top) - block * 1.6f
                // smooth arc from a.top through peak to b.top
                val base = a.top + (b.top - a.top) * u
                val lift = (base - peak) * 4 * u * (1 - u)
                return (base - lift) to true
            }
            if (x >= a.x0 && x < takeoff) return a.top to false
        }
        return (platAt(x)?.top ?: (h * 0.7f)) to false
    }

    // ------------------------------------------------------------------ drawing

    private fun makeSky(): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val g = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.rgb(20, 18, 48), Color.rgb(70, 40, 110), Color.rgb(240, 110, 90)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        val r = Random(3)
        val star = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        repeat(70) {
            val s = 2f + r.nextFloat() * 3f
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h * 0.5f
            c.drawRect(x, y, x + s, y + s, star)
        }
        return b
    }

    private fun draw(c: Canvas, t: Float) {
        val camX = t * speed // world x at the left edge of the screen
        c.drawBitmap(sky, 0f, 0f, null)
        drawCity(c, camX * 0.25f)
        ensure(camX + w * 2)
        for (pl in plats) {
            if (pl.x1 < camX - block || pl.x0 > camX + w + block) continue
            drawPlatform(c, pl, camX)
        }
        val wx = camX + runnerX
        val (feet, air) = runner(wx)
        drawRunner(c, runnerX, feet, t, air)
        // drop old platforms
        while (plats.size > 3 && plats[1].x1 < camX - w) plats.removeAt(0)
    }

    /** Far-away block towers moving slowly (parallax). */
    private fun drawCity(c: Canvas, off: Float) {
        p.shader = null
        p.color = Color.rgb(45, 30, 80)
        val step = block * 1.5f
        var i = ((off / step).toInt() - 1)
        while (true) {
            val x = i * step - off
            if (x > w) break
            val hh = (abs(sin(i * 12.9898) * 43758.5453) % 1.0).toFloat()
            val top = h * (0.35f + hh * 0.25f)
            c.drawRect(x, top, x + step + 1, h.toFloat(), p)
            i++
        }
    }

    private fun drawPlatform(c: Canvas, pl: Plat, camX: Float) {
        val x0 = pl.x0 - camX
        val x1 = pl.x1 - camX
        var y = pl.top
        var row = 0
        while (y < h) {
            var x = x0
            while (x < x1 - 1) {
                val bw = min(block, x1 - x)
                if (row == 0) {
                    p.color = Color.rgb(40, 220, 190)       // neon top
                    c.drawRect(x, y, x + bw, y + block * 0.28f, p)
                    p.color = Color.rgb(78, 84, 110)
                    c.drawRect(x, y + block * 0.28f, x + bw, y + block, p)
                } else {
                    p.color = if ((row + (x / block).toInt()) % 2 == 0) Color.rgb(62, 66, 90) else Color.rgb(56, 60, 82)
                    c.drawRect(x, y, x + bw, y + block, p)
                }
                p.color = Color.argb(70, 0, 0, 0)          // block outline
                c.drawRect(x, y + block - 3, x + bw, y + block, p)
                c.drawRect(x + bw - 3, y, x + bw, y + block, p)
                x += block
            }
            y += block
            row++
        }
    }

    /** Original blocky runner: dark helmet with glowing visor, orange jacket, white sneakers. */
    private fun drawRunner(c: Canvas, x: Float, feet: Float, t: Float, air: Boolean) {
        val u = block / 8f // one "pixel" of the character
        val swing = if (air) 0f else sin(t * 2 * PI.toFloat() * 2.6f) * 38f
        val legLen = 6 * u
        val torsoH = 7 * u
        val hip = feet - legLen
        val shoulder = hip - torsoH
        val headS = 6 * u
        val bob = if (air) 0f else abs(sin(t * 2 * PI.toFloat() * 2.6f)) * u * 0.6f

        c.save()
        c.translate(0f, -bob)
        // shadow-side limbs first
        limb(c, x, hip, 2.4f * u, legLen, if (air) 35f else -swing, Color.rgb(30, 34, 60), Color.WHITE)
        limb(c, x, shoulder + u, 2f * u, 5.5f * u, if (air) -140f else swing, Color.rgb(200, 90, 20), Color.rgb(230, 180, 140))
        // torso (jacket) with zipper stripe
        p.color = Color.rgb(255, 120, 30)
        c.drawRect(x - 2.2f * u, shoulder, x + 2.2f * u, hip, p)
        p.color = Color.rgb(255, 200, 60)
        c.drawRect(x + 1.2f * u, shoulder, x + 1.8f * u, hip, p)
        // head: helmet + visor
        p.color = Color.rgb(35, 38, 52)
        c.drawRect(x - headS / 2, shoulder - headS, x + headS / 2, shoulder, p)
        p.color = Color.rgb(60, 240, 255)
        c.drawRect(x - headS * 0.05f, shoulder - headS * 0.7f, x + headS / 2 + u * 0.4f, shoulder - headS * 0.42f, p)
        p.color = Color.argb(160, 255, 255, 255)
        c.drawRect(x + headS * 0.1f, shoulder - headS * 0.68f, x + headS * 0.25f, shoulder - headS * 0.6f, p)
        // front limbs
        limb(c, x, hip, 2.4f * u, legLen, if (air) -20f else swing, Color.rgb(40, 46, 80), Color.WHITE)
        limb(c, x, shoulder + u, 2f * u, 5.5f * u, if (air) -150f else -swing, Color.rgb(255, 140, 50), Color.rgb(240, 190, 150))
        c.restore()
    }

    /** A rectangle limb rotated around its top centre, with a coloured end (shoe/hand). */
    private fun limb(c: Canvas, px: Float, py: Float, thick: Float, len: Float, deg: Float, col: Int, end: Int) {
        c.save()
        c.rotate(deg, px, py)
        p.color = col
        c.drawRect(px - thick / 2, py, px + thick / 2, py + len, p)
        p.color = end
        c.drawRect(px - thick / 2, py + len - thick * 0.8f, px + thick / 2 + max(0f, thick * 0.4f), py + len, p)
        c.restore()
    }
}
