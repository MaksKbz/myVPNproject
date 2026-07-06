cmake_minimum_required(VERSION 3.22.1)
project(bypassvpn C)

# ==============================================================================
# ciadpi — ByeDPI C-библиотека (DPI-обход через SOCKS5)
# Исходники клонируются как git submodule:
#   git submodule add https://github.com/hufrea/byedpi.git app/src/main/jni/ciadpi
# ==============================================================================
file(GLOB CIADPI_SOURCES
    "ciadpi/*.c"
)

add_library(
    ciadpi
    SHARED
    ${CIADPI_SOURCES}
    ciadpi_jni.c       # JNI-обёртка: startCiadpi() / stopCiadpi()
)

target_include_directories(ciadpi PRIVATE ciadpi)
target_link_libraries(ciadpi log)

# ==============================================================================
# tun2socks — мост TUN → SOCKS5
# Исходники:
#   git submodule add https://github.com/ambrop72/badvpn.git app/src/main/jni/tun2socks
# (используем только tun2socks компонент из badvpn)
# ==============================================================================
file(GLOB TUN2SOCKS_SOURCES
    "tun2socks/*.c"
)

add_library(
    tun2socks
    SHARED
    ${TUN2SOCKS_SOURCES}
    tun2socks_jni.c    # JNI-обёртка: startTun2socks() / stopTun2socks()
)

target_include_directories(tun2socks PRIVATE tun2socks)
target_link_libraries(tun2socks log)
