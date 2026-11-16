package com.clipforge.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

enum class Screen { HOME, CLIPS, RESULTS }
enum class OutputMode(val label: String) { SEPARATE("Separate clips"), RANKING("Ranking video (countdown)") }
enum class TownSort(val label: String) { VIEWS("Most viewed"), RELEVANCE("Best match") }

/** One editable clip row. Times are kept as text so the user can type freely. */
data class ClipRow(
    val start: String,
    val end: String,
    val title: String,
    val reason: String,
    val selected: Boolean,
    val score: Int = -1,
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
    var townSort by mutableStateOf(TownSort.VIEWS)
    private var rawHits = emptyList<SearchHit>()
    val hits = mutableStateListOf<SearchHit>()
    var searched by mutableStateOf(false)

    // clip picking
    var info by mutableStateOf<VideoInfo?>(null)
    val clips = mutableStateListOf<ClipRow>()
    var layout by mutableStateOf(Layout.VERTICAL)
    var captionsOn by mutableStateOf(true)
    var style by mutableStateOf(CaptionStyle.BOLD)
    var uppercase by mutableStateOf(true)
    var highlight by mutableStateOf(true)
    var mode by mutableStateOf(OutputMode.SEPARATE)
    var rankingTitle by mutableStateOf("")

    // results
    val outputs = mutableStateListOf<Output>()
    var working by mutableStateOf(false)
    private var job: Job? = null

    /** Finishes once yt-dlp is unpacked and (if online) updated, so nothing runs while it's being replaced. */
    private val ytReady = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Yt.init(ctx)
                withTimeoutOrNull(60_000) { runCatching { Yt.updateOnce(ctx) } }
            } catch (t: Throwable) {
                error = "Couldn't start the downloader: ${t.message}"
            } finally {
                ytReady.complete(Unit)
            }
        }
    }

    private suspend fun awaitYt() {
        if (!ytReady.isCompleted) {
            val old = busy
            busy = "Updating the downloader (first start only)…"
            ytReady.await()
            busy = old
        }
    }

    fun showError(t: Throwable) {
        error = (t.message ?: t.toString()).lineSequence().filter { it.isNotBlank() }
            .lastOrNull { it.contains("ERROR") } ?: (t.message ?: t.toString()).lineSequence().firstOrNull { it.isNotBlank() }
        error = error?.removePrefix("ERROR: ")?.take(400)
    }

    fun setSharedText(text: String) {
        Regex("""https?://\S+""").find(text)?.value?.let {
            url = it
            tab = 0
            if (!working) screen = Screen.HOME
        }
    }

    // ------------------------------------------------------------- town search

    fun searchTown() {
        if (town.isBlank()) { error = "Type a town or tap the location button"; return }
        if (busy != null) return
        viewModelScope.launch {
            try {
                awaitYt()
                busy = "Finding popular videos in ${town.trim()}…"
                rawHits = withContext(Dispatchers.IO) { Yt.popularIn(ctx, town, range) }
                applyTownSort()
                searched = true
            } catch (t: Throwable) { showError(t) } finally { busy = null }
        }
    }

    fun applyTownSort() {
        hits.clear()
        hits.addAll(if (townSort == TownSort.VIEWS) rawHits.sortedByDescending { it.views } else rawHits)
    }

    // ------------------------------------------------------------- load video

    fun loadVideo(link: String = url) {
        if (link.isBlank()) { error = "Paste a YouTube link first"; return }
        if (busy != null) return
        url = link
        viewModelScope.launch {
            try {
                awaitYt()
                busy = "Reading video…"
                val v = withContext(Dispatchers.IO) { Yt.info(ctx, link.trim()) }
                if (v.duration <= 0) error("This looks like a live stream or an empty video – pick a normal video.")
                info = v
                val len = (clipLen.toDoubleOrNull() ?: 45.0).coerceIn(5.0, 180.0)
                val n = (count.toIntOrNull() ?: 8).coerceIn(1, 20)
                clips.clear()
                clips.addAll(suggestClips(v, len, n).map {
                    ClipRow(fmtTime(it.start), fmtTime(it.end), it.title, it.reason, it.selected, it.score)
                })
                rankingTitle = "Top moments from ${v.title}".take(95)
                screen = Screen.CLIPS
            } catch (t: Throwable) { showError(t) } finally { busy = null }
        }
    }

    fun addCustomClip() {
        val v = info ?: return
        clips.add(0, ClipRow("0:00", fmtTime(minOf(30.0, v.duration)), "${v.title.take(70)} – Clip", "Custom", true))
    }

    fun updateClip(i: Int, row: ClipRow) { if (i in clips.indices) clips[i] = row }

    fun moveClip(i: Int, by: Int) {
        val j = i + by
        if (i !in clips.indices || j !in clips.indices) return
        val a = clips[i]
        clips[i] = clips[j]
        clips[j] = a
    }

    fun sortBest() {
        val sorted = clips.sortedWith(compareByDescending<ClipRow> { it.score }.thenBy { it.startSec ?: 0.0 })
        clips.clear(); clips.addAll(sorted)
    }

    fun sortTimeline() {
        val sorted = clips.sortedBy { it.startSec ?: 0.0 }
        clips.clear(); clips.addAll(sorted)
    }

    /** Rank of a clip = its position among the selected clips, top of the list = #1. */
    fun rankOf(i: Int): Int? = if (clips.getOrNull(i)?.selected == true) clips.take(i + 1).count { it.selected } else null

    // ------------------------------------------------------------- make clips

    fun makeClips() {
        if (working) { screen = Screen.RESULTS; return }
        val v = info ?: return
        val picked = clips.filter { it.selected }
        if (picked.isEmpty()) { error = "Select at least one clip"; return }
        picked.firstOrNull { !it.valid }?.let { error = "Check the times on “${it.title}” – end must be after start"; return }
        picked.firstOrNull { it.endSec!! > v.duration + 1 }?.let { error = "“${it.title}” ends after the video ends (${fmtTime(v.duration)})"; return }
        val ranking = mode == OutputMode.RANKING && picked.size >= 2
        if (mode == OutputMode.RANKING && picked.size < 2) { error = "A ranking needs at least 2 clips"; return }

        outputs.clear()
        if (ranking) outputs += Output(rankingTitle.ifBlank { "Top ${picked.size}" }, "Waiting…")
        else outputs.addAll(picked.map { Output(it.title, "Waiting…") })
        screen = Screen.RESULTS
        working = true
        val look = Look(layout, style, uppercase, highlight)
        job = viewModelScope.launch {
            try {
                awaitYt()
                setOut(0) { copy(status = "Downloading video…", progress = 0f) }
                val src = withContext(Dispatchers.IO) {
                    Yt.downloadVideo(ctx, v) { p -> viewModelScope.launch { setOut(0) { copy(status = "Downloading video $p%", progress = p / 100f) } } }
                }
                var words = emptyList<Word>()
                var capNote = ""
                if (captionsOn) {
                    setOut(0) { copy(status = "Getting captions…", progress = null) }
                    words = withContext(Dispatchers.IO) {
                        runCatching { Yt.captions(ctx, v) }.onFailure { capNote = " (couldn't get captions)" }.getOrDefault(emptyList())
                    }
                    if (words.isEmpty() && capNote.isEmpty()) capNote = " (this video has no captions on YouTube)"
                }
                val outDir = File(ctx.cacheDir, "out").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                fun segOf(row: ClipRow, rank: Int?) = Segment(
                    row.startSec!!, row.endSec!!,
                    if (captionsOn) groupWords(wordsForClip(words, row.startSec!!, row.endSec!!)) else emptyList(),
                    rank, if (rank != null) row.title.substringAfterLast(" – ").take(40) else null,
                )

                if (ranking) {
                    // countdown: #N first, #1 last
                    val segs = picked.mapIndexed { i, r -> segOf(r, i + 1) }.reversed()
                    renderOne(0, src, outDir, segs, look, outputs[0].title, capNote) {
                        rankingDescription(v, picked)
                    }
                } else {
                    picked.forEachIndexed { i, row ->
                        renderOne(i, src, outDir, listOf(segOf(row, null)), look, row.title, capNote) {
                            describe(v, row.startSec!!, words)
                        }
                    }
                }
            } catch (t: CancellationException) {
                outputs.indices.filter { !outputs[it].done }.forEach { outputs[it] = outputs[it].copy(status = "Stopped", progress = null) }
            } catch (t: Throwable) {
                showError(t)
                outputs.indices.filter { !outputs[it].done }.forEach {
                    outputs[it] = outputs[it].copy(status = "Failed: ${error ?: "unknown error"}", progress = null, failed = true)
                }
            } finally {
                working = false
            }
        }
    }

    private suspend fun renderOne(i: Int, src: File, outDir: File, segs: List<Segment>, look: Look, title: String, note: String, desc: () -> String) {
        try {
            setOut(i) { copy(status = "Making video…$note", progress = 0f) }
            val file = File(outDir, "clip${i + 1}.mp4")
            Renderer.render(ctx, src, file, segs, look) { p ->
                setOut(i) { copy(status = "Making video $p%$note", progress = p / 100f) }
            }
            val uri = withContext(Dispatchers.IO) { Renderer.saveToGallery(ctx, file, title).also { file.delete() } }
            setOut(i) { copy(status = "Saved to Movies/ClipForge$note", progress = null, done = true, uri = uri, description = desc()) }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            setOut(i) { copy(status = "Failed: ${t.message?.take(200)}", progress = null, failed = true) }
        }
    }

    fun stop() { job?.cancel() }

    private fun setOut(i: Int, f: Output.() -> Output) {
        if (i in outputs.indices) outputs[i] = outputs[i].f()
    }

    fun editOutput(i: Int, title: String? = null, description: String? = null) =
        setOut(i) { copy(title = title ?: this.title, description = description ?: this.description) }

    private fun tags() = if (layout != Layout.ORIGINAL) "\n\n#Shorts" else ""

    private fun describe(v: VideoInfo, start: Double, words: List<Word>): String {
        val snippet = words.filter { it.start >= start }.take(30).joinToString(" ") { it.text }
        return buildString {
            if (snippet.isNotBlank()) append(snippet).append("…\n\n")
            append("From “${v.title}” by ${v.channel}\n${v.url}&t=${start.toInt()}s")
            append(tags())
        }
    }

    private fun rankingDescription(v: VideoInfo, picked: List<ClipRow>) = buildString {
        picked.forEachIndexed { i, r -> append("#${i + 1} ${r.title.substringAfterLast(" – ")} (${r.start})\n") }
        append("\nFrom “${v.title}” by ${v.channel}\n${v.url}")
        append(tags())
    }
}
