# TTS‑Story‑Helper
*A lightweight desktop app for turning Russian stories into high‑quality audio.*

> **TL;DR:**  Enter a text → the app auto‑marks it up, splits it into voice segments and synthesises speech with Yandex SpeechKit.
---             
## The main code writer on this project is AI, no review.
---

## 📦 Project Overview

- **Language** – Kotlin Multiplatform (JVM only for now)
- **UI framework** – Jetpack Compose Desktop (`composeApp` module)
- **Core features**
  * Automatic TTS‑markup generation via the Yandex AI API
  * Voice selection, speed & pitch control per character/voice
  * Local chapter/book management (save/load)
  * Persistent IAM token / folder ID storage

  ---

## ⚙️ Prerequisites

| Item | Minimum version |
  |------|-----------------|
| JDK   | 17 or newer    |
| Gradle| Wrapper (`gradlew`) – no manual install needed |

> The project uses the **Gradle wrapper**; just run `./gradlew` (Linux/macOS) or `.\gradlew.bat` (Windows).

  ---

## 🚀 Getting Started

  ```bash
  # 1. Clone the repo
  git clone https://github.com/your‑org/TTS-Story-Helper.git
  cd TTS-Story-Helper

  # 2. Build & run on desktop JVM
  ./gradlew :composeApp:run   # macOS / Linux
  .\gradlew.bat :composeApp:run   # Windows

  The first launch will create a local preferences file (~/.speechhelper) where the IAM token and folder ID are stored.

  ---
  🔑 Configuring Yandex Credentials

  1. Create an account on https://cloud.yandex.com/.
  2. In your console, generate:
    - IAM Token – iam_token
    - Folder ID – the numeric identifier of your folder
  3. Open the app and paste them into the “Credentials” dialog that appears at startup.

  The values are saved in Java Preferences (~/.speechhelper) so you only need to enter them once per machine.

  ---
  📖 Using the App

  ┌──────────────────────────────────────────────────────────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │                                         Step                                         │                                                        What happens                                                        │
  ├──────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ 1. Type or load a chapter text into the editor pane.                                 │ Text is stored locally and can be edited freely.                                                                           │
  ├──────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ 2. Click “Auto‑Markup”.                                                              │ The app sends the text to Yandex AI, receives markup (voice tags) and displays it in real time.                            │
  ├──────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ 3. Adjust voice settings: choose a speaker, speed & pitch per character or globally. │ Settings are persisted with each chapter/book.                                                                             │
  ├──────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ 4. Click “Synthesize”.                                                               │ The app splits the marked text into chunks, calls Yandex SpeechKit for each chunk and streams audio back to your speakers. │
  └──────────────────────────────────────────────────────────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

  All operations run asynchronously; progress is shown in a status bar.

  ---
  📁 Project Structure

  composeApp/
  ├─ src/commonMain/kotlin/   – shared logic (none yet)
  └─ src/jvmMain/kotlin/by/tigre/speechhelper
      │  AiMarkupApi.kt        – AI‑markup generation
      │  SpeechKitApi.kt       – TTS synthesis wrapper
      │  SessionStorage.kt     – Chapters, books & cache handling
      │  TokenStorage.kt       – IAM token / folder ID persistence
      └─ App.kt                – Compose UI entry point

  - AiMarkupApi talks to https://ai.api.cloud.yandex.net/v1/chat/completions.
  - SpeechKitApi calls the SpeechKit endpoint for each chunk.
  - Both APIs use a simple JSON‑based request/response model.

  ---
  🤝 Contributing

  Pull requests are welcome! Please follow these guidelines:

  ┌─────────────────────────────────────────────────────────────────────────────────┬────────┐
  │                                      Step                                       │ Action │
  ├─────────────────────────────────────────────────────────────────────────────────┼────────┤
  │ 1. Fork & clone your copy of the repo.                                          │        │
  ├─────────────────────────────────────────────────────────────────────────────────┼────────┤
  │ 2. Create a feature branch (git checkout -b feat/<name>).                       │        │
  ├─────────────────────────────────────────────────────────────────────────────────┼────────┤
  │ 3. Run ./gradlew test to ensure existing tests pass (none yet, but run anyway). │        │
  ├─────────────────────────────────────────────────────────────────────────────────┼────────┤
  │ 4. Submit a PR with clear description and relevant screenshots if applicable.   │        │
  └─────────────────────────────────────────────────────────────────────────────────┴────────┘

  ---
  📄 License

  MIT © 2026 Tigre – see the LICENSE file.
