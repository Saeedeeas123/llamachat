// llama_jni.cpp — the Android adapter over llama_core.
//
// This file holds no inference logic: everything of substance lives in llama_core.cpp so
// that the host probe (native/linux_probe.cpp) can exercise the code that actually ships.
// All that happens here is marshalling between Java values and the core API.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama_core.h"

namespace {

void android_log_sink(int level, const char * text) {
    int priority = ANDROID_LOG_INFO;
    if (level >= llamachat::LOG_LEVEL_ERROR) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == llamachat::LOG_LEVEL_WARN) {
        priority = ANDROID_LOG_WARN;
    } else if (level == llamachat::LOG_LEVEL_DEBUG) {
        priority = ANDROID_LOG_DEBUG;
    }
    __android_log_print(priority, "LlamaJNI", "%s", text);
}

// llama.cpp's logging is global state, so the sink is installed exactly once.
void install_log_sink_once() {
    static bool installed = false;
    if (!installed) {
        llamachat::set_log_sink(android_log_sink);
        installed = true;
    }
}

std::string to_std_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out = (chars != nullptr) ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Builds a java.lang.String from UTF-8 bytes. The (byte[], Charset) constructor is used
// instead of NewStringUTF because modified UTF-8 cannot carry 4-byte sequences (emoji),
// which chat models produce routinely.
jstring utf8_to_jstring(JNIEnv * env, const std::string & s) {
    static jclass    string_cls = nullptr;
    static jmethodID ctor       = nullptr;
    static jstring   utf8_name  = nullptr;

    if (string_cls == nullptr) {
        jclass local = env->FindClass("java/lang/String");
        if (local == nullptr) return nullptr;
        string_cls = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        ctor = env->GetMethodID(string_cls, "<init>", "([BLjava/lang/String;)V");

        jclass cs_cls = env->FindClass("java/nio/charset/StandardCharsets");
        jfieldID field = env->GetStaticFieldID(cs_cls, "UTF_8", "Ljava/nio/charset/Charset;");
        jstring name = static_cast<jstring>(env->GetStaticObjectField(cs_cls, field));
        utf8_name = static_cast<jstring>(env->NewGlobalRef(name));
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(cs_cls);
    }

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte *>(s.data()));
    jstring out = static_cast<jstring>(env->NewObject(string_cls, ctor, bytes, utf8_name));
    env->DeleteLocalRef(bytes);
    return out;
}

struct SinkContext {
    JNIEnv *  env = nullptr;
    jobject   sink = nullptr;
    jmethodID on_token = nullptr;
};

// Bridges the core's token callback to the Kotlin TokenSink. Returning false stops
// generation, which is what the UI's Stop button asks for.
bool forward_token(const char * utf8, int len, void * user_data) {
    SinkContext * ctx = static_cast<SinkContext *>(user_data);
    if (ctx == nullptr || ctx->on_token == nullptr) return true;

    jbyteArray bytes = ctx->env->NewByteArray(len);
    if (bytes == nullptr) return true;
    ctx->env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(utf8));
    const jboolean cancel = ctx->env->CallBooleanMethod(ctx->sink, ctx->on_token, bytes);
    ctx->env->DeleteLocalRef(bytes);

    if (ctx->env->ExceptionCheck()) {
        // A throwing sink must not unwind through native code.
        ctx->env->ExceptionClear();
        return false;
    }
    return cancel != JNI_TRUE;
}

void throw_runtime(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_saeed_llamachat_core_LlamaBridge_nativeVersion(JNIEnv * env, jclass) {
    install_log_sink_once();
    return env->NewStringUTF(llamachat::version().c_str());
}

JNIEXPORT jlong JNICALL
Java_com_saeed_llamachat_core_LlamaBridge_nativeLoadModel(JNIEnv * env, jclass,
                                                          jstring j_path,
                                                          jint n_ctx,
                                                          jint n_threads,
                                                          jint n_gpu_layers) {
    install_log_sink_once();

    const std::string path = to_std_string(env, j_path);
    std::string error;
    llamachat::Session * session = llamachat::load(path, n_ctx, n_threads, n_gpu_layers, error);
    if (session == nullptr) {
        throw_runtime(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_saeed_llamachat_core_LlamaBridge_nativeDescribe(JNIEnv * env, jclass, jlong handle) {
    llamachat::Session * session = reinterpret_cast<llamachat::Session *>(handle);
    if (session == nullptr) return nullptr;
    return env->NewStringUTF(llamachat::describe(session).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_saeed_llamachat_core_LlamaBridge_nativeChat(JNIEnv * env, jclass,
                                                     jlong handle,
                                                     jobjectArray j_roles,
                                                     jobjectArray j_contents,
                                                     jint max_tokens,
                                                     jfloat temp,
                                                     jint top_k,
                                                     jfloat top_p,
                                                     jfloat repeat_penalty,
                                                     jint repeat_last_n,
                                                     jlong seed,
                                                     jobject sink) {
    llamachat::Session * session = reinterpret_cast<llamachat::Session *>(handle);
    if (session == nullptr) {
        throw_runtime(env, "no model is loaded");
        return nullptr;
    }

    const jsize n_msg = (j_roles != nullptr) ? env->GetArrayLength(j_roles) : 0;
    std::vector<std::string> roles(n_msg), contents(n_msg);
    for (jsize i = 0; i < n_msg; ++i) {
        jstring jr = static_cast<jstring>(env->GetObjectArrayElement(j_roles, i));
        jstring jc = static_cast<jstring>(env->GetObjectArrayElement(j_contents, i));
        roles[i]    = to_std_string(env, jr);
        contents[i] = to_std_string(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }

    SinkContext ctx;
    ctx.env = env;
    ctx.sink = sink;
    if (sink != nullptr) {
        jclass sink_cls = env->GetObjectClass(sink);
        ctx.on_token = env->GetMethodID(sink_cls, "onToken", "([B)Z");
        env->DeleteLocalRef(sink_cls);
        if (ctx.on_token == nullptr) {
            // No streaming method: carry on and return the full text at the end.
            env->ExceptionClear();
            ctx.sink = nullptr;
        }
    }

    llamachat::GenParams params;
    params.max_tokens     = max_tokens;
    params.temperature    = temp;
    params.top_k          = top_k;
    params.top_p          = top_p;
    params.repeat_penalty = repeat_penalty;
    params.repeat_last_n  = repeat_last_n;
    params.seed           = seed;

    std::string out;
    std::string error;
    const bool ok = llamachat::generate(session, roles, contents, params,
                                        sink != nullptr ? forward_token : nullptr,
                                        &ctx, out, error, nullptr);
    if (!ok) {
        throw_runtime(env, error);
        return nullptr;
    }
    return utf8_to_jstring(env, out);
}

JNIEXPORT void JNICALL
Java_com_saeed_llamachat_core_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    llamachat::release(reinterpret_cast<llamachat::Session *>(handle));
}

}  // extern "C"