/*
 * crash_handler.c — myVPNproject v3.7.3 CIS-MAX
 * См. crash_handler.h — минимальный async-signal-safe сборщик крашей для
 * отладки на устройствах без adb (пишет только через write(), без malloc/
 * printf внутри самого сигнального обработчика).
 */
#include "crash_handler.h"

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <pthread.h>

static volatile int g_log_fd = -1;
static struct sigaction g_old_handlers[64];
static int g_have_old[64];
static pthread_mutex_t g_install_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_installed = 0;

static const int kSignals[] = { SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP };
static const int kNumSignals = sizeof(kSignals) / sizeof(kSignals[0]);

static const char *signal_name(int sig) {
    switch (sig) {
        case SIGABRT: return "SIGABRT";
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGFPE:  return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN_SIGNAL";
    }
}

/* async-signal-safe: только write(), без malloc/printf/локалей. */
static void safe_write(const char *s) {
    if (g_log_fd < 0 || !s) return;
    size_t len = 0;
    while (s[len]) len++;
    ssize_t ignored = write(g_log_fd, s, len);
    (void)ignored;
}

static void safe_write_ulong_dec(unsigned long v) {
    char buf[24];
    int i = (int)sizeof(buf);
    buf[--i] = '\0';
    if (v == 0) {
        buf[--i] = '0';
    } else {
        while (v > 0 && i > 0) {
            buf[--i] = (char)('0' + (v % 10));
            v /= 10;
        }
    }
    safe_write(&buf[i]);
}

static void safe_write_ulong_hex(unsigned long v) {
    static const char *hexd = "0123456789abcdef";
    char buf[20];
    int i = (int)sizeof(buf);
    buf[--i] = '\0';
    if (v == 0) {
        buf[--i] = '0';
    } else {
        while (v > 0 && i > 0) {
            buf[--i] = hexd[v & 0xful];
            v >>= 4;
        }
    }
    safe_write("0x");
    safe_write(&buf[i]);
}

static void crash_signal_handler(int sig, siginfo_t *info, void *ucontext) {
    safe_write("\n=== NATIVE CRASH (myVPNproject) ===\nSignal: ");
    safe_write(signal_name(sig));
    safe_write(" (");
    safe_write_ulong_dec((unsigned long)sig);
    safe_write(")\n");
    if (info) {
        safe_write("si_code: ");
        safe_write_ulong_dec((unsigned long)info->si_code);
        safe_write("\nfaulting address: ");
        safe_write_ulong_hex((unsigned long)info->si_addr);
        safe_write("\n");
    }
    safe_write("pid: ");
    safe_write_ulong_dec((unsigned long)getpid());
    safe_write(" tid: ");
    safe_write_ulong_dec((unsigned long)gettid());
    safe_write("\n=== END NATIVE CRASH ===\n");

    if (g_log_fd >= 0) {
        fsync(g_log_fd);
    }

    /* Передаём управление предыдущему обработчику (обычно системный
     * debuggerd/libsigchain через Android's signal chaining) — так
     * tombstone/crash reporter продолжают работать штатно, мы только
     * успели сохранить диагностику в свой файл первыми. */
    int idx = (sig >= 0 && sig < 64) ? sig : -1;
    if (idx >= 0 && g_have_old[idx]) {
        struct sigaction *old = &g_old_handlers[idx];
        if ((old->sa_flags & SA_SIGINFO) && old->sa_sigaction) {
            old->sa_sigaction(sig, info, ucontext);
            return;
        } else if (old->sa_handler == SIG_DFL || old->sa_handler == NULL) {
            signal(sig, SIG_DFL);
            raise(sig);
            return;
        } else if (old->sa_handler != SIG_IGN) {
            old->sa_handler(sig);
            return;
        }
    }
    /* Нет предыдущего обработчика (или он SIG_IGN) — восстанавливаем
     * дефолтное поведение, чтобы процесс всё равно корректно завершился
     * (не зависнуть в цикле повторных сигналов). */
    signal(sig, SIG_DFL);
    raise(sig);
}

void crash_handler_install(const char *log_path) {
    pthread_mutex_lock(&g_install_mutex);

    if (g_log_fd < 0 && log_path) {
        g_log_fd = open(log_path, O_CREAT | O_WRONLY | O_APPEND, 0600);
    }

    if (!g_installed) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = crash_signal_handler;
        sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
        sigemptyset(&sa.sa_mask);

        for (int i = 0; i < kNumSignals; i++) {
            int sig = kSignals[i];
            if (sig < 0 || sig >= 64) continue;
            struct sigaction old;
            if (sigaction(sig, &sa, &old) == 0) {
                g_old_handlers[sig] = old;
                g_have_old[sig] = 1;
            }
        }
        g_installed = 1;
    }

    pthread_mutex_unlock(&g_install_mutex);
}

void crash_log_checkpoint(const char *tag) {
    /* Обычный (не сигнальный) контекст — можно использовать снапшот
     * времени и обычную запись, чтобы при краше по последней строке файла
     * было видно, на каком этапе всё упало, даже если сигнальный
     * обработчик не сработал (например, SIGKILL от low-memory killer). */
    if (g_log_fd < 0 || !tag) return;
    char line[256];
    time_t now = time(NULL);
    int n = snprintf(line, sizeof(line), "[checkpoint t=%ld] %s\n", (long)now, tag);
    if (n > 0) {
        ssize_t ignored = write(g_log_fd, line, (size_t)n);
        (void)ignored;
    }
}
