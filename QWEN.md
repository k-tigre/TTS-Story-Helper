# TTS-Story-Helper — Project Context

## Project Overview

**TTS-Story-Helper** is a Kotlin Multiplatform desktop application (JVM only) for Russian fiction authors and publishers. It provides:

- **AI-powered dialogue markup** — automatically adds `[voice]...[/voice]` tags to text using Yandex Cloud AI or other LLM providers (OpenAI, Ollama, LM Studio)
- **Per-character voice configuration** — map each speaker to Yandex SpeechKit voices with custom speed/pitch
- **Text-to-speech synthesis** — cloud (Yandex SpeechKit) or local (Silero via HTTP API)
- **Multi-chapter workflow** — import EPUB/FB2, manage chapters, track markup/voice completion status
- **Audio export & playback** — merge synthesized segments, play in-app, export to `~/SpeechHelper`

### Architecture

```
composeApp/
  src/jvmMain/kotlin/by/tigre/speechhelper/
    main.kt                    # Entry point, Compose desktop window
    TokenStorage.kt            # Yandex credentials, LLM config (Java Preferences)
    data/                      # API clients, storage, parsers
      HttpClientProvider.kt    # Shared HttpClient instances (markupClient, defaultClient)
      OpenAiMarkupApi.kt       # LLM markup with retry logic (3 attempts, exponential backoff)
      AiMarkupApi.kt           # Yandex AI markup (legacy, folderId-based)
      SpeechKitApi.kt          # Yandex SpeechKit TTS
      LocalTtsApi.kt           # Local Silero HTTP client
      LlmModelsApi.kt          # Fetch available models from LLM providers
      SpeechSynthesizer.kt     # Backend dispatcher (cloud vs local)
      SessionStorage.kt        # Chapters, books, preferences, cache paths
      EpubParser.kt, Fb2Parser.kt
      SynthesisChunking.kt     # Split long text for TTS APIs
      WavMerge.kt              # Merge WAV segments (local multi-voice)
      ChapterAudioPlayer.kt    # In-app audio playback
    domain/                    # Business logic, models
      Models.kt                # LlmConfig, LlmProvider, VoiceSettings, API_VOICES
      TextParser.kt            # Parse/build voice-tagged text
      Validation.kt            # Paragraph mapping, validation
    ui/                        # Jetpack Compose UI
      App.kt, MainScreen.kt
      MainViewModel.kt         # UI state, chapter management, synthesis orchestration
      SegmentsView.kt, VoiceMappingPanel.kt, Dialogs.kt, Components.kt

local-tts-server/              # Optional Python FastAPI + Silero TTS
  main.py                      # /health, /synthesize endpoints
  requirements.txt
  run.ps1, run.sh              # venv setup + uvicorn start
```

### Key Design Decisions

1. **LLM Provider Abstraction** — `LlmConfig` supports multiple providers:
   - `YandexCloud` — requires `folderId` + IAM token, model URI: `gpt://{folderId}/{model}/latest`
   - `OpenAI`, `Ollama`, `LMStudio` — require `baseUrl` + `apiKey`, standard model names

2. **Shared HttpClient** — `HttpClientProvider` provides two shared clients:
   - `markupClient` — 240s timeout, for long AI markup responses
   - `defaultClient` — 15s timeout, for lightweight API calls (models list, health checks)

3. **Request Timing** — All API calls log request duration (ms) for performance monitoring:
   ```kotlin
   println("[ApiName] <- HTTP 200 (1234ms)")
   ```

4. **Retry Logic** — `OpenAiMarkupApi.sendChat()` implements automatic retry (3 attempts, exponential backoff: 1s → 2s → 4s) for transient API failures

5. **Chunked Processing** — Long texts are split into chunks (~2000 chars for markup, ~240 chars for TTS) to respect API limits; each chunk is processed sequentially with progress tracking

6. **Original Text Preservation** — After AI markup, original plain text is stored separately; users can edit prose and sync changes to tagged text

7. **Per-Chapter Cache** — Synthesis results cached in `~/SpeechHelper/cache/<chapterId>/`; failed segments can be retried without re-synthesizing successful ones

## Building and Running

### Prerequisites

- **JDK 17+**
- **Gradle** (use wrapper: `gradlew` / `gradlew.bat`)
- **Python 3** (optional, for local TTS server)

### Run Desktop App

```bash
# macOS / Linux
./gradlew :composeApp:run

# Windows
.\gradlew.bat :composeApp:run
```

### Build Native Distributions

```bash
# macOS: DMG
./gradlew :composeApp:packageDmg

# Windows: MSI
.\gradlew.bat :composeApp:packageMsi

# Linux: DEB
./gradlew :composeApp:packageDeb
```

### Run Local TTS Server (Optional)

```bash
cd local-tts-server

# Windows
.\run.ps1

# macOS / Linux
./run.sh
```

Server starts at `http://127.0.0.1:8765`

- `GET /health` → `{"ok": true}`
- `POST /synthesize` → WAV/MP3/OGG audio

## Development Conventions

### Language & Formatting

