#define ANDROID_APP 1
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include "byedpi/proxy.h"
#include "byedpi/params.h"
#include "byedpi/packets.h"
#include "crash_handler.h"

#define LOG_TAG "ciadpi_jni"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct params  params;
struct packet  fake_tls;
struct packet  fake_http;
struct packet  fake_udp;

static volatile int  g_running = 0;
static pthread_t     g_thread;

typedef struct {
    int  socks_port;
    int  split_pos;
    int  disorder;
    int  fake_enabled;
    int  fake_ttl;
    int  drop_sack;
} JniConfig;

static JniConfig g_cfg;

static void init_params(const JniConfig *cfg) {
    memset(&params,    0, sizeof(params));
    memset(&fake_tls,  0, sizeof(fake_tls));
    memset(&fake_http, 0, sizeof(fake_http));
    memset(&fake_udp,  0, sizeof(fake_udp));

    params.mode      = MODE_SOCKS5;
    params.resolve   = true;
    // v3.7.1: IPv6 destinations включены через dual-stack baddr (см. ниже).
    // Раньше params.ipv6=false, а params.baddr был чистым AF_INET —
    // remote_sock()/map_fix() в proxy.c отклоняли любое соединение, если
    // семейство адреса назначения не совпадало с семейством baddr, то
    // есть все IPv6-адресаты (S_ATP_I6 от tun2socks) молча проваливались
    // на уровне SOCKS5 (-S_ER_ATP). Протестировано локально (Linux
    // x86_64, gcc): dual-stack AF_INET6-сокет с IPV6_V6ONLY=0, привязанный
    // к in6addr_any, успешно коннектится как к v4-mapped-v6 адресам
    // (::ffff:a.b.c.d — именно так map_fix(dst,6) конвертирует чистый
    // IPv4 dst, когда params.baddr.sa_family==AF_INET6), так и к нативным
    // IPv6-адресам — то есть один и тот же baddr обслуживает оба случая.
    params.ipv6      = true;
    // v3.7 CIS-MAX: включаем SOCKS5 UDP ASSOCIATE — начиная с этой версии
    // tun2socks (badvpn) реально форвардит QUIC/DNS-over-UDP через
    // SocksUdpClient (см. tun2socks_bridge_run(): cfg.socks5_udp=1). Без
    // params.udp=true byedpi отклонял бы S_CMD_AUDP запрос, и весь UDP
    // трафик из TUN молча бы отваливался на этапе SOCKS5-хендшейка.
    params.udp       = true;
    params.max_open  = 512;
    params.bfsize    = 16384;
    params.debug     = 0;

    // Dual-stack baddr: AF_INET6 + in6addr_any. remote_sock() в proxy.c
    // создаёт отдельный сокет под каждое исходящее соединение и сам
    // включает IPV6_V6ONLY=0 на нём, когда семейство адреса назначения
    // AF_INET6 (см. `if (dst->sa.sa_family == AF_INET6) setsockopt(...
    // IPV6_V6ONLY, &no ...)` в proxy.c) — этого достаточно, чтобы такой
    // сокет одинаково успешно подключался и к v4-mapped-v6, и к чистым
    // v6 адресам, при этом bind() на params.baddr (AF_INET6/::) не
    // конфликтует ни с тем, ни с другим семейством назначения.
    params.baddr.in6.sin6_family = AF_INET6;
    params.baddr.in6.sin6_addr   = in6addr_any;
    params.baddr.in6.sin6_port   = 0;

    params.dp = (struct desync_params *)calloc(1, sizeof(struct desync_params));
    if (!params.dp) { LOGE("OOM: desync_params"); return; }
    params.dp_n = 1;
    params.dp_full_mask = 1;
    params.dp->bit = 1;

    struct desync_params *dp = params.dp;

    if (cfg->split_pos > 0) {
        dp->parts = (struct part *)calloc(1, sizeof(struct part));
        if (dp->parts) {
            dp->parts[0].pos = cfg->split_pos;
            dp->parts[0].m   = cfg->disorder
                                ? DESYNC_DISORDER : DESYNC_SPLIT;
            dp->parts[0].r   = 1;
            dp->parts_n      = 1;
        }
    }

    if (cfg->fake_enabled) {
        dp->ttl       = cfg->fake_ttl > 0 ? cfg->fake_ttl : 6;
        dp->drop_sack = (bool)cfg->drop_sack;
        if (fake_tls_init(&fake_tls, 1024) < 0)
            LOGE("fake_tls_init failed");
        if (fake_http_init(&fake_http) < 0)
            LOGE("fake_http_init failed");
    }
}

