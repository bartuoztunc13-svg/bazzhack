const $ = (s, el = document) => el.querySelector(s);
const api = async (url, body) => {
  const r = await fetch(url, body ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {});
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || r.statusText);
  return j;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

const fmt = s => {
  s = Math.max(0, +s || 0);
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = (s % 60).toFixed(1).replace(/\.0$/, "");
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(sec).padStart(sec.includes(".") ? 4 : 2, "0");
};
const parse = t => String(t).trim().split(":").reverse().reduce((acc, v, i) => acc + (parseFloat(v) || 0) * 60 ** i, 0);

let job = null, yt = { enabled: false, connected: false };

// ------------------------------------------------------------- YouTube account
async function loadAccount() {
  yt = await api("/api/youtube/status").catch(() => yt);
  const el = $("#yt-account");
  if (!yt.enabled) {
    el.innerHTML = `<span class="muted small">Direct posting off – clips download for upload in YouTube Studio</span>`;
  } else if (yt.connected) {
    el.innerHTML = `<span>Posting to <b></b></span><button class="btn tiny" id="signout">Sign out</button>`;
    $("b", el).textContent = yt.channel;
    $("#signout").onclick = async () => { await api("/api/youtube/signout", {}); loadAccount(); };
  } else {
    el.innerHTML = `<a class="btn tiny primary" href="/auth/google">Sign in with Google</a>`;
  }
  document.querySelectorAll(".result").forEach(refreshResultMode);
}

// ------------------------------------------------------------- step 1
$("#url-form").onsubmit = async e => {
  e.preventDefault();
  const st = $("#url-status");
  st.className = "status";
  st.innerHTML = `<span class="spinner"></span>Reading video…`;
  try {
    const { id } = await api("/api/jobs", { url: $("#url").value, clip_len: $("#clip-len").value, count: $("#count").value });
    while (true) {
      job = await api("/api/jobs/" + id);
      if (job.status !== "analysing") break;
      await sleep(800);
    }
    if (job.status === "error") throw new Error(job.error);
    st.textContent = "";
    showClips();
  } catch (err) {
    st.className = "status error";
    st.textContent = "Couldn't read that video: " + err.message;
  }
};

// ------------------------------------------------------------- step 2
function showClips() {
  $("#step-pick").classList.remove("hidden");
  $("#video-title").textContent = job.info.title;
  $("#video-meta").textContent = `${job.info.channel || ""} · ${fmt(job.info.duration)} · ${job.clips.length} suggested clips`;
  const box = $("#clips");
  box.innerHTML = "";
  job.clips.forEach(c => box.appendChild(clipCard(c)));
  updateCount();
  $("#step-pick").scrollIntoView({ behavior: "smooth" });
}

function clipCard(c) {
  const el = $("#clip-tpl").content.firstElementChild.cloneNode(true);
  const frame = $(".frame", el);
  frame.style.backgroundImage = `url(https://i.ytimg.com/vi/${job.info.id}/hqdefault.jpg)`;
  $(".reason", el).textContent = c.reason;
  $(".title", el).value = c.title;
  $(".start", el).value = fmt(c.start);
  $(".end", el).value = fmt(c.end);
  const sel = $(".sel", el);
  const sync = () => {
    el.classList.toggle("on", sel.checked);
    const d = parse($(".end", el).value) - parse($(".start", el).value);
    $(".dur", el).textContent = d > 0 ? Math.round(d) + "s" : "end ≤ start";
    updateCount();
  };
  sel.onchange = sync;
  $(".start", el).oninput = $(".end", el).oninput = sync;
  $(".preview", el).onclick = () => {
    const s = Math.floor(parse($(".start", el).value)), e = Math.ceil(parse($(".end", el).value));
    frame.innerHTML = `<iframe allow="autoplay; encrypted-media" allowfullscreen
      src="https://www.youtube-nocookie.com/embed/${job.info.id}?start=${s}&end=${e}&autoplay=1"></iframe>`;
  };
  sync();
  return el;
}

function selected() {
  return [...document.querySelectorAll(".clip")].filter(el => $(".sel", el).checked).map(el => ({
    start: parse($(".start", el).value), end: parse($(".end", el).value), title: $(".title", el).value,
  }));
}
function updateCount() {
  const n = document.querySelectorAll(".clip .sel:checked").length;
  $("#sel-count").textContent = `${n} clip${n === 1 ? "" : "s"} selected`;
  $("#render-btn").disabled = n === 0;
}
$("#select-all").onclick = () => {
  const boxes = [...document.querySelectorAll(".clip .sel")];
  const all = boxes.every(b => b.checked);
  boxes.forEach(b => { b.checked = !all; b.onchange(); });
};
$("#add-clip").onclick = () => {
  const el = clipCard({ start: 0, end: Math.min(30, job.info.duration), reason: "Custom", title: job.info.title.slice(0, 70) + " – Clip" });
  $("#clips").prepend(el);
};

