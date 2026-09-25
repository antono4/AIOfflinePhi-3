#include "llama_engine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>

#include "llama.h"

#define LOG_TAG "Phi3Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace phi3 {

namespace {

// llama.cpp prints a lot to stderr, which on Android is discarded. Route it to
// logcat instead so a failed model load is actually diagnosable.
void llama_log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR)      prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN)  prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO)  prio = ANDROID_LOG_INFO;

    __android_log_print(prio, LOG_TAG, "%s", text);
}

// The backend initialisation is global and must happen exactly once per process.
void init_backend_once() {
    static std::once_flag flag;
    std::call_once(flag, [] {
        llama_log_set(llama_log_callback, nullptr);
        llama_backend_init();
    });
}

int default_thread_count() {
    unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 4;
    // Big cores only: oversubscribing on a phone makes generation slower.
    int n = static_cast<int>(hw) - 2;
    if (n < 2) n = 2;
    if (n > 6) n = 6;
    return n;
}

// Length of the longest byte prefix of `s` that ends on a UTF-8 sequence
// boundary. Token pieces are arbitrary byte slices, so a multi-byte character
// can straddle two consecutive tokens; emitting early would produce mojibake.
size_t utf8_safe_prefix_len(const std::string & s) {
    size_t n = s.size();
    if (n == 0) return 0;

    size_t max_back = n < 4 ? n : 4;
    for (size_t back = 0; back < max_back; ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - 1 - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep walking back

        // Lead byte: how long is the sequence it announces?
        size_t need;
        if ((c & 0x80) == 0x00)      need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else                         need = 1;  // invalid lead byte, emit it as-is

        if (back + 1 < need) {
            return n - (back + 1);  // trailing sequence is incomplete: hold it back
        }
        return n;
    }
    return n - max_back;  // 4 continuation bytes in a row: malformed, drop them
}

}  // namespace

