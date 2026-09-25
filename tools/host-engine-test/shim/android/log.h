// Host-test shim: routes Android logging macros to stderr so the engine code
// can be compiled and executed outside the NDK.
#pragma once
#include <cstdio>
#include <cstdarg>
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
inline int __android_log_print(int, const char* tag, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = std::fprintf(stderr, "[%s] ", tag);
    n += std::vfprintf(stderr, fmt, ap);
    va_end(ap);
    std::fputc(0x0A, stderr);
    return n;
}
