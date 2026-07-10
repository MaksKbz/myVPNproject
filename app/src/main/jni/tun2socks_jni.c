#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <unistd.h>
#include "crash_handler.h"

#define LOG_TAG "tun2socks_jni"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ──────────────────────────────────────────────────────────────────
// v3.7 CIS-MAX: реальный tun2socks (badvpn, vendored в jni/badvpn/)
// вместо заглушки. Смотри tun2socks_bridge.h/.c для деталей —
// tun2socks.c пропатчен добавлением библиотечного режима
// (TUN2SOCKS_LIBRARY_MODE), который принимает уже открытый TUN fd
// вместо CLI-аргументов (BTAP_INIT_FD вместо BTAP_INIT_STRING),
// т.к. открыть /dev/net/tun напрямую на Android нельзя без root.
//
// Полный event loop (BReactor + lwIP) — блокирующий вызов,
// запускается в отдельном pthread; остановка — через self-pipe,
// зарегистрированный в реакторе (см. tun2socks_bridge_stop()).
// ──────────────────────────────────────────────────────────────────

#ifdef BADVPN_AVAILABLE
#include "tun2socks_bridge.h"
#endif

typedef struct {
    int  tun_fd;
    int  socks_port;
    char tun_addr[20];   // "10.0.0.2"
    char tun_gw[20];     // "10.0.0.1" — сетевой адрес виртуального роутера внутри TUN
    char tun_ip6addr[64];// "fd00:1:fd00:1:fd00:1:fd00:2", может быть пустым
    int  tun_prefix;
    int  mtu;
} Tun2SocksConfig;

static volatile int    g_running = 0;
static pthread_t       g_thread;
static Tun2SocksConfig g_config;

#ifdef BADVPN_AVAILABLE
static void tun2socks_log_adapter(int channel, int level, const char *msg) {
    // level: 1=ERROR..5=DEBUG (см. BLOG_ERROR..BLOG_DEBUG в base/BLog.h)
    android_LogPriority prio = ANDROID_LOG_INFO;
    switch (level) {
        case 1: prio = ANDROID_LOG_ERROR; break;
        case 2: prio = ANDROID_LOG_WARN;  break;
        case 3: prio = ANDROID_LOG_INFO;  break;
        default: prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(prio, LOG_TAG, "[badvpn] %s", msg);
}
#endif

static void* tun2socks_thread(void* arg) {
    // v3.7.4 CIS-MAX: sigaltstack() привязан к потоку, не к процессу —
    // без этого вызова краш из-за переполнения стека ИМЕННО в этом
    // потоке (весьма вероятно для lwIP/badvpn с их глубоко вложенными
    // вызовами и локальными буферами) остался бы незамеченным.
    crash_handler_install_altstack_current_thread();

    LOGI("tun2socks thread started: tun_fd=%d socks_port=%d addr=%s mtu=%d",
         g_config.tun_fd, g_config.socks_port, g_config.tun_addr, g_config.mtu);
    crash_log_checkpoint("tun2socks_thread: entered");

#ifdef BADVPN_AVAILABLE
    char socks_addr_buf[48];
    snprintf(socks_addr_buf, sizeof(socks_addr_buf), "127.0.0.1:%d", g_config.socks_port);

    struct tun2socks_bridge_config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.tun_fd = g_config.tun_fd;
    cfg.mtu = g_config.mtu > 0 ? g_config.mtu : 1500;
    cfg.netif_ipaddr = g_config.tun_gw;      // виртуальный роутер внутри TUN
    cfg.netif_netmask = "255.255.255.0";
    cfg.netif_ip6addr = g_config.tun_ip6addr[0] ? g_config.tun_ip6addr : NULL;
    cfg.socks_server_addr = socks_addr_buf;
    cfg.log_func = tun2socks_log_adapter;

    crash_log_checkpoint("tun2socks_thread: calling tun2socks_bridge_run");
    // Блокирует до tun2socks_bridge_stop() из другого потока.
    int rc = tun2socks_bridge_run(&cfg);
    LOGI("tun2socks_bridge_run returned %d", rc);
    crash_log_checkpoint("tun2socks_thread: tun2socks_bridge_run returned");
#else
    // Fallback-заглушка: используется только если badvpn submodule
    // отсутствует на этапе сборки (BADVPN_AVAILABLE не определён в
    // CMakeLists.txt) — тогда tun2socks недоступен, но остальная
    // цепочка (ciadpi SOCKS5) продолжает работать вхолостую.
    LOGE("BADVPN_AVAILABLE not defined at build time — tun2socks stub mode");
    while (g_running) {
        struct timespec ts = {0, 50000000};
        nanosleep(&ts, NULL);
    }
#endif

    g_running = 0;
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

    memset(&g_config, 0, sizeof(g_config));
    g_config.tun_fd     = (int)tunFd;
    g_config.socks_port = (int)socksPort;
    g_config.tun_prefix = (int)tunPrefix;
    g_config.mtu        = 1500;

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

    crash_log_checkpoint("tun2socksStart: config prepared, creating thread");
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
#ifdef BADVPN_AVAILABLE
    // Потокобезопасно (self-pipe) сигналим event loop завершиться —
    // после этого tun2socks_bridge_run() сам освободит все ресурсы
    // и вернётся из tun2socks_thread().
    tun2socks_bridge_stop();
#else
    g_running = 0;
#endif
    pthread_join(g_thread, NULL);
    g_running = 0;
    LOGI("tun2socks stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_isNativeTun2socksBuilt(
        JNIEnv* env,
        jobject thiz)
{
    (void)env; (void)thiz;
#ifdef BADVPN_AVAILABLE
    return 1;
#else
    return 0;
#endif
}

/*
 * v3.7.4 CIS-MAX: crash_handler.c компилируется ОТДЕЛЬНО в каждую .so
 * (ciadpi_jni.so и tun2socks_jni.so) — это два независимых набора
 * глобальных переменных (g_log_fd и т.д.), а не общий модуль. Установка
 * обработчика только со стороны ciadpi_jni.so (как было в v3.7.3) НЕ
 * покрывает крашей внутри tun2socks_jni.so — там g_log_fd оставался -1,
 * и все crash_log_checkpoint()/сигнальные обработчики в этом модуле молча
 * ничего не писали. Нужно устанавливать обработчик отдельно в каждой
 * библиотеке — см. ProxyEngine.installCrashHandler(), вызывающий эту
 * функцию наравне с одноимённой в ciadpi_jni.c.
 */
JNIEXPORT void JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_installCrashHandlerTun2socks(
        JNIEnv* env,
        jobject thiz,
        jstring logPath)
{
    (void)thiz;
    const char* path = (*env)->GetStringUTFChars(env, logPath, NULL);
    if (path) {
        crash_handler_install(path);
        crash_log_checkpoint("tun2socks_jni: crash handler installed");
        (*env)->ReleaseStringUTFChars(env, logPath, path);
    }
}
