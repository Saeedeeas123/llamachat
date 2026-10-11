// llama_core.cpp — session lifecycle and the generation loop. No JNI, no Android headers:
// this file is compiled unchanged for both Android (into libllama_jni.so) and the host
// (into the verification probe).

#include "llama_core.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace llamacpp {

namespace {

struct SessionImpl {
    llama_model       * model = nullptr;
    llama_context     * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    int                 n_ctx = 0;
    int                 n_threads = 0;
    // llama contexts are not thread-safe: one generation at a time per session.
    std::mutex          mu;
};

std::mutex      g_backend_mu;
bool            g_backend_ready = false;
void          (*g_log_sink)(int, const char *) = nullptr;

int map_level(ggml_log_level level) {
    if (level >= GGML_LOG_LEVEL_ERROR) return LOG_LEVEL_ERROR;
    if (level == GGML_LOG_LEVEL_WARN)  return LOG_LEVEL_WARN;
    if (level == GGML_LOG_LEVEL_INFO)  return LOG_LEVEL_INFO;
    return LOG_LEVEL_DEBUG;
}

void log_router(ggml_log_level level, const char * text, void *) {
    const int mapped = map_level(level);
    if (g_log_sink != nullptr) {
        g_log_sink(mapped, text);
    } else if (mapped >= LOG_LEVEL_WARN) {
        std::fputs(text, stderr);
    }
}

/** Emits one of this layer's own messages through the same route as llama.cpp's. */
void log_message(int level, const char * text) {
    if (g_log_sink != nullptr) {
        g_log_sink(level, text);
    } else if (level >= LOG_LEVEL_WARN) {
        std::fputs(text, stderr);
        std::fputc('\n', stderr);
    }
}

const char * load_mode_name(LoadMode mode) {
    switch (mode) {
        case LOAD_MODE_MMAP: return "mmap";
        case LOAD_MODE_RAM:  return "ram";
        case LOAD_MODE_AUTO:
        default:             return "auto";
    }
}

// llama_backend_init() is process-global and must run exactly once, before any model load.
void ensure_backend() {
    std::lock_guard<std::mutex> lock(g_backend_mu);
    if (!g_backend_ready) {
        llama_log_set(log_router, nullptr);
        llama_backend_init();
        g_backend_ready = true;
    }
}

// Renders the conversation through the model's own chat template.
bool render_prompt(llama_model * model,
                   const std::vector<std::string> & roles,
                   const std::vector<std::string> & contents,
                   std::string & out,
                   std::string & error) {
    std::vector<llama_chat_message> msgs(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        msgs[i].role    = roles[i].c_str();
        msgs[i].content = contents[i].c_str();
    }

    const char * tmpl = llama_model_chat_template(model, /* name */ nullptr);
    if (tmpl == nullptr) {
        // The model carries no template; fall back to a built-in one by name.
        tmpl = "chatml";
    }

    std::vector<char> buf(8192);
    // add_ass = true: without the assistant turn the prompt ends at the user's turn
    // terminator, and the model then answers with that terminator itself (zero tokens).
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                          buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                      buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) {
        error = "failed to apply the model's chat template";
        return false;
    }
    out.assign(buf.data(), n);
    return true;
}

}  // namespace

void set_log_sink(void (*sink)(int level, const char * text)) {
    g_log_sink = sink;
}

std::string version() {
    return llama_version();
}

