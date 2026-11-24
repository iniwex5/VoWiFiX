#!/usr/bin/env bash
# VoWiFiX 全栈一键构建脚本（支持 macOS / Linux）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
C_SRC="$ROOT_DIR/efs-tool/vowifix_efs.c"
ROOT_MOD_DIR="$ROOT_DIR/root-module"
BIN_OUT="$ROOT_MOD_DIR/system/bin/vowifix-efs"
LSP_DIR="$ROOT_DIR/lsposed-module"

VERSION="v1.0.2"
ZIP_OUT="$DIST_DIR/VoWiFiX-${VERSION}.zip"
APK_OUT="$DIST_DIR/VoWiFiX-LSPosed-${VERSION}.apk"

log() { printf '[VoWiFiX-Build] %s\n' "$*"; }
die() { printf '[VoWiFiX-Build] ERROR: %s\n' "$*" >&2; exit 1; }

# 自动探测 Android SDK（无需提交 local.properties）
detect_android_sdk() {
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        export ANDROID_HOME
        return 0
    fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
        export ANDROID_HOME="$ANDROID_SDK_ROOT"
        return 0
    fi
    for cand in \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/opt/android-sdk" \
        "/usr/local/lib/android/sdk"; do
        if [ -d "$cand" ]; then
            export ANDROID_HOME="$cand"
            return 0
        fi
    done
    die "未找到 Android SDK，请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT 环境变量。"
}

# 提取指定 java 二进制的主版本号（JDK 8 返回 1，JDK 17+ 返回 >= 17）
java_major_version() {
    local jbin="$1"
    local v
    v="$("$jbin" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
    printf '%s' "${v:-0}"
}

# 自动探测 JDK 17+（AGP 8.7.3 最低要求 JDK 17）
detect_java17_plus() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        if [ "$(java_major_version "$JAVA_HOME/bin/java")" -ge 17 ]; then
            return 0
        fi
        # 现有 JAVA_HOME 版本过低时丢弃它，避免 Gradle 误读旧版本
        unset JAVA_HOME
    fi
    if command -v java >/dev/null 2>&1; then
        if [ "$(java_major_version "$(command -v java)")" -ge 17 ]; then
            return 0
        fi
    fi
    if [ -x /usr/libexec/java_home ]; then
        local jh
        jh="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
        if [ -n "$jh" ] && [ -x "$jh/bin/java" ] && [ "$(java_major_version "$jh/bin/java")" -ge 17 ]; then
            export JAVA_HOME="$jh"
            return 0
        fi
    fi
    for cand in \
        "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
        "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
        "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
        "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
        "/usr/lib/jvm/java-21-openjdk-amd64" \
        "/usr/lib/jvm/java-17-openjdk-amd64"; do
        if [ -x "$cand/bin/java" ] && [ "$(java_major_version "$cand/bin/java")" -ge 17 ]; then
            export JAVA_HOME="$cand"
            return 0
        fi
    done
    die "Gradle 构建需要 JDK 17 或更高版本，请安装或设置 JAVA_HOME。"
}

# 1. 宿主编译与 EFS 合并逻辑单元测试
run_host_selftest() {
    log "1/3 编译宿主测试二进制并运行 --self-test..."
    local cc_bin=""
    if command -v cc >/dev/null 2>&1; then
        cc_bin="cc"
    elif command -v gcc >/dev/null 2>&1; then
        cc_bin="gcc"
    elif command -v clang >/dev/null 2>&1; then
        cc_bin="clang"
    else
        die "未找到宿主 C 编译器 (cc/gcc/clang)。"
    fi

    local host_bin
    host_bin="$(mktemp /tmp/vowifix-efs-test.XXXXXX)"
    trap 'rm -f "$host_bin"' RETURN
    "$cc_bin" -O2 -Wall -Wextra "$C_SRC" -o "$host_bin"
    "$host_bin" --self-test
    [ "$("$host_bin" --backend=none detect)" = "BACKEND=none" ] || die "detect --backend=none 校验失败"
    rm -f "$host_bin"
    trap - RETURN
}

# 2. 交叉编译静态 ARM64 二进制并打包 Root 模块 Zip
build_root_module() {
    log "2/3 交叉编译静态 ARM64 vowifix-efs 并打包 Root 模块..."
    mkdir -p "$(dirname "$BIN_OUT")" "$DIST_DIR"

    if command -v zig >/dev/null 2>&1; then
        zig cc -target aarch64-linux-musl -static -O2 -s -Wall -Wextra "$C_SRC" -o "$BIN_OUT"
    elif command -v aarch64-linux-musl-gcc >/dev/null 2>&1; then
        aarch64-linux-musl-gcc -static -O2 -s -Wall -Wextra "$C_SRC" -o "$BIN_OUT"
    elif [ -n "${ANDROID_NDK_HOME:-}" ] && ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android29-clang >/dev/null 2>&1; then
        local ndk_clang
        ndk_clang="$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android29-clang | head -n 1)"
        "$ndk_clang" -static -O2 -s -Wall -Wextra "$C_SRC" -o "$BIN_OUT"
    else
        die "未找到 Zig 或 ARM64 静态交叉编译器 (zig / aarch64-linux-musl-gcc / ANDROID_NDK_HOME)。"
    fi

    chmod 0755 "$BIN_OUT"
    sh -n "$ROOT_MOD_DIR/customize.sh"
    sh -n "$ROOT_MOD_DIR/service.sh"
    chmod 0755 "$ROOT_MOD_DIR/customize.sh" "$ROOT_MOD_DIR/service.sh"

    rm -f "$ZIP_OUT"
    (cd "$ROOT_MOD_DIR" && zip -r9 "$ZIP_OUT" . -x '*.DS_Store*')
}

# 3. 编译 LSPosed 插件与原生控制台 Release APK
build_lsposed_apk() {
    log "3/3 构建 LSPosed 模块与原生控制台 APK..."
    detect_android_sdk
    detect_java17_plus

    local gradle_cmd=""
    if [ -x "$LSP_DIR/gradlew" ]; then
        gradle_cmd="$LSP_DIR/gradlew"
    elif command -v gradle >/dev/null 2>&1; then
        gradle_cmd="gradle"
    else
        die "未找到 gradle 或 lsposed-module/gradlew。"
    fi

    "$gradle_cmd" -p "$LSP_DIR" assembleRelease

    local built_apk="$LSP_DIR/app/build/outputs/apk/release/app-release.apk"
    [ -f "$built_apk" ] || die "未生成预期 Release APK: $built_apk"
    cp "$built_apk" "$APK_OUT"

    local apksigner=""
    apksigner="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
    if [ -n "$apksigner" ] && [ -x "$apksigner" ]; then
        "$apksigner" verify --verbose "$APK_OUT" >/dev/null
        log "apksigner 签名校验通过"
    fi
}

print_summary() {
    log "构建完成，交付产物位于 dist/："
    for f in "$ZIP_OUT" "$APK_OUT"; do
        local sz sha
        sz="$(wc -c < "$f" | tr -d ' ')"
        if command -v shasum >/dev/null 2>&1; then
            sha="$(shasum -a 256 "$f" | awk '{print $1}')"
        else
            sha="$(sha256sum "$f" | awk '{print $1}')"
        fi
        printf '  - %s (%s bytes, sha256=%s)\n' "$f" "$sz" "$sha"
    done
}

run_host_selftest
build_root_module
build_lsposed_apk
print_summary
