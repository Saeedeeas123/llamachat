// Host stub for <android/log.h>.
//
// llama_jni.cpp logs through __android_log_print. To exercise that exact file under a plain
// JVM on this machine, the header is stubbed to write to stderr instead. Nothing else about
// the JNI layer changes, so a crash here is a crash there.

#pragma once

#include <stdarg.h>
#include <stdio.h>

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_WARN  5
#define ANDROID_LOG_ERROR 6

static inline int __android_log_print(int priority, const char * tag, const char * format, ...) {
    va_list args;
    va_start(args, format);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
    va_end(args);
    return 0;
}
