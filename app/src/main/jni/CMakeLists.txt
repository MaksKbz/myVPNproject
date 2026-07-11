cmake_minimum_required(VERSION 3.22.1)
project(myvpnproject_native VERSION 1.0)

# ── byedpi vendored sources (always present, no submodule needed) ──
set(BYEDPI_DIR ${CMAKE_SOURCE_DIR}/byedpi)

# ── badvpn/tun2socks: vendored (not a submodule) since v3.7 CIS-MAX ──
set(BADVPN_DIR ${CMAKE_SOURCE_DIR}/badvpn)
if(EXISTS "${BADVPN_DIR}/tun2socks/tun2socks.c")
    set(BADVPN_AVAILABLE TRUE)
    message(STATUS "badvpn vendored sources FOUND — building real tun2socks")
else()
    set(BADVPN_AVAILABLE FALSE)
    message(STATUS "badvpn vendored sources NOT found — tun2socks stub mode")
endif()

# ── ciadpi_jni.so — ALWAYS real (byedpi vendored) ───────────────
# v3.7.3 CIS-MAX: crash_handler.c добавлен в оба .so-модуля — минимальный
# сборщик нативных крашей (SIGABRT/SIGSEGV/...) для отладки на устройствах
# без adb logcat (см. crash_handler.h/.c).
add_library(ciadpi_jni SHARED
    ciadpi_jni.c
    crash_handler.c
    byedpi/proxy.c
    byedpi/desync.c
    byedpi/extend.c
    byedpi/packets.c
    byedpi/conev.c
    byedpi/mpool.c
)
target_include_directories(ciadpi_jni PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${BYEDPI_DIR}
)
target_compile_definitions(ciadpi_jni PRIVATE
    ANDROID_APP=1
    FAKE_SUPPORT=1
    TIMEOUT_SUPPORT=1
)
target_compile_options(ciadpi_jni PRIVATE -O2 -Wall -fvisibility=hidden)
target_link_libraries(ciadpi_jni android log)

# ── tun2socks_jni.so — stub or real (badvpn) ─────────────────────
add_library(tun2socks_jni SHARED tun2socks_jni.c crash_handler.c)
target_include_directories(tun2socks_jni PRIVATE ${CMAKE_SOURCE_DIR})