std::string to_modified_utf8(const std::string & utf8) {
    std::string out;
    out.reserve(utf8.size());

    size_t i = 0;
    while (i < utf8.size()) {
        const unsigned char c = static_cast<unsigned char>(utf8[i]);
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
            ++i;
            continue;
        }

        const int extra = (c >= 0xF0) ? 3 : (c >= 0xE0) ? 2 : (c >= 0xC0) ? 1 : 0;
        if (extra == 0 || i + static_cast<size_t>(extra) >= utf8.size()) {
            out.push_back(static_cast<char>(c));  // stray or truncated byte
            ++i;
            continue;
        }

        // A lead byte carrying `extra` continuation bytes holds 7 - 1 - extra
        // payload bits (2-byte lead: 0x1F, 3-byte: 0x0F, 4-byte: 0x07).
        unsigned int cp = c & ((1u << (6 - extra)) - 1u);
        bool valid = true;
        for (int k = 1; k <= extra; ++k) {
            const unsigned char cc = static_cast<unsigned char>(utf8[i + k]);
            if ((cc & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!valid) {
            out.push_back(static_cast<char>(c));
            ++i;
            continue;
        }

        auto push3 = [&out](unsigned int v) {
            out.push_back(static_cast<char>(0xE0 | (v >> 12)));
            out.push_back(static_cast<char>(0x80 | ((v >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (v & 0x3F)));
        };

        // Modified UTF-8 uses the shortest form up to U+FFFF (1/2/3 bytes) and
        // surrogate pairs only above it, so re-encoding must not widen 2-byte
        // characters into 3-byte ones.
        if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            push3(cp);
        } else {
            const unsigned int v = cp - 0x10000;
            push3(0xD800 | (v >> 10));   // high surrogate
            push3(0xDC00 | (v & 0x3FF)); // low surrogate
        }
        i += static_cast<size_t>(extra) + 1;
    }
    return out;
}

namespace {

std::string piece_for_token(const llama_vocab * vocab, int32_t token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n <= 0) {
        if (n == 0) return {};
        // Buffer too small: retry with the reported size.
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
        if (n <= 0) return {};
        big.resize(static_cast<size_t>(n));
        return big;
    }
    return std::string(buf, static_cast<size_t>(n));
}

}  // namespace

Engine::~Engine() {
    unload();
}

void Engine::unload() {
    if (ctx_) {
        llama_free(ctx_);
        ctx_ = nullptr;
    }
    if (model_) {
        llama_model_free(model_);
        model_ = nullptr;
    }
    vocab_ = nullptr;
    kv_tokens_.clear();
    n_ctx_ = 0;
}

bool Engine::load(const std::string & model_path,
                  int n_ctx,
                  int n_threads,
                  int n_batch,
                  const ProgressFn & progress,
                  std::string & error) {
    init_backend_once();
    unload();

    if (n_threads <= 0) n_threads = default_thread_count();
    if (n_ctx <= 0)     n_ctx = 4096;
    if (n_batch <= 0)   n_batch = 256;
    if (n_batch > n_ctx) n_batch = n_ctx;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;                             // CPU only: no GPU backend is shipped
    mparams.load_mode    = LLAMA_LOAD_MODE_MMAP;          // keeps RSS low; weights stay in the page cache
    mparams.lazy_mode    = LLAMA_LAZY_MODE_OFF;           // mmap'd pages must be resident before inference
    mparams.check_tensors = false;
    if (progress) {
        mparams.progress_callback = [](float p, void * ud) -> bool {
            auto * fn = static_cast<const ProgressFn *>(ud);
            bool keep_going = (*fn)(p);
            // Returning false aborts the load; it also marks the loader as failed.
            return keep_going;
        };
        mparams.progress_callback_user_data =
            const_cast<void *>(static_cast<const void *>(&progress));
    }

    LOGI("loading model: %s (n_ctx=%d, n_threads=%d, n_batch=%d)",
         model_path.c_str(), n_ctx, n_threads, n_batch);

    model_ = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model_) {
        error = "Failed to load model. The file is missing, corrupt, or not a GGUF file.";
        return false;
    }

    vocab_ = llama_model_get_vocab(model_);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(n_ctx);
    cparams.n_batch         = static_cast<uint32_t>(n_batch);
    cparams.n_ubatch        = static_cast<uint32_t>(n_batch);
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.offload_kqv     = false;   // no GPU
    cparams.no_perf         = true;

    ctx_ = llama_init_from_model(model_, cparams);
    if (!ctx_) {
        error = "Model loaded but the inference context could not be created (out of memory?).";
        llama_model_free(model_);
        model_ = nullptr;
        vocab_ = nullptr;
        return false;
    }

    n_ctx_   = static_cast<int>(llama_n_ctx(ctx_));
    n_batch_ = n_batch;
    kv_tokens_.clear();

    system_info_ = llama_print_system_info() ? llama_print_system_info() : "";
    LOGI("model ready: n_ctx=%d, template=%s", n_ctx_,
         llama_model_chat_template(model_, nullptr) ? "from model" : "default(phi3)");
    return true;
}

std::string Engine::system_info() const {
    std::string info = system_info_;
    if (!model_) return info;
    const char * tmpl = llama_model_chat_template(model_, nullptr);
    info += "\nchat template: ";
    info += tmpl ? tmpl : "(built-in phi3 fallback)";
    return info;
}

int Engine::context_size() const {
    return n_ctx_;
}

int64_t Engine::model_size_bytes() const {
    if (!model_) return 0;
    return static_cast<int64_t>(llama_model_size(model_));
}

