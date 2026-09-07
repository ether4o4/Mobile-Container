// llama_bridge.cpp - JNI bridge over llama.cpp for on-device GGUF inference.
// Exposes model load, streaming completion, model info, and free.
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <vector>
#include <string>
#include <atomic>
#include "llama.h"

#define TAG "MCLLAMA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int32_t n_ctx = 0;
};

static std::atomic<bool> g_backend_inited{false};

extern "C" {

static void ensure_backend() {
    if (!g_backend_inited.exchange(true)) {
        llama_backend_init();
        LOGI("llama backend initialized; %s", llama_print_system_info());
    }
}

JNIEXPORT jlong JNICALL
Java_com_ether4o4_mobilecontainer_inference_LlamaBridge_nativeLoadModel(
    JNIEnv *env, jclass, jstring jpath, jint jgpu, jint jctx, jint jthreads, jint jbatch) {

    ensure_backend();

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = jgpu;
    mp.use_mmap = true;
    mp.use_mlock = false;

    llama_model *model = llama_load_model_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)jctx;
    cp.n_batch = (uint32_t)jbatch;
    cp.n_threads = (uint32_t)(jthreads > 0 ? jthreads : 4);
    cp.n_threads_batch = cp.n_threads;
    cp.logits_all = false;
    cp.embeddings = false;

    llama_context *ctx = llama_new_context_with_model(model, cp);
    if (!ctx) {
        llama_free_model(model);
        LOGE("failed to create context");
        return 0;
    }

    LlamaHandle *h = new LlamaHandle();
    h->model = model;
    h->ctx = ctx;
    h->n_ctx = (int32_t)llama_n_ctx(ctx);
    LOGI("model loaded, n_ctx=%d", h->n_ctx);
    return (jlong)(intptr_t)h;
}

JNIEXPORT jstring JNICALL
Java_com_ether4o4_mobilecontainer_inference_LlamaBridge_nativeModelInfo(
    JNIEnv *env, jclass, jlong handle) {
    LlamaHandle *h = (LlamaHandle *)(intptr_t)handle;
    if (!h || !h->model) return env->NewStringUTF("");
    char buf[512];
    llama_model_desc(h->model, buf, sizeof(buf));
    uint64_t size = llama_model_size(h->model);
    uint64_t nparams = llama_model_n_params(h->model);
    char out[768];
    snprintf(out, sizeof(out), "%s | size=%.1fMB | params=%.1fM | n_ctx=%d",
             buf, size / 1048576.0, nparams / 1e6, h->n_ctx);
    return env->NewStringUTF(out);
}

// nativeGenerate(handle, prompt, temp, topK, topP, repeatPenalty, maxTokens, callback)
// callback is a Java method reference: void onToken(String piece)
JNIEXPORT void JNICALL
Java_com_ether4o4_mobilecontainer_inference_LlamaBridge_nativeGenerate(
    JNIEnv *env, jclass, jlong handle, jstring jprompt,
    jfloat temp, jint topK, jfloat topP, jfloat repeatPenalty,
    jint maxTokens, jobject callback) {

    LlamaHandle *h = (LlamaHandle *)(intptr_t)handle;
    if (!h || !h->model || !h->ctx) return;

    const char *pcstr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(pcstr ? pcstr : "");
    env->ReleaseStringUTFChars(jprompt, pcstr);

    // tokenize prompt
    std::vector<llama_token> prompt_tokens(prompt.size() + 16);
    int n_prompt = llama_tokenize(h->model, prompt.c_str(), (int32_t)prompt.size(),
                                  prompt_tokens.data(), (int32_t)prompt_tokens.size(),
                                  true, true);
    if (n_prompt < 0) {
        prompt_tokens.resize(-n_prompt);
        n_prompt = llama_tokenize(h->model, prompt.c_str(), (int32_t)prompt.size(),
                                  prompt_tokens.data(), (int32_t)prompt_tokens.size(),
                                  true, true);
    }
    if (n_prompt <= 0) {
        LOGE("empty prompt tokens");
        return;
    }
    prompt_tokens.resize(n_prompt);

    // reset KV cache
    llama_kv_cache_seq_rm(h->ctx, 0, (llama_pos)0, (llama_pos)-1);

    // feed prompt in batches
    int32_t n_batch = 512;
    llama_token eos = llama_token_eos(h->model);
    int32_t n_vocab = llama_n_vocab(h->model);

    // decode prompt
    for (int i = 0; i < n_prompt; i += n_batch) {
        int n = std::min(n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + i, n, i, 0);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("prompt decode failed at %d", i);
            return;
        }
    }

    // get callback class/method
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID isCancelled = env->GetMethodID(cbClass, "isCancelled", "()Z");

    auto emit = [&](const std::string &piece) {
        if (!onToken) return;
        jstring js = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, onToken, js);
        env->DeleteLocalRef(js);
    };

    // generation loop
    std::vector<llama_token> generated;
    generated.reserve(maxTokens > 0 ? maxTokens : 256);
    llama_token last_token = prompt_tokens.back();
    int n_decoded = 0;
    int limit = (maxTokens > 0) ? maxTokens : 256;

    std::vector<llama_token_data> candidates;
    candidates.resize(n_vocab);

    while (n_decoded < limit) {
        if (isCancelled && env->CallBooleanMethod(callback, isCancelled) == JNI_TRUE) {
            break;
        }
        // decode last token
        llama_batch batch = llama_batch_get_one(&last_token, 1, n_prompt + n_decoded, 0);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("decode failed at step %d", n_decoded);
            break;
        }

        float *logits = llama_get_logits_ith(h->ctx, -1);
        if (!logits) break;

        for (int32_t t = 0; t < n_vocab; t++) {
            candidates[t] = {t, logits[t], 0.0f};
        }
        llama_token_data_array cands = {candidates.data(), (size_t)n_vocab, false};

        // apply repeat penalty
        if (repeatPenalty > 1.0f && !generated.empty()) {
            llama_sample_repetition_penalties(h->ctx, &cands,
                generated.data(), generated.size(), repeatPenalty, 0.0f, 0.0f);
        }
        if (topK > 0) llama_sample_top_k(h->ctx, &cands, topK, 1);
        if (topP < 1.0f) llama_sample_top_p(h->ctx, &cands, topP, 1);
        if (temp > 0.0f) {
            llama_sample_temp(h->ctx, &cands, temp);
            last_token = llama_sample_token(h->ctx, &cands);
        } else {
            last_token = llama_sample_token_greedy(h->ctx, &cands);
        }

        if (last_token == eos) break;

        // convert to piece
        char buf[64];
        int n = llama_token_to_piece(h->model, last_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            emit(std::string(buf, n));
        }
        generated.push_back(last_token);
        n_decoded++;
    }

    // signal completion
    jmethodID onDone = env->GetMethodID(cbClass, "onDone", "()V");
    if (onDone) env->CallVoidMethod(callback, onDone);
}

JNIEXPORT void JNICALL
Java_com_ether4o4_mobilecontainer_inference_LlamaBridge_nativeFreeModel(
    JNIEnv *env, jclass, jlong handle) {
    LlamaHandle *h = (LlamaHandle *)(intptr_t)handle;
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_free_model(h->model);
    delete h;
}

JNIEXPORT jboolean JNICALL
Java_com_ether4o4_mobilecontainer_inference_LlamaBridge_nativeSupportsGpu(
    JNIEnv *env, jclass) {
    return llama_supports_gpu_offload() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
