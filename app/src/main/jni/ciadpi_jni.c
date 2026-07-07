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
    params.ipv6      = false;
    params.udp       = false;
    params.max_open  = 512;
    params.bfsize    = 16384;
    params.debug     = 0;

    params.baddr.in.sin_family      = AF_INET;
    params.baddr.in.sin_addr.s_addr = INADDR_ANY;
    params.baddr.in.sin_port        = 0;

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

    init_params(cfg);

    union sockaddr_u srv;
    memset(&srv, 0, sizeof(srv));
    srv.in.sin_family      = AF_INET;
    srv.in.sin_port        = htons((uint16_t)cfg->socks_port);
    srv.in.sin_addr.s_addr = inet_addr("127.0.0.1");

    int rc = run(&srv);
    LOGI("ciadpi run() returned %d", rc);

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
