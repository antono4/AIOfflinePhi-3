# Phi-3 Chat — offline AI chat for Android

An Android chat app that runs **Phi-3-mini-4k-instruct** entirely on the phone.
There is no network permission in the manifest and no server component: the model
is loaded with [llama.cpp](https://github.com/ggml-org/llama.cpp) compiled for
`arm64-v8a` and exposed to Kotlin through a thin JNI bridge.

| | |
|---|---|
| Model | `microsoft/Phi-3-mini-4k-instruct-gguf` (Q4, ~2.4 GB) |
| Inference | llama.cpp, CPU only, static `llama`/`ggml` archives |
| UI | Jetpack Compose (Material 3) |
| Storage | Room for conversations, DataStore for settings |
| Min SDK | 26 (Android 8.0), but a 64-bit ARM device with ~4 GB RAM is required |

## Layout

```
app/src/main/cpp/
  CMakeLists.txt        llama.cpp + ggml config and the JNI bridge target
  llama_engine.{h,cpp}  dependency-free wrapper: load, chat template, streamed generate
  phi_jni.cpp           JNI surface, handle registry, progress/token callbacks
app/src/main/java/com/phi3chat/
  engine/PhiEngine.kt   coroutine wrapper, single generation at a time
  native/NativeBridge.kt Kotlin mirror of the JNI surface
  ui/                   ChatScreen, SettingsScreen, Components, theme
  data/                 Room entities/DAO/DB, DataStore SettingsRepository
scripts/                fetch llama.cpp, download the model, push it to a device
```

## Building

Requirements: JDK 17+, Android SDK 34, NDK `27.2.12479018`, and CMake 3.22.1
(all installed by `sdkmanager`). `local.properties` must point at the SDK.

```bash
./scripts/fetch-llama-cpp.sh      # clones llama.cpp at the pinned revision
./gradlew :app:assembleDebug      # builds libphi3chat.so + the APK
```

The first build compiles llama.cpp for arm64-v8a and takes several minutes. The
result is `app/build/outputs/apk/debug/app-debug.apk` (~22 MB, the model is not
bundled).

## Getting a model onto the device

```bash
./scripts/download-model.sh       # ~2.4 GB into models/
./scripts/push-model.sh           # adb push into the app's private storage
```

End users do not need these scripts: the app imports a GGUF through the system
file picker, copies it into app storage, and loads it from there. The copy step
is required because llama.cpp memory-maps the file and cannot read a
`content://` URI.

## Using the app

1. First launch → the chat screen shows a "no model" banner. Pick **Import
   model** and choose a Phi-3 GGUF.
2. The import is validated by size (a real Q4 Phi-3 is ~2 GB, so a 50 MB file is
   rejected with an explanation) and then copied with a progress bar.
3. **Settings → Model** lists every imported GGUF, allows switching between them,
   and has an auto-load toggle so the last-used model returns on relaunch.
4. **Settings → Generation** exposes context size, thread count, max tokens,
   temperature, top-p, top-k, min-p, repetition penalty/window, and the system
   prompt. Changing the context size or thread count reloads the model.

Generation streams token by token; the stop button calls
`Engine::request_stop()`, which the decode loop polls between tokens.

## How the native layer works

`Engine` owns one `llama_model` + `llama_context` pair and is deliberately
single-threaded — the Kotlin side serialises calls. Notable details:

- **Chat template.** The prompt is rendered with the template stored in the GGUF
  (`llama_chat_apply_template`). If metadata is missing, it falls back to the
  built-in `phi3` template, which is correct for Phi-3-mini-4k-instruct.
- **KV-cache reuse.** `kv_tokens_` records the exact token ids in the cache, so a
  new turn re-decodes only the suffix after the longest common prefix instead of
  the whole conversation.
- **Context overflow.** When prompt + max tokens no longer fit, the oldest tokens
  are dropped from both the prompt and the KV cache and the remaining positions
  are renumbered with `llama_memory_seq_add`.
- **UTF-8 correctness.** Token pieces are arbitrary byte slices, so a multi-byte
  character can straddle two tokens. `utf8_safe_prefix_len()` holds back an
  incomplete trailing sequence until the rest arrives. On the way out,
  `to_modified_utf8()` converts standard UTF-8 into the CESU-8 form that JNI's
  `NewStringUTF` expects, so code points above U+FFFF are not mangled.
- **Build configuration.** `GGML_OPENMP`, the LLAMAFILE kernels and the CPU
  repacking path are off (the NDK has no OpenMP); `GGML_CPU_ARM_ARCH` is
  `armv8.2-a+dotprod+fp16` to enable the quantised mat-mul kernels.

## Testing the engine without a phone

`app/src/main/cpp` has no Android-only dependencies beyond `<android/log.h>`, so
the engine can be exercised on the host against a real GGUF. That is how the
template, cache-reuse and UTF-8 paths were verified before ever running on a
device:

```bash
# build llama.cpp for the host, compile engine_test.cpp against the real
# llama_engine.cpp, and run it with a downloaded model
```

The test covers prompt formatting for single- and multi-turn conversations,
streamed generation, cancellation via `request_stop()`, KV-cache reuse across
turns, and the UTF-8 helpers.

## Notes and limits

- arm64-v8a only. A 3.8B model needs the memory bandwidth of a modern big core;
  32-bit and x86 builds were intentionally left out (`abiFilters`).
- CPU only. `n_gpu_layers = 0` and `offload_kqv = false`; there is no NNAPI or
  Vulkan backend here.
- Generation blocks a native thread for its whole duration. `PhiEngine` runs it
  on `Dispatchers.Default` and never on the main thread.
- `allowBackup="false"` — a 2.4 GB model has no business in a cloud backup.
