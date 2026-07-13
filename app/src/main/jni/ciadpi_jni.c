#define ANDROID_APP 1
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <errno.h>

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

/*
 * v3.7.14 CIS-MAX: полный набор desync-параметров, которые раньше
 * частично игнорировались JNI-мостом. Без них пресеты вроде universal
 * (OOB по SNI) и youtube (TLS record split) фактически сводились к
 * простому split/disorder на фиксированной позиции — этого часто
 * недостаточно против ТСПУ (meduza.io, youtube, cloudflare-hosted).
 */
typedef struct {
    int  socks_port;
    int  split_pos;
    int  split_flag;      /* OFFSET_* bitmask, e.g. OFFSET_SNI for "1+s" */
    int  disorder;        /* 1 → DESYNC_DISORDER, 0 → DESYNC_SPLIT */
    int  oob_enabled;     /* 1 → DESYNC_OOB / DESYNC_DISOOB */
    int  oob_pos;
    int  oob_flag;
    int  fake_enabled;
    int  fake_ttl;
    int  drop_sack;
    int  tlsrec_pos;      /* 0 = off */
    int  tlsrec_flag;
    int  udp_fake_count;
    int  mod_http;        /* bit flags from byedpi -M */
} JniConfig;

static JniConfig g_cfg;

/* Detect host default TTL so disorder/fake can restore a working value.
 * byedpi's main.c does the same via getsockopt(IP_TTL) on a dummy socket.
 * Leaving def_ttl=0 (memset) makes restore_state() set IP_TTL=0 after the
 * first desync fragment — subsequent TLS bytes never leave the host. */
static int detect_default_ttl(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return 64;
    int ttl = 0;
    socklen_t len = sizeof(ttl);
    if (getsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, &len) < 0 || ttl <= 0) {
        ttl = 64;
    }
    close(fd);
    return ttl;
}

