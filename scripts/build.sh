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

VERSION="v1.0.10"
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

# 校验 root-module/apk_install.sh 纯 POSIX sh 兼容与流式安装/升级/防降级/签名保护/回退逻辑
run_apk_install_selftest() {
    log "1b/3 运行 apk_install.sh 宿主 mock 单元测试..."
    sh -n "$ROOT_MOD_DIR/apk_install.sh"

    (
        local td
        td="$(mktemp -d /tmp/vowifix-apk-test.XXXXXX)"
        trap 'rm -rf "$td"' EXIT

        mkdir -p "$td/mod/files" "$td/bin" "$td/state"
        printf 'MOCK_VOWIFIX_APK_PAYLOAD_110_BYTES_CHECK' > "$td/mod/files/VoWiFiX.apk"
        local expected_sz
        expected_sz="$(wc -c < "$td/mod/files/VoWiFiX.apk" | tr -d ' ')"
        cat > "$td/mod/files/apk.meta" <<EOF
APK_PKG="io.iniwex.vowifix"
APK_VERSION_CODE="110"
APK_VERSION_NAME="v1.0.10"
EOF
        cat > "$td/mod/module.prop" <<EOF
id=vowifix
version=v1.0.10
versionCode=110
EOF

        cat > "$td/bin/pm" <<'EOF'
#!/bin/sh
state_dir="$VOWIFIX_MOCK_STATE"
case "$1" in
    path)
        if [ "$2" = "android" ]; then
            echo "package:/system/framework/framework-res.apk"
            exit 0
        fi
        if [ "$2" = "io.iniwex.vowifix" ]; then
            cur="$(cat "$state_dir/vc" 2>/dev/null || echo 0)"
            if [ "$cur" -gt 0 ] 2>/dev/null; then
                echo "package:/data/app/io.iniwex.vowifix/base.apk"
                exit 0
            fi
            exit 1
        fi
        exit 1
        ;;
    install)
        echo "INSTALL:$*" >> "$state_dir/calls"
        for a in "$@"; do
            case "$a" in
                *VoWiFiX.apk*)
                    echo "ERROR: pm install received path instead of stdin stream" >&2
                    exit 99
                    ;;
            esac
        done
        cat > "$state_dir/stdin.apk"
        mode="$(cat "$state_dir/fail_mode" 2>/dev/null || echo ok)"
        if [ "$mode" = "sig_conflict" ]; then
            echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Signatures do not match previously installed version]"
            exit 1
        fi
        if [ "$mode" = "user0_fallback" ]; then
            has_u0=0
            prev=""
            for a in "$@"; do
                if [ "$prev" = "--user" ] && [ "$a" = "0" ]; then
                    has_u0=1
                fi
                prev="$a"
            done
            if [ "$has_u0" -eq 0 ]; then
                echo "Failure [INSTALL_FAILED_USER_RESTRICTED]"
                exit 1
            fi
        fi
        echo "Success"
        exit 0
        ;;
    uninstall)
        echo "UNINSTALL:$*" >> "$state_dir/calls"
        exit 0
        ;;
esac
exit 1
EOF

        cat > "$td/bin/dumpsys" <<'EOF'
#!/bin/sh
state_dir="$VOWIFIX_MOCK_STATE"
if [ "$1" = "package" ] && [ "$2" = "io.iniwex.vowifix" ]; then
    cur="$(cat "$state_dir/vc" 2>/dev/null || echo 0)"
    echo "  Package [io.iniwex.vowifix] (mock):"
    echo "    versionCode=$cur minSdk=29 targetSdk=35"
    exit 0
