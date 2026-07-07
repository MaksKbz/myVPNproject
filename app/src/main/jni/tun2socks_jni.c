#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "tun2socks_jni"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ──────────────────────────────────────────────────────────────────
// STUB: badvpn/tun2socks подключается после `git submodule`.
// Раскомментируйте #include после добавления submodule.
// ──────────────────────────────────────────────────────────────────

// #include "badvpn/tun2socks/tun2socks.h"

typedef struct {
    int  tun_fd;
    int  socks_port;
    char tun_addr[20];   // "10.0.0.2"
    char tun_gw[20];     // "10.0.0.1"
    int  tun_prefix;
} Tun2SocksConfig;

static volatile int    g_running = 0;
static pthread_t       g_thread;
static Tun2SocksConfig g_config;

static void* tun2socks_thread(void* arg) {
    LOGI("tun2socks thread started: tun_fd=%d socks_port=%d addr=%s",
         g_config.tun_fd, g_config.socks_port, g_config.tun_addr);

    // STUB: реальный вызов после submodule:
    // tun2socks_run(g_config.tun_fd, "127.0.0.1", g_config.socks_port,
    //               g_config.tun_addr, g_config.tun_gw, g_config.tun_prefix);

    // Временная заглушка
    while (g_running) {
        struct timespec ts = {0, 50000000};
        nanosleep(&ts, NULL);
    }
    LOGI("tun2socks thread stopped");
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_tun2socksStart(
        JNIEnv* env,
        jobject thiz,
        jint    tunFd,
        jint    socksPort,
        jstring tunAddr,
        jstring tunGw,
        jint    tunPrefix)
{
    if (g_running) {
        LOGI("tun2socks already running");
        return 0;
    }

    g_config.tun_fd     = (int)tunFd;
    g_config.socks_port = (int)socksPort;
    g_config.tun_prefix = (int)tunPrefix;

    const char* addr = (*env)->GetStringUTFChars(env, tunAddr, NULL);
    if (addr) {
        strncpy(g_config.tun_addr, addr, sizeof(g_config.tun_addr) - 1);
        (*env)->ReleaseStringUTFChars(env, tunAddr, addr);
    }
    const char* gw = (*env)->GetStringUTFChars(env, tunGw, NULL);
    if (gw) {
        strncpy(g_config.tun_gw, gw, sizeof(g_config.tun_gw) - 1);
        (*env)->ReleaseStringUTFChars(env, tunGw, gw);
    }

    g_running = 1;
    int rc = pthread_create(&g_thread, NULL, tun2socks_thread, NULL);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        g_running = 0;
        return -1;
    }
    LOGI("tun2socks started: tun_fd=%d → SOCKS5 127.0.0.1:%d",
         g_config.tun_fd, g_config.socks_port);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_tun2socksStop(
        JNIEnv* env,
        jobject thiz)
{
    if (!g_running) return;
    g_running = 0;
    // STUB: реальный вызов: tun2socks_stop();
    pthread_join(g_thread, NULL);
    LOGI("tun2socks stopped");
}
