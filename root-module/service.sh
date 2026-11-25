#!/system/bin/sh
# 开机稳定后合并双卡 Modem EFS（overideconfig + 漫游开关解锁），并常驻后台监听热切卡自动适配与 QMI 热刷新

MODDIR="${0%/*}"
BIN="$MODDIR/system/bin/vowifix-efs"
DATA_DIR="/data/adb/vowifix"
OLD_DIR="/data/adb/vowifi_efs_backup"
LOG_FILE="$DATA_DIR/service.log"
PID_FILE="$DATA_DIR/watcher.pid"
LOCK_FILE="$DATA_DIR/diag.lock"
DISABLED_FLAG="$DATA_DIR/watcher.disabled"

# 立即脱离主引导序列转入后台守护，避免阻塞 KSU/APatch/Magisk 其他模块启动
if [ "$1" != "--watcher" ]; then
    nohup /system/bin/sh "$0" --watcher </dev/null >/dev/null 2>&1 &
    exit 0
fi

mkdir -p "$DATA_DIR"
chmod 0755 "$DATA_DIR" 2>/dev/null

# 兼容并无损迁移旧目录备份文件，防止历史备份丢失
if [ -d "$OLD_DIR" ]; then
    for src in "$OLD_DIR"/*.bin "$OLD_DIR"/service.log; do
        [ -f "$src" ] || continue
        base="${src##*/}"
        if [ ! -e "$DATA_DIR/$base" ]; then
            cp -p "$src" "$DATA_DIR/$base" 2>/dev/null
        fi
    done
fi

log_msg() {
    # 截断过大日志防止长期运行无限膨胀
    if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)" -gt 65536 ]; then
        tail -n 120 "$LOG_FILE" > "${LOG_FILE}.tmp" 2>/dev/null && mv "${LOG_FILE}.tmp" "$LOG_FILE"
    fi
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
    chmod 0644 "$LOG_FILE" 2>/dev/null
}

# 单实例互斥：确保同一时刻仅有一个守护进程驻留监听
if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null)"
    if [ -n "$OLD_PID" ] && [ "$OLD_PID" != "$$" ] && [ -r "/proc/$OLD_PID/cmdline" ]; then
        if tr '\0' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null | grep -q "service.sh"; then
            kill "$OLD_PID" 2>/dev/null
            sleep 1
        fi
    fi
fi
echo "$$" > "$PID_FILE"

if [ ! -x "$BIN" ]; then
    chmod 0755 "$BIN" 2>/dev/null
fi

if [ ! -x "$BIN" ]; then
    log_msg "ERROR: vowifix-efs binary not executable at $BIN"
    rm -f "$PID_FILE"
    exit 0
fi

# 检查是否有活跃或呼入中的语音通话，避免在通话过程中刷新基带引起断话
is_call_active() {
    dumpsys telephony.registry 2>/dev/null | grep -qE 'mCallState=[12]'
}

# 等待当前基带与卡槽进入稳定 LOADED 状态（不卡在 NOT_READY/IMSI/UNKNOWN 中间态）
is_sim_settled() {
    bb="$(getprop gsm.version.baseband)"
    sim_st="$(getprop gsm.sim.state)"
    [ -n "$bb" ] || return 1
    echo "$sim_st" | grep -q "LOADED" || return 1
    echo "$sim_st" | grep -qE "NOT_READY|IMSI|UNKNOWN" && return 1
    return 0
}

get_sim_sig() {
    echo "$(getprop gsm.sim.state)|$(getprop gsm.sim.operator.numeric)|$(getprop gsm.sim.operator.iso-country)"
}

