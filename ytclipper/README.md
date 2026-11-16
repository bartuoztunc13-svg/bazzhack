# ClipForge

Paste a YouTube link, pick the best moments, get captioned clips (vertical for Shorts or original shape), then download them or post them straight to your channel.

## What it does

1. **Find clips.** Reads the video and suggests clips from YouTube's "Most replayed" peaks, then chapters, then evenly spaced parts. You can change the start and end of each clip, preview it, rename it, or add your own.
2. **Captions.** Only the parts you picked are downloaded. Speech is turned into text on your own computer (faster-whisper, no API key needed) and burned into the video as bold captions, with the spoken word highlighted. You also get an `.srt` file.
3. **Post.**
   - **Download (always on):** download the clip, copy the title and description, and upload it in YouTube Studio.
   - **Direct posting (optional):** click "Sign in with Google" and post with a title, description, tags and privacy setting. Vertical clips get `#Shorts` added.

## Run it

You need Python 3.10+ and ffmpeg (`brew install ffmpeg`, `sudo apt install ffmpeg`, or `winget install ffmpeg`).

```bash
cd ytclipper
python3 -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

Open http://localhost:5000.

The first caption job downloads the speech model (about 150 MB for "Balanced"). Later runs start quickly.

## Turning on direct posting (one time, about 10 minutes)

The site never asks for your Google password. You sign in on Google's own page, the same way you do with any "Sign in with Google" button. Google requires each app to have its own key for this:

1. Go to https://console.cloud.google.com/ and create a project (any name).
2. **APIs & Services → Library** → search **YouTube Data API v3** → **Enable**.
3. **APIs & Services → OAuth consent screen** → External → fill in an app name and your email → under **Test users** add your own Google account.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID** → type **Web application** → under **Authorized redirect URIs** add
   `http://localhost:5000/auth/callback`
5. Download the JSON file and save it as `ytclipper/client_secret.json`.
6. Restart `python app.py`, then click **Sign in with Google** at the top right.

Notes:
- While the app is in "Testing" mode, Google keeps uploads from unverified apps **private**. Change them to public in YouTube Studio, or submit the app for verification.
- The default API quota allows about 6 uploads a day.
- Sign-in is saved in `data/youtube_token.json`. Use **Sign out** to remove it.

## Notes

- Only clip videos you own or have permission to use.
- Files are kept in `data/jobs/`. Delete that folder whenever you like.
- Accuracy settings: Fast = `tiny`, Balanced = `base`, Accurate = `small` Whisper models.
