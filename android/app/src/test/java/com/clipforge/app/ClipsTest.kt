package com.clipforge.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipsTest {

    @Test
    fun timesRoundTrip() {
        assertEquals(0.0, parseTime("0:00")!!, 1e-9)
        assertEquals(100.5, parseTime("1:40.5")!!, 1e-9)
        assertEquals(3725.0, parseTime("1:02:05")!!, 1e-9)
        assertEquals(12.5, parseTime("12,5")!!, 1e-9)
        assertEquals("1:40.5", fmtTime(100.5))
        assertEquals("0:45", fmtTime(45.0))
        assertEquals("1:02:05", fmtTime(3725.0))
        assertEquals(null, parseTime("abc"))
    }

    @Test
    fun suggestsFromHeatmapWithoutOverlap() {
        val heat = (0 until 100).map { i -> Heat(i * 6.0, i * 6.0 + 6, if (i == 20 || i == 70) 0.9 else 0.3) }
        val info = VideoInfo("abc", "My Video", 600.0, "Me", "u", heat, emptyList(), null)
        val clips = suggestClips(info, 45.0, 5)
        assertEquals(5, clips.size)
        assertTrue(clips.any { it.reason.startsWith("Most replayed (90") })
        clips.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        clips.forEach { assertEquals(45.0, it.end - it.start, 0.11) }
    }

    @Test
    fun shortVideoFallsBackToEvenSpacing() {
        val info = VideoInfo("abc", "Short", 20.0, "Me", "u", emptyList(), emptyList(), null)
        val clips = suggestClips(info, 45.0, 3)
        assertEquals(1, clips.size)
        assertEquals(0.0, clips[0].start, 1e-9)
        assertEquals(20.0, clips[0].end, 1e-9)
    }

    @Test
    fun parsesAutoCaptionWordTimings() {
        val json = """
            {"events":[
              {"tStartMs":1000,"dDurationMs":3000,"segs":[{"utf8":"hello"},{"utf8":" world","tOffsetMs":400},{"utf8":" again","tOffsetMs":900}]},
              {"tStartMs":2500,"dDurationMs":10,"aAppend":1,"segs":[{"utf8":"\n"}]},
              {"tStartMs":4000,"dDurationMs":2000,"segs":[{"utf8":"[Music]"}]},
              {"tStartMs":6000,"dDurationMs":2000,"segs":[{"utf8":"This is manual text."}]}
            ]}
        """.trimIndent()
        val w = parseJson3(json)
        assertEquals(listOf("hello", "world", "again", "This", "is", "manual", "text."), w.map { it.text })
        assertEquals(1.0, w[0].start, 1e-9)
        assertEquals(1.4, w[1].start, 1e-9)
        assertEquals(1.4, w[0].end, 1e-9)
        assertEquals(6.5, w[4].start, 1e-9)
        w.forEach { assertTrue(it.end > it.start) }
    }

    @Test
    fun groupsAndShiftsWords() {
        val words = (0 until 10).map { Word(10 + it * 0.3, 10 + it * 0.3 + 0.25, if (it == 4) "end." else "w$it") }
        val clip = wordsForClip(words, 10.0, 12.0)
        assertEquals(7, clip.size)
        assertEquals(0.0, clip[0].start, 1e-9)
        val lines = groupWords(clip)
        assertTrue(lines.all { it.words.size <= 3 })
        assertEquals("end.", lines[1].words.last().text) // sentence end closes a line
    }

    @Test
    fun picksOriginalLanguageCaptions() {
        val j = JSONObject("""{"language":"tr","subtitles":{},"automatic_captions":{"en":[],"tr-orig":[],"tr":[]}}""")
        assertEquals("tr-orig", pickCaptionLang(j))
        val j2 = JSONObject("""{"language":"en","subtitles":{"en-US":[]},"automatic_captions":{"en-orig":[]}}""")
        assertEquals("en-US", pickCaptionLang(j2))
        assertEquals(null, pickCaptionLang(JSONObject("{}")))
    }

    @Test
    fun parsesSearchResults() {
        val j = JSONObject("""{"entries":[{"id":"dQw4w9WgXcQ","title":"A","channel":"C","view_count":1500,"duration":61},{"id":"UCxyz","title":"channel"}]}""")
        val hits = parseSearch(j)
        assertEquals(1, hits.size)
        assertEquals("1.5K views", fmtViews(hits[0].views))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", hits[0].url)
    }
}
