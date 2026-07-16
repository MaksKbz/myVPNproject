package com.makskbz.myvpnproject.vpn

import android.net.VpnService
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Ipv6Connectivity — v3.7.18 CIS-MAX
 *
 * КРИТИЧЕСКАЯ НАХОДКА: подробный checkpoint-лог (v3.7.17 diagnostic-
 * строки) показал, что на реальном устройстве пользователя ВСЕ без
 * исключения попытки `connect()` к реальным сайтам (порт 443) —
 * meduza.io (Cloudflare/DDoS-Guard, `2a06:98c1:52::3`), Google
 * (`2001:4860:...`), Cloudflare (`2606:4700:...`) — шли по IPv6 и
 * ВСЕ проваливались с `errno=101` (ENETUNREACH — "сеть недостижима").
 * При этом ЕДИНСТВЕННЫЕ успешные исходящие соединения за весь тест —
 * DNS-over-TLS на порт 853 к 1.1.1.1/8.8.8.8 — шли по IPv4.
 *
 * ENETUNREACH на connect() (не ETIMEDOUT/ECONNREFUSED) означает, что
 * ядро Android НЕ МОЖЕТ найти маршрут к IPv6-адресу вообще — это
 * типичная ситуация для многих мобильных операторов СНГ/РФ, у которых
 * mobile data канал попросту IPv4-only (нет native IPv6 на APN).
 * `VpnService.protect()` тут ни при чём — она лишь помечает сокет через
 * `SO_MARK`, чтобы обойти сам VPN-туннель, но не может создать
 * несуществующий IPv6-маршрут на реальном сетевом интерфейсе.
 *
 * ПРОБЛЕМА АРХИТЕКТУРЫ: наш собственный VPN-туннель вешает на TUN
 * приватный IPv6-адрес (`addAddress("fd00:...", 64)` в
 * BypassVpnService + netif_ip6addr в tun2socks_jni.c, добавлено в
 * v3.7.14 для ДРУГОГО сценария — когда IPv6 у оператора РЕАЛЬНО есть).
 * Из-за этого Android/Chrome видят "рабочий" IPv6-путь внутри VPN и по
 * алгоритму Happy Eyeballs предпочитают его — а он гарантированно
 * проваливается, потому что за TUN'ом никакого реального IPv6 нет.
 * Уходят драгоценные секунды на заведомо мёртвые попытки, прежде чем
 * браузер (если вообще) откатится на IPv4 — со стороны пользователя
 * это выглядит как "сайт не открывается" или открывается через раз.
 *
 * РЕШЕНИЕ: перед установкой VPN-туннеля (когда обычная сеть ещё
 * доступна) пробуем открыть настоящий IPv6 TCP-сокет к публичному
 * IPv6-адресу (Google DNS, 2001:4860:4860::8888:443) НАПРЯМУЮ, в обход
 * VPN. Если это удаётся — у оператора есть реальный IPv6, и мы
 * оставляем IPv6 в TUN/ciadpi включённым (текущее поведение). Если
 * не удаётся (или таймаут) — оператор IPv4-only, и мы полностью
 * отключаем IPv6 на всех трёх уровнях (VpnService.Builder.addAddress,
 * tun2socks netif_ip6addr, ciadpi params.ipv6) — тогда ни Android, ни
 * Chrome не увидят внутри VPN никакого IPv6-пути и сразу пойдут по
 * IPv4, который реально работает.
 */
object Ipv6Connectivity {

    private const val TAG = "Ipv6Connectivity"

    // Google Public DNS — стабильный, всегда отвечает на TCP/443 (HTTPS
    // сервис на dns.google), хороший индикатор реальной IPv6-связности.
    private const val PROBE_HOST = "2001:4860:4860::8888"
    private const val PROBE_PORT = 443
    private const val PROBE_TIMEOUT_MS = 1500

    /**
     * Проверяет, есть ли у устройства реальный (не через наш VPN) выход
     * в IPv6-интернет. Вызывать ДО builder.establish(), пока обычная
     * сеть ещё не подменена туннелем.
     *
     * @return true, если удалось установить TCP-соединение по IPv6.
     */
    fun hasRealIpv6(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MS)
                Log.i(TAG, "Real IPv6 connectivity detected ($PROBE_HOST reachable)")
                true
            }
        } catch (e: Exception) {
            Log.i(TAG, "No real IPv6 connectivity ($PROBE_HOST unreachable: ${e.message}) — " +
                    "will disable IPv6 in VPN tunnel to avoid dead Happy-Eyeballs attempts")
            false
        }
    }
}