- **Kotlin** with explicit API markers where needed
- **Russian** for UI strings (user-facing)
- **English** for:
  - Log messages (`println("[OpenAiMarkup] ...")`)
  - Code comments (technical explanations)
  - Variable/function names
  - Documentation

### Logging

All console output uses **English** to avoid encoding issues in terminals:

```kotlin
// Good
println("[OpenAiMarkup] Text split into ${chunks.size} chunks, model=${config.model}")
println("[OpenAiMarkup] <- HTTP ${response.status.value} (attempt $attempt)")

// Avoid
println("[OpenAiMarkup] Текст разбит на ${chunks.size} чанк(ов)")
```

### Error Handling

- API failures in `OpenAiMarkupApi` use retry logic before throwing
- UI layer catches exceptions and displays user-friendly messages in Russian
- Always log stack traces for debugging: `e.printStackTrace()`

### Testing

- No formal test suite currently (per README: "the main code writer on this project is AI")
- Manual testing via UI is primary verification method
- When adding features, consider edge cases:
  - Empty text input
  - Network failures
  - Missing credentials (IAM token, folder ID, API key)
  - Invalid voice markers

## Data Storage

| Location | Contents |
|----------|----------|
| `~/.speechhelper/` | Chapters, voice mapping, current book, synthesis preferences, window size, saved books |
| `~/SpeechHelper/` | Exported audio files |
| `~/SpeechHelper/cache/<chapterId>/` | Synthesis part files for resume/retry |
| Java Preferences (`by.tigre.speechhelper.TokenStorage`) | IAM token, folder ID, LLM config, first-launch flag |

## Credentials & Configuration

### Yandex Cloud (Required for Cloud Features)

1. **IAM Token** — obtained from Yandex Cloud console
2. **Folder ID** — Yandex Cloud folder ID (for AI markup only)

### LLM Providers

| Provider | Base URL | Requires API Key | Requires folderId |
|----------|----------|------------------|-------------------|
| Yandex Cloud | `https://ai.api.cloud.yandex.net/v1` | No (uses IAM token) | Yes |
| OpenAI | `https://api.openai.com/v1` | Yes | No |
| Ollama | `http://localhost:11434/v1` | No | No |
| LM Studio | `http://localhost:1234/v1` | No | No |

**Important:** The `folderId` dialog should only appear when using Yandex Cloud (when `llmConfig.isConfigured == false`). For configured LLM providers, skip the dialog.

## Voice Mapping

### Yandex SpeechKit Voices

```kotlin
API_VOICES = [
    "alena", "filipp", "ermil", "jane", "omazh", "zahar",
    "dasha", "julia", "lera", "masha", "marina", "alexander",
    "kirill", "anton", "madi_ru", "saule_ru", "zamira_ru",
    "zhanar_ru", "yulduz_ru"
]
```

### Silero Speaker Mapping (Local TTS)

```python
YANDEX_TO_SILERO = {
    "alena": "baya",      "filipp": "aidar",    "ermil": "eugene",
    "jane": "xenia",      "omazh": "xenia",     "zahar": "aidar",
    "dasha": "baya",      "julia": "kseniya",   "lera": "baya",
    "masha": "xenia",     "marina": "xenia",    "alexander": "aidar",
    "kirill": "eugene",   "anton": "aidar",     "madi_ru": "aidar",
    "saule_ru": "kseniya", "zamira_ru": "baya", "zhanar_ru": "baya",
    "yulduz_ru": "xenia"
}
```

## Common Workflows

### Auto-Markup Flow

1. User loads/pastes text (optionally with chapters)
2. Clicks "Авто-разметка" (Auto-markup)
3. If LLM configured → call `OpenAiMarkupApi.autoMarkup()`
4. If not configured → prompt for `folderId`, use `AiMarkupApi.autoMarkup()`
5. Text split into chunks, each sent to LLM with system prompt
6. Results joined, original text saved, markup applied

### Multi-Voice Synthesis Flow

1. User configures voice mapping (speaker → Yandex voice + settings)
2. Clicks "Озвучить" (Synthesize)
3. Each segment synthesized separately (cached)
4. Failed segments can be retried
5. All parts merged (WavMerge for local, concat for cloud MP3)
6. Audio saved to `~/SpeechHelper/`, path stored for playback

## Troubleshooting

### "Корябры" (Garbled Characters) in Terminal

All `println()` log messages must use **English** characters only. Russian text in console output may appear garbled depending on terminal encoding.

### LLM API Failures

- Check `llmConfig.isConfigured` before making requests
- `OpenAiMarkupApi` has built-in retry (3 attempts)
- Verify `baseUrl`, `apiKey`, `model` are set correctly

### Local TTS Not Responding

- Ensure server is running: `GET /health` should return `{"ok": true}`
- Check `localTtsSettings.baseUrl` matches server address
- First request downloads Silero model via `torch.hub` (needs network)

### Missing folderId Dialog for OpenAI

The `showFolderIdDialog` should only be triggered when:
- `llmConfig.isConfigured == false` (no LLM configured) AND
- `TokenStorage.folderId.isBlank()`

If LLM is configured (OpenAI/Ollama/LM Studio), skip the dialog entirely.
