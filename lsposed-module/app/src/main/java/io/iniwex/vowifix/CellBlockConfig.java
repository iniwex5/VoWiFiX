package io.iniwex.vowifix;

/**
 * 单卡禁蜂窝纯 VoWiFi 共享策略模型与脚本生成器：
 * 保留完整 3GPP/4G/5G 能力，驱动进程内 Hook 对开启禁蜂窝的非豁免槽位锁定空网 PLMN (00101)，
 * 并平滑清理历史制式降级快照，避免 3GPP2 纯数据模式引发的高通 Modem 拒呼问题。
 */
public final class CellBlockConfig {

    public static final String CONF_PATH = RootShell.DATA_DIR + "/cell_block.conf";
    public static final String BLOCK_PLMN = "00101";

    public static final String KEY_BLOCK_SLOT0 = "vowifix_cell_block_slot0";
    public static final String KEY_BLOCK_SLOT1 = "vowifix_cell_block_slot1";
    public static final String KEY_EXEMPT_CN = "vowifix_cell_exempt_cn";
    public static final String KEY_MODEM_RAF_PREFIX = "vowifix_modem_raf_slot";

    // AOSP TelephonyManager / RadioAccessFamily 网络制式位掩码常量（保留用于旧版快照平滑迁移清理）
    public static final int RAF_CDMA = 8;
    public static final int RAF_EVDO_0 = 16;
    public static final int RAF_EVDO_A = 32;
    public static final int RAF_1XRTT = 64;
    public static final int RAF_EVDO_B = 2048;
    public static final int RAF_GSM = 32768;
    public static final int RAF_TD_SCDMA = 65536;
    public static final int RAF_DEFAULT_FULL = 916479;

    // AOSP RILConstants 对应首选网络模式值
    public static final int NET_MODE_GSM_ONLY = 1;
    public static final int NET_MODE_CDMA_NO_EVDO = 5;
    public static final int NET_MODE_EVDO_NO_CDMA = 6;
    public static final int NET_MODE_TDSCDMA_ONLY = 13;

    /**
     * 自适应计算得到的单卡隔离目标（保留类型以兼容已有外部引用）。
     */
    public static final class IsolationTarget {
        public final int rafBitmask;
        public final int networkMode;
        public final String label;
        public final boolean gsmFallback;

        public IsolationTarget(int rafBitmask, int networkMode, String label, boolean gsmFallback) {
            this.rafBitmask = rafBitmask;
            this.networkMode = networkMode;
            this.label = label;
            this.gsmFallback = gsmFallback;
        }

        public String getBinaryBitmask() {
            return rafBitmask > 0 ? toBinaryBitmask20(rafBitmask) : "";
        }
    }

    private CellBlockConfig() {
    }

    public static String slotOrigPath(int slotIndex) {
        return RootShell.DATA_DIR + "/slot" + slotIndex + "_cell_orig.conf";
    }

    public static String slotBlockKey(int slotIndex) {
        return slotIndex == 1 ? KEY_BLOCK_SLOT1 : KEY_BLOCK_SLOT0;
    }

    public static String modemRafKey(int slotIndex) {
        return KEY_MODEM_RAF_PREFIX + slotIndex;
    }

    /**
     * 判断是否为旧版制式降级方案遗留的单制式掩码。
     */
    public static boolean isLegacyIsolationMask(int raf) {
        return raf == RAF_EVDO_A
                || raf == RAF_TD_SCDMA
                || raf == RAF_EVDO_B
                || raf == RAF_EVDO_0
                || raf == RAF_1XRTT
                || raf == RAF_CDMA
                || raf == RAF_GSM;
    }

    /**
     * 当前主力隔离方案已升级为手动锁定空网 PLMN 00101，不再切换网络制式。
     */
    public static IsolationTarget selectIsolationTarget(int modemRaf) {
        return new IsolationTarget(0, -1, "锁定空网 " + BLOCK_PLMN, false);
    }

    public static boolean isChinaMainlandPlmn(String simNumeric) {
        return simNumeric != null && simNumeric.trim().startsWith("460");
    }

    /**
     * 判断目标槽位当前是否实际生效蜂窝屏蔽（在位 LOADED 且未被国内 460 卡自动豁免）。
     */
    public static boolean isEffectiveBlockActive(
            boolean ruleEnabled, boolean exemptCn, String simState, String simNumeric) {
        if (!ruleEnabled || !"LOADED".equalsIgnoreCase(simState == null ? "" : simState.trim())) {
            return false;
        }
        return !(exemptCn && isChinaMainlandPlmn(simNumeric));
    }

