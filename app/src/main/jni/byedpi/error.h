/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef CIADPI_ERROR_H
#define CIADPI_ERROR_H
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include "params.h"
#define get_e() errno
/* Android logcat output */
#include <android/log.h>
#define uniperror(str) \
    __android_log_print(ANDROID_LOG_ERROR, "ciadpi", "%s: %s", str, strerror(errno))
static inline int unie(int e) { return e; }
#define LOG_E ANDROID_LOG_ERROR
#define LOG_S ANDROID_LOG_DEBUG
#define LOG_L ANDROID_LOG_VERBOSE
#define LOG(s, str, ...) \
    __android_log_print(s, "ciadpi", str, ##__VA_ARGS__)
#define LOG_ENABLED 1
#define INIT_ADDR_STR(dst) \
    char ADDR_STR[INET6_ADDRSTRLEN]; \
    const char *p = 0; \
    if ((dst).sa.sa_family == AF_INET) \
        p = inet_ntop(AF_INET,  &(dst).in.sin_addr,   ADDR_STR, sizeof(ADDR_STR)); \
    else \
        p = inet_ntop(AF_INET6, &(dst).in6.sin6_addr, ADDR_STR, sizeof(ADDR_STR)); \
    if (!p) uniperror("inet_ntop");
#endif