std::string Engine::format_prompt(const std::vector<ChatMessage> & messages,
                                  bool add_assistant_header,
                                  std::string & error) const {
    if (!model_) {
        error = "No model loaded.";
        return {};
    }

    const char * tmpl = llama_model_chat_template(model_, nullptr);
    if (tmpl == nullptr) {
        // GGUF without a template: Phi-3's own format works for every Phi-3/Phi-4-mini
        // variant that was converted with a missing chat_template metadata entry.
        tmpl = "phi3";
    }

    std::vector<llama_chat_message> chat;
    chat.reserve(messages.size());
    size_t hint = 0;
    for (const auto & m : messages) {
        chat.push_back(llama_chat_message{m.role.c_str(), m.content.c_str()});
        hint += m.role.size() + m.content.size() + 16;
    }

    std::vector<char> buf(hint * 2 + 512);
    int32_t needed = llama_chat_apply_template(tmpl, chat.data(), chat.size(),
                                               add_assistant_header, buf.data(),
                                               static_cast<int32_t>(buf.size()));
    if (needed < 0) {
        error = "Chat template could not be applied to this conversation.";
        return {};
    }
    if (static_cast<size_t>(needed) >= buf.size()) {
        buf.assign(static_cast<size_t>(needed) + 1, '\0');
        needed = llama_chat_apply_template(tmpl, chat.data(), chat.size(),
                                           add_assistant_header, buf.data(),
                                           static_cast<int32_t>(buf.size()));
        if (needed < 0) {
            error = "Chat template could not be applied to this conversation.";
            return {};
        }
    }
    return std::string(buf.data(), static_cast<size_t>(needed));
}

void Engine::reset_memory() {
    if (!ctx_) return;
    llama_memory_clear(llama_get_memory(ctx_), /*data=*/true);
    kv_tokens_.clear();
}

bool Engine::evaluate_tokens(const std::vector<int32_t> & tokens, int & n_evaluated,
                             std::string & error) {
    n_evaluated = 0;
    const int total = static_cast<int>(tokens.size());

    for (int start = 0; start < total; start += n_batch_) {
        if (stop_requested_.load()) return false;

        const int count = std::min(n_batch_, total - start);
        // llama_batch_get_one leaves `logits` null, which makes llama_decode emit
        // logits for the last token of this batch only - exactly what the
        // sampling loop at the end of the prompt needs.
        std::vector<llama_token> chunk(tokens.begin() + start, tokens.begin() + start + count);
        llama_batch batch = llama_batch_get_one(chunk.data(), count);

        const int rc = llama_decode(ctx_, batch);
        if (rc < 0) {
            error = "Inference failed while reading the prompt (llama_decode error " +
                    std::to_string(rc) + ").";
            return false;
        }
        n_evaluated += count;
    }
    return true;
}

