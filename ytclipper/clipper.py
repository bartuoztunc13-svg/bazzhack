"""Core video work: read video info, suggest clips, cut, transcribe, burn captions."""
import os
import re
import subprocess

import yt_dlp
from yt_dlp.utils import download_range_func

_whisper_models = {}


# --------------------------------------------------------------------------- info

def fetch_info(url):
    """Return metadata for a YouTube URL without downloading the video."""
    with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "skip_download": True}) as ydl:
        info = ydl.extract_info(url, download=False)
    return {
        "id": info.get("id"),
        "title": info.get("title") or "Untitled",
        "duration": float(info.get("duration") or 0),
        "thumbnail": info.get("thumbnail"),
        "channel": info.get("channel") or info.get("uploader"),
        "heatmap": info.get("heatmap") or [],
        "chapters": info.get("chapters") or [],
        "url": info.get("webpage_url") or url,
    }


# ----------------------------------------------------------------- clip suggestion

def suggest_clips(info, clip_len=45, count=8):
    """Suggest clips using YouTube's 'Most replayed' data, then chapters, then even spacing."""
    duration = info["duration"]
    if duration <= 0:
        return []
    clip_len = max(5, min(clip_len, duration))

    def window(center):
        start = max(0.0, center - clip_len / 2)
        end = min(duration, start + clip_len)
        start = max(0.0, end - clip_len)
        return round(start, 1), round(end, 1)

    clips = []

    # 1) Most-replayed peaks
    heat = sorted(info.get("heatmap") or [], key=lambda h: h.get("value", 0), reverse=True)
    for h in heat:
        if len(clips) >= count:
            break
        center = (h["start_time"] + h["end_time"]) / 2
        if center < 3:  # the very start is almost always "most replayed"; skip it
            continue
        s, e = window(center)
        if any(s < c["end"] and e > c["start"] for c in clips):
            continue
        clips.append({"start": s, "end": e, "reason": f"Most replayed ({int(h.get('value', 0) * 100)}%)"})

    # 2) Chapters
    if len(clips) < count:
        for ch in info.get("chapters") or []:
            if len(clips) >= count:
                break
            s = float(ch["start_time"])
            e = min(float(ch["end_time"]), s + clip_len)
            if e - s < 5 or any(s < c["end"] and e > c["start"] for c in clips):
                continue
            clips.append({"start": round(s, 1), "end": round(e, 1), "reason": f"Chapter: {ch.get('title', '')}"})

    # 3) Evenly spaced
    if len(clips) < count:
        slots = max(1, int(duration // clip_len))
        step = duration / slots
        for i in range(slots):
            if len(clips) >= count:
                break
            s, e = window(step * i + step / 2)
            if any(s < c["end"] and e > c["start"] for c in clips):
                continue
            clips.append({"start": s, "end": e, "reason": "Evenly spaced"})

    clips.sort(key=lambda c: c["start"])
    for i, c in enumerate(clips):
        c["title"] = f"{info['title'][:70]} – Part {i + 1}"
    return clips


# ------------------------------------------------------------------- download/cut

def download_section(url, start, end, out_dir, name):
    """Download only [start, end] of the video. Returns the file path."""
    template = os.path.join(out_dir, f"{name}.%(ext)s")
    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*+ba/b",
        "merge_output_format": "mp4",
        "outtmpl": template,
        "download_ranges": download_range_func(None, [(start, end)]),
        "force_keyframes_at_cuts": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.download([url])
    for f in os.listdir(out_dir):
        if f.startswith(name + ".") and f.endswith((".mp4", ".mkv", ".webm")):
            return os.path.join(out_dir, f)
    raise RuntimeError("Download finished but no video file was found")


def run(cmd, cwd=None):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed: {p.stderr[-800:]}")
    return p.stdout


def probe_size(path):
    out = run(["ffprobe", "-v", "error", "-select_streams", "v:0",
               "-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", path])
    w, h = out.strip().split("x")[:2]
    return int(w), int(h)


def prepare_clip(src, dst, vertical):
    """Re-encode the section; optionally center-crop to 9:16 (1080x1920) for Shorts."""
    vf = "crop='min(iw,ih*9/16)':ih,scale=1080:1920,setsar=1" if vertical else "scale='min(1920,iw)':-2,setsar=1"
    run(["ffmpeg", "-y", "-i", src, "-vf", vf, "-c:v", "libx264", "-preset", "veryfast",
         "-crf", "20", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", dst])


# ------------------------------------------------------------------- transcription

def transcribe_words(video_path, model_size="base", language=None):
    """Return a list of {start, end, word} using faster-whisper."""
    from faster_whisper import WhisperModel
    if model_size not in _whisper_models:
        _whisper_models[model_size] = WhisperModel(model_size, device="cpu", compute_type="int8")
    model = _whisper_models[model_size]
    segments, _ = model.transcribe(video_path, word_timestamps=True, vad_filter=True,
                                   language=language or None)
    words = []
    for seg in segments:
        for w in seg.words or []:
            text = w.word.strip()
            if text:
                words.append({"start": w.start, "end": w.end, "word": text})
    return words


def group_words(words, max_words=3, max_gap=0.6, max_dur=1.6):
    """Group words into short punchy caption lines."""
    lines, cur = [], []
    for w in words:
        if cur and (len(cur) >= max_words or w["start"] - cur[-1]["end"] > max_gap
                    or w["end"] - cur[0]["start"] > max_dur):
            lines.append(cur)
            cur = []
        cur.append(w)
        if re.search(r"[.!?]$", w["word"]):
            lines.append(cur)
            cur = []
    if cur:
        lines.append(cur)
    return [{"start": l[0]["start"], "end": l[-1]["end"], "words": l} for l in lines]


# ------------------------------------------------------------------- captions (ASS)

STYLES = {
    # name: (primary, highlight, outline/back colour, border style, outline width)
    "bold":   ("&H00FFFFFF", "&H0000E5FF", "&H00000000", 1, 6),
    "yellow": ("&H0000FFFF", "&H00FFFFFF", "&H00000000", 1, 6),
    "boxed":  ("&H00FFFFFF", "&H0000E5FF", "&H99000000", 3, 14),
    "minimal": ("&H00FFFFFF", "&H00FFFFFF", "&H80000000", 1, 3),
}


def _ts(t):
    t = max(0.0, t)
    h, rem = divmod(t, 3600)
    m, s = divmod(rem, 60)
    return f"{int(h)}:{int(m):02d}:{s:05.2f}"


def _esc(text):
    return text.replace("\\", "").replace("{", "(").replace("}", ")")


def build_ass(lines, width, height, style="bold", uppercase=True, highlight=True):
    primary, hl, back, border, outline = STYLES.get(style, STYLES["bold"])
    font_size = int(min(width, height) * (0.085 if height > width else 0.075))
    margin_v = int(height * (0.28 if height > width else 0.10))
    head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {width}
PlayResY: {height}
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Cap,DejaVu Sans,{font_size},{primary},{primary},{back},{back},-1,0,0,0,100,100,0,0,{border},{outline},2,2,{int(width*0.06)},{int(width*0.06)},{margin_v},1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    events = []
    for i, line in enumerate(lines):
        end = line["end"]
        if i + 1 < len(lines):  # keep caption up until the next one if the gap is small
            nxt = lines[i + 1]["start"]
            if nxt - end < 0.4:
                end = nxt
        words = [(_esc(w["word"].upper() if uppercase else w["word"]), w) for w in line["words"]]
        if highlight and len(words) > 1:
            # one event per word, current word in highlight colour
            for j, (_, w) in enumerate(words):
                ws = w["start"] if j else line["start"]
                we = words[j + 1][1]["start"] if j + 1 < len(words) else end
                txt = " ".join(
                    (f"{{\\c{hl}}}{t}{{\\c{primary}}}" if k == j else t) for k, (t, _) in enumerate(words))
                events.append(f"Dialogue: 0,{_ts(ws)},{_ts(we)},Cap,,0,0,0,,{{\\fad(40,0)}}{txt}" if j == 0
                              else f"Dialogue: 0,{_ts(ws)},{_ts(we)},Cap,,0,0,0,,{txt}")
        else:
            txt = " ".join(t for t, _ in words)
            events.append(f"Dialogue: 0,{_ts(line['start'])},{_ts(end)},Cap,,0,0,0,,{{\\fad(40,0)}}{txt}")
    return head + "\n".join(events) + "\n"


def lines_to_srt(lines):
    out = []
    for i, l in enumerate(lines, 1):
        def f(t):
            ms = int(round(t * 1000))
            h, ms = divmod(ms, 3600000)
            m, ms = divmod(ms, 60000)
            s, ms = divmod(ms, 1000)
            return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"
        out.append(f"{i}\n{f(l['start'])} --> {f(l['end'])}\n{' '.join(w['word'] for w in l['words'])}\n")
    return "\n".join(out)


def burn_captions(clip_path, ass_path, dst):
    """Burn ASS captions into the clip. Runs inside the folder so paths need no escaping."""
    cwd = os.path.dirname(os.path.abspath(clip_path))
    run(["ffmpeg", "-y", "-i", os.path.basename(clip_path), "-vf", f"ass={os.path.basename(ass_path)}",
         "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "copy",
         "-movflags", "+faststart", os.path.basename(dst)], cwd=cwd)


def make_thumbnail(video, dst, at=1.0):
    run(["ffmpeg", "-y", "-ss", str(at), "-i", video, "-frames:v", "1", "-vf", "scale=360:-2", dst])
