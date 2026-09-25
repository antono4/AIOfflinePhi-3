// Minimal, dependency-free wrapper around llama.cpp for on-device Phi-3 inference.
//
// The wrapper owns a single model + context pair and streams generated tokens
// through a callback. It is intentionally single-threaded: the caller is
// expected to serialize every entry point (see PhiEngine on the Kotlin side).

#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_vocab;
struct llama_sampler;

namespace phi3 {

struct ChatMessage {
    std::string role;     // "system" | "user" | "assistant"
    std::string content;
};

struct GenerationParams {
    int   max_tokens      = 512;
    float temperature     = 0.7f;
    float top_p           = 0.95f;
    int   top_k           = 40;
    float min_p           = 0.05f;
    float repeat_penalty  = 1.1f;
    int   repeat_last_n   = 64;
    int   seed            = -1;   // negative -> random
};

struct GenerationStats {
    int prompt_tokens     = 0;
    int generated_tokens  = 0;
    int64_t prompt_ms     = 0;
    int64_t generation_ms = 0;
};

// Returns false to abort loading (used for user-initiated cancellation).
using ProgressFn = std::function<bool(float fraction)>;
using TokenFn    = std::function<void(const std::string & text, bool is_final)>;

// Re-encodes standard UTF-8 into the CESU-8 "modified UTF-8" that JNI's
// NewStringUTF expects, so code points above U+FFFF survive the JNI boundary.
std::string to_modified_utf8(const std::string & utf8);

class Engine {
public:
    Engine() = default;
    ~Engine();

    Engine(const Engine &)            = delete;
    Engine & operator=(const Engine &) = delete;

    // Loads a GGUF model from an absolute filesystem path. Memory-maps the
    // weights when possible. Idempotent: calling it while a model is loaded
    // unloads the previous one first.
    bool load(const std::string & model_path,
              int n_ctx,
              int n_threads,
              int n_batch,
              const ProgressFn & progress,
              std::string & error);

    void unload();

    bool loaded() const { return model_ != nullptr && ctx_ != nullptr; }

    // Renders the conversation with the model's own chat template (falls back
    // to the Phi-3 template when the GGUF carries none).
    std::string format_prompt(const std::vector<ChatMessage> & messages,
                              bool add_assistant_header,
                              std::string & error) const;

    // Streams a completion. `on_token` is invoked for every chunk of text that
    // forms a complete UTF-8 sequence, and once more with is_final = true.
    bool generate(const std::vector<ChatMessage> & messages,
                  const GenerationParams & params,
                  const TokenFn & on_token,
                  GenerationStats & stats,
                  std::string & error);

    // Asks an in-flight generate() to return at the next safe point. Thread-safe.
    void request_stop() { stop_requested_.store(true); }

    std::string system_info() const;

    // Model-level metadata, used by the UI to show what was loaded.
    int context_size() const;
    int64_t model_size_bytes() const;

private:
    // Tokenizes and evaluates `tokens`, reusing the KV cache prefix that is
    // already resident. Returns the number of tokens evaluated.
    bool evaluate_tokens(const std::vector<int32_t> & tokens, int & n_evaluated, std::string & error);

    void reset_memory();

    llama_model *   model_  = nullptr;
    llama_context * ctx_    = nullptr;
    const llama_vocab * vocab_ = nullptr;

    int n_ctx_   = 0;
    int n_batch_ = 0;

    // Exact token sequence currently present in the KV cache, so that a new
    // turn can reuse the shared prefix instead of re-reading the whole chat.
    std::vector<int32_t> kv_tokens_;

    std::atomic<bool> stop_requested_{false};
    std::string system_info_;
};

}  // namespace phi3