# 校准活跃卡 siminfo 数据库中被历史配置锁定为“移动网络优先 (1)”或未设 (-1) 的 WFC 模式；
# 仅在 wfc_ims_enabled != 0 时生效，绝不强行打开用户手动关闭的 VoWiFi 开关。
normalize_siminfo_wfc_mode() {
    reason="$1"
    rows="$(content query --uri content://telephony/siminfo --where "sim_id>=0" --projection _id:mcc_string:mnc_string:wfc_ims_enabled:wfc_ims_mode:wfc_ims_roaming_mode 2>/dev/null)"
    [ -n "$rows" ] || return 0

    printf '%s\n' "$rows" | tr -d '\r' | while IFS= read -r line; do
        echo "$line" | grep -q "_id=" || continue
        row_id="${line#*_id=}"; row_id="${row_id%%,*}"; row_id="${row_id%% *}"
        mcc="${line#*mcc_string=}"; mcc="${mcc%%,*}"; mcc="${mcc%% *}"
        mnc="${line#*mnc_string=}"; mnc="${mnc%%,*}"; mnc="${mnc%% *}"
        w_en="${line#*wfc_ims_enabled=}"; w_en="${w_en%%,*}"; w_en="${w_en%% *}"
        w_mode="${line#*wfc_ims_mode=}"; w_mode="${w_mode%%,*}"; w_mode="${w_mode%% *}"
        w_roam="${line#*wfc_ims_roaming_mode=}"; w_roam="${w_roam%%,*}"; w_roam="${w_roam%% *}"

        # 用户已明确关闭本卡 WLAN 通话开关时，尊重用户意图跳过校准
        [ "$w_en" = "0" ] && continue

        case "$w_mode:$w_roam" in
            1:*|*:1|-1:*|*:-1)
                if [ "$w_mode" != "2" ] || [ "$w_roam" != "2" ]; then
                    content update --uri content://telephony/siminfo --where "_id=$row_id" \
                        --bind wfc_ims_mode:i:2 --bind wfc_ims_roaming_mode:i:2 >/dev/null 2>&1
                    log_msg "INFO: [$reason] normalized siminfo _id=$row_id (${mcc}${mnc}, mode=$w_mode/$w_roam -> 2/2)"
                fi
                ;;
        esac
    done
}

CELL_CONF="$DATA_DIR/cell_block.conf"