$("#render-btn").onclick = async () => {
  const style = $("#opt-style").value;
  const btn = $("#render-btn");
  btn.disabled = true;
  try {
    await api(`/api/jobs/${job.id}/render`, {
      clips: selected(),
      vertical: $("#opt-format").value === "vertical",
      captions: style !== "none",
      style: style === "none" ? "bold" : style,
      model: $("#opt-model").value,
      language: $("#opt-lang").value,
      uppercase: $("#opt-upper").checked,
      highlight: $("#opt-hl").checked,
    });
    startResults();
  } catch (err) {
    alert(err.message);
    btn.disabled = false;
  }
};

// ------------------------------------------------------------- step 3
const resultEls = [];
async function startResults() {
  const sec = $("#step-results");
  sec.classList.remove("hidden");
  $("#results").innerHTML = "";
  resultEls.length = 0;
  $("#results-note").textContent = "Clips are made one at a time. The first run downloads the caption model, so it takes a bit longer.";
  sec.scrollIntoView({ behavior: "smooth" });
  while (true) {
    job = await api("/api/jobs/" + job.id);
    job.renders.forEach((r, i) => renderResult(r, i));
    const busy = job.renders.some(r => ["queued", "working"].includes(r.status) || /^Uploading/.test(r.upload || ""));
    if (!busy && job.status === "done") break;
    await sleep(1500);
  }
  $("#render-btn").disabled = false;
}

function renderResult(r, i) {
  let el = resultEls[i];
  if (!el) {
    el = resultEls[i] = $("#result-tpl").content.firstElementChild.cloneNode(true);
    $("#results").appendChild(el);
    $(".result-title", el).textContent = r.title;
    const f = $(".publish", el);
    f.title.value = r.title;
    f.onsubmit = async e => {
      e.preventDefault();
      $(".post", el).disabled = true;
      try {
        await api(`/api/jobs/${job.id}/clips/${i}/publish`, {
          title: f.title.value, description: f.description.value, tags: f.tags.value, privacy: f.privacy.value,
        });
        if (job.status === "done") startResults();
      } catch (err) { alert(err.message); $(".post", el).disabled = false; }
    };
    $(".copy", el).onclick = () => {
      navigator.clipboard.writeText(f.title.value + "\n\n" + f.description.value);
      $(".copy", el).textContent = "Copied!";
    };
  }
  const prog = $(".progress", el);
  prog.innerHTML = r.status === "working" ? `<span class="spinner"></span>${r.progress}` : r.progress;
  prog.style.color = r.status === "error" ? "#ff8080" : "";

  if (r.status === "done" && !el.dataset.done) {
    el.dataset.done = 1;
    const base = `/files/${job.id}/`;
    $(".video-wrap", el).innerHTML = `<video controls preload="metadata" poster="${base + r.thumb}" src="${base + r.file}"></video>`;
    $(".download", el).href = base + r.file + "?dl=1";
    if (r.srt) $(".srt", el).href = base + r.srt + "?dl=1"; else $(".srt", el).remove();
    $(".actions", el).classList.remove("hidden");
    const f = $(".publish", el);
    if (r.transcript && !f.description.value) f.description.value = r.transcript.slice(0, 300) + (r.transcript.length > 300 ? "…" : "");
    refreshResultMode(el);
  }
  const us = $(".upload-status", el);
  if (r.upload) {
    us.textContent = r.upload;
    us.classList.toggle("ok", r.upload === "Posted");
    if (r.video_id) us.innerHTML = `Posted · <a target="_blank" rel="noopener" href="https://youtu.be/${r.video_id}">open</a>`;
    $(".post", el).disabled = /^Uploading|Posted/.test(r.upload);
  }
}

function refreshResultMode(el) {
  if (!el.dataset.done) return;
  const canPost = yt.enabled && yt.connected;
  $(".publish", el).classList.remove("hidden");
  $(".post", el).classList.toggle("hidden", !canPost);
  $("select[name=privacy]", el).classList.toggle("hidden", !canPost);
  $(".manual", el).classList.toggle("hidden", canPost);
}

// ------------------------------------------------------------- boot
const q = new URLSearchParams(location.search);
if (q.get("yt") === "error") alert("Google sign-in failed: " + (q.get("msg") || ""));
if (q.has("yt")) history.replaceState(null, "", "/");
loadAccount();
