# TTS‑Story‑Helper

*Desktop app for Russian fiction: AI dialogue markup, per‑character voices, and text‑to‑speech (cloud or local).*

> **TL;DR:** Load or paste a story → optional **EPUB/FB2 import** into chapters → **Yandex AI** adds `[voice]` markup → tune **SpeechKit** or **local Silero** per speaker → synthesize (chunked, cached), **merge to one file**, **play** in the app, export to `~/SpeechHelper`. Books and progress live under `~/.speechhelper`.

---

## The main code writer on this project is AI, no review.

---

## What it does

The interface is **Russian**; the app is aimed at **long‑form narrative**: many chapters, dialogue tags, and a repeatable “markup → voices → synthesize” loop.

**Books & chapters**

- Multiple chapters per project; create, rename, delete; switch without losing edits (auto‑save is debounced).
- **Save / load book** snapshots (chapters + markup + original plain text + global voice mapping) as files under `~/.speechhelper/books/`.
- **Import EPUB or FB2** — builds a chapter list from the spine / structure; replaces the current project (use save first if needed).
- Per‑chapter **workflow flags**: “markup done” and “voice done” (stored on disk) to track production status.

**Markup & editing**

- Voice markup uses paired tags: `[speakerName]…[/speakerName]` (and optional pause tags like `<[small]>` in AI output).
- **Auto‑markup** sends the chapter to **Yandex Cloud AI** (`/v1/chat/completions`) in chunks; can run for **one chapter or a batch** (e.g. all chapters that need markup). Requires **IAM token** and **folder ID** (folder ID is requested when missing).
- **Original text** is kept separately from markup after auto‑markup so you can edit prose and sync changes into tagged text where possible.
- **Segment view**: list segments, edit text, change voice tag, **split** a segment, **re‑markup a single segment** (“fix dialog” via AI).
- **Paragraph validation**: compares stripped markup to the saved original and highlights mismatches (helps catch dropped or extra words).

**Voices & synthesis**

- **Global voice mapping**: for each `speakerName`, choose Yandex voice, role, speed, pitch; merge two speakers into one; drop unused mappings.
- **Synthesis backend**: **Yandex SpeechKit** (formats **mp3 / ogg / wav**) or **local Silero** over HTTP (**WAV** only on the wire; merged to one WAV).
- **Multi‑voice synthesis**: one request per segment; **per‑chapter cache** under `~/SpeechHelper/cache/<chapterId>/` skips successful parts on re‑run; failed segments can be retried; cloud MP3 parts are concatenated; local WAV parts are **merged** with proper headers (`WavMerge`).
- **Simple synthesis** (single voice) for text without splitting.

**Playback & export**

- Built‑in **chapter audio player** (MP3 via **mp3spi**; WAV supported) when a path to the last exported file is known.
- Exported files: `~/SpeechHelper/` with timestamp and optional book/chapter name in the filename.

---

## Tech stack

| Area | Choice |
|------|--------|
| Runtime | Kotlin Multiplatform, **JVM only** (`composeApp`) |
| UI | Jetpack Compose for Desktop, Material 3 |
| HTTP / JSON | Ktor Client (CIO), kotlinx.serialization |
| EPUB | ZIP + **jsoup** for OPF/HTML |
| FB2 | XML parsing (`Fb2Parser`) |
| Desktop packages | Gradle task can build **Dmg / Msi / Deb** (`compose.desktop { nativeDistributions { … } }`) |

---

## Prerequisites

| Item | Notes |
|------|--------|
| **JDK** | 17+ |
| **Gradle** | Use the project wrapper (`./gradlew` or `gradlew.bat`) |
| **Python 3** | Only for **local TTS** (`local-tts-server`) |
| **Network** | Yandex APIs; first **Silero** run downloads the model via `torch.hub` |

---

## Getting started

```bash
git clone https://github.com/your-org/TTS-Story-Helper.git
cd TTS-Story-Helper

./gradlew :composeApp:run          # macOS / Linux
.\gradlew.bat :composeApp:run      # Windows
```

On first launch, **Java Preferences** store paths under the user home (see **Data on disk**). A **help** dialog may show once; **Yandex credentials** are requested when needed.

---

## Yandex credentials

1. Account / console: [Yandex Cloud](https://cloud.yandex.com/).
2. You need an **IAM token** and **folder ID** (for AI markup and SpeechKit).
3. Paste them in the app’s credential / folder dialogs.

---

## Local TTS (Silero) — optional

Folder **`local-tts-server/`**: **FastAPI** + **Silero** (`v5_ru` by default) via `torch.hub`, listening on **`127.0.0.1:8765`**.

```bash
cd local-tts-server
./run.sh              # Linux / macOS
```

```powershell
cd local-tts-server
.\run.ps1             # Windows
```

Scripts create **`.venv`**, install **`requirements.txt`**, and start Uvicorn.

- **`GET /health`** — `{ "ok": true }`
- **`POST /synthesize`** — JSON body (`text`, `speaker`, optional `sample_rate`, `model_id`, `speed`, `pitch_shift`) → **`audio/wav`**. Yandex voice IDs from the app are mapped to Silero speakers.

In the app: choose **«Локально (Silero)»**, set base URL (default `http://127.0.0.1:8765`), **«Проверить соединение»**. Speed/pitch use **SSML prosody** steps — not identical to the cloud.

---

## Data on disk

| Location | Contents |
|----------|-----------|
| `~/.speechhelper/` | Chapters (`chapters/`), voice mapping, current book name, synthesis prefs (cloud vs local, local URL/model/rate), window size, saved **book** `.txt` under `books/` |
| `~/SpeechHelper/` | Exported audio files |
| `~/SpeechHelper/cache/<chapterId>/` | Synthesis part files for resume / retry |

---

## Project structure

```
local-tts-server/     # Silero HTTP API (Python)
composeApp/
  src/jvmMain/kotlin/by/tigre/speechhelper/
    main.kt, TokenStorage.kt
    data/
      AiMarkupApi.kt       # Yandex AI chat — auto-markup & per-segment fix
      SpeechKitApi.kt      # Yandex TTS
      LocalTtsApi.kt       # Local Silero client
      SpeechSynthesizer.kt # Cloud vs local dispatch
      SessionStorage.kt    # Chapters, books, prefs, cache paths
      EpubParser.kt, Fb2Parser.kt
      SynthesisChunking.kt   # split long strings for cloud TTS
      WavMerge.kt            # merge WAV segments (local multi-voice)
      ChapterAudioPlayer.kt, AudioPlayer.kt
    domain/
      Models.kt, TextParser.kt, SynthesisModels.kt
    ui/
      App.kt, MainScreen.kt, MainViewModel.kt
      SegmentsView.kt, VoiceMappingPanel.kt, Dialogs.kt, Components.kt
```

**APIs (cloud):**

- Markup: `https://ai.api.cloud.yandex.net/v1/chat/completions`
- TTS: SpeechKit REST (see `SpeechKitApi.kt`)

---

## Contributing

1. Fork and clone.  
2. Branch: `git checkout -b feat/<name>`.  
3. Run `./gradlew test` (add tests with features when it makes sense).  
4. Open a PR with a clear description (screenshots help for UI).

---

## License

MIT © 2026 Tigre — see `LICENSE`.
