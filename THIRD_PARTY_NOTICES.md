# Third-party notices

Zonk-Core's own code is under the MIT License (see [LICENSE](LICENSE)). It is built with the software below, which keeps
its own license. This list covers the app's direct building blocks, not every file inside them. Not legal advice.

## llama.cpp and ggml (the AI engine, built into the app)

https://github.com/ggml-org/llama.cpp, MIT License.
llama.cpp also contains a few small libraries of its own, each with its own permissive license; see its repository.

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Android Jetpack and Jetpack Compose (AndroidX)

Compose UI, Material 3, animation, Material icons, Activity and Core KTX.
Copyright The Android Open Source Project. Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
https://developer.android.com/jetpack/androidx

## Kotlin and kotlinx.coroutines

Copyright JetBrains s.r.o. and Kotlin contributors. Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines

## AI models (not part of this repo)

The app downloads models from Hugging Face when you ask it to. Each model keeps its maker's license. The licenses are
listed in the README and in the app (Settings > About > Terms of Service). Check a model's license before you use its
output commercially or share the model.