Session * load(const std::string & path,
               int n_ctx,
               int n_threads,
               int n_gpu_layers,
               std::string & error,
               LoadMode load_mode) {
    if (path.empty()) {
        error = "no model path given";
        return nullptr;
    }
    ensure_backend();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    // See the LoadMode documentation in llama_core.h. On Android this one choice can change
    // throughput by orders of magnitude: memory-mapped weights on FUSE-mounted shared storage
    // are reclaimable, and re-faulting them through the FUSE daemon is catastrophically slow.
    switch (load_mode) {
        case LOAD_MODE_MMAP: mparams.load_mode = LLAMA_LOAD_MODE_MMAP; break;
        case LOAD_MODE_RAM:  mparams.load_mode = LLAMA_LOAD_MODE_NONE; break;
        case LOAD_MODE_AUTO:
        default:             mparams.load_mode = LLAMA_LOAD_MODE_AUTO; break;
    }

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        error = "could not load the model file: " + path;
        return nullptr;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(n_ctx);
    // Prefill is chunked to n_batch; keeping it well below n_ctx bounds the compute buffer.
    cparams.n_batch         = static_cast<uint32_t>(std::min<int>(n_ctx, 512));
    cparams.n_ubatch        = static_cast<uint32_t>(std::min<int>(n_ctx, 512));
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        error = "the model loaded but its context could not be created "
                "(try a smaller context size)";
        return nullptr;
    }

    SessionImpl * impl = new SessionImpl();
    impl->model     = model;
    impl->ctx       = ctx;
    impl->vocab     = llama_model_get_vocab(model);
    impl->n_ctx     = static_cast<int>(llama_n_ctx(ctx));
    impl->n_threads = n_threads;

    // Logged because this single choice can change throughput by orders of magnitude on a
    // phone, and a slow run otherwise gives no clue which path was taken.
    {
        char line[224];
        std::snprintf(line, sizeof(line), "load: ctx=%d threads=%d mode=%s", impl->n_ctx,
                      impl->n_threads, load_mode_name(load_mode));
        log_message(LOG_LEVEL_INFO, line);
    }
    return reinterpret_cast<Session *>(impl);
}

std::string describe(Session * session) {
    SessionImpl * impl = reinterpret_cast<SessionImpl *>(session);
    if (impl == nullptr) return {};

    char desc[256] = {0};
    llama_model_desc(impl->model, desc, sizeof(desc));

    char buf[512];
    std::snprintf(buf, sizeof(buf), "%s | ctx %d | threads %d | %.2f B params",
                  desc, impl->n_ctx, impl->n_threads,
                  static_cast<double>(llama_model_n_params(impl->model)) / 1e9);
    return std::string(buf);
}

