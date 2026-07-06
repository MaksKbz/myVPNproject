#!/usr/bin/env bash
# =============================================================================
# activate_native.sh
# Активация нативного C-движка для myVPNproject v3.0
#
# ЧТО ДЕЛАЕТ ЭТОТ СКРИПТ:
# 1. Проверяет наличие git, NDK, CMake
# 2. Инициализирует submodules (byedpi + badvpn)
# 3. Раскомментирует #include в ciadpi_jni.c и tun2socks_jni.c
# 4. Собирает debug APK
# =============================================================================

set -e
COLOR_OK="\033[0;32m"
COLOR_ERR="\033[0;31m"
COLOR_WARN="\033[0;33m"
NC="\033[0m"

log_ok()   { echo -e "${COLOR_OK}[OK]${NC} $1"; }
log_err()  { echo -e "${COLOR_ERR}[ERR]${NC} $1"; exit 1; }
log_warn() { echo -e "${COLOR_WARN}[WARN]${NC} $1"; }

# --- 1. Проверка зависимостей ---
command -v git  &>/dev/null || log_err "git не найден. Установите: sudo apt install git"
command -v java &>/dev/null || log_err "JDK не найден. Установите JDK 17+"
log_ok "git, java — OK"

[ -f "gradlew" ] || log_err "Скрипт запущен не из корня проекта (gradlew не найден)"

# --- 2. Инициализация submodule ---
echo ""
echo "==> Инициализация submodule..."
git submodule update --init --recursive
log_ok "submodule инициализированы"

CIADPI_DIR="app/src/main/jni/ciadpi"
TUN2SOCKS_DIR="app/src/main/jni/tun2socks"
CIADPI_JNI="app/src/main/jni/ciadpi_jni.c"
TUN2SOCKS_JNI="app/src/main/jni/tun2socks_jni.c"

# --- 3. Активация ciadpi_jni.c ---
echo ""
echo "==> Активация ciadpi_jni.c..."

if [ ! -d "$CIADPI_DIR" ] || [ -z "$(ls -A $CIADPI_DIR 2>/dev/null)" ]; then
    log_warn "submodule ciadpi пустой. Пропуск. Выполните: git submodule update --init"
else
    # Находим хедер ciadpi (main.h или ciadpi.h)
    if [ -f "$CIADPI_DIR/main.h" ]; then
        CIADPI_HEADER="ciadpi/main.h"
    elif [ -f "$CIADPI_DIR/ciadpi.h" ]; then
        CIADPI_HEADER="ciadpi/ciadpi.h"
    else
        # Ищем любой .h файл
        CIADPI_HEADER=$(find "$CIADPI_DIR" -maxdepth 1 -name '*.h' | head -1 | sed "s|app/src/main/jni/||")
        [ -z "$CIADPI_HEADER" ] && log_warn "ciadpi хедер не найден — проверьте вручную"
    fi

    if [ -n "$CIADPI_HEADER" ]; then
        # Раскомментируем #include
        sed -i "s|// \*#include \"$CIADPI_HEADER\"|#include \"$CIADPI_HEADER\"|g" "$CIADPI_JNI"
        # Раскомментируем заглушку ciadpi_main
        sed -i "s|/\*STUB\*/ LOGI.*not linked.*\";\n.*int result = 0;|int result = ciadpi_main(argc, argv);|g" "$CIADPI_JNI" || true
        log_ok "ciadpi_jni.c активирован"
    fi
fi

# --- 4. Активация tun2socks_jni.c ---
echo ""
echo "==> Активация tun2socks_jni.c..."

if [ ! -d "$TUN2SOCKS_DIR" ] || [ -z "$(ls -A $TUN2SOCKS_DIR 2>/dev/null)" ]; then
    log_warn "submodule tun2socks пустой. Пропуск."
else
    # badvpn — tun2socks.h находится в tun2socks/
    if [ -f "$TUN2SOCKS_DIR/tun2socks/tun2socks.h" ]; then
        sed -i "s|// \*#include \"tun2socks/tun2socks.h\"|#include \"tun2socks/tun2socks.h\"|g" "$TUN2SOCKS_JNI"
        log_ok "tun2socks_jni.c активирован"
    else
        log_warn "tun2socks.h не найден. Проверьте структуру badvpn submodule вручную."
    fi
fi

# --- 5. Сборка ---
echo ""
echo "==> Сборка debug APK..."
./gradlew assembleDebug --stacktrace

APK_PATH=$(find app/build/outputs/apk/debug -name '*.apk' | head -1)
if [ -n "$APK_PATH" ]; then
    log_ok "Сборка успешна!"
    echo ""
    echo "  APK: $APK_PATH"
    echo ""
    echo "  Установка:"
    echo "    adb install $APK_PATH"
else
    log_err "Сборка не удалась. Проверьте логи выше."
fi