    /**
     * 将网络制式位掩码格式化为供 cmd phone set-allowed-network-types-for-users 使用的至少 20 位二进制串。
     */
    public static String toBinaryBitmask20(int raf) {
        long u = raf & 0xFFFFFFFFL;
        String raw = Long.toBinaryString(u);
        if (raw.length() >= 20) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(20);
        for (int i = raw.length(); i < 20; i++) {
            sb.append('0');
        }
        sb.append(raw);
        return sb.toString();
    }

    /**
     * 从 siminfo allowed_network_types_for_reasons 字符串中解析 user= 数值，失败返回 -1。
     */
    public static int parseUserRafFromReasons(String reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return -1;
        }
        for (String part : reasons.split(",")) {
            String t = part.trim();
            if (t.startsWith("user=")) {
                try {
                    return Integer.parseInt(t.substring(5).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * 生成通用规则同步脚本：写入配置并触发进程内空网 PLMN 锁网/自动选网恢复，同时平滑清理历史制式降级快照。
     */
    public static String buildApplyRulesShellScript(
            boolean slot0Block, boolean slot1Block, boolean exemptCn, String logTag) {
        String s0 = slot0Block ? "1" : "0";
        String s1 = slot1Block ? "1" : "0";
        String ex = exemptCn ? "1" : "0";
        String tag = (logTag == null || logTag.isEmpty()) ? "ui" : logTag;

        return ""
                + "DATA_DIR='" + RootShell.DATA_DIR + "'\n"
                + "CONF='" + CONF_PATH + "'\n"
                + "LOG='" + RootShell.LOG_PATH + "'\n"
                + "mkdir -p \"$DATA_DIR\"\n"
                + "TMP_CONF=\"${CONF}.tmp.$$\"\n"
                + "cat > \"$TMP_CONF\" <<'CONF_EOF'\n"
                + "SLOT0_BLOCK=" + s0 + "\n"
                + "SLOT1_BLOCK=" + s1 + "\n"
                + "EXEMPT_CN_460=" + ex + "\n"
                + "CONF_EOF\n"
                + "chmod 0644 \"$TMP_CONF\"\n"
                + "mv -f \"$TMP_CONF\" \"$CONF\"\n"
                + "settings put global " + KEY_EXEMPT_CN + " " + ex + " >/dev/null 2>&1\n"
                + "settings put global " + KEY_BLOCK_SLOT0 + " " + s0 + " >/dev/null 2>&1\n"
                + "settings put global " + KEY_BLOCK_SLOT1 + " " + s1 + " >/dev/null 2>&1\n"
                + buildLegacyMigrationSnippet(tag)
                + "migrate_legacy_slot_snapshot 0\n"
                + "migrate_legacy_slot_snapshot 1\n"
                + "log_cb \"rules updated (slot0=" + s0 + ", slot1=" + s1 + ", exempt_cn=" + ex + ", mode=plmn_" + BLOCK_PLMN + ")\"\n";
    }

    /**
     * 生成一键恢复全部卡槽自动选网的 Shell 脚本。
     */
    public static String buildRestoreAllShellScript(String logTag) {
        String tag = (logTag == null || logTag.isEmpty()) ? "ui" : logTag;
        return ""
                + "DATA_DIR='" + RootShell.DATA_DIR + "'\n"
                + "CONF='" + CONF_PATH + "'\n"
                + "LOG='" + RootShell.LOG_PATH + "'\n"
                + "mkdir -p \"$DATA_DIR\"\n"
                + "rm -f \"$CONF\"\n"
                + "settings put global " + KEY_EXEMPT_CN + " 1 >/dev/null 2>&1\n"
                + "settings put global " + KEY_BLOCK_SLOT0 + " 0 >/dev/null 2>&1\n"
                + "settings put global " + KEY_BLOCK_SLOT1 + " 0 >/dev/null 2>&1\n"
                + buildLegacyMigrationSnippet(tag)
                + "migrate_legacy_slot_snapshot 0\n"
                + "migrate_legacy_slot_snapshot 1\n"
                + "log_cb \"all slots restored to automatic network selection\"\n";
    }

    /**
     * 清理历史版本遗留的制式降级快照与隔离掩码，避免其覆盖现行全制式 + 手动锁网策略。
     */
    private static String buildLegacyMigrationSnippet(String logTag) {
        return ""
                + "log_cb() { echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [" + logTag + "] $1\" >> \"$LOG\"; }\n"
                + "int_to_bin20() {\n"
                + "  v=\"$1\"; out=''; i=0\n"
                + "  while [ \"$i\" -lt 20 ] || [ \"$v\" -gt 0 ]; do\n"
                + "    out=\"$((v % 2))$out\"; v=\"$((v / 2))\"; i=$((i + 1))\n"
                + "  done\n"
                + "  printf '%s' \"$out\"\n"
                + "}\n"
                + "is_legacy_iso_mask() {\n"
                + "  case \"$1\" in 32|65536|2048|16|64|8|32768) return 0 ;; *) return 1 ;; esac\n"
                + "}\n"
                + "migrate_legacy_slot_snapshot() {\n"
                + "  s=\"$1\"; snap=\"$DATA_DIR/slot${s}_cell_orig.conf\"\n"
                + "  row=$(content query --uri content://telephony/siminfo --where \"sim_id=$s\" --projection _id:allowed_network_types_for_reasons 2>/dev/null | tr -d '\\r' | grep '_id=' | head -n 1)\n"
                + "  cur_sub=$(printf '%s' \"$row\" | sed -nE 's/.*_id=([0-9]+).*/\\1/p')\n"
                + "  cur_reasons=$(printf '%s' \"$row\" | sed -n 's/.*allowed_network_types_for_reasons=//p')\n"
                + "  cur_user=$(printf '%s' \"$cur_reasons\" | sed -nE 's/.*user=([0-9]+).*/\\1/p')\n"
                + "  [ -f \"$snap\" ] || is_legacy_iso_mask \"$cur_user\" || return 0\n"
                + "  ORIG_SUB_ID=''; ORIG_REASONS=''; ORIG_PREF_MODE=''; ORIG_OPLUS_USER_MODE=''\n"
                + "  [ -f \"$snap\" ] && . \"$snap\"\n"
                + "  target_raf=" + RAF_DEFAULT_FULL + "\n"
                + "  orig_user=$(printf '%s' \"$ORIG_REASONS\" | sed -nE 's/.*user=([0-9]+).*/\\1/p')\n"
                + "  if [ -n \"$orig_user\" ] && ! is_legacy_iso_mask \"$orig_user\"; then\n"
                + "    target_raf=\"$orig_user\"\n"
                + "  fi\n"
                + "  target_bin=$(int_to_bin20 \"$target_raf\")\n"
                + "  if [ -n \"$cur_sub\" ]; then\n"
                + "    if [ \"$cur_sub\" = \"$ORIG_SUB_ID\" ] && [ -n \"$ORIG_REASONS\" ] && [ -n \"$orig_user\" ] && ! is_legacy_iso_mask \"$orig_user\"; then\n"
                + "      content update --uri content://telephony/siminfo --where \"_id=$cur_sub\" --bind \"allowed_network_types_for_reasons:s:$ORIG_REASONS\" >/dev/null 2>&1\n"
                + "      [ -n \"$ORIG_PREF_MODE\" ] && [ \"$ORIG_PREF_MODE\" != 'null' ] && settings put global \"preferred_network_mode${cur_sub}\" \"$ORIG_PREF_MODE\" >/dev/null 2>&1\n"
                + "      if [ -n \"$ORIG_OPLUS_USER_MODE\" ] && [ \"$ORIG_OPLUS_USER_MODE\" != 'null' ]; then\n"
                + "        settings put global \"oplus_user_preferred_network_mode${cur_sub}\" \"$ORIG_OPLUS_USER_MODE\" >/dev/null 2>&1\n"
                + "      else\n"
                + "        settings delete global \"oplus_user_preferred_network_mode${cur_sub}\" >/dev/null 2>&1\n"
                + "      fi\n"
                + "    else\n"
                + "      settings delete global \"oplus_user_preferred_network_mode${cur_sub}\" >/dev/null 2>&1\n"
                + "    fi\n"
                + "    cmd phone set-allowed-network-types-for-users -s \"$s\" \"$target_bin\" >/dev/null 2>&1\n"
                + "  fi\n"
                + "  rm -f \"$snap\"\n"
                + "  log_cb \"slot$s migrated legacy rat snapshot (raf=$target_raf)\"\n"
                + "}\n";
    }
}
