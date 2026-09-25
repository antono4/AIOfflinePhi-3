# Native inference layer: llama.cpp (ggml) built for Android as a static library,
# wrapped by a small C++ engine and exposed to Kotlin through JNI.

cmake_minimum_required(VERSION 3.22)
project(phi3chat LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_DIR}.\n"
        "Run ./scripts/fetch-llama-cpp.sh from the project root first.")
endif()

# ---------------------------------------------------------------------------
# llama.cpp / ggml configuration
#
# Everything that is not needed on a phone is switched off: OpenMP (absent from
# the NDK), the LLAMAFILE sgemm kernels, the optional dot-product-for-Q4_0
# repacking and the extra CPU backend variants. What remains is a single static
# CPU backend with NEON/dotprod/fp16 kernels enabled at compile time.
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_STANDALONE OFF CACHE BOOL "" FORCE)

# The unified `llama` target must stay on: llama.cpp is only attached as a
# subdirectory (not via add_subdirectory-the-project-root), so LLAMA_STANDALONE
# is OFF and the aggregate static library is what we link against.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS_3RD_PARTY OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE OFF CACHE BOOL "" FORCE)

# arm64-v8a only. armv8.2-a +dotprod +fp16 covers every mainstream SoC since
# roughly 2018 (Cortex-A75/A76 and newer, Snapdragon 845+) and unlocks the
# quantised mat-mul kernels that make CPU inference usable on a phone.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

# Silence llama.cpp's own warnings: this is vendored code we do not maintain.
add_compile_options(-Wno-unused-parameter -Wno-deprecated-declarations)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# The JNI bridge
# ---------------------------------------------------------------------------
add_library(phi3chat SHARED
    llama_engine.cpp
    phi_jni.cpp)

target_include_directories(phi3chat PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR})

# llama/ggml are built as static archives, so the whole backend has to be
# force-loaded into the shared object rather than dropped as unreferenced.
find_library(log-lib log)
set(WHOLE_ARCHIVE "-Wl,--whole-archive")
set(NO_WHOLE_ARCHIVE "-Wl,--no-whole-archive")

target_link_libraries(phi3chat PRIVATE
    ${WHOLE_ARCHIVE}
    llama
    ggml
    ggml-base
    ${NO_WHOLE_ARCHIVE}
    ${log-lib})

target_link_options(phi3chat PRIVATE "-Wl,--exclude-libs,ALL")
