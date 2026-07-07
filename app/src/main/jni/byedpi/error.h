/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef CIADPI_ERROR_H
#define CIADPI_ERROR_H
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <stdarg.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#ifdef ANDROID_APP
#include <android/log.h>
#endif
#include "params.h"

#define get_e() errno

#ifdef ANDROID_APP
#define uniperror(str) \
    __android_log_print(ANDROID_LOG_ERROR, "proxy", \
        "%s: %s\n", str, strerror(errno))
#else
#define uniperror(str) perror(str)
#endif

static inline int unie(int e) { return e; }

#ifdef ANDROID_APP
#define LOG_E ANDROID_LOG_ERROR
#define LOG_S ANDROID_LOG_DEBUG
#define LOG_L ANDROID_LOG_VERBOSE
#define LOG(s, str, ...) \
    __android_log_print(s, "proxy", str, ##__VA_ARGS__)
#define LOG_ENABLED 1
#else
#define LOG_E -1
#define LOG_S  1
#define LOG_L  2
static void LOG(int s, const char *str, ...) {
    if (params.debug >= s) {
        va_list args;
        va_start(args, str);
        vfprintf(stderr, str, args);
        va_end(args);
    }
}
#define LOG_ENABLED (params.debug >= LOG_S)
#endif

#define INIT_ADDR_STR(dst) \
    char ADDR_STR[INET6_ADDRSTRLEN]; \
    const char *p = 0; \
    if ((dst).sa.sa_family == AF_INET) \
        p = inet_ntop(AF_INET,  &(dst).in.sin_addr,   ADDR_STR, sizeof(ADDR_STR)); \
    else \
        p = inet_ntop(AF_INET6, &(dst).in6.sin6_addr, ADDR_STR, sizeof(ADDR_STR)); \
    if (!p) uniperror("inet_ntop");

#define INIT_HEX_STR(b, s) \
    char HEX_STR[(s) * 2 + 1]; \
    HEX_STR[sizeof(HEX_STR) - 1] = 0; \
    do { \
        ssize_t _i; \
        for (_i = 0; _i + 4 <= (s); _i += 4) \
            snprintf(HEX_STR + _i * 2, sizeof(HEX_STR) - _i * 2, \
                "%02x%02x%02x%02x", \
                (uint8_t)(b)[_i], (uint8_t)(b)[_i+1], \
                (uint8_t)(b)[_i+2], (uint8_t)(b)[_i+3]); \
        for (; _i < (s); _i++) \
            snprintf(HEX_STR + _i * 2, sizeof(HEX_STR) - _i * 2, \
                "%02x", (uint8_t)(b)[_i]); \
    } while (0)

#endif