bool generate(Session * session,
              const std::vector<std::string> & roles,
              const std::vector<std::string> & contents,
              const GenParams & params,
              TokenCallback callback,
              void * user_data,
              std::string & out,
              std::string & error,
              std::string * prompt_out) {
    SessionImpl * impl = reinterpret_cast<SessionImpl *>(session);
    if (impl == nullptr) {
        error = "no model is loaded";
        return false;
    }
    if (roles.empty() || roles.size() != contents.size()) {
        error = "the conversation is empty or malformed";
        return false;
    }

    std::lock_guard<std::mutex> lock(impl->mu);

    std::string prompt;
    if (!render_prompt(impl->model, roles, contents, prompt, error)) {
        return false;
    }
    if (prompt_out != nullptr) {
        *prompt_out = prompt;
    }

    const llama_vocab * vocab = impl->vocab;

    // The cache is cleared below, so this prompt is always the "first" one and should get
    // its BOS/special tokens added.
    const bool add_special = true;

    int32_t n_tok = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                    nullptr, 0, add_special, /* parse_special */ true);
    if (n_tok <= 0) {
        error = "the rendered prompt could not be tokenized";
        return false;
    }
    std::vector<llama_token> tokens(n_tok);
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), n_tok, add_special, true) < 0) {
        error = "the rendered prompt could not be tokenized";
        return false;
    }

    const int n_ctx = impl->n_ctx;
    if (static_cast<int>(tokens.size()) >= n_ctx) {
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "the conversation needs %d tokens but the context holds %d — clear the "
                      "chat, shorten the system prompt, or raise the context size",
                      static_cast<int>(tokens.size()), n_ctx);
        error = buf;
        return false;
    }

    // Stateless between turns: drop whatever the previous turn left in the cache.
    llama_memory_clear(llama_get_memory(impl->ctx), true);

    const auto prefill_started = std::chrono::steady_clock::now();

    const int n_batch = static_cast<int>(llama_n_batch(impl->ctx));
    for (int i = 0; i < static_cast<int>(tokens.size()); i += n_batch) {
        const int chunk = std::min(n_batch, static_cast<int>(tokens.size()) - i);
        const int rc = llama_decode(impl->ctx, llama_batch_get_one(tokens.data() + i, chunk));
        if (rc != 0) {
            char buf[128];
            std::snprintf(buf, sizeof(buf), "prompt prefill failed (llama_decode rc=%d)", rc);
            error = buf;
            return false;
        }
    }

    const auto prefill_finished = std::chrono::steady_clock::now();

    // Rebuilt per call so a settings change takes effect on the very next turn.
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (params.repeat_last_n > 0 && params.repeat_penalty > 0.0f && params.repeat_penalty != 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), params.repeat_last_n, params.repeat_penalty, 0.0f, 0.0f));
    }
    if (params.top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(params.top_k));
    }
    if (params.top_p > 0.0f && params.top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(params.top_p, 1));
    }
    // temperature 0 makes the chain greedy, which is a legitimate user choice.
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(params.temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(
            params.seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(params.seed)));

    out.clear();
    out.reserve(1024);

    // Timings are logged rather than returned: on this device the app's own log file is the
    // only diagnostic channel that exists (no logcat, no tombstones), and "it felt slow" is
    // not a measurement.
    int    generated_tokens = 0;
    double callback_seconds = 0.0;
    const auto decode_started = std::chrono::steady_clock::now();

    for (int produced = 0; produced < params.max_tokens; ++produced) {
        // llama_sampler_sample() calls llama_sampler_accept() internally in this revision,
        // so accepting the token again here would corrupt the penalty history.
        const llama_token id = llama_sampler_sample(smpl, impl->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        char piece_buf[512];
        const int n_piece = llama_token_to_piece(vocab, id, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece < 0) {
            break;  // a single piece larger than the buffer: treat as end of turn
        }
        out.append(piece_buf, n_piece);
        ++generated_tokens;

        if (callback != nullptr) {
            // Time spent inside the callback is measured separately: if the caller's sink is
            // slow (a UI update per token, for instance) it throttles generation, and that is
            // invisible from the outside.
            const auto callback_started = std::chrono::steady_clock::now();
            const bool keep_going = callback(piece_buf, n_piece, user_data);
            callback_seconds += std::chrono::duration<double>(
                    std::chrono::steady_clock::now() - callback_started).count();
            if (!keep_going) {
                break;
            }
        }

        // Stop before running off the end of the context.
        const int used = static_cast<int>(llama_memory_seq_pos_max(llama_get_memory(impl->ctx), 0)) + 1;
        if (used + 1 > n_ctx) {
            break;
        }

        llama_token next = id;
        if (llama_decode(impl->ctx, llama_batch_get_one(&next, 1)) != 0) {
            break;
        }
    }

    llama_sampler_free(smpl);

    const auto decode_finished = std::chrono::steady_clock::now();
    const double prefill_seconds = std::chrono::duration<double>(
            prefill_finished - prefill_started).count();
    const double decode_seconds = std::chrono::duration<double>(
            decode_finished - decode_started).count();

    {
        char line[352];
        std::snprintf(line, sizeof(line),
                      "inference: prefill %d tok in %.2fs (%.1f tok/s) | decode %d tok in %.2fs "
                      "(%.1f tok/s) | %.2fs of the decode was spent in the callback",
                      static_cast<int>(tokens.size()), prefill_seconds,
                      prefill_seconds > 0.0 ? tokens.size() / prefill_seconds : 0.0,
                      generated_tokens, decode_seconds,
                      decode_seconds > 0.0 ? generated_tokens / decode_seconds : 0.0,
                      callback_seconds);
        log_message(LOG_LEVEL_INFO, line);
    }
    return true;
}

void release(Session * session) {
    SessionImpl * impl = reinterpret_cast<SessionImpl *>(session);
    if (impl == nullptr) return;
    if (impl->ctx != nullptr)   llama_free(impl->ctx);
    if (impl->model != nullptr) llama_model_free(impl->model);
    delete impl;
}

}  // namespace llamacpp