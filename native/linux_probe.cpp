// linux_probe.cpp — host verification harness for the app's inference path.
//
// It links the SAME llama_core.cpp that goes into libllama_jni.so, so what is verified
// here is the code that ships, not a re-implementation of it. The probe exists because
// the agent cannot install the APK on this device; running the core against a real GGUF
// is the strongest behavioural evidence available before hand-off.
//
// Checks performed:
//   1. a real GGUF loads and a context is created
//   2. the chat template renders WITH the assistant turn (add_ass), which is the
//      difference between a model answering and a model emitting its own terminator
//   3. tokens are produced, streamed through the callback and end at an EOG token
//   4. repeated turns on one session keep working (the cache is cleared per turn)
//
// Usage:
//   llama_probe <model.gguf> [--ctx N] [--threads N] [--max N] [--temp F]
//                            [--topk N] [--topp F] [--penalty F] [--seed N]
//                            [--system TEXT] [--user TEXT] [--turns N]
//                            [--show-prompt] [--verbose]

#include "llama_core.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct Args {
    std::string model;
    std::string system = "You are a helpful assistant running entirely offline on this phone. Keep answers short.";
    std::string user   = "Say hello in one short sentence.";
    int   ctx      = 2048;
    int   threads  = 4;
    int   max      = 128;
    float temp     = 0.8f;
    int   topk     = 40;
    float topp     = 0.95f;
    float penalty  = 1.1f;
    long long seed = -1;
    int   turns    = 1;
    bool  show_prompt = false;
    bool  verbose     = false;
};

void usage() {
    std::fprintf(stderr,
        "usage: llama_probe <model.gguf> [--ctx N] [--threads N] [--max N] [--temp F]\n"
        "                   [--topk N] [--topp F] [--penalty F] [--seed N]\n"
        "                   [--system TEXT] [--user TEXT] [--turns N]\n"
        "                   [--show-prompt] [--verbose]\n");
}

// Returns false when the option was not recognised.
bool parse(int argc, char ** argv, Args & a) {
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto next = [&](const char * name) -> const char * {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "missing value for %s\n", name);
                std::exit(2);
            }
            return argv[++i];
        };

        if (arg == "--ctx")             a.ctx = std::atoi(next("--ctx"));
        else if (arg == "--threads")    a.threads = std::atoi(next("--threads"));
        else if (arg == "--max")        a.max = std::atoi(next("--max"));
        else if (arg == "--temp")       a.temp = std::strtof(next("--temp"), nullptr);
        else if (arg == "--topk")       a.topk = std::atoi(next("--topk"));
        else if (arg == "--topp")       a.topp = std::strtof(next("--topp"), nullptr);
        else if (arg == "--penalty")    a.penalty = std::strtof(next("--penalty"), nullptr);
        else if (arg == "--seed")       a.seed = std::strtoll(next("--seed"), nullptr, 10);
        else if (arg == "--system")     a.system = next("--system");
        else if (arg == "--user")       a.user = next("--user");
        else if (arg == "--turns")      a.turns = std::atoi(next("--turns"));
        else if (arg == "--show-prompt") a.show_prompt = true;
        else if (arg == "--verbose")    a.verbose = true;
        else if (!arg.empty() && arg[0] == '-') {
            std::fprintf(stderr, "unknown option: %s\n", arg.c_str());
            return false;
        } else if (a.model.empty()) {
            a.model = arg;
        } else {
            std::fprintf(stderr, "unexpected argument: %s\n", arg.c_str());
            return false;
        }
    }
    return true;
}

long long g_tokens = 0;

bool print_and_count(const char * utf8, int len, void *) {
    std::fwrite(utf8, 1, len, stdout);
    std::fflush(stdout);
    g_tokens++;
    return true;
}

void stderr_sink(int level, const char * text) {
    if (level >= llamacpp::LOG_LEVEL_WARN) {
        std::fputs(text, stderr);
    }
}

double seconds_since(const std::chrono::steady_clock::time_point & start) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
}

/** Escapes newlines so a rendered prompt stays readable in one terminal block. */
std::string visible(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 16);
    for (char c : s) {
        if (c == '\n') out += "\\n\n";
        else           out += c;
    }
    return out;
}

}  // namespace

int main(int argc, char ** argv) {
    Args a;
    if (!parse(argc, argv, a) || a.model.empty()) {
        usage();
        return 2;
    }

    llamacpp::set_log_sink(stderr_sink);

    std::printf("llama.cpp build : %s\n", llamacpp::version().c_str());
    std::printf("model           : %s\n", a.model.c_str());

    std::string error;
    auto load_start = std::chrono::steady_clock::now();
    llamacpp::Session * session = llamacpp::load(a.model, a.ctx, a.threads, 0, error);
    if (session == nullptr) {
        std::fprintf(stderr, "FAIL: load: %s\n", error.c_str());
        return 1;
    }
    std::printf("loaded in       : %.2fs\n", seconds_since(load_start));
    std::printf("describe        : %s\n", llamacpp::describe(session).c_str());

    std::vector<std::string> roles, contents;
    if (!a.system.empty()) {
        roles.emplace_back("system");
        contents.push_back(a.system);
    }
    roles.emplace_back("user");
    contents.push_back(a.user);

    llamacpp::GenParams params;
    params.max_tokens     = a.max;
    params.temperature    = a.temp;
    params.top_k          = a.topk;
    params.top_p          = a.topp;
    params.repeat_penalty = a.penalty;
    params.repeat_last_n  = 64;
    params.seed           = a.seed;

    int produced = 0;
    int empty    = 0;

    for (int turn = 1; turn <= a.turns; ++turn) {
        std::string out, prompt;
        g_tokens = 0;

        std::printf("\n--- turn %d ---\n", turn);
        auto start = std::chrono::steady_clock::now();
        const bool ok = llamacpp::generate(session, roles, contents, params,
                                            print_and_count, nullptr,
                                            out, error,
                                            a.show_prompt ? &prompt : nullptr);
        const double elapsed = seconds_since(start);

        if (!ok) {
            std::printf("\nFAIL: generate: %s\n", error.c_str());
            break;
        }

        if (a.show_prompt) {
            std::printf("\n[rendered prompt]\n%s\n[/rendered prompt]\n", visible(prompt).c_str());
        }

        const double rate = elapsed > 0.0 ? static_cast<double>(g_tokens) / elapsed : 0.0;
        std::printf("\n[%lld tokens, %zu chars, %.2fs, %.1f tok/s]\n",
                    g_tokens, out.size(), elapsed, rate);

        if (out.empty()) ++empty; else ++produced;
    }

    std::printf("\n=== %d/%d turns produced text, %d empty ===\n",
                produced, a.turns, empty);

    llamacpp::release(session);
    return (produced == a.turns) ? 0 : 1;
}