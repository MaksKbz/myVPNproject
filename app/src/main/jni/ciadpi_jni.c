#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#define LOG_TAG "ciadpi_jni"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ──────────────────────────────────────────────────────────────────
// STUB: функции реального byedpi подключаются после `git submodule`
// Сборка без submodule проходит — вызовы возвращают -1.
// После добавления submodule раскомментируйте строки с #include.
// ──────────────────────────────────────────────────────────────────

// #include "byedpi/proxy.h"
// #include "byedpi/params.h"

typedef struct {
    int  socks_port;
    int  split_pos;
    int  disorder;
    int  fake_enabled;
    int  fake_ttl;
    int  drop_sack;
    char auto_mode[8];
} CiadpiConfig;

static volatile int  g_running  = 0;
static pthread_t     g_thread;
static CiadpiConfig  g_config;

static void* ciadpi_thread(void* arg) {
    LOGI("ciadpi thread started: socks_port=%d split=%d disorder=%d fake=%d",
         g_config.socks_port, g_config.split_pos,
         g_config.disorder,   g_config.fake_enabled);

    // STUB: реальный вызов после submodule:
    // struct Params p = params_from_config(&g_config);
    // proxy_run(&p);  // блокирует поток до proxy_stop()

    // Временная заглушка — спим пока g_running == 1
    while (g_running) {
        struct timespec ts = {0, 50000000}; // 50 ms
        nanosleep(&ts, NULL);
    }
    LOGI("ciadpi thread stopped");
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_ciadpiStart(
        JNIEnv*  env,
        jobject  thiz,
        jint     socksPort,
        jint     splitPos,
        jint     disorder,
        jboolean fakeEnabled,
        jint     fakeTtl,
        jboolean dropSack,
        jstring  autoMode)
{
    if (g_running) {
        LOGI("ciadpi already running");
        return 0;
    }

    g_config.socks_port   = (int)socksPort;
    g_config.split_pos    = (int)splitPos;
    g_config.disorder     = (int)disorder;
    g_config.fake_enabled = (int)fakeEnabled;
    g_config.fake_ttl     = (int)fakeTtl;
    g_config.drop_sack    = (int)dropSack;

    const char* mode = (*env)->GetStringUTFChars(env, autoMode, NULL);
    if (mode) {
        strncpy(g_config.auto_mode, mode, sizeof(g_config.auto_mode) - 1);
        g_config.auto_mode[sizeof(g_config.auto_mode) - 1] = '\0';
        (*env)->ReleaseStringUTFChars(env, autoMode, mode);
    }

    g_running = 1;
    int rc = pthread_create(&g_thread, NULL, ciadpi_thread, NULL);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        g_running = 0;
        return -1;
    }
    LOGI("ciadpi started on SOCKS5 port %d", g_config.socks_port);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_ciadpiStop(
        JNIEnv* env,
        jobject thiz)
{
    if (!g_running) return;
    g_running = 0;
    // STUB: реальный вызов: proxy_stop();
    pthread_join(g_thread, NULL);
    LOGI("ciadpi stopped");
}

JNIEXPORT jstring JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_ciadpiVersion(
        JNIEnv* env,
        jobject thiz)
{
    // STUB: реальная версия: return (*env)->NewStringUTF(env, proxy_version());
    return (*env)->NewStringUTF(env, "byedpi/stub (submodule not added)");
}
