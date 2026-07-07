cmake_minimum_required(VERSION 3.22.1)
project(myvpnproject_native VERSION 1.0)

# ── byedpi vendored sources (always present, no submodule needed) ──
set(BYEDPI_DIR ${CMAKE_SOURCE_DIR}/byedpi)

# ── badvpn/tun2socks: still optional via submodule ──────────────
set(BADVPN_DIR ${CMAKE_SOURCE_DIR}/badvpn)
if(EXISTS "${BADVPN_DIR}/tun2socks/tun2socks.c")
    set(BADVPN_AVAILABLE TRUE)
    message(STATUS "badvpn submodule FOUND — building real tun2socks")
else()
    set(BADVPN_AVAILABLE FALSE)
    message(STATUS "badvpn submodule NOT found — tun2socks stub mode")
endif()

# ── ciadpi_jni.so — ALWAYS real (byedpi vendored) ───────────────
add_library(ciadpi_jni SHARED
    ciadpi_jni.c
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

# ── tun2socks_jni.so — stub or real ─────────────────────────────
add_library(tun2socks_jni SHARED tun2socks_jni.c)
target_include_directories(tun2socks_jni PRIVATE ${CMAKE_SOURCE_DIR})
if(BADVPN_AVAILABLE)
    target_include_directories(tun2socks_jni PRIVATE
        ${BADVPN_DIR} ${BADVPN_DIR}/tun2socks)
    target_compile_definitions(tun2socks_jni PRIVATE BADVPN_AVAILABLE=1)
    target_link_libraries(tun2socks_jni tun2socks android log)
else()
    target_link_libraries(tun2socks_jni android log)
endif()
