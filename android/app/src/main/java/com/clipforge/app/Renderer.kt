package com.clipforge.app

import android.content.ContentValues
import android.content.Context
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
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
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

    /** Cuts [startSec, endSec], crops/scales, burns captions. Runs the encoder on the main thread as Media3 requires. */
    @OptIn(UnstableApi::class)
    suspend fun render(
        ctx: Context,
        src: File,
        out: File,
        startSec: Double,
        endSec: Double,
        vertical: Boolean,
        lines: List<CapLine>,
        style: CaptionStyle,
        uppercase: Boolean,
        highlight: Boolean,
        onProgress: (Int) -> Unit,
    ) {
        val (w, h) = withContext(Dispatchers.IO) { videoSize(src) }
        val (ow, oh) = if (vertical) 1080 to 1920 else {
            val oh = even(min(h, 1080))
            even(w * oh / h) to oh
        }
        val effects = mutableListOf<Effect>(
            Presentation.createForWidthAndHeight(ow, oh, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        )
        if (lines.isNotEmpty()) {
            effects += OverlayEffect(
                ImmutableList.of<TextureOverlay>(CaptionOverlay(lines, ow, oh, style, uppercase, highlight))
            )
        }
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(src))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((startSec * 1000).toLong())
                    .setEndPositionMs((endSec * 1000).toLong())
                    .build()
            ).build()
        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(emptyList(), effects))
            .build()
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
                transformer.start(edited, out.absolutePath)
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

    /** Copies the finished clip into Movies/ClipForge so it shows in the gallery and the YouTube app. */
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
        resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
