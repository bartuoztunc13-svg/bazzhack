package com.clipforge.app

import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class Heat(val start: Double, val end: Double, val value: Double)
data class Chapter(val start: Double, val end: Double, val title: String)

data class VideoInfo(
    val id: String,
    val title: String,
    val duration: Double,
    val channel: String,
    val url: String,
    val heatmap: List<Heat>,
    val chapters: List<Chapter>,
    val captionLang: String?,
)

data class ClipSpec(
    val start: Double,
    val end: Double,
    val reason: String,
    val title: String,
    val selected: Boolean = true,
)

data class SearchHit(
    val id: String,
    val title: String,
    val channel: String,
    val views: Long,
    val duration: Double,
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val thumb get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

data class Word(val start: Double, val end: Double, val text: String)
data class CapLine(val start: Double, val end: Double, val words: List<Word>)

// ------------------------------------------------------------------ parsing

fun parseInfo(j: JSONObject): VideoInfo {
    val heat = j.optJSONArray("heatmap")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Heat(it.optDouble("start_time"), it.optDouble("end_time"), it.optDouble("value"))
        }
    } ?: emptyList()
    val chapters = j.optJSONArray("chapters")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Chapter(it.optDouble("start_time"), it.optDouble("end_time"), it.optString("title"))
        }
    } ?: emptyList()
    return VideoInfo(
        id = j.optString("id"),
        title = j.optString("title", "Untitled"),
        duration = j.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
        channel = j.optString("channel").ifBlank { j.optString("uploader") },
        url = j.optString("webpage_url").ifBlank { "https://www.youtube.com/watch?v=${j.optString("id")}" },
        heatmap = heat,
        chapters = chapters,
        captionLang = pickCaptionLang(j),
    )
}

/** Prefer real subtitles in the video's language, then YouTube's auto captions. */
fun pickCaptionLang(j: JSONObject): String? {
    val lang = j.optString("language").ifBlank { null }
    val subs = j.optJSONObject("subtitles")?.keys()?.asSequence()?.filter { it != "live_chat" }?.toList() ?: emptyList()
    val auto = j.optJSONObject("automatic_captions")?.keys()?.asSequence()?.toList() ?: emptyList()
    if (lang != null) subs.firstOrNull { it == lang || it.startsWith("$lang-") }?.let { return it }
    auto.firstOrNull { it.endsWith("-orig") }?.let { return it }
    if (lang != null) auto.firstOrNull { it == lang }?.let { return it }
    subs.firstOrNull()?.let { return it }
    return auto.firstOrNull { it == "en" }
}

fun parseSearch(j: JSONObject): List<SearchHit> {
    val a = j.optJSONArray("entries") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }
        .filter { it.optString("id").length == 11 }
        .map {
            SearchHit(
                id = it.optString("id"),
                title = it.optString("title"),
                channel = it.optString("channel").ifBlank { it.optString("uploader") },
                views = it.optLong("view_count", 0),
                duration = it.optDouble("duration", 0.0).let { d -> if (d.isNaN()) 0.0 else d },
            )
        }
}

// ------------------------------------------------------------ clip suggestion

fun suggestClips(info: VideoInfo, clipLen: Double = 45.0, count: Int = 8): List<ClipSpec> {
    val dur = info.duration
    if (dur <= 0) return emptyList()
    val len = clipLen.coerceIn(5.0, dur)
    fun window(center: Double): Pair<Double, Double> {
        var s = max(0.0, center - len / 2)
        val e = min(dur, s + len)
        s = max(0.0, e - len)
        return round1(s) to round1(e)
    }
    val out = mutableListOf<Triple<Double, Double, String>>()
    fun overlaps(s: Double, e: Double) = out.any { s < it.second && e > it.first }

    for (h in info.heatmap.sortedByDescending { it.value }) {
        if (out.size >= count) break
        val c = (h.start + h.end) / 2
        if (c < 3) continue
        val (s, e) = window(c)
        if (overlaps(s, e)) continue
        out += Triple(s, e, "Most replayed (${(h.value * 100).toInt()}%)")
    }
    for (ch in info.chapters) {
        if (out.size >= count) break
        val s = ch.start
        val e = min(ch.end, s + len)
        if (e - s < 5 || overlaps(s, e)) continue
        out += Triple(round1(s), round1(e), "Chapter: ${ch.title}")
    }
    if (out.size < count) {
        val slots = max(1, (dur / len).toInt())
        val step = dur / slots
        for (i in 0 until slots) {
            if (out.size >= count) break
            val (s, e) = window(step * i + step / 2)
            if (overlaps(s, e)) continue
            out += Triple(s, e, "Evenly spaced")
        }
    }
    return out.sortedBy { it.first }.mapIndexed { i, (s, e, r) ->
        ClipSpec(s, e, r, "${info.title.take(70)} – Part ${i + 1}", selected = i < 3)
    }
}

