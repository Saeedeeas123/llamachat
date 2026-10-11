// llama_jni.cpp — the Android adapter over llama_core.
//
// This file holds no inference logic: everything of substance lives in llama_core.cpp so
// that the host probe (native/linux_probe.cpp) can exercise the code that actually ships.
// All that happens here is marshalling between Java values and the core API.

#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama_core.h"

namespace {

// Optional durable log file. llama.cpp's own warnings and this layer's messages are the
// first thing anyone needs when an engine misbehaves on a device, and logcat rotates far too
// fast on a busy phone to rely on. Set from Java via nativeSetLogFile.
std::mutex g_log_file_mu;
std::FILE * g_log_file = nullptr;

void android_log_sink(int level, const char * text) {
    int priority = ANDROID_LOG_INFO;
    if (level >= llamacpp::LOG_LEVEL_ERROR) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == llamacpp::LOG_LEVEL_WARN) {
        priority = ANDROID_LOG_WARN;
    } else if (level == llamacpp::LOG_LEVEL_DEBUG) {
        priority = ANDROID_LOG_DEBUG;
    }
    __android_log_print(priority, "LlamaJNI", "%s", text);

    std::lock_guard<std::mutex> lock(g_log_file_mu);
    if (g_log_file != nullptr) {
        const char * tag = (priority == ANDROID_LOG_ERROR) ? "E"
                         : (priority == ANDROID_LOG_WARN)  ? "W"
                         : (priority == ANDROID_LOG_DEBUG) ? "D" : "I";
        std::fprintf(g_log_file, "[%s] %s", tag, text);
        if (text[0] != '\0' && text[std::strlen(text) - 1] != '\n') {
            std::fputc('\n', g_log_file);
        }
        std::fflush(g_log_file);
    }
}

// llama.cpp's logging is global state, so the sink is installed exactly once.
void install_log_sink_once() {
    static bool installed = false;
    if (!installed) {
        llamacpp::set_log_sink(android_log_sink);
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
// rather than NewStringUTF because modified UTF-8 cannot carry 4-byte sequences (emoji),
// which chat models produce routinely.
//
// NOTE ON THE SIGNATURE: it must be "([BLjava/nio/charset/Charset;)V". The neighbouring
// overload "([BLjava/lang/String;)V" takes a charset *name*; passing a Charset object to it
// makes the VM read a String reference that is not one, and it dies with a NullPointerException
// inside String.lookupCharset. That mistake only shows up when the reply is turned into a
// Java string — i.e. at the very end of a generation, after the streaming already looked fine.
jstring utf8_to_jstring(JNIEnv * env, const std::string & s) {
    static jclass    string_cls = nullptr;
    static jmethodID ctor       = nullptr;
    static jobject   utf8_name  = nullptr;

    if (string_cls == nullptr) {
        jclass local_string = env->FindClass("java/lang/String");
        jclass local_charsets = env->FindClass("java/nio/charset/StandardCharsets");
        if (local_string == nullptr || local_charsets == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }

        jmethodID local_ctor =
            env->GetMethodID(local_string, "<init>", "([BLjava/nio/charset/Charset;)V");
        jfieldID utf8_field =
            env->GetStaticFieldID(local_charsets, "UTF_8", "Ljava/nio/charset/Charset;");
        if (local_ctor == nullptr || utf8_field == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(local_string);
            env->DeleteLocalRef(local_charsets);
            return nullptr;
        }

        jobject local_utf8 = env->GetStaticObjectField(local_charsets, utf8_field);
        if (local_utf8 == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(local_string);
            env->DeleteLocalRef(local_charsets);
            return nullptr;
        }

        // Cache only once every lookup has succeeded, so a failure cannot leave a
        // half-initialised cache behind that makes every later call fail the same way.
        ctor       = local_ctor;
        utf8_name  = env->NewGlobalRef(local_utf8);
        string_cls = static_cast<jclass>(env->NewGlobalRef(local_string));

        env->DeleteLocalRef(local_utf8);
        env->DeleteLocalRef(local_charsets);
        env->DeleteLocalRef(local_string);
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
Java_com_saeed_llamacpp_LlamaCppBridge_nativeVersion(JNIEnv * env, jclass) {
    install_log_sink_once();
    return env->NewStringUTF(llamacpp::version().c_str());
}

// Redirects llama.cpp's own logging (and this layer's messages) into a file, in addition to
// logcat. Pass null to stop. Needed because logcat rotates within minutes on a busy device,
// which leaves nothing behind when an engine problem has to be diagnosed after the fact.
JNIEXPORT void JNICALL
Java_com_saeed_llamacpp_LlamaCppBridge_nativeSetLogFile(JNIEnv * env, jclass, jstring j_path) {
    install_log_sink_once();

    std::lock_guard<std::mutex> lock(g_log_file_mu);
    if (g_log_file != nullptr) {
        std::fclose(g_log_file);
        g_log_file = nullptr;
    }
    if (j_path == nullptr) {
        return;
    }
    const std::string path = to_std_string(env, j_path);
    if (!path.empty()) {
        g_log_file = std::fopen(path.c_str(), "a");
    }
}

JNIEXPORT jlong JNICALL
Java_com_saeed_llamacpp_LlamaCppBridge_nativeLoadModel(JNIEnv * env, jclass,
                                                          jstring j_path,
                                                          jint n_ctx,
                                                          jint n_threads,
                                                          jint n_gpu_layers) {
    install_log_sink_once();

    const std::string path = to_std_string(env, j_path);
    std::string error;
    llamacpp::Session * session = llamacpp::load(path, n_ctx, n_threads, n_gpu_layers, error);
    if (session == nullptr) {
        throw_runtime(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_saeed_llamacpp_LlamaCppBridge_nativeDescribe(JNIEnv * env, jclass, jlong handle) {
    llamacpp::Session * session = reinterpret_cast<llamacpp::Session *>(handle);
    if (session == nullptr) return nullptr;
    return env->NewStringUTF(llamacpp::describe(session).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_saeed_llamacpp_LlamaCppBridge_nativeChat(JNIEnv * env, jclass,
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
    llamacpp::Session * session = reinterpret_cast<llamacpp::Session *>(handle);
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

    llamacpp::GenParams params;
    params.max_tokens     = max_tokens;
    params.temperature    = temp;
    params.top_k          = top_k;
    params.top_p          = top_p;
    params.repeat_penalty = repeat_penalty;
    params.repeat_last_n  = repeat_last_n;
    params.seed           = seed;

    std::string out;
    std::string error;
    const bool ok = llamacpp::generate(session, roles, contents, params,
                                        sink != nullptr ? forward_token : nullptr,
                                        &ctx, out, error, nullptr);
    if (!ok) {
        throw_runtime(env, error);
        return nullptr;
    }
    return utf8_to_jstring(env, out);
}

JNIEXPORT void JNICALL
Java_com_saeed_llamacpp_LlamaCppBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    llamacpp::release(reinterpret_cast<llamacpp::Session *>(handle));
}

}  // extern "C"