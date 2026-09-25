// Host-side harness for the real phi3::Engine implementation.
//
// Compiled against the same llama_engine.cpp the app ships, with a shim for
// android/log.h, so this exercises the production code path rather than a
// reimplementation. Build with build.sh.

#include <cctype>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "llama_engine.h"

namespace {

int failures = 0;

void check(bool condition, const std::string & label) {
    std::printf("%s  %s\n", condition ? "PASS" : "FAIL", label.c_str());
    if (!condition) ++failures;
}

std::string truncate_for_display(const std::string & s, size_t limit = 400) {
    if (s.size() <= limit) return s;
    return s.substr(0, limit) + "... [truncated]";
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: engine_test <model.gguf>\n");
        return 2;
    }
    const std::string model_path = argv[1];

    // ---- Pure helpers (no model needed) ---------------------------------
    {
        // ASCII passes through untouched.
        const std::string ascii = "hello world";
        check(phi3::to_modified_utf8(ascii) == ascii, "modified utf8: ascii unchanged");

        // 2- and 3-byte sequences are re-emitted identically.
        const std::string cafe = "caf\xC3\xA9";          // café
        check(phi3::to_modified_utf8(cafe) == cafe, "modified utf8: latin-1 range unchanged");
        const std::string euro = "\xE2\x82\xAC";         // €
        check(phi3::to_modified_utf8(euro) == euro, "modified utf8: bmp 3-byte unchanged");

        // A 4-byte code point (U+1F600) must become a surrogate pair of 3-byte
        // sequences, which is what JNI's NewStringUTF requires.
        const std::string emoji = "\xF0\x9F\x98\x80";
        const std::string expected = "\xED\xA0\xBD\xED\xB8\x80";
        check(phi3::to_modified_utf8(emoji) == expected,
              "modified utf8: astral char becomes surrogate pair");

        // Truncated input must not read out of bounds or loop forever.
        const std::string truncated = "a\xE2\x82";
        const std::string out = phi3::to_modified_utf8(truncated);
        check(out.size() <= truncated.size() + 2, "modified utf8: truncated tail tolerated");
    }

    phi3::Engine engine;
    std::string error;

    // ---- Load -----------------------------------------------------------
    std::vector<float> progress_samples;
    const bool loaded = engine.load(
        model_path,
        /*n_ctx=*/2048,
        /*n_threads=*/0,   // 0 -> engine picks a sane default
        /*n_batch=*/128,
        [&progress_samples](float f) {
            progress_samples.push_back(f);
            return true;
        },
        error);

    check(loaded, "model loads: " + (loaded ? std::string("ok") : error));
    if (!loaded) return 1;

    check(engine.loaded(), "engine reports loaded");
    check(engine.context_size() == 2048, "context size is 2048");
    check(engine.model_size_bytes() > 0, "model size reported");
    check(!progress_samples.empty(), "progress callback was invoked");

    // ---- Chat template --------------------------------------------------
    std::vector<phi3::ChatMessage> messages = {
        {"system", "You are a concise assistant."},
        {"user", "What is the capital of France?"},
    };
    const std::string prompt = engine.format_prompt(messages, true, error);
    check(!prompt.empty(), "chat template renders a prompt");
    check(prompt.find("<|user|>") != std::string::npos, "prompt uses Phi-3 markers");
    check(prompt.find("<|system|>") != std::string::npos, "prompt includes system turn");

    // ---- Generation -----------------------------------------------------
    phi3::GenerationParams params;
    params.max_tokens = 48;
    params.temperature = 0.0f;   // greedy: deterministic, so assertions are stable
    params.top_p = 1.0f;
    params.top_k = 0;
    params.min_p = 0.0f;
    params.repeat_penalty = 1.0f;
    params.seed = 1234;

    std::string streamed;
    int delta_events = 0;
    int final_events = 0;

    phi3::GenerationStats stats;
    const bool generated = engine.generate(
        messages, params,
        [&](const std::string & text, bool is_final) {
            if (is_final) ++final_events;
            else {
                ++delta_events;
                streamed += text;
            }
        },
        stats, error);

    check(generated, "generation succeeds: " + (generated ? std::string("ok") : error));
    check(final_events == 1, "exactly one final callback");
    check(delta_events > 0, "at least one streaming delta");
    check(!streamed.empty(), "generated non-empty text");
    check(stats.generated_tokens > 0, "generated_tokens > 0");
    check(stats.prompt_tokens > 0, "prompt_tokens > 0");

    std::printf("\n--- reply ---\n%s\n-------------\n", truncate_for_display(streamed).c_str());
    std::printf("prompt=%d tok (%lld ms), generated=%d tok (%lld ms, %.1f tok/s)\n",
                stats.prompt_tokens, (long long) stats.prompt_ms,
                stats.generated_tokens, (long long) stats.generation_ms,
                stats.generation_ms > 0
                    ? stats.generated_tokens * 1000.0 / stats.generation_ms : 0.0);

    // Phi-3 should name Paris. Case-insensitive to avoid punishing casing.
    std::string lower = streamed;
    for (char & c : lower) c = static_cast<char>(::tolower(static_cast<unsigned char>(c)));
    check(lower.find("paris") != std::string::npos, "answer mentions Paris");

    // ---- Multi-turn KV reuse -------------------------------------------
    std::vector<phi3::ChatMessage> turn2 = {
        {"system", "You are a concise assistant."},
        {"user", "What is the capital of France?"},
        {"assistant", streamed},
        {"user", "And of Italy?"},
    };

    std::string reply2;
    phi3::GenerationStats stats2;
    const bool generated2 = engine.generate(
        turn2, params,
        [&](const std::string & text, bool is_final) {
            if (!is_final) reply2 += text;
        },
        stats2, error);

    check(generated2, "second turn succeeds: " + (generated2 ? std::string("ok") : error));
    check(!reply2.empty(), "second turn produced text");
    // Prefix reuse means turn 2 only evaluates its new suffix.
    check(stats2.prompt_tokens > stats.prompt_tokens, "second prompt is longer (history grew)");

    std::string lower2 = reply2;
    for (char & c : lower2) c = static_cast<char>(::tolower(static_cast<unsigned char>(c)));
    check(lower2.find("rome") != std::string::npos, "second answer mentions Rome");

    std::printf("\n--- second reply ---\n%s\n--------------------\n",
                truncate_for_display(reply2).c_str());

    // ---- Cancellation ---------------------------------------------------
    // Request a long generation, then stop it from inside the token callback
    // and confirm generation ended well before the token cap.
    phi3::GenerationParams long_params = params;
    long_params.max_tokens = 400;

    std::string cancelled_text;
    int tokens_before_stop = 0;
    phi3::GenerationStats stats3;
    const bool generated3 = engine.generate(
        messages, long_params,
        [&](const std::string & text, bool is_final) {
            if (is_final) return;
            cancelled_text += text;
            if (++tokens_before_stop >= 5) engine.request_stop();
        },
        stats3, error);

    check(generated3, "cancelled run returns cleanly");
    check(stats3.generated_tokens < long_params.max_tokens,
          "stop flag ended generation early (" + std::to_string(stats3.generated_tokens) +
              " of " + std::to_string(long_params.max_tokens) + " tokens)");
    check(stats3.generated_tokens >= 5, "stopped after the requested minimum");

    std::printf("\n%d check(s) failed\n", failures);
    return failures == 0 ? 0 : 1;
}
