#!/system/bin/sh
# 内嵌 VoWiFiX APK 安装助手（供 customize.sh 与 service.sh 共用；纯 POSIX sh，使用流式 stdin 安装规避 SELinux 限制）

# 输出安装期 ui_print（若存在）并追加写入守护日志
vowifix_apk_log() {
    mode="$1"
    log_file="$2"
    level="$3"
    msg="$4"
    if [ "$mode" = "install" ] && command -v ui_print >/dev/null 2>&1; then
        if [ "$level" = "WARN" ] || [ "$level" = "ERROR" ]; then
            ui_print "! $msg"
        else
            ui_print "- $msg"
        fi
    fi
    if [ -n "$log_file" ]; then
        log_dir="${log_file%/*}"
        [ -n "$log_dir" ] && mkdir -p "$log_dir" 2>/dev/null
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] [$mode] $level: $msg" >> "$log_file" 2>/dev/null
    fi
}

# 从 key=value 属性文件读取单行字段（去除 CR/LF）
vowifix_read_prop_value() {
    prop_file="$1"
    prop_key="$2"
    [ -r "$prop_file" ] || return 0
    sed -n "s/^${prop_key}=//p" "$prop_file" 2>/dev/null | head -n 1 | tr -d '\r\n'
}

# 检查 Android PackageManagerService 是否已就绪
vowifix_pm_ready() {
    pm_bin="${VOWIFIX_PM_BIN:-pm}"
    "$pm_bin" path android 2>/dev/null | grep -q "^package:"
}

# 获取已安装包的 versionCode；未安装或无法读取时输出 0
vowifix_get_installed_apk_vc() {
    pkg="$1"
    pm_bin="${VOWIFIX_PM_BIN:-pm}"
    ds_bin="${VOWIFIX_DUMPSYS_BIN:-dumpsys}"
    if ! "$pm_bin" path "$pkg" 2>/dev/null | grep -q "^package:"; then
        echo "0"
        return 0
    fi
    vc="$("$ds_bin" package "$pkg" 2>/dev/null | sed -nE 's/^[[:space:]]*versionCode=([0-9]+).*/\1/p' | head -n 1)"
    case "$vc" in
        ''|*[!0-9]*) echo "0" ;;
        *) echo "$vc" ;;
    esac
}

# 统一记录新装或覆写升级成功提示
vowifix_report_install_success() {
    mode="$1"
    log_file="$2"
    cur_vc="$3"
    ver_label="$4"
    if [ "$cur_vc" -eq 0 ]; then
        vowifix_apk_log "$mode" "$log_file" "INFO" "已自动安装 VoWiFiX ${ver_label} 控制台与 LSPosed 模块，请在重启前于 LSPosed 启用模块并勾选作用域，随后重启 1 次生效"
    else
        vowifix_apk_log "$mode" "$log_file" "INFO" "已自动同步升级 VoWiFiX APK 至 ${ver_label}（已保留原有 Root 授权与 LSPosed 作用域）"
    fi
}

