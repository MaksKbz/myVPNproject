/**
 * ciadpi_jni.c
 * JNI-обёртка для запуска ByeDPI (ciadpi) C-движка.
 *
 * КАК ПОДКЛЮЧИТЬ C-ДВИЖОК:
 * 1. Добавить submodule:
 *    git submodule add https://github.com/hufrea/byedpi.git app/src/main/jni/ciadpi
 * 2. Раскомментировать #include ниже
 * 3. Собрать через ./gradlew assembleDebug
 *
 * До подключения submodule функции работают как заглушки — приложение
 * компилируется, но DPI-обход не активен.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#define LOG_TAG "ciadpi_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * После добавления submodule раскомментировать:
 * #include "ciadpi/main.h"
 */

/* Признак работы движка (простой флаг; в реальной реализации — атомик) */
static volatile int g_running = 0;

/**
 * Java: ProxyEngine.startCiadpi(args: Array<String>): Int
 * Запускает ciadpi с переданными CLI-аргументами.
 * Возвращает 0 при успехе, -1 при ошибке.
 */
JNIEXPORT jint JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_startCiadpi(
        JNIEnv *env,
        jobject thiz,
        jobjectArray jargs)
{
    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = (char **)malloc((argc + 1) * sizeof(char *));
    if (!argv) { LOGE("malloc failed"); return -1; }

    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *cstr = (*env)->GetStringUTFChars(env, jstr, NULL);
        argv[i] = strdup(cstr);
        (*env)->ReleaseStringUTFChars(env, jstr, cstr);
        (*env)->DeleteLocalRef(env, jstr);
    }
    argv[argc] = NULL;

    LOGI("startCiadpi called with %d args", argc);
    for (int i = 0; i < argc; i++) LOGI("  argv[%d] = %s", i, argv[i]);

    g_running = 1;

    /*
     * После подключения submodule заменить на реальный вызов:
     *   int result = ciadpi_main(argc, argv);
     * Функция ciadpi_main() блокируется до вызова stopCiadpi().
     */
    LOGI("[STUB] ciadpi engine not linked yet — add submodule to activate");
    int result = 0;

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    g_running = 0;
    return result;
}

/**
 * Java: ProxyEngine.stopCiadpi()
 * Останавливает работающий движок.
 */
JNIEXPORT void JNICALL
Java_com_makskbz_myvpnproject_vpn_ProxyEngine_stopCiadpi(
        JNIEnv *env,
        jobject thiz)
{
    LOGI("stopCiadpi called");
    g_running = 0;
    /*
     * После подключения submodule:
     *   ciadpi_stop();
     */
}
