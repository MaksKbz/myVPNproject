/**
 * tun2socks_jni.c
 * JNI-обёртка для tun2socks — моста между TUN-интерфейсом и локальным SOCKS5.
 *
 * КАК ПОДКЛЮЧИТЬ:
 * 1. Добавить submodule badvpn или hev-socks5-tunnel:
 *    git submodule add https://github.com/ambrop72/badvpn.git app/src/main/jni/tun2socks
 * 2. Раскомментировать #include
 * 3. Заменить заглушку на реальный вызов tun2socks_main()
 *
 * Альтернатива: использовать готовую .aar библиотеку от ByeDPIAndroid.
 */

#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#define LOG_TAG "tun2socks_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static volatile int g_running = 0;

/**
 * Java: ProxyEngine.startTun2socks(tunFd: Int, socksAddr: String, socksPort: Int): Int
 * Запускает мост TUN → SOCKS5.
 * tunFd — файловый дескриптор TUN-интерфейса (из ParcelFileDescriptor.detachFd())
 * socksAddr — адрес SOCKS5-прокси (обычно "127.0.0.1")
 * socksPort — порт SOCKS5-прокси (обычно 1080)
 */
JNIEXPORT jint JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_startTun2socks(
        JNIEnv *env,
        jobject thiz,
        jint tunFd,
        jstring jSocksAddr,
        jint socksPort)
{
    const char *socksAddr = (*env)->GetStringUTFChars(env, jSocksAddr, NULL);
    LOGI("startTun2socks: fd=%d socks=%s:%d", tunFd, socksAddr, socksPort);
    g_running = 1;

    /*
     * После подключения submodule:
     *   tun2socks_main(tunFd, socksAddr, socksPort);
     */
    LOGI("[STUB] tun2socks not linked yet — add submodule to activate");

    (*env)->ReleaseStringUTFChars(env, jSocksAddr, socksAddr);
    g_running = 0;
    return 0;
}

/**
 * Java: ProxyEngine.stopTun2socks()
 */
JNIEXPORT void JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_stopTun2socks(
        JNIEnv *env,
        jobject thiz)
{
    LOGI("stopTun2socks called");
    g_running = 0;
    /*
     * После подключения submodule:
     *   tun2socks_stop();
     */
}
