package com.clipforge.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

enum class Layout(val label: String) {
    VERTICAL("Vertical 9:16"),
    SPLIT("Split screen + Block Runner"),
    ORIGINAL("Original shape"),
}

/** One piece of video to cut out of the source, with its captions and optional rank badge. */
data class Segment(
    val startSec: Double,
    val endSec: Double,
    val lines: List<CapLine>,
    val rank: Int? = null,
    val label: String? = null,
)

data class Look(
    val layout: Layout,
    val style: CaptionStyle,
    val uppercase: Boolean,
    val highlight: Boolean,
)

@OptIn(UnstableApi::class)
object Renderer {

    private fun videoSize(file: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if (rot == 90 || rot == 270) h to w else w to h
        } finally {
            r.release()
        }
    }

    private fun even(x: Int) = x - x % 2

    private fun outputSize(layout: Layout, w: Int, h: Int): Pair<Int, Int> = when (layout) {
        Layout.VERTICAL, Layout.SPLIT -> 1080 to 1920
        Layout.ORIGINAL -> {
            val oh = even(min(h, 1080))
            even(w * oh / h) to oh
        }
    }

    private fun buildItem(src: File, seg: Segment, look: Look, ow: Int, oh: Int, runnerOffset: Double): EditedMediaItem {
        val effects = mutableListOf<Effect>()
        val overlays = mutableListOf<TextureOverlay>()
        when (look.layout) {
            Layout.SPLIT -> {
                // video fills the top half; Block Runner fills the bottom half
                effects += Presentation.createForWidthAndHeight(ow, oh / 2, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
                effects += Presentation.createForWidthAndHeight(ow, oh, Presentation.LAYOUT_SCALE_TO_FIT)
                effects += MatrixTransformation { Matrix().apply { postTranslate(0f, 0.5f) } }
                overlays += ParkourOverlay(ow, oh / 2, runnerOffset)
            }
            else -> effects += Presentation.createForWidthAndHeight(ow, oh, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }
        if (seg.lines.isNotEmpty()) {
            val center = if (look.layout == Layout.SPLIT) 0.5f else null // split: captions on the seam
            overlays += CaptionOverlay(seg.lines, ow, oh, look.style, look.uppercase, look.highlight, center)
        }
        if (seg.rank != null) overlays += rankBadge(seg.rank, seg.label, ow, oh)
        if (overlays.isNotEmpty()) effects += OverlayEffect(ImmutableList.copyOf(overlays))

        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(src))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((seg.startSec * 1000).toLong())
                    .setEndPositionMs((seg.endSec * 1000).toLong())
                    .build()
            ).build()
        return EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).build()
    }

    /** Big "#3" badge (plus a short label) in the top-left corner. */
    private fun rankBadge(rank: Int, label: String?, ow: Int, oh: Int): BitmapOverlay {
        val size = min(ow, oh) * 0.2f
        val pad = size * 0.18f
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.62f
            color = Color.WHITE
        }
        val sub = Paint(num).apply { textSize = size * 0.2f }
        val text = "#$rank"
        val labelText = label?.take(40)?.trim().orEmpty()
        val textW = maxOf(num.measureText(text), if (labelText.isEmpty()) 0f else sub.measureText(labelText))
        val boxW = min(textW + pad * 2, ow * 0.9f)
        val boxH = size * if (labelText.isEmpty()) 0.85f else 1.12f
        val margin = pad * 1.2f // gap from the video's corner
        val bmp = Bitmap.createBitmap((margin + boxW).toInt() + 1, (margin + boxH).toInt() + 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 59, 59) }
        c.drawRoundRect(RectF(margin, margin, margin + boxW, margin + boxH), pad, pad, box)
        c.drawText(text, margin + pad, margin + size * 0.68f, num)
        if (labelText.isNotEmpty()) {
            sub.color = Color.argb(235, 255, 255, 255)
            c.drawText(labelText, margin + pad, margin + size * 0.98f, sub)
        }
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(-1f, 1f)
            .setOverlayFrameAnchor(-1f, 1f)
            .build()
        return BitmapOverlay.createStaticBitmapOverlay(bmp, settings)
    }

    /**
     * Renders the segments one after another into a single file.
     * One segment = a normal clip. Several = a ranking countdown.
     */
    suspend fun render(ctx: Context, src: File, out: File, segments: List<Segment>, look: Look, onProgress: (Int) -> Unit) {
        require(segments.isNotEmpty())
        val (w, h) = withContext(Dispatchers.IO) { videoSize(src) }
        val (ow, oh) = outputSize(look.layout, w, h)
        var offset = 0.0
        val items = segments.map { s ->
            buildItem(src, s, look, ow, oh, offset).also { offset += s.endSec - s.startSec }
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        out.delete()

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                lateinit var poll: Runnable
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                val holder = ProgressHolder()
                poll = Runnable {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    handler.postDelayed(poll, 400)
                }
                transformer.start(composition, out.absolutePath)
                handler.post(poll)
                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poll)
                        transformer.cancel()
                    }
                }
            }
        }
    }

    /** Copies the finished video into Movies/ClipForge so it shows in the gallery and the YouTube app. */
    fun saveToGallery(ctx: Context, file: File, name: String): Uri {
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "").trim().take(80).ifBlank { "clip" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$safe.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipForge")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't save to the gallery")
        try {
            resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null) // don't leave a broken, hidden file behind
            throw t
        }
        return uri
    }
}