fi
exit 1
EOF
        chmod +x "$td/bin/pm" "$td/bin/dumpsys"

        export VOWIFIX_PM_BIN="$td/bin/pm"
        export VOWIFIX_DUMPSYS_BIN="$td/bin/dumpsys"
        export VOWIFIX_MOCK_STATE="$td/state"
        export VOWIFIX_PM_WAIT_MAX=0

        run_case() {
            local vc="$1" fmode="$2" mode="$3"
            printf '%s' "$vc" > "$td/state/vc"
            printf '%s' "$fmode" > "$td/state/fail_mode"
            : > "$td/state/calls"
            : > "$td/state/stdin.apk"
            : > "$td/state/test.log"
            local rc=0
            sh -c '. "$1"; vowifix_install_bundled_apk "$2" "$3" "$4"' \
                sh "$ROOT_MOD_DIR/apk_install.sh" "$td/mod" "$mode" "$td/state/test.log" || rc=$?
            return "$rc"
        }

        # 用例 1：未安装时流式安装（-r -S <size> + stdin 完整字节）
        run_case 0 ok install || die "apk_install T1 预期退出码 0"
        grep -q -- "^INSTALL:install -r -S $expected_sz$" "$td/state/calls" || die "apk_install T1 pm 参数不符: $(cat "$td/state/calls")"
        cmp -s "$td/mod/files/VoWiFiX.apk" "$td/state/stdin.apk" || die "apk_install T1 stdin 字节流不匹配"

        # 用例 2：旧版本 (109 -> 110) 覆盖升级
        run_case 109 ok boot || die "apk_install T2 预期退出码 0"
        grep -q -- "^INSTALL:install -r -S $expected_sz$" "$td/state/calls" || die "apk_install T2 未执行升级安装"
        grep -q "已自动同步升级" "$td/state/test.log" || die "apk_install T2 缺少升级日志"

        # 用例 3：同版本 (110 == 110) 跳过重复安装
        run_case 110 ok boot || die "apk_install T3 预期退出码 0"
        [ ! -s "$td/state/calls" ] || die "apk_install T3 同版本不应触发 pm install"

        # 用例 4：高版本 (120 > 110) 防降级跳过
        run_case 120 ok install || die "apk_install T4 预期退出码 0"
        [ ! -s "$td/state/calls" ] || die "apk_install T4 高版本不应触发 pm install"

        # 用例 5：签名冲突返回 1 且绝不调用 pm uninstall
        local t5_rc=0
        run_case 109 sig_conflict install || t5_rc=$?
        [ "$t5_rc" -eq 1 ] || die "apk_install T5 签名冲突预期返回 1，实际 $t5_rc"
        if grep -q "UNINSTALL:" "$td/state/calls"; then
            die "apk_install T5 严禁卸载旧包"
        fi
        grep -q "签名不一致" "$td/state/test.log" || die "apk_install T5 缺少签名不一致提示"

        # 用例 6：首次全局安装失败时自动回退 --user 0 流式安装
        run_case 0 user0_fallback boot || die "apk_install T6 预期回退 --user 0 成功"
        grep -q -- "^INSTALL:install -r -S $expected_sz$" "$td/state/calls" || die "apk_install T6 缺少首次全局安装调用"
        grep -q -- "^INSTALL:install -r --user 0 -S $expected_sz$" "$td/state/calls" || die "apk_install T6 缺少 --user 0 回退调用"
        cmp -s "$td/mod/files/VoWiFiX.apk" "$td/state/stdin.apk" || die "apk_install T6 回退流数据不匹配"
    )
    log "apk_install.sh 6 项宿主 mock 单元测试全部通过"
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
    [ "$("$host_bin" refresh-probe)" = "REFRESH=unsupported" ] || die "refresh-probe 校验失败"
    local ref_rc=0
    local ref_out=""
    ref_out="$("$host_bin" refresh --no-lock 2>&1)" || ref_rc=$?
    [ "$ref_rc" -eq 98 ] || die "refresh --no-lock 预期退出码 98，实际 $ref_rc"
    printf '%s\n' "$ref_out" | grep -q "EFS_REFRESH_UNSUPPORTED=1" || die "refresh --no-lock 缺少 EFS_REFRESH_UNSUPPORTED=1 输出"
    rm -f "$host_bin"
    trap - RETURN

    run_apk_install_selftest
}

