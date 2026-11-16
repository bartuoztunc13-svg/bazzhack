package com.clipforge.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Thin wrapper around yt-dlp running on the phone (youtubedl-android). */
object Yt {
    @Volatile private var ready = false
    @Volatile private var updated = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }

    /** YouTube changes often; keep the bundled yt-dlp fresh. Safe to call repeatedly. */
    fun updateOnce(ctx: Context): String {
        init(ctx)
        if (updated) return "already checked"
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        updated = true
        return status?.toString() ?: "unknown"
    }

    fun version(ctx: Context): String = runCatching { init(ctx); YoutubeDL.getInstance().version(ctx.applicationContext) ?: "?" }.getOrDefault("?")

    private fun run(req: YoutubeDLRequest, onProgress: ((Float, String) -> Unit)? = null): String {
        val cb: ((Float, Long, String) -> Unit)? =
            onProgress?.let { f -> { progress: Float, _: Long, line: String -> f(progress, line) } }
        // 4 explicit arguments picks one overload unambiguously
        return YoutubeDL.getInstance().execute(req, null, false, cb).out
    }

    fun info(ctx: Context, url: String): VideoInfo {
        init(ctx)
        val req = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
        }
        return parseInfo(JSONObject(run(req)))
    }

    enum class Range(val label: String, val sp: String) {
        TODAY("Today", "CAMSAggC"),      // sort: views · uploaded today
        WEEK("This week", "CAMSAggD"),   // sort: views · uploaded this week
        MONTH("This month", "CAMSAggE"), // sort: views · uploaded this month
    }

    /** Most-viewed recent videos about a place. */
    fun popularIn(ctx: Context, town: String, range: Range, limit: Int = 30): List<SearchHit> {
        init(ctx)
        val q = URLEncoder.encode(town.trim(), "UTF-8")
        val req = YoutubeDLRequest("https://www.youtube.com/results?search_query=$q&sp=${range.sp}").apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--playlist-end", limit.toString())
        }
        return parseSearch(JSONObject(run(req))).sortedByDescending { it.views }
    }

    /** Downloads the whole video once (H.264 so every phone can re-encode it). */
    fun downloadVideo(ctx: Context, info: VideoInfo, onProgress: (Int) -> Unit): File {
        init(ctx)
        val dir = File(ctx.cacheDir, "videos").apply { mkdirs() }
        dir.listFiles()?.firstOrNull { it.name.startsWith(info.id + ".") && it.extension == "mp4" }?.let { return it }
        // remove older downloads so the phone doesn't fill up
        dir.listFiles()?.forEach { it.deleteRecursively() }
        val req = YoutubeDLRequest(info.url).apply {
            addOption("-f", "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/b")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--no-part")
            addOption("-o", File(dir, "${info.id}.%(ext)s").absolutePath)
        }
        run(req) { p, _ -> if (p >= 0) onProgress(p.toInt()) }
        return dir.listFiles()?.firstOrNull { it.name.startsWith(info.id + ".") && it.extension == "mp4" }
            ?: error("Download finished but the video file is missing")
    }

    /** YouTube's own subtitles/auto-captions as words with timings. Empty if the video has none. */
    fun captions(ctx: Context, info: VideoInfo): List<Word> {
        val lang = info.captionLang ?: return emptyList()
        init(ctx)
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val req = YoutubeDLRequest(info.url).apply {
            addOption("--skip-download")
            addOption("--write-subs")
            addOption("--write-auto-subs")
            addOption("--sub-format", "json3")
            addOption("--sub-langs", lang)
            addOption("--no-playlist")
            addOption("-o", File(dir, info.id).absolutePath)
        }
        run(req)
        val f = dir.listFiles()?.firstOrNull { it.name.endsWith(".json3") } ?: return emptyList()
        return parseJson3(f.readText())
    }
}