# 清理历史版本遗留的制式降级快照与隔离掩码，防止妨碍现行全制式 + 手动锁网策略
is_legacy_iso_mask() {
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

migrate_legacy_slot_cell_snapshot() {
    s="$1"
    snap="$DATA_DIR/slot${s}_cell_orig.conf"
    row="$(content query --uri content://telephony/siminfo --where "sim_id=$s" --projection _id:allowed_network_types_for_reasons 2>/dev/null | tr -d '\r' | grep '_id=' | head -n 1)"
    cur_sub="$(printf '%s' "$row" | sed -nE 's/.*_id=([0-9]+).*/\1/p')"
    cur_reasons="$(printf '%s' "$row" | sed -n 's/.*allowed_network_types_for_reasons=//p')"
    cur_user="$(printf '%s' "$cur_reasons" | sed -nE 's/.*user=([0-9]+).*/\1/p')"

    [ -f "$snap" ] || is_legacy_iso_mask "$cur_user" || return 0

    ORIG_SUB_ID=""
    ORIG_REASONS=""
    ORIG_PREF_MODE=""
    ORIG_OPLUS_USER_MODE=""
    [ -f "$snap" ] && . "$snap"

    target_raf=916479
    orig_user="$(printf '%s' "$ORIG_REASONS" | sed -nE 's/.*user=([0-9]+).*/\1/p')"
    if [ -n "$orig_user" ] && ! is_legacy_iso_mask "$orig_user"; then
        target_raf="$orig_user"
    fi
    target_bin="$(int_to_bin20 "$target_raf")"

    if [ -n "$cur_sub" ]; then
        if [ "$cur_sub" = "$ORIG_SUB_ID" ] && [ -n "$ORIG_REASONS" ] && [ -n "$orig_user" ] && ! is_legacy_iso_mask "$orig_user"; then
            content update --uri content://telephony/siminfo --where "_id=$cur_sub" \
                --bind "allowed_network_types_for_reasons:s:$ORIG_REASONS" >/dev/null 2>&1
            [ -n "$ORIG_PREF_MODE" ] && [ "$ORIG_PREF_MODE" != "null" ] && \
                settings put global "preferred_network_mode${cur_sub}" "$ORIG_PREF_MODE" >/dev/null 2>&1
            if [ -n "$ORIG_OPLUS_USER_MODE" ] && [ "$ORIG_OPLUS_USER_MODE" != "null" ]; then
                settings put global "oplus_user_preferred_network_mode${cur_sub}" "$ORIG_OPLUS_USER_MODE" >/dev/null 2>&1
            else
                settings delete global "oplus_user_preferred_network_mode${cur_sub}" >/dev/null 2>&1
            fi
        else
            settings delete global "oplus_user_preferred_network_mode${cur_sub}" >/dev/null 2>&1
        fi
        cmd phone set-allowed-network-types-for-users -s "$s" "$target_bin" >/dev/null 2>&1
    fi

    rm -f "$snap"
    log_msg "INFO: slot$s migrated legacy rat snapshot (sub=$cur_sub, raf=$target_raf)"
}

# 将持久禁蜂窝配置同步到 Settings.Global，由进程内 CellBlockHook 执行手动锁空网 00101 与国内卡豁免恢复
sync_cell_block_rules() {
    reason="$1"
    if [ ! -f "$CELL_CONF" ]; then
        need_del=0
        for s in 0 1; do
            v="$(settings get global "vowifix_cell_block_slot${s}" 2>/dev/null)"
            if [ "$v" = "1" ]; then
                # 先置 0 通知进程内 ContentObserver 切回自动选网，延时后再删除残留控制键
                settings put global "vowifix_cell_block_slot${s}" 0 >/dev/null 2>&1
                need_del=1
            fi
            migrate_legacy_slot_cell_snapshot "$s"
        done
        if [ "$need_del" -eq 1 ]; then
            sleep 2
            settings delete global vowifix_cell_block_slot0 >/dev/null 2>&1
            settings delete global vowifix_cell_block_slot1 >/dev/null 2>&1
        fi
        return 0
    fi

    SLOT0_BLOCK="$(sed -nE 's/^SLOT0_BLOCK=([01]).*/\1/p' "$CELL_CONF" | head -n 1)"
    SLOT1_BLOCK="$(sed -nE 's/^SLOT1_BLOCK=([01]).*/\1/p' "$CELL_CONF" | head -n 1)"
    EXEMPT_CN_460="$(sed -nE 's/^EXEMPT_CN_460=([01]).*/\1/p' "$CELL_CONF" | head -n 1)"
    [ -z "$SLOT0_BLOCK" ] && SLOT0_BLOCK=0
    [ -z "$SLOT1_BLOCK" ] && SLOT1_BLOCK=0
    [ -z "$EXEMPT_CN_460" ] && EXEMPT_CN_460=1

    [ "$(settings get global vowifix_cell_exempt_cn 2>/dev/null)" != "$EXEMPT_CN_460" ] && \
        settings put global vowifix_cell_exempt_cn "$EXEMPT_CN_460" >/dev/null 2>&1
    [ "$(settings get global vowifix_cell_block_slot0 2>/dev/null)" != "$SLOT0_BLOCK" ] && \
        settings put global vowifix_cell_block_slot0 "$SLOT0_BLOCK" >/dev/null 2>&1
    [ "$(settings get global vowifix_cell_block_slot1 2>/dev/null)" != "$SLOT1_BLOCK" ] && \
        settings put global vowifix_cell_block_slot1 "$SLOT1_BLOCK" >/dev/null 2>&1

    migrate_legacy_slot_cell_snapshot 0
    migrate_legacy_slot_cell_snapshot 1
    log_msg "INFO: [$reason] synced cell block rules (slot0=$SLOT0_BLOCK, slot1=$SLOT1_BLOCK, exempt_cn=$EXEMPT_CN_460)"
}

LAST_REFRESH_TS=0

# 串行执行：等通话空闲 -> 持 shared flock -> 合并 EFS + 校准 siminfo + 同步禁蜂窝规则 -> 有写入则严格等 6s 落盘 -> QRTR 热刷新 -> 静候 20s 安全窗口
apply_patch_and_refresh() {
    reason="$1"

    wait_call=0
    while is_call_active && [ "$wait_call" -lt 360 ]; do
        [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] && return 0
        if [ "$wait_call" -eq 0 ]; then
            log_msg "INFO: call active, deferring patch ($reason)"
        fi
        sleep 5
        wait_call=$((wait_call + 1))
    done

    DETECT_LINE="$("$BIN" detect 2>/dev/null | head -n 1)"
    DIAG_BACKEND="${DETECT_LINE#BACKEND=}"
    [ -n "$DIAG_BACKEND" ] || DIAG_BACKEND="none"

    if [ "$DIAG_BACKEND" = "none" ]; then
        # 无可用 Diag 通道（如 GKI 新平台）时进入仅上层与 siminfo 校准模式，跳过 EFS 写入避免报错刷屏
        log_msg "INFO: [$reason] no diag backend available (GKI/upper-layer only mode), skipping EFS patch (bb=$(getprop gsm.version.baseband), sim=$(getprop gsm.sim.state), op=$(getprop gsm.sim.operator.numeric))"
        normalize_siminfo_wfc_mode "$reason"
        sync_cell_block_rules "$reason"
        return 0
    fi

    did_refresh=0
    REFRESH_TARGET="all"
    (
        tries=0
        while ! /system/bin/flock -n 8 2>/dev/null; do
            tries=$((tries + 1))
            if [ "$tries" -ge 30 ]; then
                log_msg "WARN: [$reason] diag.lock busy timeout after 30s"
                exit 1
            fi
            sleep 1
        done

        log_msg "INFO: [$reason] running vowifix-efs patch all (backend=$DIAG_BACKEND, bb=$(getprop gsm.version.baseband), sim=$(getprop gsm.sim.state), op=$(getprop gsm.sim.operator.numeric))"
        OUT=$("$BIN" patch all --no-lock --backup-dir "$DATA_DIR" 2>&1)
        RC=$?
        log_msg "RC=$RC | $(echo "$OUT" | tr '\n' '; ')"

        normalize_siminfo_wfc_mode "$reason"
        sync_cell_block_rules "$reason"

        [ "$RC" -ne 0 ] && exit 1

        if echo "$OUT" | grep -qE "CHANGED=[1-9]"; then
            # 仅按实际被写入变更的卡槽选择性触发 QMI 热刷新，热插副卡时不打断主卡在线状态
            CHG_S0=0
            CHG_S1=0
            printf '%s\n' "$OUT" | grep -qE "(/efsprofiles/overideconfig|/nv/item_files/ims/qp_ims_service_enablement_config|/data/iwlan_s2b_config\.xml) ->" && CHG_S0=1
            printf '%s\n' "$OUT" | grep -q "_Subscription01 ->" && CHG_S1=1
            if [ "$CHG_S0" -eq 1 ] && [ "$CHG_S1" -eq 0 ]; then
                REFRESH_TARGET="slot0"
            elif [ "$CHG_S0" -eq 0 ] && [ "$CHG_S1" -eq 1 ]; then
                REFRESH_TARGET="slot1"
            else
                REFRESH_TARGET="all"
            fi

            # 规程 A：EFS_SYNC_COMPLETE=1 后至少静候 5 秒以上才能触发任何基带复位或刷新动作
            log_msg "INFO: [$reason] EFS updated (target=$REFRESH_TARGET), waiting 6s for persistent flush before QMI refresh..."
            sleep 6

            now_ts="$(date +%s)"
            elapsed_refresh=$((now_ts - LAST_REFRESH_TS))
            if [ "$LAST_REFRESH_TS" -ne 0 ] && [ "$elapsed_refresh" -lt 45 ]; then
                log_msg "INFO: [$reason] refresh cooldown active (${elapsed_refresh}s < 45s), skipping extra refresh"
                exit 0
            fi

            if is_call_active; then
                log_msg "INFO: [$reason] call became active during flush window, deferring refresh (target=$REFRESH_TARGET)"
                case "$REFRESH_TARGET" in
                    slot0) exit 20 ;;
                    slot1) exit 21 ;;
                    *) exit 22 ;;
                esac
            fi

            ROUT=$("$BIN" refresh "$REFRESH_TARGET" --no-lock 2>&1)
            RRC=$?
            log_msg "INFO: [$reason] refresh target=$REFRESH_TARGET RRC=$RRC | $(echo "$ROUT" | tr '\n' '; ')"
            [ "$RRC" -eq 0 ] && exit 10
            exit 1
        fi
        exit 0
    ) 8>"$LOCK_FILE"
    TX_RC=$?

    case "$TX_RC" in
        20) REFRESH_TARGET="slot0"; TX_RC=2 ;;
        21) REFRESH_TARGET="slot1"; TX_RC=2 ;;
        22) REFRESH_TARGET="all"; TX_RC=2 ;;
    esac

    if [ "$TX_RC" -eq 2 ]; then
        while is_call_active; do
            [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] && return 0
            sleep 5
        done
        (
            tries=0
            while ! /system/bin/flock -n 8 2>/dev/null; do
                tries=$((tries + 1))
                [ "$tries" -ge 30 ] && exit 1
                sleep 1
            done
            ROUT=$("$BIN" refresh "$REFRESH_TARGET" --no-lock 2>&1)
            RRC=$?
            log_msg "INFO: [$reason] deferred refresh target=$REFRESH_TARGET RRC=$RRC | $(echo "$ROUT" | tr '\n' '; ')"
            [ "$RRC" -eq 0 ] && exit 10
            exit 1
        ) 8>"$LOCK_FILE"
        TX_RC=$?
    fi

    if [ "$TX_RC" -eq 10 ]; then
        LAST_REFRESH_TS="$(date +%s)"
        did_refresh=1
    fi

    if [ "$did_refresh" -eq 1 ]; then
        # 规程 B：基带刷新后等待 baseband 与 LOADED 恢复并再安静延迟 20 秒不碰 Diag 通道
        w=0
        while [ "$w" -lt 30 ]; do
            if is_sim_settled; then
                break
            fi
            sleep 2
            w=$((w + 2))
        done
        sleep 20
    fi
    return 0
}