static void cleanup_params(void) {
    if (params.dp) {
        for (int i = 0; i < params.dp_n; i++) {
            free(params.dp[i].parts);
            free(params.dp[i].tlsrec);
        }
        free(params.dp);
        params.dp   = NULL;
        params.dp_n = 0;
    }
    free(fake_tls.data);  fake_tls.data  = NULL;
    free(fake_http.data); fake_http.data = NULL;
    free(fake_udp.data);  fake_udp.data  = NULL;
}

static void *ciadpi_thread(void *arg) {
    JniConfig *cfg = (JniConfig *)arg;
    LOGI("ciadpi starting on SOCKS5 127.0.0.1:%d split=%d disorder=%d fake=%d",
         cfg->socks_port, cfg->split_pos, cfg->disorder, cfg->fake_enabled);
    crash_log_checkpoint("ciadpi_thread: entered");

    init_params(cfg);
    crash_log_checkpoint("ciadpi_thread: init_params done");

    union sockaddr_u srv;
    memset(&srv, 0, sizeof(srv));
    srv.in.sin_family      = AF_INET;
    srv.in.sin_port        = htons((uint16_t)cfg->socks_port);
    srv.in.sin_addr.s_addr = inet_addr("127.0.0.1");

    crash_log_checkpoint("ciadpi_thread: calling run()");
    int rc = run(&srv);
    LOGI("ciadpi run() returned %d", rc);
    crash_log_checkpoint("ciadpi_thread: run() returned");

    cleanup_params();
    g_running = 0;
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_ciadpiStart(
        JNIEnv  *env,
        jobject  thiz,
        jint     socksPort,
        jint     splitPos,
        jint     disorder,
        jboolean fakeEnabled,
        jint     fakeTtl,
        jboolean dropSack,
        jstring  autoMode)
{
    (void)env; (void)thiz; (void)autoMode;
    if (g_running) { LOGI("ciadpi already running"); return 0; }

    g_cfg.socks_port   = (int)socksPort;
    g_cfg.split_pos    = (int)splitPos;
    g_cfg.disorder     = (int)disorder;
    g_cfg.fake_enabled = (int)fakeEnabled;
    g_cfg.fake_ttl     = (int)fakeTtl;
    g_cfg.drop_sack    = (int)dropSack;

    g_running = 1;
    int rc = pthread_create(&g_thread, NULL, ciadpi_thread, &g_cfg);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        g_running = 0;
        return -1;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_ciadpiStop(
        JNIEnv *env,
        jobject thiz)
{
    (void)env; (void)thiz;
    if (!g_running) return;
    extern int server_fd;
    if (server_fd > 0) shutdown(server_fd, SHUT_RDWR);
    pthread_join(g_thread, NULL);
    g_running = 0;
    LOGI("ciadpi stopped");
}

JNIEXPORT jstring JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_ciadpiVersion(
        JNIEnv *env,
        jobject thiz)
{
    (void)thiz;
    return (*env)->NewStringUTF(env, "byedpi/vendored");
}

/*
 * v3.7.3 CIS-MAX: устанавливает нативный сборщик крашей (SIGABRT/SIGSEGV/...)
 * — пишет диагностику в файл внутри filesDir приложения, чтобы можно было
 * увидеть причину краша прямо в UI на устройстве без adb logcat. Вызывается
 * из ProxyEngine.<init> при первой загрузке нативных библиотек — до любого
 * реального запуска ciadpi/tun2socks.
 */
JNIEXPORT void JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_installCrashHandler(
        JNIEnv *env,
        jobject thiz,
        jstring logPath)
{
    (void)thiz;
    const char *path = (*env)->GetStringUTFChars(env, logPath, NULL);
    if (path) {
        crash_handler_install(path);
        crash_log_checkpoint("ciadpi_jni: crash handler installed");
        (*env)->ReleaseStringUTFChars(env, logPath, path);
    }
}