# 2. 编译 LSPosed 插件与原生控制台 Release APK（先行构建，供后续打入一体化 Root 模块）
build_lsposed_apk() {
    log "2/3 构建 LSPosed 模块与原生控制台 APK..."
    detect_android_sdk
    detect_java17_plus
    mkdir -p "$DIST_DIR"

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

# 3. 交叉编译静态 ARM64 二进制、内嵌已验签 APK 并打包一体化 Root 模块 Zip
build_root_module() {
    log "3/3 交叉编译静态 ARM64 vowifix-efs 并打包一体化 Root 模块..."
    [ -f "$APK_OUT" ] || die "缺少已构建的 Release APK: $APK_OUT"
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

    local gradle_vc prop_vc apk_sz apk_sha
    gradle_vc="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$LSP_DIR/app/build.gradle" | head -n 1)"
    prop_vc="$(sed -nE 's/^versionCode=([0-9]+).*/\1/p' "$ROOT_MOD_DIR/module.prop" | head -n 1)"
    [ -n "$gradle_vc" ] && [ "$gradle_vc" = "$prop_vc" ] || die "versionCode 不一致: build.gradle=${gradle_vc:-?} module.prop=${prop_vc:-?}"

    rm -rf "$ROOT_MOD_DIR/files"
    mkdir -p "$ROOT_MOD_DIR/files"
    cp "$APK_OUT" "$ROOT_MOD_DIR/files/VoWiFiX.apk"
    apk_sz="$(wc -c < "$ROOT_MOD_DIR/files/VoWiFiX.apk" | tr -d ' ')"
    if command -v shasum >/dev/null 2>&1; then
        apk_sha="$(shasum -a 256 "$ROOT_MOD_DIR/files/VoWiFiX.apk" | awk '{print $1}')"
    else
        apk_sha="$(sha256sum "$ROOT_MOD_DIR/files/VoWiFiX.apk" | awk '{print $1}')"
    fi
    cat > "$ROOT_MOD_DIR/files/apk.meta" <<EOF
APK_PKG="io.iniwex.vowifix"
APK_VERSION_CODE="$gradle_vc"
APK_VERSION_NAME="$VERSION"
APK_SIZE="$apk_sz"
APK_SHA256="$apk_sha"
EOF

    chmod 0755 "$BIN_OUT"
    sh -n "$ROOT_MOD_DIR/customize.sh"
    sh -n "$ROOT_MOD_DIR/service.sh"
    sh -n "$ROOT_MOD_DIR/uninstall.sh"
    sh -n "$ROOT_MOD_DIR/apk_install.sh"
    chmod 0755 "$ROOT_MOD_DIR/customize.sh" "$ROOT_MOD_DIR/service.sh" "$ROOT_MOD_DIR/uninstall.sh" "$ROOT_MOD_DIR/apk_install.sh"

    rm -f "$ZIP_OUT"
    (cd "$ROOT_MOD_DIR" && zip -r9 "$ZIP_OUT" . -x '*.DS_Store*')

    local zip_listing
    zip_listing="$(unzip -l "$ZIP_OUT")"
    printf '%s\n' "$zip_listing" | grep -q "files/VoWiFiX.apk" || die "Zip 缺少 files/VoWiFiX.apk"
    printf '%s\n' "$zip_listing" | grep -q "files/apk.meta" || die "Zip 缺少 files/apk.meta"
    printf '%s\n' "$zip_listing" | grep -q "apk_install.sh" || die "Zip 缺少 apk_install.sh"
    printf '%s\n' "$zip_listing" | grep -q "system/bin/vowifix-efs" || die "Zip 缺少 system/bin/vowifix-efs"
    if printf '%s\n' "$zip_listing" | grep -q "system/app/"; then
        die "Zip 不应包含 system/app/"
    fi
}

print_summary() {
    log "构建完成，交付产物位于 dist/（一体化 Zip 已内置配套 APK，独立 APK 留作手动备选）："
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
build_lsposed_apk
build_root_module
print_summary
