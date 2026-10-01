// Minimal llama.cpp JNI bridge for Zonk-Core.
// Compact greedy-decoding example. Strings cross the JNI boundary as UTF-8
// byte arrays (NewStringUTF only handles "modified" UTF-8 and breaks on emoji).
#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"

struct LlamaHandle {
    llama_model* model;
    llama_context* ctx;
};

static jbyteArray toBytes(JNIEnv* env, const std::string& s) {
    jbyteArray arr = env->NewByteArray((jsize)s.size());
    if (arr) env->SetByteArrayRegion(arr, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_aiapp_LlamaBackend_nativeInit(JNIEnv* env, jobject /* thiz */, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!model) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) { llama_model_free(model); return 0; }
    return reinterpret_cast<jlong>(new LlamaHandle{model, ctx});
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_aiapp_LlamaBackend_nativeComplete(JNIEnv* env, jobject /* thiz */, jlong handle, jbyteArray prompt) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return toBytes(env, "Error: model not loaded.");

    jsize plen = env->GetArrayLength(prompt);
    std::string text((size_t)plen, '\0');
    if (plen > 0) env->GetByteArrayRegion(prompt, 0, plen, reinterpret_cast<jbyte*>(&text[0]));

    const llama_vocab* vocab = llama_model_get_vocab(h->model);

    // The app resends the whole chat every time, so start from an empty cache.
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // Tokenize (grow buffer if needed).
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                           tokens.data(), (int)tokens.size(), true, false);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                           tokens.data(), (int)tokens.size(), true, false);
    }
    if (n <= 0) return toBytes(env, "Error: could not tokenize prompt.");
    tokens.resize(n);

    // If the chat is longer than the context window, keep the first token and the newest part.
    const int maxNew = 512;
    const int nCtx = (int)llama_n_ctx(h->ctx);
    const int maxPrompt = std::max(2, nCtx - maxNew);
    if ((int)tokens.size() > maxPrompt) {
        std::vector<llama_token> cut;
        cut.push_back(tokens[0]);
        cut.insert(cut.end(), tokens.end() - (maxPrompt - 1), tokens.end());
        tokens.swap(cut);
    }

    std::string out;
    const int nVocab = llama_vocab_n_tokens(vocab);
    llama_token next = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    for (int i = 0; i < maxNew; i++) {
        if (llama_decode(h->ctx, batch) != 0) break;

        float* logits = llama_get_logits_ith(h->ctx, -1);
        if (!logits) break;
        next = 0;
        float best = logits[0];
        for (int t = 1; t < nVocab; t++) {
            if (logits[t] > best) { best = logits[t]; next = t; }
        }
        if (llama_vocab_is_eog(vocab, next)) break;   // end of reply (EOS, <|im_end|>, ...)

        char piece[128];
        int len = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);

        batch = llama_batch_get_one(&next, 1);        // feed the new token back in
    }
    return toBytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiapp_LlamaBackend_nativeClose(JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}
