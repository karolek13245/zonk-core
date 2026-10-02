// llama.cpp JNI bridge for Zonk-Core.
//  - applies the model's own chat template (Qwen/Llama/... all differ)
//  - samples with top-k / top-p / temperature
//  - streams every generated piece back to Kotlin (TokenSink.onToken)
//  - text crosses JNI as UTF-8 byte arrays (NewStringUTF breaks on emoji)
#include <jni.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

struct LlamaHandle {
    llama_model* model;
    llama_context* ctx;
};

static const int kMaxNew = 512;

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

// Build the prompt text for a list of messages using the model's chat template.
static std::string buildPrompt(const llama_model* model, const std::vector<llama_chat_message>& msgs) {
    std::string prompt;
    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) {
        size_t total = 0;
        for (const auto& m : msgs) total += strlen(m.content) + strlen(m.role) + 32;
        std::vector<char> buf(total * 2 + 1024);
        int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
        if (n > (int)buf.size()) {
            buf.resize((size_t)n + 16);
            n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
        }
        if (n > 0) prompt.assign(buf.data(), (size_t)n);
    }
    if (prompt.empty()) {   // no template (or an unsupported one): plain fallback
        for (const auto& m : msgs) {
            prompt += (strcmp(m.role, "user") == 0) ? "User: " : "Assistant: ";
            prompt += m.content;
            prompt += "\n";
        }
        prompt += "Assistant:";
    }
    return prompt;
}

static int tokenizePrompt(const llama_vocab* vocab, const std::string& prompt, std::vector<llama_token>& tokens) {
    // Templates like Llama 3 already write the BOS token as text.
    const bool addSpecial = prompt.rfind("<|begin_of_text|>", 0) != 0;
    tokens.assign(prompt.size() + 16, 0);
    int n = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), (int)tokens.size(), addSpecial, true);
    if (n < 0) {
        tokens.assign((size_t)(-n), 0);
        n = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), (int)tokens.size(), addSpecial, true);
    }
    if (n > 0) tokens.resize((size_t)n);
    return n;
}

// chat = role\0content\0role\0content\0 ...   (UTF-8)
// Returns null on success, or an error message.
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_aiapp_LlamaBackend_nativeChat(JNIEnv* env, jobject /* thiz */, jlong handle, jbyteArray chat, jobject sink) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return env->NewStringUTF("Error: model not loaded.");

    // 1) Split the byte blob into messages.
    jsize len = env->GetArrayLength(chat);
    std::string blob((size_t)len, '\0');
    if (len > 0) env->GetByteArrayRegion(chat, 0, len, reinterpret_cast<jbyte*>(&blob[0]));
    std::vector<std::string> parts;
    size_t start = 0;
    for (size_t i = 0; i < blob.size(); i++) {
        if (blob[i] == '\0') { parts.emplace_back(blob.substr(start, i - start)); start = i + 1; }
    }
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i + 1 < parts.size(); i += 2) msgs.push_back({parts[i].c_str(), parts[i + 1].c_str()});
    if (msgs.empty()) return env->NewStringUTF("Error: empty chat.");

    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    const int nCtx = (int)llama_n_ctx(h->ctx);
    const int maxPrompt = std::max(16, nCtx - kMaxNew);

    // 2) Build + tokenize; if the chat is too long, drop the oldest messages first.
    std::vector<llama_token> tokens;
    size_t first = 0;
    while (true) {
        std::vector<llama_chat_message> sub(msgs.begin() + (long)first, msgs.end());
        std::string prompt = buildPrompt(h->model, sub);
        int n = tokenizePrompt(vocab, prompt, tokens);
        if (n <= 0) return env->NewStringUTF("Error: could not read the chat.");
        if (n <= maxPrompt || first + 1 >= msgs.size()) break;
        first++;
    }
    if ((int)tokens.size() > maxPrompt) {   // a single huge message: keep its newest part
        std::vector<llama_token> cut;
        cut.push_back(tokens[0]);
        cut.insert(cut.end(), tokens.end() - (maxPrompt - 1), tokens.end());
        tokens.swap(cut);
    }

    // 3) Fresh cache every time (the app resends the whole chat).
    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass sinkCls = env->GetObjectClass(sink);
    jmethodID onToken = env->GetMethodID(sinkCls, "onToken", "([B)Z");

    // 4) Generate, streaming each piece to Kotlin.
    std::string err;
    llama_token next = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    for (int i = 0; i < kMaxNew; i++) {
        if (llama_decode(h->ctx, batch) != 0) {
            if (i == 0) err = "Error: the model could not read the chat. Try a shorter message.";
            break;
        }
        next = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;   // end of reply

        char piece[256];
        int plen = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (plen > 0 && onToken) {
            jbyteArray arr = env->NewByteArray(plen);
            env->SetByteArrayRegion(arr, 0, plen, reinterpret_cast<const jbyte*>(piece));
            jboolean cont = env->CallBooleanMethod(sink, onToken, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (!cont) break;                          // cancelled
        }
        batch = llama_batch_get_one(&next, 1);         // feed the new token back in
    }
    llama_sampler_free(smpl);
    if (err.empty()) return nullptr;
    return env->NewStringUTF(err.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiapp_LlamaBackend_nativeClose(JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}
