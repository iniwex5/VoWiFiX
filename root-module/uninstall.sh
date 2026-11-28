#!/system/bin/sh
# VoWiFiX 模块卸载清理与恢复脚本：还原被禁蜂窝卡槽的原制式快照并清理 Settings.Global 配置（保留 EFS 原始备份 *_orig.bin）

DATA_DIR="/data/adb/vowifix"
CELL_CONF="$DATA_DIR/cell_block.conf"
LOG_FILE="$DATA_DIR/service.log"
PKG_NAME="io.iniwex.vowifix"
FULL_BIN="11011111101111111111"
FULL_RAF=916479

# 在包管理器就绪后同步卸载配套 VoWiFiX APK（保留 /data/adb/vowifix/*_orig.bin 原始 EFS 备份）
uninstall_bundled_apk() {
    t=0
    while ! pm path android 2>/dev/null | grep -q "^package:" && [ "$t" -lt 60 ]; do
        sleep 2
        t=$((t + 2))
    done
    if pm path "$PKG_NAME" 2>/dev/null | grep -q "^package:"; then
        if pm uninstall "$PKG_NAME" >/dev/null 2>&1; then
            mkdir -p "$DATA_DIR" 2>/dev/null
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [uninstall] uninstalled companion APK ($PKG_NAME)" >> "$LOG_FILE" 2>/dev/null
        else
            mkdir -p "$DATA_DIR" 2>/dev/null
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] WARN: [uninstall] failed to uninstall companion APK ($PKG_NAME)" >> "$LOG_FILE" 2>/dev/null
        fi
    fi
}

is_iso_mask() {
    case "$1" in
        32|65536|2048|16|64|8|32768) return 0 ;;
        *) return 1 ;;
    esac
}

int_to_bin20() {
    v="$1"
    out=""
    i=0
    while [ "$i" -lt 20 ] || [ "$v" -gt 0 ]; do
        out="$((v % 2))$out"
        v="$((v / 2))"
        i=$((i + 1))
    done
    printf '%s' "$out"
}

perform_uninstall_restore() {
    # 先记录是否存在持久规则文件，再置 0 通知 CellBlockHook 切回全自动选网并留出触发窗口
    had_conf=0
    [ -f "$CELL_CONF" ] && had_conf=1

    settings put global vowifix_cell_exempt_cn 1 >/dev/null 2>&1
    settings put global vowifix_cell_block_slot0 0 >/dev/null 2>&1
    settings put global vowifix_cell_block_slot1 0 >/dev/null 2>&1
    rm -f "$CELL_CONF"
    sleep 2

    settings delete global vowifix_cell_block_slot0 >/dev/null 2>&1
    settings delete global vowifix_cell_block_slot1 >/dev/null 2>&1
    settings delete global vowifix_cell_exempt_cn >/dev/null 2>&1
    settings delete global vowifix_modem_raf_slot0 >/dev/null 2>&1
    settings delete global vowifix_modem_raf_slot1 >/dev/null 2>&1

    for s in 0 1; do
        snap="$DATA_DIR/slot${s}_cell_orig.conf"
        has_snap=0
        restored_oplus_sub=""
        ORIG_SUB_ID=""
        ORIG_REASONS=""
        ORIG_PREF_MODE=""
        ORIG_OPLUS_USER_MODE=""
        if [ -f "$snap" ]; then
            has_snap=1
            . "$snap"
        fi

        # 1. 若记录过原订阅 ID，先将原订阅恢复到快照状态
        if [ "$has_snap" -eq 1 ] && [ -n "$ORIG_SUB_ID" ]; then
            orig_user="$(printf '%s' "$ORIG_REASONS" | sed -nE 's/.*user=([0-9]+).*/\1/p')"
            if [ -n "$ORIG_REASONS" ] && [ -n "$orig_user" ] && ! is_iso_mask "$orig_user"; then
                content update --uri content://telephony/siminfo --where "_id=$ORIG_SUB_ID" \
                    --bind "allowed_network_types_for_reasons:s:$ORIG_REASONS" >/dev/null 2>&1
            fi
            [ -n "$ORIG_PREF_MODE" ] && [ "$ORIG_PREF_MODE" != "null" ] && \
                settings put global "preferred_network_mode${ORIG_SUB_ID}" "$ORIG_PREF_MODE" >/dev/null 2>&1
            if [ -n "$ORIG_OPLUS_USER_MODE" ] && [ "$ORIG_OPLUS_USER_MODE" != "null" ]; then
                settings put global "oplus_user_preferred_network_mode${ORIG_SUB_ID}" "$ORIG_OPLUS_USER_MODE" >/dev/null 2>&1
                restored_oplus_sub="$ORIG_SUB_ID"
            else
                settings delete global "oplus_user_preferred_network_mode${ORIG_SUB_ID}" >/dev/null 2>&1
            fi
        fi

        # 2. 检查当前物理槽位活跃订阅，若被锁定或存在过快照/规则，同步复原制式并恢复自动选网
        row="$(content query --uri content://telephony/siminfo --where "sim_id=$s" --projection _id:allowed_network_types_for_reasons 2>/dev/null | tr -d '\r' | grep '_id=' | head -n 1)"
        cur_sub="$(printf '%s' "$row" | sed -nE 's/.*_id=([0-9]+).*/\1/p')"
        cur_reasons="$(printf '%s' "$row" | sed -n 's/.*allowed_network_types_for_reasons=//p')"
        cur_user="$(printf '%s' "$cur_reasons" | sed -nE 's/.*user=([0-9]+).*/\1/p')"

        if [ "$has_snap" -eq 1 ] || [ "$had_conf" -eq 1 ] || is_iso_mask "$cur_user"; then
            restore_bin="$FULL_BIN"
            if [ -n "$ORIG_REASONS" ]; then
                orig_u="$(printf '%s' "$ORIG_REASONS" | sed -nE 's/.*user=([0-9]+).*/\1/p')"
                if [ -n "$orig_u" ] && ! is_iso_mask "$orig_u" && [ "$cur_sub" = "$ORIG_SUB_ID" ]; then
                    restore_bin="$(int_to_bin20 "$orig_u")"
                fi
            fi
            if [ -n "$cur_sub" ]; then
                if [ "$cur_sub" != "$restored_oplus_sub" ]; then
                    settings delete global "oplus_user_preferred_network_mode${cur_sub}" >/dev/null 2>&1
                fi
                cmd phone set-allowed-network-types-for-users -s "$s" "$restore_bin" >/dev/null 2>&1
            fi
        fi

        rm -f "$snap"
    done

    rm -f "$CELL_CONF"
    mkdir -p "$DATA_DIR" 2>/dev/null
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [uninstall] cell block rules & snapshots cleaned and cellular restored" >> "$LOG_FILE" 2>/dev/null
    uninstall_bundled_apk
}

