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

## On-device model (llama.cpp)
1. `git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp`
2. In `app/build.gradle.kts`, uncomment the `externalNativeBuild` block (and the
   `abiFilters` line above it).
3. Sync and build. The Kotlin side (`LlamaBackend.kt`) already calls the JNI bridge.
4. Push a small `.gguf` model (e.g. a 1–3B Q4 model) to the device:
   `adb push model.gguf /data/local/tmp/model.gguf` then copy it to the app's files dir,
   or add a download screen later. The app shows the expected path in Settings when
   "Run on device" is enabled.

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
- Models live at `files/models/model-1.5b.gguf`, `model-3b.gguf`, `model-7b.gguf`.
- The Start/Stop sharing button is a placeholder until the relay server is built.

## Known limits
- The API key is saved in plain app storage (cloud backups are turned off). For production, keep keys on your own server.
- The on-device engine (llama.cpp) is optional and only builds after you follow "On-device model" above. The C++ bridge targets the current llama.cpp API; if it fails to compile after a new llama.cpp release, adjust the function names.
- Sharing (relay) is not built yet: the Host screen's Start/Stop button is a placeholder.
