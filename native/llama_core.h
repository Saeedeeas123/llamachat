// llama_core.h — the inference logic, with no JNI in sight.
//
// WHY THIS LAYER EXISTS
// ---------------------
// The app runs on Android, but an APK cannot be installed by the agent on this device, so
// a build that merely compiles proves very little about the inference path. By keeping the
// session and generation logic free of JNI, the exact same translation unit can be linked
// into a host (glibc) probe and run against a real GGUF. That is how the chat-template
// rendering, tokenisation, sampling loop and end-of-generation handling are verified
// before the APK is shipped.
//
// llama_jni.cpp is a thin adapter over this API; linux_probe.cpp is the verification
// harness over the same API.

#pragma once

#include <string>
#include <vector>

namespace llamacpp {

/** Opaque model + context session. */
struct Session;

/** Sampling parameters for one generation. */
struct GenParams {
    int       max_tokens     = 256;
    float     temperature    = 0.8f;
    int       top_k          = 40;
    float     top_p          = 0.95f;
    float     repeat_penalty = 1.1f;
    int       repeat_last_n  = 64;
    long long seed           = -1;  // negative = random seed
};

/**
 * Called once per generated token with its raw UTF-8 bytes.
 * Return false to stop generation (this is how the UI's Stop button works).
 */
using TokenCallback = bool (*)(const char * utf8, int len, void * user_data);

/** Log levels, mirroring ggml's ordering. */
enum LogLevel { LOG_LEVEL_DEBUG = 0, LOG_LEVEL_INFO = 1, LOG_LEVEL_WARN = 2, LOG_LEVEL_ERROR = 3 };

/** Routes llama.cpp's own logging. Pass nullptr to restore the stderr default. */
void set_log_sink(void (*sink)(int level, const char * text));

/**
 * Loads a GGUF model and creates its context.
 * @return a session, or nullptr with [error] filled in.
 */
Session * load(const std::string & path,
               int n_ctx,
               int n_threads,
               int n_gpu_layers,
               std::string & error);

/** Short human-readable description of a loaded session. */
std::string describe(Session * session);

/**
 * Renders [roles]/[contents] through the model's chat template, prefills the prompt and
 * generates up to params.max_tokens tokens.
 *
 * The session is treated as stateless between calls: its cache is cleared and the whole
 * conversation is re-rendered every time, so [out] is always a complete assistant turn.
 *
 * @param prompt_out optional; receives the rendered prompt (used by the verification probe).
 * @return false with [error] filled in on failure.
 */
bool generate(Session * session,
              const std::vector<std::string> & roles,
              const std::vector<std::string> & contents,
              const GenParams & params,
              TokenCallback callback,
              void * user_data,
              std::string & out,
              std::string & error,
              std::string * prompt_out = nullptr);

/** Releases the model, the context and all associated memory. */
void release(Session * session);

/** The llama.cpp build string this library was compiled from. */
std::string version();

}  // namespace llamacpp