if [ ! -f "$CELL_CONF" ] && [ ! -f "$DATA_DIR/slot0_cell_orig.conf" ] && [ ! -f "$DATA_DIR/slot1_cell_orig.conf" ]; then
    b0="$(settings get global vowifix_cell_block_slot0 2>/dev/null)"
    b1="$(settings get global vowifix_cell_block_slot1 2>/dev/null)"
    if [ "$b0" = "1" ] || [ "$b1" = "1" ]; then
        if [ "$(getprop sys.boot_completed)" = "1" ]; then
            # 无本地规则文件但 Settings.Global 仍残余 1 时，仍先置 0 通知进程内 Hook 恢复自动选网
            settings put global vowifix_cell_exempt_cn 1 >/dev/null 2>&1
            settings put global vowifix_cell_block_slot0 0 >/dev/null 2>&1
            settings put global vowifix_cell_block_slot1 0 >/dev/null 2>&1
            sleep 2
        fi
    fi
    settings delete global vowifix_cell_block_slot0 >/dev/null 2>&1
    settings delete global vowifix_cell_block_slot1 >/dev/null 2>&1
    settings delete global vowifix_cell_exempt_cn >/dev/null 2>&1
    settings delete global vowifix_modem_raf_slot0 >/dev/null 2>&1
    settings delete global vowifix_modem_raf_slot1 >/dev/null 2>&1
    if [ "$(getprop sys.boot_completed)" = "1" ]; then
        uninstall_bundled_apk
    else
        (
            t=0
            while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$t" -lt 60 ]; do
                sleep 2
                t=$((t + 2))
            done
            sleep 3
            uninstall_bundled_apk
        ) </dev/null >/dev/null 2>&1 &
    fi
    exit 0
fi

# 优先就地删除规则文件以彻底阻止守护再触发，随后视系统就绪情况同步或延后执行 Telephony 恢复
if [ "$(getprop sys.boot_completed)" = "1" ]; then
    perform_uninstall_restore
else
    rm -f "$CELL_CONF"
    (
        t=0
        while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$t" -lt 60 ]; do
            sleep 2
            t=$((t + 2))
        done
        sleep 3
        perform_uninstall_restore
    ) </dev/null >/dev/null 2>&1 &
fi