# 自动安装或升级模块内置 APK（<mod_dir>/files/VoWiFiX.apk）：
# 参数：<mod_dir> <mode: install|boot> <log_file>
# 返回：0=已安装/已升级/无需操作，1=安装失败但已跳过阻断，2=包管理器未就绪
vowifix_install_bundled_apk() {
    mod_dir="$1"
    mode="${2:-boot}"
    log_file="${3:-/data/adb/vowifix/service.log}"
    pm_bin="${VOWIFIX_PM_BIN:-pm}"
    apk_file="$mod_dir/files/VoWiFiX.apk"
    meta_file="$mod_dir/files/apk.meta"

    [ -r "$apk_file" ] || return 0

    APK_PKG="io.iniwex.vowifix"
    APK_VERSION_CODE=""
    APK_VERSION_NAME=""
    if [ -r "$meta_file" ]; then
        . "$meta_file"
    fi
    if [ -z "$APK_VERSION_CODE" ] && [ -r "$mod_dir/module.prop" ]; then
        APK_VERSION_CODE="$(vowifix_read_prop_value "$mod_dir/module.prop" "versionCode")"
    fi
    if [ -z "$APK_VERSION_NAME" ] && [ -r "$mod_dir/module.prop" ]; then
        APK_VERSION_NAME="$(vowifix_read_prop_value "$mod_dir/module.prop" "version")"
    fi

    apk_sz="$(wc -c < "$apk_file" 2>/dev/null | tr -d ' \t\r\n')"
    case "$apk_sz" in
        ''|*[!0-9]*|0)
            vowifix_apk_log "$mode" "$log_file" "WARN" "内嵌 VoWiFiX.apk 文件大小异常，已跳过安装 ($apk_file)"
            return 1
            ;;
    esac

    if [ "$mode" = "boot" ]; then
        wait_max="${VOWIFIX_PM_WAIT_MAX:-30}"
        waited=0
        while ! vowifix_pm_ready && [ "$waited" -lt "$wait_max" ]; do
            sleep 2
            waited=$((waited + 2))
        done
    fi

    if ! vowifix_pm_ready; then
        if [ "$mode" = "install" ]; then
            vowifix_apk_log "$mode" "$log_file" "INFO" "当前包管理器暂未就绪，将在首次开机后由 service.sh 自动补装 VoWiFiX APK"
        else
            vowifix_apk_log "$mode" "$log_file" "WARN" "开机等待包管理器超时，暂未补装 VoWiFiX APK；可手动安装 /data/adb/modules/vowifix/files/VoWiFiX.apk"
        fi
        return 2
    fi

    cur_vc="$(vowifix_get_installed_apk_vc "$APK_PKG")"
    target_vc=0
    case "$APK_VERSION_CODE" in
        ''|*[!0-9]*) target_vc=0 ;;
        *) target_vc="$APK_VERSION_CODE" ;;
    esac

    if [ "$cur_vc" -gt 0 ] && [ "$target_vc" -gt 0 ]; then
        if [ "$cur_vc" -gt "$target_vc" ]; then
            vowifix_apk_log "$mode" "$log_file" "INFO" "已安装更高版本 VoWiFiX APK (vc=$cur_vc > $target_vc)，跳过安装避免降级"
            return 0
        fi
        if [ "$cur_vc" -eq "$target_vc" ]; then
            vowifix_apk_log "$mode" "$log_file" "INFO" "已安装相同版本 VoWiFiX APK (vc=$cur_vc)，跳过重复安装"
            return 0
        fi
    fi

    ver_label="${APK_VERSION_NAME:-vc${target_vc}}"
    # 通过 stdin 流式喂入 pm install，避免 Android 11+ system_server 无权直接读取 /data/adb 路径
    inst_out="$("$pm_bin" install -r -S "$apk_sz" < "$apk_file" 2>&1)"
    inst_rc=$?
    if [ "$inst_rc" -eq 0 ] && ! printf '%s\n' "$inst_out" | grep -q "Failure"; then
        vowifix_report_install_success "$mode" "$log_file" "$cur_vc" "$ver_label"
        return 0
    fi

    if printf '%s\n' "$inst_out" | grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES"; then
        vowifix_apk_log "$mode" "$log_file" "WARN" "现有 VoWiFiX APK 签名不一致，为保留 Root 授权与 LSPosed 作用域已跳过覆盖；如需切换签名请手动卸载旧 App 后安装 /data/adb/modules/vowifix/files/VoWiFiX.apk"
        return 1
    fi

    if printf '%s\n' "$inst_out" | grep -q "INSTALL_FAILED_VERSION_DOWNGRADE"; then
        vowifix_apk_log "$mode" "$log_file" "INFO" "系统已存在更高版本 VoWiFiX APK，跳过安装"
        return 0
    fi

    # 兼容部分 OEM/多用户环境要求显式指定 --user 0 的 pm install 限制
    inst_out2="$("$pm_bin" install -r --user 0 -S "$apk_sz" < "$apk_file" 2>&1)"
    inst_rc2=$?
    if [ "$inst_rc2" -eq 0 ] && ! printf '%s\n' "$inst_out2" | grep -q "Failure"; then
        vowifix_report_install_success "$mode" "$log_file" "$cur_vc" "$ver_label"
        return 0
    fi

    last_err="$(printf '%s\n' "$inst_out2" | tr '\r' '\n' | grep -v '^[[:space:]]*$' | tail -n 1)"
    [ -n "$last_err" ] || last_err="$(printf '%s\n' "$inst_out" | tr '\r' '\n' | grep -v '^[[:space:]]*$' | tail -n 1)"
    vowifix_apk_log "$mode" "$log_file" "WARN" "自动安装 VoWiFiX APK 失败 (${last_err:-rc=$inst_rc2})；模块主体已正常安装，可手动安装 /data/adb/modules/vowifix/files/VoWiFiX.apk 或从 Release 单独下载 APK"
    return 1
}
