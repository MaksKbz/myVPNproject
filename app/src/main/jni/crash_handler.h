/*
 * crash_handler.h — myVPNproject v3.7.4 CIS-MAX
 *
 * Минимальный сборщик нативных крашей (SIGABRT/SIGSEGV/...) для отладки на
 * устройствах без доступа к `adb logcat`. Пишет диагностику в текстовый
 * файл внутри filesDir приложения, который затем показывается пользователю
 * прямо в UI (см. CrashLogger.kt / MainActivity.kt).
 *
 * ВАЖНО (найденный в v3.7.3 баг): этот файл компилируется ОТДЕЛЬНО в
 * КАЖДУЮ .so-библиотеку (ciadpi_jni.so и tun2socks_jni.so) — после
 * линковки это два НЕЗАВИСИМЫХ набора глобальных переменных (g_log_fd и
 * т.д.), а не общий модуль с единым состоянием на процесс. Значит
 * crash_handler_install() нужно вызывать ЯВНО из ОБЕИХ библиотек — вызов
 * только с одной стороны оставляет вторую библиотеку с g_log_fd == -1,
 * и все crash_log_checkpoint()/сигнальные обработчики там молча ничего
 * не пишут (см. ProxyEngine.installCrashHandler() — вызывает JNI-функции
 * из обоих модулей: installCrashHandler() и installCrashHandlerTun2socks()).
 */
#ifndef MYVPNPROJECT_CRASH_HANDLER_H
#define MYVPNPROJECT_CRASH_HANDLER_H

#ifdef __cplusplus
extern "C" {
#endif

/* Устанавливает обработчики SIGABRT/SIGSEGV/SIGBUS/SIGILL/SIGFPE/SIGTRAP,
 * пишущие краткую диагностику в файл по указанному пути (уже открывается
 * заранее, чтобы избежать open() внутри самого сигнального обработчика).
 * Также регистрирует альтернативный стек (sigaltstack) для ТЕКУЩЕГО
 * потока — см. crash_handler_install_altstack_current_thread() ниже,
 * если краш может произойти в другом потоке (обычная ситуация, т.к.
 * ciadpi/tun2socks работают в отдельных pthread). */
void crash_handler_install(const char *log_path);

/* v3.7.4 CIS-MAX: sigaltstack() — per-thread настройка, а не глобальная.
 * Если поток, в котором краш реально происходит (например,
 * ciadpi_thread/tun2socks_thread), не зарегистрировал свой альтернативный
 * стек, обработчик при переполнении стека этого потока попытается
 * выполниться на уже испорченной памяти и сам немедленно погибнет молча
 * (двойной фолт, без единой строки в логе — именно так и проявлялся баг
 * до этого фикса). Вызывать в начале каждого рабочего потока, где может
 * произойти краш, ДО любого потенциально опасного кода. */
void crash_handler_install_altstack_current_thread(void);

/* Записывает "контрольную точку" — обычная (не async-signal-safe) запись
 * в тот же файл, вызывается ДО потенциально опасных операций (запуск
 * ciadpi/tun2socks, инициализация badvpn), чтобы при краше по последней
 * строке файла было понятно, где именно всё упало, даже если сигнальный
 * обработчик не успел или не смог собрать полный стектрейс. */
void crash_log_checkpoint(const char *tag);

#ifdef __cplusplus
}
#endif

#endif /* MYVPNPROJECT_CRASH_HANDLER_H */
