cmake_minimum_required(VERSION 3.22.1)
project(myvpnproject_native VERSION 1.0)

# ──────────────────────────────────────────────────────────────
# Stub-режим: сборка проходит без submodule-ов.
# JNI-мосты компилируются всегда — они не зависят от submodule.
# Реальный DPI-обход активируется после:
#   git submodule add https://github.com/hufrea/byedpi.git  app/src/main/jni/byedpi
#   git submodule add https://github.com/ambrop72/badvpn.git app/src/main/jni/badvpn
#   git submodule update --init --recursive
# ──────────────────────────────────────────────────────────────

set(BYEDPI_DIR ${CMAKE_SOURCE_DIR}/byedpi)
set(BADVPN_DIR ${CMAKE_SOURCE_DIR}/badvpn)

# ── Определяем наличие submodule-ов ──────────────────────────
if(EXISTS "${BYEDPI_DIR}/proxy.c")
    set(BYEDPI_AVAILABLE TRUE)
    message(STATUS "byedpi submodule FOUND — building real ciadpi")
else()
    set(BYEDPI_AVAILABLE FALSE)
    message(STATUS "byedpi submodule NOT found — stub mode (ciadpi_jni.so without byedpi)")
endif()

if(EXISTS "${BADVPN_DIR}/tun2socks/tun2socks.c")
    set(BADVPN_AVAILABLE TRUE)
    message(STATUS "badvpn submodule FOUND — building real tun2socks")
else()
    set(BADVPN_AVAILABLE FALSE)
    message(STATUS "badvpn submodule NOT found — stub mode (tun2socks_jni.so without badvpn)")
endif()

# ── ciadpi (byedpi) — только если submodule добавлен ─────────
if(BYEDPI_AVAILABLE)
    add_library(ciadpi STATIC
        ${BYEDPI_DIR}/desync.c
        ${BYEDPI_DIR}/proxy.c
        ${BYEDPI_DIR}/packet.c
        ${BYEDPI_DIR}/utils.c
        ${BYEDPI_DIR}/params.c
    )
    target_include_directories(ciadpi PUBLIC ${BYEDPI_DIR})
    target_compile_options(ciadpi PRIVATE -O2 -Wall -Wextra)
endif()

# ── tun2socks (badvpn) — только если submodule добавлен ──────
if(BADVPN_AVAILABLE)
    add_library(tun2socks STATIC
        ${BADVPN_DIR}/tun2socks/tun2socks.c
        ${BADVPN_DIR}/tun2socks/socksclient.c
        ${BADVPN_DIR}/base/BLog_syslog.c
        ${BADVPN_DIR}/system/BReactor_badvpn.c
    )
    target_include_directories(tun2socks PUBLIC
        ${BADVPN_DIR}
        ${BADVPN_DIR}/tun2socks
    )
    target_compile_options(tun2socks PRIVATE -O2 -Wall)
endif()

# ── JNI bridge: ciadpi_jni.so — собирается ВСЕГДА ────────────
add_library(ciadpi_jni SHARED ciadpi_jni.c)
target_include_directories(ciadpi_jni PRIVATE
    ${CMAKE_SOURCE_DIR}  # для доступа к локальным заголовкам
)
if(BYEDPI_AVAILABLE)
    target_include_directories(ciadpi_jni PRIVATE ${BYEDPI_DIR})
    target_compile_definitions(ciadpi_jni PRIVATE BYEDPI_AVAILABLE=1)
    target_link_libraries(ciadpi_jni ciadpi android log)
else()
    target_link_libraries(ciadpi_jni android log)
endif()

# ── JNI bridge: tun2socks_jni.so — собирается ВСЕГДА ─────────
add_library(tun2socks_jni SHARED tun2socks_jni.c)
target_include_directories(tun2socks_jni PRIVATE
    ${CMAKE_SOURCE_DIR}
)
if(BADVPN_AVAILABLE)
    target_include_directories(tun2socks_jni PRIVATE
        ${BADVPN_DIR}
        ${BADVPN_DIR}/tun2socks
    )
    target_compile_definitions(tun2socks_jni PRIVATE BADVPN_AVAILABLE=1)
    target_link_libraries(tun2socks_jni tun2socks android log)
else()
    target_link_libraries(tun2socks_jni android log)
endif()
