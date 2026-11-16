# ClipForge for Android

Makes captioned clips from YouTube videos on your phone, then hands them to the YouTube app so you post them yourself. No Google account setup, no computer.

## Get the APK

Every push to `android/` builds the app with GitHub Actions:

- **Releases → "ClipForge (latest build)"** on this repo. Download `ClipForge-arm64-v8a.apk` (most phones) or `ClipForge-universal.apk` (works on every phone, bigger).
- Or **Actions → Build ClipForge APK → latest run → Artifacts**.

On the phone, open the APK and allow "Install unknown apps" when asked. Needs Android 10 or newer.

> Each build is signed with a new key, so to install a newer build you may need to uninstall the old one first.

## Using it

**Paste link tab:** paste a YouTube link (or in the YouTube app tap **Share → ClipForge**) and tap **Find clips**.

**Popular in my town tab:** type a town (or tap 📍 to use your location), choose Today / This week / This month, and tap **Show popular videos**. You get the most-viewed recent videos about that place. Tap one to clip it.

**Pick clips:** clips are suggested from YouTube's "Most replayed" data, then chapters, then evenly spaced parts. Tick the ones you want, adjust start and end (`1:23` or `1:23.5`), tap **Watch** to check them on YouTube, or add a **Custom clip**.

**Options:** vertical 9:16 for Shorts or the original shape. Caption style (Bold white, Yellow, Boxed, Minimal), ALL CAPS, and highlighting the spoken word.

**Make clips:** the video is downloaded once, each clip is cut, cropped and captioned on the phone, and saved to **Movies/ClipForge**. Then:
- **Post with YouTube app** copies the title and description and opens YouTube's upload screen with the clip.
- **Watch**, **Copy text** and **Share** (to TikTok, Instagram and so on) are also there.

Keep the app open while clips are being made. The screen stays on by itself.

## How it works

| Part | How |
|---|---|
| Video info, search, download | [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp on the phone). It updates yt-dlp by itself at start-up so it keeps working when YouTube changes. |
| Captions | YouTube's own subtitles or auto-captions (word-level timing), in the video's language |
| Cut, crop, captions, encode | AndroidX Media3 Transformer (uses the phone's hardware encoder) |
| "Popular in my town" | YouTube search for the town, sorted by views, filtered by upload date |

Captions come from YouTube, so a video with no captions on YouTube gives clips without captions. The app tells you when that happens.

## Building it yourself

Open the `android/` folder in Android Studio and press Run, or:

```bash
cd android
./gradlew assembleRelease     # APKs land in app/build/outputs/apk/release/
./gradlew testReleaseUnitTest # clip & caption logic tests
```

## Be careful with other people's videos

Reposting someone else's video, even a short clip, can get your channel a copyright strike. Clip your own videos, ones you have permission to use, or use clips in a way that adds your own commentary.
