/*
 * crash_handler.h — myVPNproject v3.7.3 CIS-MAX
 *
 * Минимальный сборщик нативных крашей (SIGABRT/SIGSEGV/...) для отладки на
 * устройствах без доступа к `adb logcat`. Пишет диагностику в текстовый
 * файл внутри filesDir приложения, который затем показывается пользователю
 * прямо в UI (см. CrashLogger.kt / MainActivity.kt).
 *
 * Компилируется отдельно в КАЖДУЮ .so-библиотеку (ciadpi_jni.so и
 * tun2socks_jni.so) — так его можно вызывать из обоих модулей без
 * межбиблиотечного линковки. Повторная установка сигнального обработчика
 * из второй библиотеки безвредна (POSIX sigaction — процесс-глобальный,
 * последняя установка просто "побеждает", но обе версии делают то же
 * самое — пишут в один и тот же файл).
 */
#ifndef MYVPNPROJECT_CRASH_HANDLER_H
#define MYVPNPROJECT_CRASH_HANDLER_H

#ifdef __cplusplus
extern "C" {
#endif

/* Устанавливает обработчики SIGABRT/SIGSEGV/SIGBUS/SIGILL/SIGFPE/SIGTRAP,
 * пишущие краткую диагностику в файл по указанному пути (уже открывается
 * заранее, чтобы избежать open() внутри самого сигнального обработчика). */
void crash_handler_install(const char *log_path);

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