static void init_params(const JniConfig *cfg) {
    memset(&params,    0, sizeof(params));
    memset(&fake_tls,  0, sizeof(fake_tls));
    memset(&fake_http, 0, sizeof(fake_http));
    memset(&fake_udp,  0, sizeof(fake_udp));

    params.mode      = MODE_SOCKS5;
    params.resolve   = true;
    // v3.7.1: IPv6 destinations через dual-stack baddr (см. ниже).
    params.ipv6      = true;
    // SOCKS5 UDP ASSOCIATE — QUIC/DNS-over-UDP из tun2socks.
    params.udp       = true;
    params.max_open  = 512;
    params.bfsize    = 16384;
    params.debug     = 0;

    // v3.7.14: CRITICAL — def_ttl must be a real host TTL.
    // Universal preset uses disorder (DESYNC_DISORDER): first TCP fragment
    // is sent with TTL=1 to confuse DPI, then restore_state() restores
    // params.def_ttl for the rest of the stream. With def_ttl left at 0
    // after memset, every byte after the first split left with TTL=0 and
    // never reached the remote host. Symptom: sites like meduza.io /
    // cloudflare HTTPS hang forever ("site unavailable") while the VPN
    // itself looks healthy (notification, packet counters).
    params.def_ttl   = detect_default_ttl();
    params.custom_ttl = false;
    LOGI("ciadpi def_ttl=%d", params.def_ttl);

    // Dual-stack baddr: AF_INET6 + in6addr_any.
    params.baddr.in6.sin6_family = AF_INET6;
    params.baddr.in6.sin6_addr   = in6addr_any;
    params.baddr.in6.sin6_port   = 0;

    // v3.7.10: mempool required by cache_get()/cache_add() in extend.c.
    if (!params.mempool) {
        params.mempool = mem_pool(MF_EXTRA, CMP_BITS);
        if (!params.mempool) {
            LOGE("OOM: mem_pool (mempool)");
        }
    }

    params.dp = (struct desync_params *)calloc(1, sizeof(struct desync_params));
    if (!params.dp) { LOGE("OOM: desync_params"); return; }
    params.dp_n = 1;
    params.dp_full_mask = 1;
    params.dp->bit = 1;
    params.dp->id  = 0;

    struct desync_params *dp = params.dp;

    // Default OOB character (byedpi uses 'a' when oob_char[1]==0).
    dp->oob_char[0] = 'a';
    dp->oob_char[1] = 0;

    // Build desync part list: primary split/disorder/oob at configured pos.
    // Prefer OOB when requested (universal preset: oob 1+s); fall back to
    // disorder/split. Always ensure at least one part so desync runs.
    int mode = DESYNC_SPLIT;
    int pos  = cfg->split_pos > 0 ? cfg->split_pos : 1;
    int flag = cfg->split_flag;

    if (cfg->oob_enabled) {
        mode = cfg->disorder ? DESYNC_DISOOB : DESYNC_OOB;
        if (cfg->oob_pos > 0) pos = cfg->oob_pos;
        flag = cfg->oob_flag ? cfg->oob_flag : flag;
    } else if (cfg->disorder) {
        mode = DESYNC_DISORDER;
    } else if (cfg->fake_enabled) {
        mode = DESYNC_FAKE;
    }

    dp->parts = (struct part *)calloc(1, sizeof(struct part));
    if (dp->parts) {
        dp->parts[0].pos  = pos;
        dp->parts[0].m    = mode;
        dp->parts[0].flag = flag;
        dp->parts[0].r    = 1;
        dp->parts[0].s    = 0;
        dp->parts_n       = 1;
    }

    if (cfg->drop_sack) {
        dp->drop_sack = true;
    }

    if (cfg->fake_enabled) {
        dp->ttl = cfg->fake_ttl > 0 ? cfg->fake_ttl : 6;
        // When fake is the primary mode it is already in parts[0]; when
        // combined with split/disorder, add a FAKE part at the same offset
        // so DPI sees a low-TTL decoy ClientHello first.
        if (mode != DESYNC_FAKE && dp->parts) {
            struct part *np = (struct part *)realloc(dp->parts, 2 * sizeof(struct part));
            if (np) {
                dp->parts = np;
                // Insert FAKE as first part, shift original to second.
                dp->parts[1] = dp->parts[0];
                dp->parts[0].pos  = pos;
                dp->parts[0].m    = DESYNC_FAKE;
                dp->parts[0].flag = flag;
                dp->parts[0].r    = 1;
                dp->parts[0].s    = 0;
                dp->parts_n       = 2;
            }
        }
        if (fake_tls_init(&fake_tls, 1024) < 0)
            LOGE("fake_tls_init failed");
        if (fake_http_init(&fake_http) < 0)
            LOGE("fake_http_init failed");
    }

    // TLS record split (byedpi -r): splits the TLS record layer so SNI
    // spans two TLS records — effective against many TSPU signatures.
    if (cfg->tlsrec_pos > 0) {
        dp->tlsrec = (struct part *)calloc(1, sizeof(struct part));
        if (dp->tlsrec) {
            dp->tlsrec[0].pos  = cfg->tlsrec_pos;
            dp->tlsrec[0].flag = cfg->tlsrec_flag;
            dp->tlsrec[0].m    = DESYNC_SPLIT;
            dp->tlsrec[0].r    = 1;
            dp->tlsrec_n       = 1;
        }
    }

    if (cfg->udp_fake_count > 0) {
        dp->udp_fake_count = cfg->udp_fake_count;
    }

    if (cfg->mod_http) {
        dp->mod_http = cfg->mod_http;
    }

    LOGI("ciadpi desync: mode=%d pos=%d flag=0x%x fake=%d ttl=%d tlsrec=%d oob=%d sack=%d",
         mode, pos, flag, cfg->fake_enabled, dp->ttl, cfg->tlsrec_pos,
         cfg->oob_enabled, cfg->drop_sack);
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
    if (params.mempool) {
        mem_destroy(params.mempool);
        params.mempool = NULL;
    }
    free(fake_tls.data);  fake_tls.data  = NULL;
    free(fake_http.data); fake_http.data = NULL;
    free(fake_udp.data);  fake_udp.data  = NULL;
}

