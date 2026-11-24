package io.iniwex.vowifix;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 双卡基带与系统状态探测器：
 * 公开态只读反射 SystemProperties（无 Root 也可工作）；底层 EFS 与日志仅在用户按需查询时持锁访问。
 */
public final class TelephonyProbe {

    public static final class SlotSnapshot {
        public final int slotIndex;
        public final String simState;
        public final String simAlpha;
        public final String simNumeric;
        public final String simIso;
        public final String netAlpha;
        public final String netNumeric;
        public final String netType;
        public final String imsType;

        public SlotSnapshot(int slotIndex, String simState, String simAlpha, String simNumeric,
                            String simIso, String netAlpha, String netNumeric,
                            String netType, String imsType) {
            this.slotIndex = slotIndex;
            this.simState = simState;
            this.simAlpha = simAlpha;
            this.simNumeric = simNumeric;
            this.simIso = simIso;
            this.netAlpha = netAlpha;
            this.netNumeric = netNumeric;
            this.netType = netType;
            this.imsType = imsType;
        }

        public boolean isSimLoaded() {
            return "LOADED".equalsIgnoreCase(simState);
        }

        public boolean isSimAbsent() {
            return simState.isEmpty() || "ABSENT".equalsIgnoreCase(simState) || "UNKNOWN".equalsIgnoreCase(simState);
        }

        public boolean isVoWiFi() {
            return "vowifi".equalsIgnoreCase(imsType);
        }

        public boolean isVoLte() {
            return "volte".equalsIgnoreCase(imsType) || "vonr".equalsIgnoreCase(imsType);
        }

        public boolean isRoamingOnCellular() {
            if (!isSimLoaded() || simNumeric.length() < 3 || netNumeric.length() < 3) {
                return false;
            }
            return !simNumeric.substring(0, 3).equals(netNumeric.substring(0, 3));
        }

        public String getImsBadgeText() {
            if (isVoWiFi()) {
                return "VoWiFi";
            }
            if (isVoLte()) {
                return imsType.toUpperCase(Locale.US);
            }
            return "IMS 未注册";
        }
    }

    public static final class EfsSlotStatus {
        public static final String STATE_NO_DIAG = "NO_DIAG_BACKEND";

        public String iniState = "未读取";
        public String seState = "未读取";
        public String seDetail = "";
        public int iniSize = 0;

        public boolean isFullyPatched() {
            return "ALREADY_PATCHED".equals(iniState) &&
                    ("ALREADY_PATCHED".equals(seState) || "SKIP_INACTIVE".equals(seState) || "NOT_FOUND".equals(seState));
        }

        public boolean isAbsent() {
            return "NOT_FOUND".equals(iniState);
        }

        public String getIniSummaryText() {
            if (STATE_NO_DIAG.equals(iniState)) {
                return "配置合并: 未开放 Diag 通道 (仅上层模式)";
            }
            if ("ALREADY_PATCHED".equals(iniState)) {
                return "配置合并: 已补丁" + (iniSize > 0 ? " (" + iniSize + " B)" : "");
            }
            if ("UNPATCHED".equals(iniState)) {
                return "配置合并: 未补丁";
            }
            if ("NOT_FOUND".equals(iniState)) {
                return "配置合并: 无配置文件";
            }
            if ("READ_ERROR".equals(iniState)) {
                return "配置合并: 读取失败";
            }
            return "配置合并: " + iniState;
        }

        public String getSeSummaryText() {
            if (STATE_NO_DIAG.equals(seState)) {
                return "漫游解锁: 由上层配置与原厂 MBN 接管";
            }
            if ("ALREADY_PATCHED".equals(seState)) {
                return "漫游解锁: 已解锁" + (seDetail.isEmpty() ? "" : " (" + seDetail + ")");
            }
            if ("UNPATCHED".equals(seState)) {
                return "漫游解锁: 受限/移动优先" + (seDetail.isEmpty() ? "" : " (" + seDetail + ")");
            }
            if ("SKIP_INACTIVE".equals(seState) || "NOT_FOUND".equals(seState)) {
                return "漫游解锁: 槽位未激活";
            }
            if ("READ_ERROR".equals(seState)) {
                return "漫游解锁: 读取失败";
            }
            return "漫游解锁: " + seState;
        }
    }

    public static final class FullSnapshot {
        public boolean lsposedActive;
        public boolean hasRoot;
        public boolean moduleInstalled;
        public boolean binaryAvailable;
        public boolean watcherRunning;
        public boolean watcherEnabled;
        public String diagBackend = "unknown";
        public String baseband = "";
        public SlotSnapshot[] slots = new SlotSnapshot[2];
        public EfsSlotStatus[] efs = new EfsSlotStatus[]{new EfsSlotStatus(), new EfsSlotStatus()};
        public String serviceLog = "";
        public String errorNote = "";

