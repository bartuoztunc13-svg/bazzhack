package com.clipforge.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { HOME, CLIPS, RESULTS }

/** One editable clip row. Times are kept as text so the user can type freely. */
data class ClipRow(
    val start: String,
    val end: String,
    val title: String,
    val reason: String,
    val selected: Boolean,
) {
    val startSec get() = parseTime(start)
    val endSec get() = parseTime(end)
    val valid get() = startSec != null && endSec != null && endSec!! > startSec!!
}

data class Output(
    val title: String,
    val status: String,
    val progress: Float? = null,
    val done: Boolean = false,
    val failed: Boolean = false,
    val uri: Uri? = null,
    val description: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var screen by mutableStateOf(Screen.HOME)
    var tab by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)

    // paste link
    var url by mutableStateOf("")
    var clipLen by mutableStateOf("45")
    var count by mutableStateOf("8")

    // popular in town
    var town by mutableStateOf("")
    var range by mutableStateOf(Yt.Range.WEEK)
    val hits = mutableStateListOf<SearchHit>()
    var searched by mutableStateOf(false)

    // clip picking
    var info by mutableStateOf<VideoInfo?>(null)
    val clips = mutableStateListOf<ClipRow>()
    var vertical by mutableStateOf(true)
    var captionsOn by mutableStateOf(true)
    var style by mutableStateOf(CaptionStyle.BOLD)
    var uppercase by mutableStateOf(true)
    var highlight by mutableStateOf(true)

    // results
    val outputs = mutableStateListOf<Output>()
    var working by mutableStateOf(false)
    private var job: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Yt.init(ctx) }.onFailure { error = "Couldn't start the downloader: ${it.message}" }
            runCatching { Yt.updateOnce(ctx) } // keeps up with YouTube changes; ignore if offline
        }
    }

    fun showError(t: Throwable) {
        error = (t.message ?: t.toString()).lineSequence().filter { it.isNotBlank() }.lastOrNull()?.take(400)
    }

    fun setSharedText(text: String) {
        Regex("""https?://\S+""").find(text)?.value?.let {
            url = it
            tab = 0
            screen = Screen.HOME
        }
    }

    // ------------------------------------------------------------- town search

    fun searchTown() {
        if (town.isBlank()) { error = "Type a town or tap the location button"; return }
        viewModelScope.launch {
            busy = "Finding popular videos in ${town.trim()}…"
            try {
                val list = withContext(Dispatchers.IO) { Yt.popularIn(ctx, town, range) }
                hits.clear(); hits.addAll(list); searched = true
            } catch (t: Throwable) { showError(t) } finally { busy = null }
        }
    }

    // ------------------------------------------------------------- load video

    fun loadVideo(link: String = url) {
        if (link.isBlank()) { error = "Paste a YouTube link first"; return }
        url = link
        viewModelScope.launch {
            busy = "Reading video…"
            try {
                val v = withContext(Dispatchers.IO) { Yt.info(ctx, link.trim()) }
                info = v
                val len = clipLen.toDoubleOrNull() ?: 45.0
                val n = count.toIntOrNull() ?: 8
                clips.clear()
                clips.addAll(suggestClips(v, len, n).map {
                    ClipRow(fmtTime(it.start), fmtTime(it.end), it.title, it.reason, it.selected)
                })
                screen = Screen.CLIPS
            } catch (t: Throwable) { showError(t) } finally { busy = null }
        }
    }

    fun addCustomClip() {
        val v = info ?: return
        clips.add(0, ClipRow("0:00", fmtTime(minOf(30.0, v.duration)), "${v.title.take(70)} – Clip", "Custom", true))
    }

    fun updateClip(i: Int, row: ClipRow) { clips[i] = row }

    // ------------------------------------------------------------- make clips

    fun makeClips() {
        val v = info ?: return
        val picked = clips.filter { it.selected }
        if (picked.isEmpty()) { error = "Select at least one clip"; return }
        picked.firstOrNull { !it.valid }?.let { error = "Check the times on “${it.title}” – end must be after start"; return }

        outputs.clear()
        outputs.addAll(picked.map { Output(it.title, "Waiting…") })
        screen = Screen.RESULTS
        working = true
        job = viewModelScope.launch {
            try {
                outputs.indices.forEach { outputs[it] = outputs[it].copy(status = "Waiting for download…") }
                setOut(0) { copy(status = "Downloading video…", progress = 0f) }
                val src = withContext(Dispatchers.IO) {
                    Yt.downloadVideo(ctx, v) { p -> viewModelScope.launch { setOut(0) { copy(status = "Downloading video $p%", progress = p / 100f) } } }
                }
                var words = emptyList<Word>()
                if (captionsOn) {
                    setOut(0) { copy(status = "Getting captions…", progress = null) }
                    words = withContext(Dispatchers.IO) { runCatching { Yt.captions(ctx, v) }.getOrDefault(emptyList()) }
                }
                val outDir = File(ctx.cacheDir, "out").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

                picked.forEachIndexed { i, row ->
                    val s = row.startSec!!; val e = row.endSec!!
                    try {
                        val lines = if (captionsOn) groupWords(wordsForClip(words, s, e)) else emptyList()
                        val note = if (captionsOn && lines.isEmpty()) " (this video has no captions on YouTube)" else ""
                        setOut(i) { copy(status = "Making clip…$note", progress = 0f) }
                        val file = File(outDir, "clip${i + 1}.mp4")
                        Renderer.render(ctx, src, file, s, e, vertical, lines, style, uppercase, highlight) { p ->
                            setOut(i) { copy(status = "Making clip $p%$note", progress = p / 100f) }
                        }
                        val uri = withContext(Dispatchers.IO) { Renderer.saveToGallery(ctx, file, row.title) }
                        file.delete()
                        setOut(i) {
                            copy(status = "Saved to Movies/ClipForge$note", progress = null, done = true, uri = uri,
                                description = describe(v, s, words))
                        }
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        setOut(i) { copy(status = "Failed: ${t.message?.take(200)}", progress = null, failed = true) }
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                outputs.indices.filter { !outputs[it].done }.forEach { outputs[it] = outputs[it].copy(status = "Stopped", progress = null) }
            } catch (t: Throwable) {
                showError(t)
                outputs.indices.filter { !outputs[it].done }.forEach { outputs[it] = outputs[it].copy(status = "Failed", progress = null, failed = true) }
            } finally {
                working = false
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun setOut(i: Int, f: Output.() -> Output) {
        if (i in outputs.indices) outputs[i] = outputs[i].f()
    }

    fun editOutput(i: Int, title: String? = null, description: String? = null) =
        setOut(i) { copy(title = title ?: this.title, description = description ?: this.description) }

    private fun describe(v: VideoInfo, start: Double, words: List<Word>): String {
        val snippet = words.filter { it.start >= start }.take(30).joinToString(" ") { it.text }
        return buildString {
            if (snippet.isNotBlank()) append(snippet).append("…\n\n")
            append("From “${v.title}” by ${v.channel}\n${v.url}&t=${start.toInt()}s")
            if (vertical) append("\n\n#Shorts")
        }
    }
}
