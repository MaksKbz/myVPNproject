cmake_minimum_required(VERSION 3.22.1)
project(myvpnproject_native VERSION 1.0)

# ──────────────────────────────────────────────────────────────
# Пути к submodule-ам (добавляются командами в README):
#   git submodule add https://github.com/hufrea/byedpi.git   app/src/main/jni/byedpi
#   git submodule add https://github.com/ambrop72/badvpn.git  app/src/main/jni/badvpn
# ──────────────────────────────────────────────────────────────

set(BYEDPI_DIR  ${CMAKE_SOURCE_DIR}/byedpi)
set(BADVPN_DIR  ${CMAKE_SOURCE_DIR}/badvpn)

# ── ciadpi (byedpi) ──────────────────────────────────────────
add_library(ciadpi STATIC
    ${BYEDPI_DIR}/desync.c
    ${BYEDPI_DIR}/proxy.c
    ${BYEDPI_DIR}/packet.c
    ${BYEDPI_DIR}/utils.c
    ${BYEDPI_DIR}/params.c
)
target_include_directories(ciadpi PUBLIC ${BYEDPI_DIR})
target_compile_options(ciadpi PRIVATE -O2 -Wall -Wextra)

# ── tun2socks (badvpn/tun2socks) ─────────────────────────────
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

# ── JNI bridge: ciadpi ────────────────────────────────────────
add_library(ciadpi_jni SHARED
    ciadpi_jni.c
)
target_include_directories(ciadpi_jni PRIVATE ${BYEDPI_DIR})
target_link_libraries(ciadpi_jni
    ciadpi
    android
    log
)

# ── JNI bridge: tun2socks ─────────────────────────────────────
add_library(tun2socks_jni SHARED
    tun2socks_jni.c
)
target_include_directories(tun2socks_jni PRIVATE
    ${BADVPN_DIR}
    ${BADVPN_DIR}/tun2socks
)
target_link_libraries(tun2socks_jni
    tun2socks
    android
    log
)