        public boolean hasDiagBackend() {
            return diagBackend != null && !diagBackend.isEmpty()
                    && !"none".equalsIgnoreCase(diagBackend)
                    && !"unknown".equalsIgnoreCase(diagBackend);
        }
    }

    private static final Pattern INI_LINE = Pattern.compile("^(slot[01]) \\([^)]+\\): (.*)$");
    private static final Pattern SE_LINE = Pattern.compile("^(slot[01])_se \\([^)]+\\): (.*)$");

    private TelephonyProbe() {
    }

    public static String getProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, def);
            return v != null ? v.toString() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static String getSlotPart(String prop, int slotIndex) {
        String raw = getProp(prop, "");
        if (raw.isEmpty()) {
            return "";
        }
        String[] parts = raw.split(",", -1);
        if (slotIndex < parts.length) {
            return parts[slotIndex].trim();
        }
        return "";
    }

    /**
     * 仅通过 SystemProperties 采集双卡可见状态（零 Root 权限下也可即时运行）。
     */
    public static SlotSnapshot[] readPublicSlotSnapshots() {
        SlotSnapshot[] res = new SlotSnapshot[2];
        for (int i = 0; i < 2; i++) {
            res[i] = new SlotSnapshot(
                    i,
                    getSlotPart("gsm.sim.state", i),
                    getSlotPart("gsm.sim.operator.alpha", i),
                    getSlotPart("gsm.sim.operator.numeric", i),
                    getSlotPart("gsm.sim.operator.iso-country", i),
                    getSlotPart("gsm.operator.alpha", i),
                    getSlotPart("gsm.operator.numeric", i),
                    getSlotPart("gsm.network.type", i),
                    getProp("gsm.ims.type" + i, "").trim()
            );
        }
        return res;
    }

    /**
     * 为控制中心快捷磁贴生成简洁的当前 IMS 注册概要。
     */
    public static String[] buildTileDisplay() {
        SlotSnapshot[] slots = readPublicSlotSnapshots();
        boolean s0Wifi = slots[0].isVoWiFi();
        boolean s1Wifi = slots[1].isVoWiFi();
        if (s0Wifi && s1Wifi) {
            return new String[]{"1", "VoWiFi · 双卡", "卡1/卡2 已就绪"};
        }
        if (s0Wifi) {
            String op = slots[0].simAlpha.isEmpty() ? "卡1 WLAN 通话" : slots[0].simAlpha + " · WLAN 通话";
            return new String[]{"1", "VoWiFi · 卡1", op};
        }
        if (s1Wifi) {
            String op = slots[1].simAlpha.isEmpty() ? "卡2 WLAN 通话" : slots[1].simAlpha + " · WLAN 通话";
            return new String[]{"1", "VoWiFi · 卡2", op};
        }
        if (slots[0].isVoLte()) {
            return new String[]{"0", slots[0].getImsBadgeText() + " · 卡1", "点击进入 VoWiFiX"};
        }
        if (slots[1].isVoLte()) {
            return new String[]{"0", slots[1].getImsBadgeText() + " · 卡2", "点击进入 VoWiFiX"};
        }
        return new String[]{"0", "IMS 未注册", "点击进入 VoWiFiX"};
    }

    /**
     * 采集完整双端状态；仅当存在可用 Diag 后端且 queryEfs=true 时按需持锁读一次基带状态。
     */
    public static FullSnapshot collectSnapshot(boolean queryEfs) {
        FullSnapshot snap = new FullSnapshot();
        snap.lsposedActive = ActivationStatus.isModuleActive();
        snap.baseband = getProp("gsm.version.baseband", "");
        snap.slots = readPublicSlotSnapshots();
        snap.hasRoot = RootShell.checkRoot();
        if (!snap.hasRoot) {
            snap.efs[0].iniState = "需要 Root 权限";
            snap.efs[0].seState = "需要 Root 权限";
            snap.efs[1].iniState = "需要 Root 权限";
            snap.efs[1].seState = "需要 Root 权限";
            return snap;
        }

        // 先读取纯文件状态、无副作用后端探测与日志（不打开 Diag 通道）
        String metaScript = RootShell.resolveBinarySnippet()
                + "MOD_OK=0; [ -d '" + RootShell.MODULE_DIR + "' ] && MOD_OK=1\n"
                + "BIN_OK=0; [ -n \"$BIN\" ] && BIN_OK=1\n"
                + "WATCH_EN=1; [ -f '" + RootShell.DISABLED_FLAG + "' ] && WATCH_EN=0\n"
                + "WATCH_RUN=0\n"
                + "if [ -f '" + RootShell.PID_PATH + "' ]; then\n"
                + "  WPID=$(cat '" + RootShell.PID_PATH + "' 2>/dev/null)\n"
                + "  if [ -n \"$WPID\" ] && [ -r \"/proc/$WPID/cmdline\" ]; then\n"
                + "    tr '\\0' ' ' < \"/proc/$WPID/cmdline\" 2>/dev/null | grep -q 'service.sh' && WATCH_RUN=1\n"
                + "  fi\n"
                + "fi\n"
                + "BKD='unknown'\n"
                + "if [ -n \"$BIN\" ]; then\n"
                + "  DET=$(\"$BIN\" detect 2>/dev/null | head -n 1)\n"
                + "  BKD=\"${DET#BACKEND=}\"\n"
                + "  [ -n \"$BKD\" ] || BKD='none'\n"
                + "fi\n"
                + "echo \"META:$MOD_OK:$BIN_OK:$WATCH_EN:$WATCH_RUN:$BKD\"\n"
                + "echo '===LOG_BEGIN==='\n"
                + "[ -f '" + RootShell.LOG_PATH + "' ] && tail -n 60 '" + RootShell.LOG_PATH + "' 2>/dev/null\n"
                + "echo '===LOG_END==='\n";

        RootShell.Result mr = RootShell.execRoot(metaScript, 8);
        if (mr.isSuccess()) {
            parseMetaAndLog(mr.stdout, snap);
        } else {
            snap.errorNote = mr.stderr;
        }

        if (snap.binaryAvailable && !snap.hasDiagBackend()) {
            snap.efs[0].iniState = EfsSlotStatus.STATE_NO_DIAG;
            snap.efs[0].seState = EfsSlotStatus.STATE_NO_DIAG;
            snap.efs[1].iniState = EfsSlotStatus.STATE_NO_DIAG;
            snap.efs[1].seState = EfsSlotStatus.STATE_NO_DIAG;
            return snap;
        }

        if (queryEfs && snap.binaryAvailable && snap.hasDiagBackend()) {
            String diagCmd = RootShell.resolveBinarySnippet()
                    + "[ -n \"$BIN\" ] || exit 1\n"
                    + RootShell.wrapWithDiagLock("\"$BIN\" status all --no-lock --backup-dir '" + RootShell.DATA_DIR + "' 2>&1");
            RootShell.Result er = RootShell.execRoot(diagCmd, 25);
            if (er.isSuccess()) {
                parseEfsStatusOutput(er.stdout, snap.efs);
            } else {
                String msg = er.stdout.isEmpty() ? er.stderr : er.stdout;
                String label = msg.contains("DIAG_LOCK_BUSY") ? "守护正在操作中" : "读取失败";
                snap.efs[0].iniState = label;
                snap.efs[0].seState = label;
                snap.efs[1].iniState = label;
                snap.efs[1].seState = label;
                if (!msg.isEmpty()) {
                    snap.errorNote = msg;
                }
            }
        }
        return snap;
    }

    private static void parseMetaAndLog(String out, FullSnapshot snap) {
        String[] lines = out.split("\n");
        boolean inLog = false;
        StringBuilder logSb = new StringBuilder();
        for (String line : lines) {
            if ("===LOG_BEGIN===".equals(line)) {
                inLog = true;
                continue;
            }
            if ("===LOG_END===".equals(line)) {
                inLog = false;
                continue;
            }
            if (inLog) {
                if (logSb.length() > 0) {
                    logSb.append('\n');
                }
                logSb.append(line);
                continue;
            }
            if (line.startsWith("META:")) {
                String[] p = line.split(":");
                if (p.length >= 5) {
                    snap.moduleInstalled = "1".equals(p[1]);
                    snap.binaryAvailable = "1".equals(p[2]);
                    snap.watcherEnabled = "1".equals(p[3]);
                    snap.watcherRunning = "1".equals(p[4]);
                }
                if (p.length >= 6 && !p[5].trim().isEmpty()) {
                    snap.diagBackend = p[5].trim();
                }
            }
        }
        snap.serviceLog = logSb.toString();
    }

    public static void parseEfsStatusOutput(String out, EfsSlotStatus[] efs) {
        if (out == null) {
            return;
        }
        for (String rawLine : out.split("\n")) {
            String line = rawLine.trim();
            Matcher mIni = INI_LINE.matcher(line);
            if (mIni.matches()) {
                int idx = "slot1".equals(mIni.group(1)) ? 1 : 0;
                String info = mIni.group(2);
                if (info.contains("ALREADY_PATCHED")) {
                    efs[idx].iniState = "ALREADY_PATCHED";
                } else if (info.contains("UNPATCHED")) {
                    efs[idx].iniState = "UNPATCHED";
                } else if (info.contains("NOT_FOUND")) {
                    efs[idx].iniState = "NOT_FOUND";
                } else if (info.contains("READ_ERROR")) {
                    efs[idx].iniState = "READ_ERROR";
                }
                Matcher sz = Pattern.compile("size=(\\d+)").matcher(info);
                if (sz.find()) {
                    try {
                        efs[idx].iniSize = Integer.parseInt(sz.group(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            Matcher mSe = SE_LINE.matcher(line);
            if (mSe.matches()) {
                int idx = "slot1".equals(mSe.group(1)) ? 1 : 0;
                String info = mSe.group(2);
                if (info.contains("ALREADY_PATCHED")) {
                    efs[idx].seState = "ALREADY_PATCHED";
                } else if (info.contains("UNPATCHED")) {
                    efs[idx].seState = "UNPATCHED";
                } else if (info.contains("SKIP_INACTIVE")) {
                    efs[idx].seState = "SKIP_INACTIVE";
                } else if (info.contains("NOT_FOUND")) {
                    efs[idx].seState = "NOT_FOUND";
                } else if (info.contains("READ_ERROR")) {
                    efs[idx].seState = "READ_ERROR";
                }
                Matcher bytes = Pattern.compile("wfc=(\\S+) pref=(\\S+) roam=(\\S+)").matcher(info);
                if (bytes.find()) {
                    efs[idx].seDetail = "wfc=" + bytes.group(1) + ", pref=" + bytes.group(2) + ", roam=" + bytes.group(3);
                }
            }
        }
    }

    /**
     * 检查通话状态（含来电响铃与通话中），避免操作干扰正在进行的语音通话。
     */
    public static boolean isCallActive() {
        RootShell.Result r = RootShell.execRoot(
                "dumpsys telephony.registry 2>/dev/null | grep -qE 'mCallState=[12]' && echo CALL_ACTIVE || echo CALL_IDLE",
                6
        );
        return r.stdout.contains("CALL_ACTIVE");
    }

    /**
     * 校准活跃卡 siminfo 中历史留存的 wfc_ims_mode=1 / -1（与 service.sh 口径一致）。
     */
    public static String normalizeSiminfoShellSnippet() {
        return "rows=\"$(content query --uri content://telephony/siminfo --where 'sim_id>=0' "
                + "--projection _id:mcc_string:mnc_string:wfc_ims_enabled:wfc_ims_mode:wfc_ims_roaming_mode 2>/dev/null)\"\n"
                + "if [ -n \"$rows\" ]; then\n"
                + "  printf '%s\\n' \"$rows\" | tr -d '\\r' | while IFS= read -r line; do\n"
                + "    echo \"$line\" | grep -q '_id=' || continue\n"
                + "    row_id=\"${line#*_id=}\"; row_id=\"${row_id%%,*}\"; row_id=\"${row_id%% *}\"\n"
                + "    w_en=\"${line#*wfc_ims_enabled=}\"; w_en=\"${w_en%%,*}\"; w_en=\"${w_en%% *}\"\n"
                + "    w_mode=\"${line#*wfc_ims_mode=}\"; w_mode=\"${w_mode%%,*}\"; w_mode=\"${w_mode%% *}\"\n"
                + "    w_roam=\"${line#*wfc_ims_roaming_mode=}\"; w_roam=\"${w_roam%%,*}\"; w_roam=\"${w_roam%% *}\"\n"
                + "    [ \"$w_en\" = '0' ] && continue\n"
                + "    case \"$w_mode:$w_roam\" in\n"
                + "      1:*|*:1|-1:*|*:-1)\n"
                + "        if [ \"$w_mode\" != '2' ] || [ \"$w_roam\" != '2' ]; then\n"
                + "          content update --uri content://telephony/siminfo --where \"_id=$row_id\" "
                + "--bind wfc_ims_mode:i:2 --bind wfc_ims_roaming_mode:i:2 >/dev/null 2>&1\n"
                + "          echo \"SIMINFO_NORMALIZED=_id=$row_id\"\n"
                + "        fi ;;\n"
                + "    esac\n"
                + "  done\n"
                + "fi\n";
    }
}
