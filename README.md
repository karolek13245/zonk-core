# Zonk-Core

**Run AI models on your phone. Share them through your own server.**

Zonk-Core is a free, open-source Android app with an iOS-style design. You download a model once, then chat with it
on your phone with no account and no cloud. The AI engine is [llama.cpp](https://github.com/ggml-org/llama.cpp),
built into the app.

[**Download the latest APK**](https://github.com/karolek13245/zonk-core/releases/latest) · [Terms](TERMS.md) · [Report a problem](https://github.com/karolek13245/zonk-core/issues)

> **Status:** version 1.3. Chatting on the phone works. Sharing a phone's model with other people is still in development.

---

## What it does

- **Chat on the phone.** Models run on the phone's own chip and answer offline once downloaded. Replies appear as they are written.
- **Pictures.** Send a photo and the AI explains what is in it. This needs the small Vision model (see below) and works on the phone only.
- **Code boxes.** Code in a reply appears in a black box with a **Copy** button and a **Download** button.
- **Token total.** The chat shows how many tokens it uses so far, above the message box. You can switch it off in Settings.
- **Chat history.** Start a new chat, and archive or delete old ones. Chats are saved only on your phone.
- **Model catalog.** Download models inside the app over Wi-Fi, with a search box and category filters.
- **Cloud mode.** Point the app at any OpenAI-compatible server instead of using the phone's model.
- **Host tab.** Live battery, temperature, Wi-Fi and memory readings, a workload ring, and a model picker that only offers sizes your phone can run.
- **Clear AI notice.** Every chat shows "(model) is AI and can make mistakes."

## Models

The text models are 4-bit (Q4_K_M) GGUF files from Hugging Face. The Vision model is 8-bit and comes as two files (the model and its picture reader). They belong to their makers and have their own licenses.

| Model | Size | Good for | License | Memory needed (approx.) |
|---|---|---|---|---|
| Qwen2.5 1.5B Instruct | 0.99 GB | everyday chat, fastest | Apache 2.0 | 2.5 GB |
| SmolLM2 1.7B Instruct | 1.06 GB | everyday chat | Apache 2.0 | 2.6 GB |
| Llama 3.2 3B Instruct | 2.02 GB | better answers | Llama 3.2 Community License | 4 GB |
| Qwen2.5 Coder 3B Instruct | 2.01 GB | code | Apache 2.0 | 4 GB |
| Qwen2.5 7B Instruct | 4.68 GB | best answers, needs a strong phone | Apache 2.0 | 8 GB |
| SmolVLM 500M (reads pictures) | 0.55 GB (two files) | explaining pictures | Apache 2.0 | 2 GB |

Check each model's page on Hugging Face before using it commercially.

### Sending pictures

1. In **Settings → Models**, download **SmolVLM 500M (reads pictures)**.
2. On the **Host** tab, pick it as your model, and turn on **Run on device** in Settings.
3. In **Chat**, tap the picture button next to the message box, choose a photo, and ask about it (or just send it).

Pictures are shrunk and kept only on your phone, and are deleted with their chat. Pictures are not sent in Cloud mode.
Reading a picture takes a while on a phone, and a small model can get details wrong.

## Install

1. Open the [latest release](https://github.com/karolek13245/zonk-core/releases/latest) on your phone.
2. Download `app-debug.apk` and open it. Allow installing from your browser if Android asks.
3. Open Zonk-Core, follow the short walkthrough, and download a model from the Host tab or Settings.

**You need:** a 64-bit ARM Android phone (almost every phone since about 2018), Android 8.0 or newer, and free storage for the model.
The app downloads models over Wi-Fi only.

## How sharing will work (in development)

The goal is that your phone runs a model and answers requests from other people through a small relay server:

```
Other user's app  ──►  relay (Cloudflare Worker)  ──►  your phone  ──►  answer back
```

Phones connect out to the relay, so no router setup is needed. The relay lives in a separate repo, `zonk-relay`, and is
deployed but not yet connected to the app. The Start button on the Host tab is a placeholder for now.

## Privacy

- On-device chat is processed on your phone and not sent anywhere.
- Cloud mode sends your messages to the server address you set.
- Chat history is stored only on your phone.
- Full details are in the [Terms](TERMS.md).

## Build it yourself

The easiest way is GitHub Actions: push this repo and the included workflow builds the APK
(Actions → latest run → download `app-debug-apk`). The repo root must contain `settings.gradle.kts` and `app/`.

To build locally with Android Studio:

```
git clone --depth 1 --branch b10667 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
./gradlew assembleDebug
```

The C++ bridge targets llama.cpp release `b10667`. If you move to a newer release and it fails to compile, the function names may have changed.

## Project layout

```
app/src/main/java/com/example/aiapp/
  MainActivity.kt        tabs, chat, history logic
  ChatStore.kt           saves chats to a file
  ChatHistoryScreen.kt   the Chats screen
  HostScreen.kt          device readings and workload
  SettingsScreen.kt      server, models, About
  AboutScreen.kt         About card and Terms screen
  CodeBlocks.kt          black code boxes with Copy and Download
  ImageUtils.kt          shrinks and stores pictures
  Models.kt              model catalog and downloads
  LlamaBackend.kt        on-device engine (Kotlin side)
  Backend.kt             cloud server client
app/src/main/cpp/        llama.cpp bridge (C++)
```

## Known limits

- The API key is stored as plain text in the app's private storage. For real use, keep keys on your own server.
- Models answer from a 2048-token window (4096 for the Vision model), so very long chats drop their oldest messages.
- The Vision model looks at the newest picture in a chat only, and drops older messages before it.
- Sharing (the relay) is not connected yet.
- Before a Play Store release: change `applicationId` in `app/build.gradle.kts`, add a privacy policy, and turn on release shrinking.
- No license file has been added to this repo yet.

## Contact

Questions and ideas: [open an issue](https://github.com/karolek13245/zonk-core/issues).