bool Engine::generate(const std::vector<ChatMessage> & messages,
                      const GenerationParams & params,
                      const TokenFn & on_token,
                      GenerationStats & stats,
                      std::string & error) {
    if (!loaded()) {
        error = "No model loaded.";
        return false;
    }

    stop_requested_.store(false);
    stats = GenerationStats{};

    std::string prompt = format_prompt(messages, /*add_assistant_header=*/true, error);
    if (prompt.empty() && !error.empty()) return false;

    // Tokenize. add_special honours the GGUF metadata, parse_special is required
    // so the template's <|user|> / <|assistant|> markers become single tokens.
    int n_prompt_tokens = -llama_tokenize(vocab_, prompt.c_str(),
                                          static_cast<int32_t>(prompt.size()),
                                          nullptr, 0, true, true);
    if (n_prompt_tokens <= 0) {
        error = "Could not tokenize the conversation.";
        return false;
    }

    std::vector<int32_t> tokens(static_cast<size_t>(n_prompt_tokens));
    int written = llama_tokenize(vocab_, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                 tokens.data(), n_prompt_tokens, true, true);
    if (written <= 0) {
        error = "Could not tokenize the conversation.";
        return false;
    }
    tokens.resize(static_cast<size_t>(written));

    auto * mem = llama_get_memory(ctx_);

    // ---- Make room for the new turn inside the context window -------------
    const int reserve = std::min<int>(params.max_tokens, n_ctx_ / 2) + 4;
    int overflow = static_cast<int>(tokens.size()) + reserve - n_ctx_;
    if (overflow > 0) {
        LOGI("context full: dropping %d oldest tokens", overflow);
        tokens.erase(tokens.begin(), tokens.begin() + overflow);

        if (static_cast<size_t>(overflow) <= kv_tokens_.size()) {
            // Drop the same tokens out of the KV cache and renumber the rest so
            // the positions stay inside [0, n_ctx).
            if (llama_memory_seq_rm(mem, 0, 0, overflow)) {
                llama_memory_seq_add(mem, 0, overflow, -1, -overflow);
                kv_tokens_.erase(kv_tokens_.begin(), kv_tokens_.begin() + overflow);
            } else {
                reset_memory();
            }
        } else {
            // The dropped region extends past what the cache holds, so nothing
            // in the cache lines up with the new prompt any more.
            reset_memory();
        }
    }

    // ---- Reuse the KV cache prefix shared with the previous turn ----------
    size_t common = 0;
    while (common < tokens.size() && common < kv_tokens_.size() &&
           tokens[common] == kv_tokens_[common]) {
        ++common;
    }

    // Sampling reads the logits left by the previous decode, so the last prompt
    // token must always be decoded. Without this, a prompt that is already fully
    // cached would be sampled against the *previous* turn's logits.
    if (common == tokens.size() && common > 0) --common;

    if (common < kv_tokens_.size()) {
        if (llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
            kv_tokens_.resize(common);
        } else {
            // Some cache layouts refuse partial removal.
            reset_memory();
            common = 0;
        }
    }
    LOGI("prompt tokens=%zu, cache reuse=%zu", tokens.size(), common);

    const auto t_prompt_start = std::chrono::steady_clock::now();
    int n_evaluated = 0;
    if (common < tokens.size()) {
        std::vector<int32_t> to_eval(tokens.begin() + common, tokens.end());
        if (!evaluate_tokens(to_eval, n_evaluated, error)) return false;
    }
    kv_tokens_ = tokens;
    stats.prompt_tokens = static_cast<int>(tokens.size());
    stats.prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::steady_clock::now() - t_prompt_start).count();

    // ---- Sampler chain ----------------------------------------------------
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    if (params.repeat_penalty != 1.0f && params.repeat_last_n > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab_), params.repeat_last_n,
            params.repeat_penalty, 0.0f, 0.0f));
    }
    if (params.top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(params.top_k));
    }
    if (params.top_p < 1.0f && params.top_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(params.top_p, 1));
    }
    if (params.min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(params.min_p, 1));
    }
    if (params.temperature > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(params.temperature));
    }
    const uint32_t seed = params.seed < 0 ? LLAMA_DEFAULT_SEED
                                          : static_cast<uint32_t>(params.seed);
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));

    // ---- Token loop -------------------------------------------------------
    const auto t_gen_start = std::chrono::steady_clock::now();
    std::string pending;   // bytes held back until a full UTF-8 char is available
    int n_generated = 0;

    for (int i = 0; i < params.max_tokens; ++i) {
        if (stop_requested_.load()) break;

        const llama_token id = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, id)) break;

        pending += piece_for_token(vocab_, id);

        const size_t emit = utf8_safe_prefix_len(pending);
        if (emit > 0) {
            on_token(pending.substr(0, emit), false);
            pending.erase(0, emit);
        }

        // Feed the sampled token back in so the next step sees it.
        std::vector<llama_token> one{id};
        llama_batch batch = llama_batch_get_one(one.data(), 1);
        if (llama_decode(ctx_, batch) < 0) {
            LOGW("llama_decode failed while generating token %d", i);
            break;
        }
        kv_tokens_.push_back(id);
        ++n_generated;
    }

    if (!pending.empty()) {
        on_token(pending, true);
    } else {
        on_token(std::string(), true);
    }

    stats.generated_tokens = n_generated;
    stats.generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                              std::chrono::steady_clock::now() - t_gen_start).count();

    llama_sampler_free(smpl);
    return true;
}

}  // namespace phi3
