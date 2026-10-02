# Zonk-Core (Android, Kotlin + Compose)

iOS-styled AI chat app. Two modes:
- **Cloud**: talks to any OpenAI-compatible server (`/v1/chat/completions`).
- **On-device**: runs a local llama.cpp model (optional, needs native build + a `.gguf` model).

## How it works
1. First launch shows onboarding; afterwards you land in chat.
2. Your message is added to the conversation, the last 40 messages are sent to the
   chosen backend, and the reply appears as a bubble.
3. Settings (gear icon): server URL, API key, model name, on-device toggle. Settings
   persist; chat history survives rotation.

## Build
1. Open this folder in Android Studio (it creates the Gradle wrapper and installs the SDK),
   or build from CI.
2. Debug APK: Build > Build APK(s), or `./gradlew assembleDebug` (after `gradle wrapper`).
3. Or push the folder to a GitHub repo — the included workflow builds the APK automatically
   (Actions > latest run > download `app-debug-apk`).
   **Repo root must be this folder** (`settings.gradle.kts` and `app/` at the top level).

## On-device engine (llama.cpp)
The app bundles llama.cpp (pinned release `b10667`) for 64-bit ARM phones. The GitHub workflow downloads it
and builds it with the app, so there is nothing to install by hand. To build locally instead:
`git clone --depth 1 --branch b10667 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp`

- Models download inside the app (Host > Model, or Settings > Models). Replies stream in as they are written.
- The engine uses each model's own chat template, top-k / top-p / temperature sampling, and drops the oldest
  messages when a chat gets too long for the 2048-token window.
- Needs a phone with the Arm dot-product instructions (most phones since about 2018).

## Before publishing
- Change `applicationId` in `app/build.gradle.kts` (`com.example.aiapp` is a placeholder).
- Use HTTPS servers only (cleartext is disabled).
- Don't ship a real API key in the app — proxy through your own server for production.
- Add a privacy policy (required for Play Store).
- Enable minification/shrinking for release and test thoroughly.

## Notes
- Design mimics iOS (iMessage bubbles, iOS colors, dark mode) using Jetpack Compose.
  A true Apple-design app for iPhones would need a separate SwiftUI project; this one is Android-only.


## Host tab (Zonk-Core)
- Real readings: battery %, charging, battery temperature, Wi-Fi, phone RAM.
- Workload ring = share of the last 60 seconds spent answering requests.
- Model picker only enables sizes the phone has enough RAM for.
- Models download inside the app (Host > Model, or Settings > Models) over Wi-Fi: Qwen2.5 1.5B, Llama 3.2 3B, Qwen2.5 7B (all Q4_K_M GGUF from Hugging Face). They are saved in the app's own storage as `models/model-1.5b.gguf`, `model-3b.gguf`, `model-7b.gguf`.
- The Start/Stop sharing button is a placeholder until the relay server is built.

## Known limits
- The API key is saved in plain app storage (cloud backups are turned off). For production, keep keys on your own server.
- The C++ bridge targets the llama.cpp API of release b10667. If you move to a newer release and it fails to compile, adjust the function names.
- Sharing (relay) is not built yet: the Host screen's Start/Stop button is a placeholder.
