"""ClipForge – paste a YouTube link, pick clips, add captions, post or download."""
import os
import secrets
import threading
import traceback
import uuid

from flask import Flask, abort, jsonify, redirect, render_template, request, send_from_directory, session, url_for

import clipper
import youtube_upload

HERE = os.path.dirname(os.path.abspath(__file__))
JOBS_DIR = os.path.join(HERE, "data", "jobs")
os.makedirs(JOBS_DIR, exist_ok=True)

# Google sign-in over plain http is fine when the app runs on your own computer.
os.environ.setdefault("OAUTHLIB_INSECURE_TRANSPORT", "1")
os.environ.setdefault("OAUTHLIB_RELAX_TOKEN_SCOPE", "1")

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY") or secrets.token_hex(32)

JOBS = {}
LOCK = threading.Lock()


def update(job_id, **kw):
    with LOCK:
        JOBS[job_id].update(kw)


def update_clip(job_id, idx, **kw):
    with LOCK:
        JOBS[job_id]["renders"][idx].update(kw)


def bg(fn, *args):
    threading.Thread(target=fn, args=args, daemon=True).start()


# ---------------------------------------------------------------------------- pages

@app.route("/")
def index():
    return render_template("index.html")


# ------------------------------------------------------------------- step 1: analyse

@app.post("/api/jobs")
def create_job():
    data = request.get_json(force=True)
    url = (data.get("url") or "").strip()
    if not url:
        return jsonify(error="Paste a YouTube link first"), 400
    clip_len = float(data.get("clip_len") or 45)
    count = int(data.get("count") or 8)
    job_id = uuid.uuid4().hex[:12]
    os.makedirs(os.path.join(JOBS_DIR, job_id), exist_ok=True)
    with LOCK:
        JOBS[job_id] = {"id": job_id, "url": url, "status": "analysing", "renders": []}
    bg(_analyse, job_id, url, clip_len, count)
    return jsonify(id=job_id)


def _analyse(job_id, url, clip_len, count):
    try:
        info = clipper.fetch_info(url)
        clips = clipper.suggest_clips(info, clip_len, count)
        info.pop("heatmap", None)
        update(job_id, status="ready", info=info, clips=clips)
    except Exception as e:
        traceback.print_exc()
        update(job_id, status="error", error=str(e))


@app.get("/api/jobs/<job_id>")
def get_job(job_id):
    with LOCK:
        job = JOBS.get(job_id)
        if not job:
            abort(404)
        return jsonify(job)


# --------------------------------------------------------------------- step 2: render

@app.post("/api/jobs/<job_id>/render")
def render(job_id):
    job = JOBS.get(job_id)
    if not job or job["status"] not in ("ready", "done"):
        return jsonify(error="Job is not ready"), 400
    data = request.get_json(force=True)
    clips = data.get("clips") or []
    if not clips:
        return jsonify(error="Select at least one clip"), 400
    opts = {
        "vertical": bool(data.get("vertical", True)),
        "style": data.get("style", "bold"),
        "uppercase": bool(data.get("uppercase", True)),
        "highlight": bool(data.get("highlight", True)),
        "captions": bool(data.get("captions", True)),
        "model": data.get("model", "base"),
        "language": data.get("language") or None,
    }
    renders = []
    for c in clips:
        s, e = float(c["start"]), float(c["end"])
        if e <= s:
            return jsonify(error=f"Clip end must be after start ({c.get('title')})"), 400
        renders.append({"start": s, "end": e, "title": c.get("title") or "Clip",
                        "status": "queued", "progress": "Waiting…"})
    update(job_id, status="rendering", renders=renders, opts=opts)
    bg(_render_all, job_id)
    return jsonify(ok=True)


