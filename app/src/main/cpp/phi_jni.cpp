// JNI surface for the Phi-3 inference engine.
//
// Every entry point takes an opaque handle created by nativeCreate(). Calls are
// expected to be serialized by the Kotlin layer; the handle registry only
// protects against use-after-free from a stale handle.

#include <jni.h>

#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama_engine.h"

#define LOG_TAG "Phi3Jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_registry_mutex;
std::unordered_map<jlong, std::unique_ptr<phi3::Engine>> g_engines;
jlong g_next_handle = 1;

phi3::Engine * lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    auto it = g_engines.find(handle);
    return it == g_engines.end() ? nullptr : it->second.get();
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// NewStringUTF needs modified UTF-8; llama.cpp emits standard UTF-8. The
// conversion lives in llama_engine so the host self-test can cover it.
jstring to_jstring(JNIEnv * env, const std::string & s) {
    const std::string encoded = phi3::to_modified_utf8(s);
    return env->NewStringUTF(encoded.c_str());
}

std::vector<phi3::ChatMessage> read_messages(JNIEnv * env,
                                             jobjectArray roles,
                                             jobjectArray contents) {
    std::vector<phi3::ChatMessage> messages;
    const jsize n = env->GetArrayLength(roles);
    messages.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        messages.push_back({to_string(env, role), to_string(env, content)});
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    return messages;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_phi3chat_native_NativeBridge_nativeCreate(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const jlong handle = g_next_handle++;
    g_engines.emplace(handle, std::make_unique<phi3::Engine>());
    return handle;
}

JNIEXPORT void JNICALL
Java_com_phi3chat_native_NativeBridge_nativeRelease(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    g_engines.erase(handle);
}

JNIEXPORT jstring JNICALL
Java_com_phi3chat_native_NativeBridge_nativeLoad(JNIEnv * env,
                                                 jobject,
                                                 jlong handle,
                                                 jstring model_path,
                                                 jint n_ctx,
                                                 jint n_threads,
                                                 jint n_batch,
                                                 jobject progress_listener) {
    phi3::Engine * engine = lookup(handle);
    if (engine == nullptr) return to_jstring(env, "Internal error: invalid engine handle.");

    const std::string path = to_string(env, model_path);

    jmethodID on_progress = nullptr;
    if (progress_listener != nullptr) {
        jclass cls = env->GetObjectClass(progress_listener);
        on_progress = env->GetMethodID(cls, "onProgress", "(F)Z");
        env->DeleteLocalRef(cls);
        if (on_progress == nullptr) {
            return to_jstring(env, "Internal error: LoadProgressListener.onProgress is missing.");
        }
    }

    phi3::ProgressFn progress;
    if (on_progress != nullptr) {
        progress = [env, progress_listener, on_progress](float fraction) -> bool {
            // The callback may allocate; bound the local ref use per invocation.
            env->PushLocalFrame(8);
            jboolean keep = env->CallBooleanMethod(progress_listener, on_progress,
                                                   static_cast<jfloat>(fraction));
            env->PopLocalFrame(nullptr);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                return false;
            }
            return keep == JNI_TRUE;
        };
    }

    std::string error;
    if (!engine->load(path, n_ctx, n_threads, n_batch, progress, error)) {
        return to_jstring(env, error.empty() ? "Unknown error while loading the model." : error);
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_phi3chat_native_NativeBridge_nativeUnload(JNIEnv *, jobject, jlong handle) {
    if (phi3::Engine * engine = lookup(handle)) engine->unload();
}

JNIEXPORT void JNICALL
Java_com_phi3chat_native_NativeBridge_nativeRequestStop(JNIEnv *, jobject, jlong handle) {
    if (phi3::Engine * engine = lookup(handle)) engine->request_stop();
}

JNIEXPORT jstring JNICALL
Java_com_phi3chat_native_NativeBridge_nativeFormatPrompt(JNIEnv * env,
                                                         jobject,
                                                         jlong handle,
                                                         jobjectArray roles,
                                                         jobjectArray contents,
                                                         jboolean add_assistant_header) {
    phi3::Engine * engine = lookup(handle);
    if (engine == nullptr) return nullptr;

    std::string error;
    std::string prompt = engine->format_prompt(read_messages(env, roles, contents),
                                              add_assistant_header == JNI_TRUE, error);
    if (prompt.empty() && !error.empty()) return nullptr;
    return to_jstring(env, prompt);
}

JNIEXPORT jlongArray JNICALL
Java_com_phi3chat_native_NativeBridge_nativeGenerate(JNIEnv * env,
                                                     jobject,
                                                     jlong handle,
                                                     jobjectArray roles,
                                                     jobjectArray contents,
                                                     jint max_tokens,
                                                     jfloat temperature,
                                                     jfloat top_p,
                                                     jint top_k,
                                                     jfloat min_p,
                                                     jfloat repeat_penalty,
                                                     jint repeat_last_n,
                                                     jint seed,
                                                     jobject token_listener) {
    phi3::Engine * engine = lookup(handle);
    if (engine == nullptr) return nullptr;

    jmethodID on_token = nullptr;
    if (token_listener != nullptr) {
        jclass cls = env->GetObjectClass(token_listener);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Z)V");
        env->DeleteLocalRef(cls);
        if (on_token == nullptr) {
            LOGE("TokenListener.onToken(String, boolean) not found");
            return nullptr;
        }
    }

    phi3::GenerationParams params;
    params.max_tokens     = max_tokens;
    params.temperature    = temperature;
    params.top_p          = top_p;
    params.top_k          = top_k;
    params.min_p          = min_p;
    params.repeat_penalty = repeat_penalty;
    params.repeat_last_n  = repeat_last_n;
    params.seed           = seed;

    phi3::TokenFn token_fn;
    if (on_token != nullptr) {
        token_fn = [env, token_listener, on_token](const std::string & text, bool is_final) {
            env->PushLocalFrame(8);
            jstring jtext = to_jstring(env, text);
            env->CallVoidMethod(token_listener, on_token, jtext,
                                is_final ? JNI_TRUE : JNI_FALSE);
            env->PopLocalFrame(nullptr);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        };
    }

    phi3::GenerationStats stats;
    std::string error;
    const bool ok = engine->generate(read_messages(env, roles, contents), params,
                                     token_fn, stats, error);
    if (!ok) {
        LOGE("generate failed: %s", error.c_str());
        return nullptr;
    }

    const jlong values[4] = {stats.prompt_tokens, stats.generated_tokens,
                             stats.prompt_ms, stats.generation_ms};
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_phi3chat_native_NativeBridge_nativeSystemInfo(JNIEnv * env, jobject, jlong handle) {
    phi3::Engine * engine = lookup(handle);
    if (engine == nullptr) return nullptr;
    return to_jstring(env, engine->system_info());
}

JNIEXPORT jlongArray JNICALL
Java_com_phi3chat_native_NativeBridge_nativeModelInfo(JNIEnv * env, jobject, jlong handle) {
    phi3::Engine * engine = lookup(handle);
    const jlong values[2] = {
        engine == nullptr ? 0 : engine->context_size(),
        engine == nullptr ? 0 : engine->model_size_bytes(),
    };
    jlongArray result = env->NewLongArray(2);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

}  // extern "C"
