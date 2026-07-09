/*
 * tun2socks_bridge.h — myVPNproject v3.7 CIS-MAX
 *
 * Библиотечный API поверх оригинального badvpn/tun2socks.c, добавленный
 * для встраивания tun2socks в Android-приложение через JNI без отдельного
 * процесса и без CLI-аргументов.
 *
 * Ключевое отличие от апстримного CLI (`badvpn-tun2socks --tundev ...`):
 * TUN-интерфейс не открывается по имени устройства (это невозможно без
 * root на Android), а принимается как уже открытый файловый дескриптор
 * (полученный из VpnService.Builder.establish()), через существующий в
 * badvpn/tuntap/BTap.h механизм BTAP_INIT_FD.
 *
 * Жизненный цикл:
 *   1. tun2socks_bridge_run() — блокирующий вызов, разворачивает полный
 *      event loop (BReactor + lwIP), должен вызываться из отдельного
 *      pthread. Возвращает 0 при штатном завершении (после
 *      tun2socks_bridge_stop()), -1 при ошибке инициализации.
 *   2. tun2socks_bridge_stop() — потокобезопасно (можно звать из другого
 *      потока) запрашивает остановку event loop через self-pipe,
 *      зарегистрированный в реакторе — обычный BReactor_Quit() из чужого
 *      потока небезопасен, т.к. реактор не защищён мьютексами (single
 *      threaded design, см. lwipopts.h NO_SYS=1).
 */

#ifndef TUN2SOCKS_BRIDGE_H
#define TUN2SOCKS_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Совместим с сигнатурой _BLog_log_func из base/BLog.h — принимает уже
 * отформатированную строку лога badvpn/lwIP. level: 1=ERROR..5=DEBUG
 * (см. BLOG_ERROR..BLOG_DEBUG в base/BLog.h).
 */
typedef void (*Tun2SocksBridgeLogFunc)(int channel, int level, const char *msg);

struct tun2socks_bridge_config {
    int tun_fd;                 /* уже открытый TUN fd (dup'нутый, см. .c) */
    int mtu;                    /* MTU TUN-интерфейса, напр. 1500 */
    const char *netif_ipaddr;   /* IP виртуального роутера внутри TUN, напр. "10.0.0.1" */
    const char *netif_netmask;  /* напр. "255.255.255.0" */
    const char *netif_ip6addr;  /* напр. "fd00:1:fd00:1:fd00:1:fd00:2", может быть NULL */
    const char *socks_server_addr; /* напр. "127.0.0.1:1080" (ciadpi) */
    Tun2SocksBridgeLogFunc log_func; /* может быть NULL -> лог отключён */
};

/**
 * Запускает tun2socks event loop. БЛОКИРУЕТ вызывающий поток до вызова
 * tun2socks_bridge_stop() из другого потока.
 *
 * @return 0 при штатной остановке, -1 при ошибке инициализации.
 */
int tun2socks_bridge_run(const struct tun2socks_bridge_config *cfg);

/**
 * Запрашивает остановку запущенного tun2socks_bridge_run(). Потокобезопасно.
 * Не блокирует — tun2socks_bridge_run() вернётся из своего потока после
 * корректного освобождения ресурсов.
 */
void tun2socks_bridge_stop(void);

#ifdef __cplusplus
}
#endif

#endif