private fun round1(x: Double) = Math.round(x * 10) / 10.0

// ------------------------------------------------------------------ captions

private val junk = Regex("""^\[.*]$""")

/** Reads YouTube's json3 caption format. Auto captions carry per-word timing. */
fun parseJson3(text: String): List<Word> {
    val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
    data class Piece(val start: Double, val text: String, val evEnd: Double)
    val pieces = mutableListOf<Piece>()
    for (i in 0 until events.length()) {
        val ev = events.getJSONObject(i)
        val segs = ev.optJSONArray("segs") ?: continue
        val t0 = ev.optLong("tStartMs") / 1000.0
        val evEnd = t0 + ev.optLong("dDurationMs") / 1000.0
        val raw = (0 until segs.length()).map { segs.getJSONObject(it) }
            .map { (t0 + it.optLong("tOffsetMs", 0) / 1000.0) to it.optString("utf8").replace("\n", " ").trim() }
            .filter { it.second.isNotBlank() }
        raw.forEachIndexed { k, (start, txt) ->
            val words = txt.split(Regex("\\s+")).filter { it.isNotBlank() && !junk.matches(it) }
            if (words.isEmpty()) return@forEachIndexed
            val segEnd = if (k + 1 < raw.size) raw[k + 1].first else evEnd
            val each = max(0.05, (segEnd - start) / words.size)
            words.forEachIndexed { w, word -> pieces += Piece(start + w * each, word, evEnd) }
        }
    }
    pieces.sortBy { it.start }
    // drop exact duplicates (manual subtitles sometimes repeat lines)
    val dedup = pieces.filterIndexed { i, p -> i == 0 || !(p.text == pieces[i - 1].text && p.start - pieces[i - 1].start < 0.05) }
    return dedup.mapIndexed { i, p ->
        val next = dedup.getOrNull(i + 1)?.start ?: p.evEnd
        val end = minOf(next, p.evEnd.takeIf { it > p.start } ?: next, p.start + 1.5)
        Word(p.start, max(end, p.start + 0.1), p.text)
    }
}

/** Words inside [start, end], shifted so the clip starts at 0. */
fun wordsForClip(all: List<Word>, start: Double, end: Double) =
    all.filter { it.start >= start - 0.05 && it.start < end }
        .map { Word(it.start - start, min(it.end, end) - start, it.text) }

fun groupWords(words: List<Word>, maxWords: Int = 3, maxGap: Double = 0.6, maxDur: Double = 1.6): List<CapLine> {
    val lines = mutableListOf<CapLine>()
    var cur = mutableListOf<Word>()
    fun flush() { if (cur.isNotEmpty()) { lines += CapLine(cur.first().start, cur.last().end, cur); cur = mutableListOf() } }
    for (w in words) {
        if (cur.isNotEmpty() && (cur.size >= maxWords || w.start - cur.last().end > maxGap || w.end - cur.first().start > maxDur)) flush()
        cur += w
        if (w.text.last() in ".!?") flush()
    }
    flush()
    return lines
}

fun fmtTime(sec: Double): String {
    val s = max(0.0, sec)
    val h = (s / 3600).toInt()
    val m = ((s % 3600) / 60).toInt()
    val r = s % 60
    val secs = if (r % 1.0 < 0.05) String.format(Locale.US, "%02d", r.toInt()) else String.format(Locale.US, "%04.1f", r)
    return if (h > 0) "$h:" + String.format(Locale.US, "%02d", m) + ":$secs" else "$m:$secs"
}

fun parseTime(t: String): Double? {
    val parts = t.trim().split(":").reversed()
    if (parts.isEmpty() || parts.size > 3) return null
    var total = 0.0
    var mult = 1.0
    for (p in parts) {
        total += (p.replace(',', '.').toDoubleOrNull() ?: return null) * mult
        mult *= 60
    }
    return total
}

fun fmtViews(v: Long) = when {
    v >= 1_000_000 -> String.format(Locale.US, "%.1fM views", v / 1e6)
    v >= 10_000 -> String.format(Locale.US, "%.0fK views", v / 1e3)
    v >= 1_000 -> String.format(Locale.US, "%.1fK views", v / 1e3)
    v > 0 -> "$v views"
    else -> ""
}