# 1. 等待 Android 启动完成（最长 120 秒）
elapsed=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$elapsed" -lt 120 ]; do
    sleep 2
    elapsed=$((elapsed + 2))
done

# 2. 等待基带版本号非空且至少一张 SIM 加载完成（最长 60 秒）
elapsed=0
while [ "$elapsed" -lt 60 ]; do
    if is_sim_settled; then
        break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done

# 3. 遵循防 MHI/MCFG 竞态规程：确认基带就绪后再安静等待 15 秒
sleep 15

if [ ! -f "$DISABLED_FLAG" ]; then
    apply_patch_and_refresh "boot"
else
    log_msg "INFO: watcher paused via watcher.disabled, skipping boot auto-patch"
fi

PREV_SIG="$(get_sim_sig)"
SAW_CHANGE=0
WAS_DISABLED=0
[ -f "$DISABLED_FLAG" ] && WAS_DISABLED=1

# 4. 常驻后台监听热切卡/切换运营商（低功耗每 3 秒仅读内存属性）
while true; do
    if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -x "$BIN" ]; then
        log_msg "INFO: module disabled/removed, watcher exiting"
        rm -f "$PID_FILE"
        exit 0
    fi

    sleep 3

    if [ -f "$DISABLED_FLAG" ]; then
        if [ "$WAS_DISABLED" -eq 0 ]; then
            log_msg "INFO: background auto-patch paused (watcher.disabled present)"
            WAS_DISABLED=1
        fi
        PREV_SIG="$(get_sim_sig)"
        SAW_CHANGE=0
        continue
    elif [ "$WAS_DISABLED" -eq 1 ]; then
        log_msg "INFO: background auto-patch resumed"
        WAS_DISABLED=0
    fi

    CUR_SIG="$(get_sim_sig)"
    if [ "$CUR_SIG" != "$PREV_SIG" ]; then
        SAW_CHANGE=1
        PREV_SIG="$CUR_SIG"
        continue
    fi

    if [ "$SAW_CHANGE" -eq 1 ] && is_sim_settled; then
        # 防抖等待 8 秒：留足高通 MCFG/PDC 解压并覆写新卡配置文件的时间
        sleep 8
        SETTLED_SIG="$(get_sim_sig)"
        if [ "$SETTLED_SIG" != "$CUR_SIG" ]; then
            PREV_SIG="$SETTLED_SIG"
            continue
        fi

        if is_sim_settled; then
            apply_patch_and_refresh "sim_hot_swap"
            PREV_SIG="$(get_sim_sig)"
            SAW_CHANGE=0
        fi
    fi
done
