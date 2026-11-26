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
            if (!CountrySpoofer.isValidCountryMcc(simNumeric)
                    || !CountrySpoofer.isValidCountryMcc(netNumeric)) {
                return false;
            }
            return !simNumeric.substring(0, 3).equals(netNumeric.substring(0, 3));
        }

        public SlotSnapshot withImsType(String newImsType) {
            return new SlotSnapshot(
                    slotIndex,
                    simState,
                    simAlpha,
                    simNumeric,
                    simIso,
                    netAlpha,
                    netNumeric,
                    netType,
                    newImsType == null ? "" : newImsType.trim()
            );
        }

        public boolean hasCellularNetwork() {
            return CountrySpoofer.isValidCountryMcc(netNumeric)
                    && (CountrySpoofer.hasActiveCellularRadioTech(netType) || isRoamingOnCellular());
        }

        public boolean isHomeCellular() {
            if (!isSimLoaded() || simNumeric.length() < 3 || netNumeric.length() < 3) {
                return false;
            }
            if (!CountrySpoofer.isValidCountryMcc(netNumeric)) {
                return false;
            }
            return CountrySpoofer.hasActiveCellularRadioTech(netType)
                    && simNumeric.substring(0, 3).equals(netNumeric.substring(0, 3));
        }

        public boolean isIwlanOnly() {
            return !hasCellularNetwork() && (isVoWiFi() || "IWLAN".equalsIgnoreCase(netType));
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

    public static final class CellBlockSlotStatus {
        public boolean ruleEnabled = false;
        public boolean exemptCnActive = false;
        public boolean manualSelection = false;
        public int modemRaf = 0;
        public int currentUserRaf = -1;
        public String wwanVoiceReg = "";
        public String wwanDataReg = "";
        public String wwanPlmn = "";

        public CellBlockConfig.IsolationTarget getTarget() {
            return CellBlockConfig.selectIsolationTarget(modemRaf);
        }

        /**
         * 基于 GsmCdmaPhone 的 WWAN CS/PS 注册态及 rRplmn 判断是否已实现零蜂窝基站驻网。
         */
        public boolean isZeroCellularRegistered() {
            if (wwanVoiceReg.isEmpty() && wwanDataReg.isEmpty()) {
                return false;
            }
            boolean vReg = wwanVoiceReg.contains("HOME") || wwanVoiceReg.contains("ROAMING");
            boolean dReg = wwanDataReg.contains("HOME") || wwanDataReg.contains("ROAMING");
            return !vReg && !dReg && wwanPlmn.isEmpty();
        }

        /**
         * 仅当同时处于手动选网态且 WWAN CS/PS 双域零驻网时，才认定空网锁定真正生效。
         */
        public boolean isManualZeroCellularLocked() {
            return manualSelection && isZeroCellularRegistered();
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
        public boolean cellExemptCn = true;
        public CellBlockSlotStatus[] cellBlock = new CellBlockSlotStatus[]{
                new CellBlockSlotStatus(), new CellBlockSlotStatus()};
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

    private static final Pattern IMS_PHONE_ID_LINE = Pattern.compile("^mPhoneId\\s*=\\s*(\\d+)$");
    private static final Pattern IMS_REG_BOOL_LINE = Pattern.compile("^mImsRegistered\\s*=\\s*(true|false)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMS_MMTEL_STATE_LINE = Pattern.compile("^mImsMmTelRegistrationState\\s*=\\s*([A-Za-z0-9_]+)");
    private static final Pattern IMS_SS_DATA_TECH = Pattern.compile("getRilDataRadioTechnology=([^,}\\s]+)");
    private static final Pattern IMS_SS_VOICE_TECH = Pattern.compile("getRilVoiceRadioTechnology=([^,}\\s]+)");
    private static final Pattern IMS_TRANSPORT_TYPE = Pattern.compile("imsTransportType=([A-Za-z0-9_]+)");
    private static final Pattern IMS_RADIO_TECH = Pattern.compile("imsRadioTech=([A-Za-z0-9_]+)");

    // 进程内缓存最近一次通过 Root 解析得到的 IMS 类型，供快捷磁贴与无 Root 状态复用
    private static final long IMS_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Object IMS_CACHE_LOCK = new Object();
    private static final String[] CACHED_IMS_TYPES = new String[]{"", ""};
    private static final String[] CACHED_SIM_NUMERICS = new String[]{"", ""};
    private static final String[] CACHED_NET_TYPES = new String[]{"", ""};
    private static final long[] CACHED_IMS_TS = new long[]{0L, 0L};

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
     * 缓存最新解析到的双卡 IMS 状态，供跨组件无 Root 读取复用。
     */
    public static void cacheResolvedImsTypes(SlotSnapshot[] slots) {
        if (slots == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (IMS_CACHE_LOCK) {
            int count = Math.min(2, slots.length);
            for (int i = 0; i < count; i++) {
                SlotSnapshot slot = slots[i];
                if (slot == null) {
                    continue;
                }
                CACHED_IMS_TYPES[i] = slot.imsType == null ? "" : slot.imsType.trim();
                CACHED_SIM_NUMERICS[i] = slot.simNumeric == null ? "" : slot.simNumeric.trim();
                CACHED_NET_TYPES[i] = slot.netType == null ? "" : slot.netType.trim();
                CACHED_IMS_TS[i] = now;
            }
        }
    }

    /**
     * 仅原样读取 SystemProperties，不做任何缓存回填或网络类型推断。
     */
    private static SlotSnapshot[] readRawPublicSlotSnapshots() {
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
     * 在非 Oplus 机型缺少 gsm.ims.type* 时，优先复用进程内缓存，否则仅对 IWLAN 做温和兜底。
     */
    private static SlotSnapshot applyPublicImsFallback(SlotSnapshot slot) {
        if (slot == null || !slot.imsType.isEmpty() || !slot.isSimLoaded()) {
            return slot;
        }
        int idx = slot.slotIndex;
        if (idx >= 0 && idx < 2) {
            long now = System.currentTimeMillis();
            synchronized (IMS_CACHE_LOCK) {
                long ts = CACHED_IMS_TS[idx];
                if (ts > 0L
                        && (now - ts) >= 0L
                        && (now - ts) <= IMS_CACHE_TTL_MS
                        && slot.simNumeric.equals(CACHED_SIM_NUMERICS[idx])
                        && slot.netType.equalsIgnoreCase(CACHED_NET_TYPES[idx])) {
                    return slot.withImsType(CACHED_IMS_TYPES[idx]);
                }
            }
        }
        if ("IWLAN".equalsIgnoreCase(slot.netType)) {
            return slot.withImsType("vowifi");
        }
        return slot;
    }

    /**
     * 仅通过 SystemProperties 与进程内缓存采集双卡可见状态（零 Root 权限下也可即时运行）。
     */
    public static SlotSnapshot[] readPublicSlotSnapshots() {
        SlotSnapshot[] raw = readRawPublicSlotSnapshots();
        for (int i = 0; i < raw.length; i++) {
            raw[i] = applyPublicImsFallback(raw[i]);
        }
        return raw;
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
        SlotSnapshot[] rawSlots = readRawPublicSlotSnapshots();
        snap.hasRoot = RootShell.checkRoot();
        if (!snap.hasRoot) {
            for (int i = 0; i < rawSlots.length; i++) {
                snap.slots[i] = applyPublicImsFallback(rawSlots[i]);
            }
            snap.efs[0].iniState = "需要 Root 权限";
            snap.efs[0].seState = "需要 Root 权限";
            snap.efs[1].iniState = "需要 Root 权限";
            snap.efs[1].seState = "需要 Root 权限";
            return snap;
        }

        // 仅当存在已加载但缺少 Oplus gsm.ims.type* 属性的 SIM 卡时，按需附加 AOSP ImsPhone 探测
        boolean needImsDump = (rawSlots[0].isSimLoaded() && rawSlots[0].imsType.isEmpty())
                || (rawSlots[1].isSimLoaded() && rawSlots[1].imsType.isEmpty());

        // 先读取纯文件状态、无副作用后端探测与日志（不打开 Diag 通道）
        StringBuilder metaScript = new StringBuilder();
        metaScript.append(RootShell.resolveBinarySnippet())
                .append("MOD_OK=0; [ -d '").append(RootShell.MODULE_DIR).append("' ] && MOD_OK=1\n")
                .append("BIN_OK=0; [ -n \"$BIN\" ] && BIN_OK=1\n")
                .append("WATCH_EN=1; [ -f '").append(RootShell.DISABLED_FLAG).append("' ] && WATCH_EN=0\n")
                .append("WATCH_RUN=0\n")
                .append("if [ -f '").append(RootShell.PID_PATH).append("' ]; then\n")
                .append("  WPID=$(cat '").append(RootShell.PID_PATH).append("' 2>/dev/null)\n")
                .append("  if [ -n \"$WPID\" ] && [ -r \"/proc/$WPID/cmdline\" ]; then\n")
                .append("    tr '\\0' ' ' < \"/proc/$WPID/cmdline\" 2>/dev/null | grep -q 'service.sh' && WATCH_RUN=1\n")
                .append("  fi\n")
                .append("fi\n")
                .append("BKD='unknown'\n")
                .append("if [ -n \"$BIN\" ]; then\n")
                .append("  DET=$(\"$BIN\" detect 2>/dev/null | head -n 1)\n")
                .append("  BKD=\"${DET#BACKEND=}\"\n")
                .append("  [ -n \"$BKD\" ] || BKD='none'\n")
                .append("fi\n")
                .append("echo \"META:$MOD_OK:$BIN_OK:$WATCH_EN:$WATCH_RUN:$BKD\"\n")
                .append("echo '===LOG_BEGIN==='\n")
                .append("[ -f '").append(RootShell.LOG_PATH).append("' ] && tail -n 60 '").append(RootShell.LOG_PATH).append("' 2>/dev/null\n")
                .append("echo '===LOG_END==='\n");
        if (needImsDump) {
            metaScript.append("echo '===IMS_DUMP_BEGIN==='\n")
                    .append("dumpsys activity service com.android.phone/.TelephonyDebugService 2>/dev/null")
                    .append(" | grep -F -e 'ImsPhone extends:' -e 'mPhoneId=' -e 'mPhoneId ='")
                    .append(" -e 'mImsRegistered=' -e 'ImsPhone:' -e 'mImsMmTelRegistrationState'")
                    .append(" -e ' mSS =' -e 'onImsMmTelConnected' -e 'handleImsRegistered:' || true\n")
                    .append("echo '===IMS_DUMP_END==='\n");
        }
        // 读取单卡禁蜂窝规则、siminfo user 掩码与 GsmCdmaPhone WWAN 驻网态（绝不打开 Diag 通道）
        metaScript.append("echo '===CELL_BEGIN==='\n")
                .append("CB0=0; CB1=0; CEX=1\n")
                .append("CONF='").append(CellBlockConfig.CONF_PATH).append("'\n")
                .append("if [ -f \"$CONF\" ]; then\n")
                .append("  SLOT0_BLOCK=$(sed -nE 's/^SLOT0_BLOCK=([01]).*/\\1/p' \"$CONF\" | head -n 1)\n")
                .append("  SLOT1_BLOCK=$(sed -nE 's/^SLOT1_BLOCK=([01]).*/\\1/p' \"$CONF\" | head -n 1)\n")
                .append("  EXEMPT_CN_460=$(sed -nE 's/^EXEMPT_CN_460=([01]).*/\\1/p' \"$CONF\" | head -n 1)\n")
                .append("  CB0=\"${SLOT0_BLOCK:-0}\"; CB1=\"${SLOT1_BLOCK:-0}\"; CEX=\"${EXEMPT_CN_460:-1}\"\n")
                .append("fi\n")
                .append("echo \"CELL_CONF:${CB0}:${CB1}:${CEX}\"\n")
                .append("MR0=$(settings get global ").append(CellBlockConfig.KEY_MODEM_RAF_PREFIX).append("0 2>/dev/null | tr -dc '0-9')\n")
                .append("MR1=$(settings get global ").append(CellBlockConfig.KEY_MODEM_RAF_PREFIX).append("1 2>/dev/null | tr -dc '0-9')\n")
                .append("echo \"CELL_MRAF:${MR0:-0}:${MR1:-0}\"\n")
                .append("content query --uri content://telephony/siminfo --where 'sim_id>=0' --projection sim_id:allowed_network_types_for_reasons 2>/dev/null | tr -d '\\r' | grep 'sim_id=' || true\n")
                .append("dumpsys activity service com.android.phone/.TelephonyDebugService 2>/dev/null | grep -F -e 'GsmCdmaPhone extends:' -e 'ImsPhone extends:' -e 'mPhoneId=' -e 'mPhoneId =' -e 'mSS={' -e 'mSS = {' || true\n")
                .append("echo '===CELL_END==='\n");

        String imsDump = "";
        RootShell.Result mr = RootShell.execRoot(metaScript.toString(), 10);
        if (mr.isSuccess()) {
            imsDump = parseMetaAndLog(mr.stdout, snap);
        } else {
            snap.errorNote = mr.stderr;
        }

        String[] parsedIms = needImsDump ? parseImsTypesFromDebugDump(imsDump, rawSlots) : new String[2];
        for (int i = 0; i < rawSlots.length; i++) {
            SlotSnapshot slot = rawSlots[i];
            if (!slot.imsType.isEmpty() || !slot.isSimLoaded()) {
                snap.slots[i] = slot;
            } else if (parsedIms[i] != null) {
                // 信任 ImsPhone 的实时权威注册状态（包括明确的未注册 ""）
                snap.slots[i] = slot.withImsType(parsedIms[i]);
            } else if ("IWLAN".equalsIgnoreCase(slot.netType)) {
                // 当 ROM 禁用或缺少 TelephonyDebugService 时温和兜底 IWLAN
                snap.slots[i] = slot.withImsType("vowifi");
            } else {
                snap.slots[i] = slot;
            }
            snap.cellBlock[i].exemptCnActive = snap.cellBlock[i].ruleEnabled
                    && snap.cellExemptCn
                    && snap.slots[i].isSimLoaded()
                    && CellBlockConfig.isChinaMainlandPlmn(snap.slots[i].simNumeric);
        }
        cacheResolvedImsTypes(snap.slots);

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

    static String parseMetaAndLog(String out, FullSnapshot snap) {
        if (out == null) {
            snap.serviceLog = "";
            return "";
        }
        String[] lines = out.split("\n");
        boolean inLog = false;
        boolean inImsDump = false;
        boolean inCellDump = false;
        StringBuilder logSb = new StringBuilder();
        StringBuilder imsSb = new StringBuilder();
        StringBuilder cellSb = new StringBuilder();
        for (String line : lines) {
            if ("===LOG_BEGIN===".equals(line)) {
                inLog = true;
                inImsDump = false;
                inCellDump = false;
                continue;
            }
            if ("===LOG_END===".equals(line)) {
                inLog = false;
                continue;
            }
            if ("===IMS_DUMP_BEGIN===".equals(line)) {
                inImsDump = true;
                inLog = false;
                inCellDump = false;
                continue;
            }
            if ("===IMS_DUMP_END===".equals(line)) {
                inImsDump = false;
                continue;
            }
            if ("===CELL_BEGIN===".equals(line)) {
                inCellDump = true;
                inLog = false;
                inImsDump = false;
                continue;
            }
            if ("===CELL_END===".equals(line)) {
                inCellDump = false;
                continue;
            }
            if (inLog) {
                if (logSb.length() > 0) {
                    logSb.append('\n');
                }
                logSb.append(line);
                continue;
            }
            if (inImsDump) {
                if (imsSb.length() > 0) {
                    imsSb.append('\n');
                }
                imsSb.append(line);
                continue;
            }
            if (inCellDump) {
                if (cellSb.length() > 0) {
                    cellSb.append('\n');
                }
                cellSb.append(line);
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
        parseCellDump(cellSb.toString(), snap);
        return imsSb.toString();
    }

    /**
     * 解析禁蜂窝规则配置、modemRaf、siminfo user 掩码与 GsmCdmaPhone 蜂窝注册状态。
     */
    static void parseCellDump(String cellDump, FullSnapshot snap) {
        if (cellDump == null || cellDump.trim().isEmpty() || snap == null) {
            return;
        }
        int curPhoneSlot = -1;
        boolean hasGsmHeader = false;
        boolean expectingGsmPhoneId = false;
        boolean[] capturedSs = new boolean[]{false, false};
        for (String rawLine : cellDump.split("\n")) {
            String t = rawLine.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("CELL_CONF:")) {
                String[] p = t.split(":", -1);
                if (p.length >= 4) {
                    snap.cellBlock[0].ruleEnabled = "1".equals(p[1].trim());
                    snap.cellBlock[1].ruleEnabled = "1".equals(p[2].trim());
                    snap.cellExemptCn = !"0".equals(p[3].trim());
                }
                continue;
            }
            if (t.startsWith("CELL_MRAF:")) {
                String[] p = t.split(":", -1);
                if (p.length >= 3) {
                    snap.cellBlock[0].modemRaf = parseIntSafe(p[1], 0);
                    snap.cellBlock[1].modemRaf = parseIntSafe(p[2], 0);
                }
                continue;
            }
            if (t.contains("sim_id=")) {
                Matcher mSim = Pattern.compile("sim_id=(\\d+)").matcher(t);
                if (mSim.find()) {
                    int sid = parseIntSafe(mSim.group(1), -1);
                    if (sid == 0 || sid == 1) {
                        int idx = t.indexOf("allowed_network_types_for_reasons=");
                        if (idx >= 0) {
                            String reasons = t.substring(idx + "allowed_network_types_for_reasons=".length()).trim();
                            snap.cellBlock[sid].currentUserRaf = CellBlockConfig.parseUserRafFromReasons(reasons);
                        }
                    }
                }
                continue;
            }
            if (t.startsWith("GsmCdmaPhone extends:")) {
                hasGsmHeader = true;
                expectingGsmPhoneId = true;
                curPhoneSlot = -1;
                continue;
            }
            Matcher mPid = IMS_PHONE_ID_LINE.matcher(t);
            if (mPid.matches()) {
                int pid = parseIntSafe(mPid.group(1), -1);
                if ((!hasGsmHeader || expectingGsmPhoneId) && (pid == 0 || pid == 1)) {
                    curPhoneSlot = pid;
                    expectingGsmPhoneId = false;
                }
                continue;
            }
            boolean isSsLine = t.startsWith("mSS={") || t.startsWith("mSS = {");
            if (isSsLine
                    && (t.contains("transportType=WWAN") || t.contains("isManualNetworkSelection="))
                    && curPhoneSlot >= 0 && curPhoneSlot < 2 && !capturedSs[curPhoneSlot]) {
                capturedSs[curPhoneSlot] = true;
                snap.cellBlock[curPhoneSlot].manualSelection = t.contains("isManualNetworkSelection=true");
                snap.cellBlock[curPhoneSlot].wwanVoiceReg = extractWwanRegState(t, "CS");
                snap.cellBlock[curPhoneSlot].wwanDataReg = extractWwanRegState(t, "PS");
                snap.cellBlock[curPhoneSlot].wwanPlmn = extractWwanRplmn(t);
            }
        }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static String extractWwanRegState(String mSsLine, String domain) {
        Pattern p = Pattern.compile("domain=" + domain + "\\s+transportType=WWAN\\s+registrationState=([A-Za-z0-9_]+)");
        Matcher m = p.matcher(mSsLine);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extractWwanRplmn(String mSsLine) {
        String rplmn = extractRplmnFromDomain(mSsLine, "domain=PS transportType=WWAN");
        if (!rplmn.isEmpty()) {
            return rplmn;
        }
        return extractRplmnFromDomain(mSsLine, "domain=CS transportType=WWAN");
    }

    private static String extractRplmnFromDomain(String mSsLine, String domainMarker) {
        int start = mSsLine.indexOf(domainMarker);
        if (start < 0) {
            return "";
        }
        int from = start + domainMarker.length();
        int nextInfo = mSsLine.indexOf("NetworkRegistrationInfo{", from);
        int endList = mSsLine.indexOf("]}", from);
        int end = mSsLine.length();
        if (nextInfo >= 0) {
            end = Math.min(end, nextInfo);
        }
        if (endList >= 0) {
            end = Math.min(end, endList);
        }
        Matcher m = Pattern.compile("rRplmn=([0-9]*)").matcher(mSsLine.substring(start, end));
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 解析 AOSP TelephonyDebugService 中双卡 ImsPhone 的实时注册状态与传输类型。
     * 无法在 dump 中匹配到对应槽位时保持 null，已匹配到但未注册时返回 ""。
     */
    public static String[] parseImsTypesFromDebugDump(String dump, SlotSnapshot[] slots) {
        String[] results = new String[]{null, null};
        if (dump == null || dump.trim().isEmpty()) {
            return results;
        }
        boolean[] strongParsed = new boolean[]{false, false};

        int currentSlot = -1;
        boolean inImsBody = false;
        boolean hasImsRegistered = false;
        boolean imsRegistered = false;
        String mmTelRegState = "";
        String ssDataTech = "";
        String ssVoiceTech = "";
        String lastRegLine = "";

        for (String rawLine : dump.split("\n")) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("ImsPhone extends:")) {
                // 若此前已进入过 ImsPhone 主体却未见外层 mPhoneId，先行结算上一槽位
                if (inImsBody && currentSlot >= 0 && currentSlot < 2) {
                    commitImsBlock(results, strongParsed, slots, currentSlot, inImsBody,
                            hasImsRegistered, imsRegistered, mmTelRegState, ssDataTech, ssVoiceTech, lastRegLine);
                    currentSlot = -1;
                    inImsBody = false;
                    hasImsRegistered = false;
                    imsRegistered = false;
                    mmTelRegState = "";
                    ssDataTech = "";
                    ssVoiceTech = "";
                    lastRegLine = "";
                }
                continue;
            }
            Matcher mPid = IMS_PHONE_ID_LINE.matcher(trimmed);
            if (mPid.matches()) {
                int pid;
                try {
                    pid = Integer.parseInt(mPid.group(1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (pid < 0 || pid >= 2) {
                    continue;
                }
                // 仅在已经进入过 ImsPhone 主体后遇到新 mPhoneId，或槽位号变化时才切槽位；
                // 避免在 ImsPhone extends: 与 ImsPhone: 之间的同槽位 CallTracker mPhoneId 处误重置
                if (currentSlot >= 0 && (inImsBody || pid != currentSlot)) {
                    commitImsBlock(results, strongParsed, slots, currentSlot, inImsBody,
                            hasImsRegistered, imsRegistered, mmTelRegState, ssDataTech, ssVoiceTech, lastRegLine);
                    inImsBody = false;
                    hasImsRegistered = false;
                    imsRegistered = false;
                    mmTelRegState = "";
                    ssDataTech = "";
                    ssVoiceTech = "";
                    lastRegLine = "";
                }
                currentSlot = pid;
                continue;
            }
            if (trimmed.startsWith("ImsPhone:")) {
                inImsBody = true;
                continue;
            }
            if (currentSlot < 0 || currentSlot >= 2) {
                continue;
            }

            Matcher mRegBool = IMS_REG_BOOL_LINE.matcher(trimmed);
            if (mRegBool.matches()) {
                hasImsRegistered = true;
                imsRegistered = "true".equalsIgnoreCase(mRegBool.group(1));
                continue;
            }
            Matcher mMmTel = IMS_MMTEL_STATE_LINE.matcher(trimmed);
            if (mMmTel.find()) {
                mmTelRegState = mMmTel.group(1).trim();
                continue;
            }
            if (trimmed.startsWith("mSS =") || trimmed.startsWith("mServiceState=") || trimmed.startsWith("service state=")) {
                Matcher mData = IMS_SS_DATA_TECH.matcher(trimmed);
                if (mData.find()) {
                    ssDataTech = mData.group(1).trim();
                }
                Matcher mVoice = IMS_SS_VOICE_TECH.matcher(trimmed);
                if (mVoice.find()) {
                    ssVoiceTech = mVoice.group(1).trim();
                }
                continue;
            }
            if ((trimmed.contains("onImsMmTelConnected") || trimmed.contains("handleImsRegistered:"))
                    && !trimmed.contains("Unregistered")
                    && !trimmed.contains("Disconnected")) {
                lastRegLine = trimmed;
            }
        }

        if (currentSlot >= 0 && currentSlot < 2) {
            commitImsBlock(results, strongParsed, slots, currentSlot, inImsBody,
                    hasImsRegistered, imsRegistered, mmTelRegState, ssDataTech, ssVoiceTech, lastRegLine);
        }
        return results;
    }

    private static void commitImsBlock(
            String[] results,
            boolean[] strongParsed,
            SlotSnapshot[] slots,
            int slotIndex,
            boolean inImsBody,
            boolean hasImsRegistered,
            boolean imsRegistered,
            String mmTelRegState,
            String ssDataTech,
            String ssVoiceTech,
            String lastRegLine) {
        boolean hasEvidence = inImsBody || hasImsRegistered || !mmTelRegState.isEmpty()
                || !ssDataTech.isEmpty() || !ssVoiceTech.isEmpty() || !lastRegLine.isEmpty();
        if (!hasEvidence) {
            return;
        }
        boolean isStrong = inImsBody || !mmTelRegState.isEmpty()
                || !ssDataTech.isEmpty() || !ssVoiceTech.isEmpty() || !lastRegLine.isEmpty();
        if (!isStrong && strongParsed[slotIndex]) {
            return;
        }
        String slotNetType = (slots != null && slotIndex < slots.length && slots[slotIndex] != null)
                ? slots[slotIndex].netType : "";
        results[slotIndex] = resolveSlotImsType(
                hasImsRegistered, imsRegistered, mmTelRegState, ssDataTech, ssVoiceTech, lastRegLine, slotNetType);
        if (isStrong) {
            strongParsed[slotIndex] = true;
        }
    }

    private static String resolveSlotImsType(
            boolean hasImsRegistered,
            boolean imsRegistered,
            String mmTelRegState,
            String ssDataTech,
            String ssVoiceTech,
            String lastRegLine,
            String slotNetType) {
        boolean registered;
        if (!mmTelRegState.isEmpty()) {
            registered = "2".equals(mmTelRegState)
                    || "REGISTERED".equalsIgnoreCase(mmTelRegState)
                    || "REGISTRATION_STATE_REGISTERED".equalsIgnoreCase(mmTelRegState);
        } else {
            registered = hasImsRegistered && imsRegistered;
        }
        if (!registered) {
            return "";
        }

        boolean nrActive = containsNrTech(ssDataTech) || containsNrTech(ssVoiceTech) || containsNrTech(slotNetType);

        if (!lastRegLine.isEmpty()) {
            Matcher mTr = IMS_TRANSPORT_TYPE.matcher(lastRegLine);
            if (mTr.find()) {
                String upper = mTr.group(1).toUpperCase(Locale.US);
                if (upper.contains("WLAN") || upper.contains("WIFI") || upper.contains("CROSS_SIM") || "2".equals(upper)) {
                    return "vowifi";
                }
                if (upper.contains("WWAN") || "1".equals(upper)) {
                    return nrActive ? "vonr" : "volte";
                }
            }
            Matcher mRt = IMS_RADIO_TECH.matcher(lastRegLine);
            if (mRt.find()) {
                String upper = mRt.group(1).toUpperCase(Locale.US);
                if (upper.contains("WLAN") || upper.contains("IWLAN") || upper.contains("WIFI")
                        || upper.contains("CROSS_SIM") || "1".equals(upper) || "2".equals(upper)) {
                    return "vowifi";
                }
                if (upper.contains("NR") || "3".equals(upper)) {
                    return "vonr";
                }
                return nrActive ? "vonr" : "volte";
            }
        }

        if (containsWlanTech(ssDataTech) || containsWlanTech(ssVoiceTech)) {
            return "vowifi";
        }
        if (containsNrTech(ssDataTech) || containsNrTech(ssVoiceTech)) {
            return "vonr";
        }
        if ("IWLAN".equalsIgnoreCase(slotNetType)) {
            return "vowifi";
        }
        if (containsNrTech(slotNetType)) {
            return "vonr";
        }
        return "volte";
    }

    private static boolean containsWlanTech(String tech) {
        if (tech == null || tech.isEmpty()) {
            return false;
        }
        String upper = tech.toUpperCase(Locale.US);
        return upper.contains("IWLAN") || upper.contains("WLAN") || upper.contains("WIFI")
                || upper.startsWith("18(") || "18".equals(upper);
    }

    private static boolean containsNrTech(String tech) {
        if (tech == null || tech.isEmpty()) {
            return false;
        }
        String upper = tech.toUpperCase(Locale.US);
        return upper.contains("NR") || upper.startsWith("20(") || "20".equals(upper);
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
