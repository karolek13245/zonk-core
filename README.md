# AI App (Android, Kotlin + Compose)

Chat app with two backends: any OpenAI-compatible server (cloud) or an on-device stub.

## Build
1. Open this folder in Android Studio (it creates the Gradle wrapper and installs the SDK).
2. Run on a phone/emulator, or build an APK:
   - Debug (installable for testing): Build > Build APK(s), or `./gradlew assembleDebug`
   - Release: Build > Generate Signed Bundle / APK (needed for Play Store and for a shareable release APK)
3. In the app: Settings > enter your server URL, key, and model name.

## Before going worldwide
- Change `applicationId` in app/build.gradle.kts (com.example.aiapp is a placeholder).
- Use HTTPS servers only (cleartext is disabled).
- Add translations in res/values-xx/strings.xml and a privacy policy (Play Store requires one).
- For on-device models: implement OnDeviceBackend with llama.cpp / MLC / ONNX Runtime.

## Build an APK without installing anything
Push this folder to a GitHub repo. The included workflow (.github/workflows/build.yml) builds the APK automatically.
Open the repo's Actions tab > latest run > download the `app-debug-apk` artifact.