def _render_all(job_id):
    job = JOBS[job_id]
    out_dir = os.path.join(JOBS_DIR, job_id)
    opts = job["opts"]
    for i, r in enumerate(job["renders"]):
        name = f"clip{i + 1}"
        try:
            update_clip(job_id, i, status="working", progress="Downloading section…")
            raw = clipper.download_section(job["url"], r["start"], r["end"], out_dir, name + "_src")
            update_clip(job_id, i, progress="Cropping & encoding…")
            prepared = os.path.join(out_dir, name + "_prep.mp4")
            clipper.prepare_clip(raw, prepared, opts["vertical"])
            final = os.path.join(out_dir, name + ".mp4")
            transcript = ""
            if opts["captions"]:
                update_clip(job_id, i, progress="Listening & writing captions…")
                words = clipper.transcribe_words(prepared, opts["model"], opts["language"])
                lines = clipper.group_words(words)
                transcript = " ".join(w["word"] for w in words)
                w, h = clipper.probe_size(prepared)
                ass = os.path.join(out_dir, name + ".ass")
                with open(ass, "w", encoding="utf-8") as f:
                    f.write(clipper.build_ass(lines, w, h, opts["style"], opts["uppercase"], opts["highlight"]))
                with open(os.path.join(out_dir, name + ".srt"), "w", encoding="utf-8") as f:
                    f.write(clipper.lines_to_srt(lines))
                update_clip(job_id, i, progress="Burning captions…")
                clipper.burn_captions(prepared, ass, final)
            else:
                os.replace(prepared, final)
            clipper.make_thumbnail(final, os.path.join(out_dir, name + ".jpg"),
                                   min(1.0, (r["end"] - r["start"]) / 2))
            for f in (raw, prepared):
                if os.path.exists(f):
                    os.remove(f)
            update_clip(job_id, i, status="done", progress="Ready", file=name + ".mp4",
                        thumb=name + ".jpg", srt=(name + ".srt") if opts["captions"] else None,
                        transcript=transcript[:2000])
        except Exception as e:
            traceback.print_exc()
            update_clip(job_id, i, status="error", progress=str(e)[:300])
    update(job_id, status="done")


@app.get("/files/<job_id>/<path:name>")
def files(job_id, name):
    if not job_id.isalnum():
        abort(404)
    return send_from_directory(os.path.join(JOBS_DIR, job_id), name,
                               as_attachment=request.args.get("dl") == "1")


# --------------------------------------------------------------- step 3: post to YouTube

@app.get("/api/youtube/status")
def yt_status():
    if not youtube_upload.enabled():
        return jsonify(enabled=False, connected=False)
    try:
        name = youtube_upload.channel_name()
    except Exception:
        name = None
    return jsonify(enabled=True, connected=bool(name), channel=name)


@app.get("/auth/google")
def auth_google():
    if not youtube_upload.enabled():
        return redirect("/?yt=not-configured")
    url, state, verifier = youtube_upload.auth_url(url_for("auth_callback", _external=True))
    session["oauth_state"] = state
    session["oauth_verifier"] = verifier
    return redirect(url)


@app.get("/auth/callback")
def auth_callback():
    try:
        youtube_upload.finish_auth(url_for("auth_callback", _external=True), session.get("oauth_state"),
                                   session.get("oauth_verifier"), request.url)
    except Exception as e:
        traceback.print_exc()
        return redirect("/?yt=error&msg=" + str(e)[:200])
    return redirect("/?yt=connected")


@app.post("/api/youtube/signout")
def yt_signout():
    youtube_upload.sign_out()
    return jsonify(ok=True)


@app.post("/api/jobs/<job_id>/clips/<int:idx>/publish")
def publish(job_id, idx):
    job = JOBS.get(job_id)
    if not job or idx >= len(job["renders"]) or job["renders"][idx].get("status") != "done":
        return jsonify(error="Clip is not ready"), 400
    d = request.get_json(force=True)
    title = (d.get("title") or job["renders"][idx]["title"]).strip()
    description = d.get("description") or ""
    tags = [t.strip() for t in (d.get("tags") or "").split(",") if t.strip()]
    privacy = d.get("privacy") if d.get("privacy") in ("public", "unlisted", "private") else "private"
    if job.get("opts", {}).get("vertical") and "#shorts" not in (title + description).lower():
        description = (description + "\n\n#Shorts").strip()
    path = os.path.join(JOBS_DIR, job_id, job["renders"][idx]["file"])
    update_clip(job_id, idx, upload="Uploading 0%")

    def go():
        try:
            vid = youtube_upload.upload(path, title, description, tags, privacy,
                                        lambda p: update_clip(job_id, idx, upload=f"Uploading {p}%"))
            update_clip(job_id, idx, upload="Posted", video_id=vid)
        except Exception as e:
            traceback.print_exc()
            update_clip(job_id, idx, upload="Failed: " + str(e)[:200])

    bg(go)
    return jsonify(ok=True)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    print(f"\n  ClipForge running → http://localhost:{port}\n")
    app.run(host="127.0.0.1", port=port, debug=False, threaded=True)
