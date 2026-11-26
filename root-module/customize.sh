#!/system/bin/sh
SKIPUNZIP=0

ui_print "- Installing VoWiFiX v1.0.7 (by iniwex)"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm "$MODPATH/service.sh" 0 0 0755
[ -f "$MODPATH/uninstall.sh" ] && set_perm "$MODPATH/uninstall.sh" 0 0 0755

DATA_DIR="/data/adb/vowifix"
OLD_DIR="/data/adb/vowifi_efs_backup"
LOG_FILE="$DATA_DIR/service.log"
LOCK_FILE="$DATA_DIR/diag.lock"
PATCHER="$MODPATH/system/bin/vowifix-efs"

mkdir -p "$DATA_DIR"
chmod 0755 "$DATA_DIR"

# 兼容并无损迁移旧目录 /data/adb/vowifi_efs_backup 下的原厂备份与日志
if [ -d "$OLD_DIR" ]; then
  migrated=0
  for src in "$OLD_DIR"/*.bin "$OLD_DIR"/service.log; do
    [ -f "$src" ] || continue
    base="${src##*/}"
    if [ ! -e "$DATA_DIR/$base" ]; then
      cp -p "$src" "$DATA_DIR/$base" 2>/dev/null && migrated=$((migrated + 1))
    fi
  done
  if [ "$migrated" -gt 0 ]; then
    ui_print "- 已从旧备份目录无损迁移 $migrated 个历史备份/日志文件至 $DATA_DIR"
  fi
fi

# 仅在开机状态下就地安全合并高通 Modem EFS，使首次安装只需重启 1 次即可全栈生效
if [ "$BOOTMODE" = "true" ] || [ "$(getprop sys.boot_completed)" = "1" ]; then
  chmod 0755 "$PATCHER" 2>/dev/null
  BB="$(getprop gsm.version.baseband)"
  if [ -x "$PATCHER" ] && [ -n "$BB" ]; then
    DETECT_LINE="$("$PATCHER" detect 2>/dev/null | head -n 1)"
    DIAG_BACKEND="${DETECT_LINE#BACKEND=}"
    [ -n "$DIAG_BACKEND" ] || DIAG_BACKEND="none"

    if [ "$DIAG_BACKEND" = "none" ]; then
      echo "[$(date '+%Y-%m-%d %H:%M:%S')] [install] INFO: backend=none (bb=$BB), upper-layer & siminfo mode active" >> "$LOG_FILE"
      ui_print "- 当前设备未开放 /dev/diag 或 diag-router 通道，已启用上层解锁与 siminfo 自动校准模式，跳过安装期 EFS 合并"
    else
      ui_print "- 检测到开机态高通基带 ($BB, backend=$DIAG_BACKEND)，正在就地安全合并 Modem EFS..."
      OUT=""
      RC=1
      (
        tries=0
        while ! /system/bin/flock -n 8 2>/dev/null; do
          tries=$((tries + 1))
          [ "$tries" -ge 20 ] && exit 99
          sleep 1
        done
        OUT=$("$PATCHER" patch all --no-lock --backup-dir "$DATA_DIR" 2>&1)
        RC=$?
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] [install] RC=$RC | $(echo "$OUT" | tr '\n' '; ')" >> "$LOG_FILE"
        printf '%s\n' "$OUT" > "$DATA_DIR/.install_out.tmp"
        printf '%s' "$RC" > "$DATA_DIR/.install_rc.tmp"
        if echo "$OUT" | grep -q "REBOOT_REQUIRED=1"; then
          ui_print "- 正在等待 EFS 持久层缓冲落盘 (6s)..."
          sleep 6
        fi
      ) 8>"$LOCK_FILE"
      OUT="$(cat "$DATA_DIR/.install_out.tmp" 2>/dev/null)"
      RC="$(cat "$DATA_DIR/.install_rc.tmp" 2>/dev/null)"
      rm -f "$DATA_DIR/.install_out.tmp" "$DATA_DIR/.install_rc.tmp"
      [ -z "$RC" ] && RC=1

      printf '%s\n' "$OUT" | while IFS= read -r line; do
        [ -n "$line" ] && ui_print "  $line"
      done
      if [ "$RC" -ne 0 ]; then
        ui_print "! EFS 安装期合并未完成 (RC=$RC)，开机后将由 service.sh 自动重试"
      elif echo "$OUT" | grep -q "REBOOT_REQUIRED=1"; then
        ui_print "- 基带 EFS 已就地合并并落盘；首次安装启用 VoWiFiX LSPosed 模块后重启 1 次即可"
      else
        ui_print "- Modem EFS 已是最新通用配置，零写入跳过（后续切卡后台将自动热合并并刷新）"
      fi
    fi
  else
    ui_print "- 未检测到就绪的高通基带，跳过安装期 EFS 合并（开机由 service.sh 兜底）"
  fi
else
  ui_print "- 非开机刷入环境（Recovery），将在首次开机后由 service.sh 自动完成 EFS 合并"
fi