static void *ciadpi_thread(void *arg) {
    crash_handler_install_altstack_current_thread();

    JniConfig *cfg = (JniConfig *)arg;
    LOGI("ciadpi starting on SOCKS5 127.0.0.1:%d split=%d disorder=%d fake=%d oob=%d",
         cfg->socks_port, cfg->split_pos, cfg->disorder, cfg->fake_enabled,
         cfg->oob_enabled);
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

/* Parse byedpi-style position string: "1", "1+s", "2+s", "1+m", "end", "r".
 * Returns numeric base position (>=1) and fills *out_flag with OFFSET_* bits. */
static int parse_pos_string(JNIEnv *env, jstring jstr, int default_pos, int *out_flag) {
    *out_flag = 0;
    if (!jstr) return default_pos;
    const char *s = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (!s) return default_pos;
    int pos = default_pos;
    int flag = 0;
    // Leading number
    if (s[0] >= '0' && s[0] <= '9') {
        pos = atoi(s);
        if (pos <= 0) pos = default_pos;
    } else if (s[0] == 'e' || s[0] == 'E') {
        // "end"
        flag |= OFFSET_END;
        pos = 0;
    } else if (s[0] == 'm' || s[0] == 'M') {
        flag |= OFFSET_MID;
        pos = 0;
    } else if (s[0] == 'r' || s[0] == 'R') {
        flag |= OFFSET_RAND;
        pos = 0;
    }
    // Flags after '+' or anywhere in the string
    if (strchr(s, 's') || strchr(s, 'S')) flag |= OFFSET_SNI;
    if (strchr(s, 'h') || strchr(s, 'H')) flag |= OFFSET_HOST;
    if (strchr(s, 'm') || strchr(s, 'M')) {
        // "1+m" mid-host; bare "m" already handled
        if (s[0] >= '0' && s[0] <= '9') flag |= OFFSET_MID;
    }
    if (strchr(s, 'e') || strchr(s, 'E')) {
        if (s[0] >= '0' && s[0] <= '9') flag |= OFFSET_END;
    }
    if (strchr(s, 'r') || strchr(s, 'R')) {
        if (s[0] >= '0' && s[0] <= '9') flag |= OFFSET_RAND;
    }
    *out_flag = flag;
    (*env)->ReleaseStringUTFChars(env, jstr, s);
    return pos;
}

/* Parse byedpi -M http mods: "h,d,r" → bit flags (packets.h):
 *   MH_HMIX=1, MH_SPACE=2, MH_DMIX=4 */
static int parse_mod_http(JNIEnv *env, jstring jstr) {
    if (!jstr) return 0;
    const char *s = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (!s) return 0;
    int flags = 0;
    for (const char *p = s; *p; p++) {
        if (*p == 'h' || *p == 'H') flags |= MH_HMIX;
        if (*p == 'd' || *p == 'D') flags |= MH_DMIX;
        if (*p == 'r' || *p == 'R') flags |= MH_SPACE;
    }
    (*env)->ReleaseStringUTFChars(env, jstr, s);
    return flags;
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
        jstring  autoMode,
        jstring  splitPosStr,
        jstring  oobPosStr,
        jstring  tlsRecStr,
        jstring  modHttpStr,
        jint     udpFakeCount)
{
    (void)thiz; (void)autoMode;
    if (g_running) { LOGI("ciadpi already running"); return 0; }

    memset(&g_cfg, 0, sizeof(g_cfg));
    g_cfg.socks_port   = (int)socksPort;
    g_cfg.disorder     = (int)disorder;
    g_cfg.fake_enabled = (int)fakeEnabled;
    g_cfg.fake_ttl     = (int)fakeTtl;
    g_cfg.drop_sack    = (int)dropSack;
    g_cfg.udp_fake_count = (int)udpFakeCount;

    // Prefer rich position strings from Kotlin presets; fall back to numeric splitPos.
    int split_flag = 0;
    int parsed_split = parse_pos_string(env, splitPosStr, (int)splitPos > 0 ? (int)splitPos : 1, &split_flag);
    g_cfg.split_pos  = parsed_split > 0 ? parsed_split : 1;
    g_cfg.split_flag = split_flag;

    int oob_flag = 0;
    int oob_pos = parse_pos_string(env, oobPosStr, 0, &oob_flag);
    if (oobPosStr != NULL && (*env)->GetStringUTFLength(env, oobPosStr) > 0) {
        g_cfg.oob_enabled = 1;
        g_cfg.oob_pos     = oob_pos > 0 ? oob_pos : 1;
        g_cfg.oob_flag    = oob_flag;
    }

    int tls_flag = 0;
    int tls_pos = parse_pos_string(env, tlsRecStr, 0, &tls_flag);
    if (tlsRecStr != NULL && (*env)->GetStringUTFLength(env, tlsRecStr) > 0) {
        g_cfg.tlsrec_pos  = tls_pos > 0 ? tls_pos : 1;
        g_cfg.tlsrec_flag = tls_flag;
    }

    g_cfg.mod_http = parse_mod_http(env, modHttpStr);

    // If nothing configured at all, force a minimal working desync (split@1)
    // so the engine never runs as a pure transparent SOCKS (which loses to DPI).
    if (!g_cfg.disorder && !g_cfg.oob_enabled && !g_cfg.fake_enabled
            && g_cfg.tlsrec_pos <= 0) {
        g_cfg.split_pos = g_cfg.split_pos > 0 ? g_cfg.split_pos : 1;
        g_cfg.disorder  = 1; // disorder@1 is the most universal TSPU bypass
    }

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