if(BADVPN_AVAILABLE)
    # v3.7 CIS-MAX: собираем badvpn/lwIP как статическую библиотеку и
    # линкуем в tun2socks_jni.so. Список файлов и флаги компиляции
    # взяты из badvpn/compile-tun2socks.sh (официальный "быстрый Linux
    # билд без CMake" от автора badvpn) — Android/Bionic поддерживает
    # тот же набор Linux-специфичных API (epoll, signalfd), что и
    # обычный Linux, поэтому эти флаги переносятся без изменений.
    # Дополнительно: -DTUN2SOCKS_LIBRARY_MODE активирует библиотечный
    # режим (tun2socks_bridge_run/stop) вместо CLI int main() — см.
    # tun2socks/tun2socks_bridge.h и патч в tun2socks/tun2socks.c.
    #
    # Проверено сборкой + E2E-тестом (TUN + фейковый SOCKS5-сервер) на
    # Linux x86_64 в рамках разработки — весь путь TUN → lwIP →
    # BSocksClient → SOCKS5 → обратно подтверждён рабочим.
    add_library(badvpn_tun2socks STATIC
        ${BADVPN_DIR}/base/BLog.c
        ${BADVPN_DIR}/base/BLog_syslog.c
        ${BADVPN_DIR}/base/BPending.c
        ${BADVPN_DIR}/base/DebugObject.c
        ${BADVPN_DIR}/system/BReactor_badvpn.c
        ${BADVPN_DIR}/system/BSignal.c
        ${BADVPN_DIR}/system/BConnection_unix.c
        ${BADVPN_DIR}/system/BConnection_common.c
        ${BADVPN_DIR}/system/BTime.c
        ${BADVPN_DIR}/system/BUnixSignal.c
        ${BADVPN_DIR}/system/BNetwork.c
        ${BADVPN_DIR}/system/BDatagram_common.c
        ${BADVPN_DIR}/system/BDatagram_unix.c
        ${BADVPN_DIR}/flow/StreamRecvInterface.c
        ${BADVPN_DIR}/flow/PacketRecvInterface.c
        ${BADVPN_DIR}/flow/PacketPassInterface.c
        ${BADVPN_DIR}/flow/StreamPassInterface.c
        ${BADVPN_DIR}/flow/SinglePacketBuffer.c
        ${BADVPN_DIR}/flow/BufferWriter.c
        ${BADVPN_DIR}/flow/PacketBuffer.c
        ${BADVPN_DIR}/flow/PacketStreamSender.c
        ${BADVPN_DIR}/flow/PacketPassConnector.c
        ${BADVPN_DIR}/flow/PacketProtoFlow.c
        ${BADVPN_DIR}/flow/PacketPassFairQueue.c
        ${BADVPN_DIR}/flow/PacketProtoEncoder.c
        ${BADVPN_DIR}/flow/PacketProtoDecoder.c
        ${BADVPN_DIR}/socksclient/BSocksClient.c
        ${BADVPN_DIR}/tuntap/BTap.c
        ${BADVPN_DIR}/lwip/src/core/udp.c
        ${BADVPN_DIR}/lwip/src/core/memp.c
        ${BADVPN_DIR}/lwip/src/core/init.c
        ${BADVPN_DIR}/lwip/src/core/pbuf.c
        ${BADVPN_DIR}/lwip/src/core/tcp.c
        ${BADVPN_DIR}/lwip/src/core/tcp_out.c
        ${BADVPN_DIR}/lwip/src/core/sys.c
        ${BADVPN_DIR}/lwip/src/core/netif.c
        ${BADVPN_DIR}/lwip/src/core/def.c
        ${BADVPN_DIR}/lwip/src/core/mem.c
        ${BADVPN_DIR}/lwip/src/core/tcp_in.c
        ${BADVPN_DIR}/lwip/src/core/stats.c
        ${BADVPN_DIR}/lwip/src/core/ip.c
        ${BADVPN_DIR}/lwip/src/core/timeouts.c
        ${BADVPN_DIR}/lwip/src/core/inet_chksum.c
        ${BADVPN_DIR}/lwip/src/core/ipv4/icmp.c
        ${BADVPN_DIR}/lwip/src/core/ipv4/ip4.c
        ${BADVPN_DIR}/lwip/src/core/ipv4/ip4_addr.c
        ${BADVPN_DIR}/lwip/src/core/ipv4/ip4_frag.c
        ${BADVPN_DIR}/lwip/src/core/ipv6/ip6.c
        ${BADVPN_DIR}/lwip/src/core/ipv6/nd6.c
        ${BADVPN_DIR}/lwip/src/core/ipv6/icmp6.c
        ${BADVPN_DIR}/lwip/src/core/ipv6/ip6_addr.c
        ${BADVPN_DIR}/lwip/src/core/ipv6/ip6_frag.c
        ${BADVPN_DIR}/lwip/custom/sys.c
        ${BADVPN_DIR}/flowextra/PacketPassInactivityMonitor.c
        ${BADVPN_DIR}/tun2socks/SocksUdpGwClient.c
        ${BADVPN_DIR}/udpgw_client/UdpGwClient.c
        ${BADVPN_DIR}/socks_udp_client/SocksUdpClient.c
        ${BADVPN_DIR}/tun2socks/tun2socks.c
    )
    target_include_directories(badvpn_tun2socks PUBLIC
        ${BADVPN_DIR}
        ${BADVPN_DIR}/lwip/src/include
        ${BADVPN_DIR}/lwip/src/include/ipv4
        ${BADVPN_DIR}/lwip/src/include/ipv6
        ${BADVPN_DIR}/lwip/custom
        ${BADVPN_DIR}/tun2socks
        ${CMAKE_SOURCE_DIR}
    )
    target_compile_definitions(badvpn_tun2socks PRIVATE
        TUN2SOCKS_LIBRARY_MODE=1
        BADVPN_THREAD_SAFE=0
        BADVPN_LINUX=1
        BADVPN_BREACTOR_BADVPN=1
        _GNU_SOURCE=1
        BADVPN_USE_SIGNALFD=1
        BADVPN_USE_EPOLL=1
        BADVPN_LITTLE_ENDIAN=1
    )
    target_compile_options(badvpn_tun2socks PRIVATE -std=gnu99 -O2 -w)

    target_include_directories(tun2socks_jni PRIVATE
        ${BADVPN_DIR} ${BADVPN_DIR}/tun2socks)
    target_compile_definitions(tun2socks_jni PRIVATE BADVPN_AVAILABLE=1)
    target_link_libraries(tun2socks_jni badvpn_tun2socks android log)
else()
    target_link_libraries(tun2socks_jni android log)
endif()
