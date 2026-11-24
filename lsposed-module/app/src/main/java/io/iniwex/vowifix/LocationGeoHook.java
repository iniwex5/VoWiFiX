package io.iniwex.vowifix;

import android.location.Address;
import android.location.Location;
import android.os.SystemClock;
import android.util.Pair;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Layer 3：国家码与地理位置伪装（双卡按槽自适应）。
 */
public final class LocationGeoHook {

    private static final String TAG = "[VoWiFiX][Geo] ";
    private static final int COUNTRY_SOURCE_SIM = 2;

    private LocationGeoHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;

        // 全作用域通用 TelephonyManager 拦截（P0）
        hookTelephonyManager(lpparam);

        if ("com.android.phone".equals(pkg)) {
            hookLocaleTracker(lpparam);
            hookTelephonyCountryDetector(lpparam);
            hookPhoneInterfaceManager(lpparam);
        }

        if ("org.codeaurora.ims".equals(pkg)) {
            hookQtiImsGeolocation(lpparam);
        }

        if ("com.qualcomm.qti.cne".equals(pkg)) {
            hookQtiCne(lpparam);
        }

        // 兼容 Vector/LSPosed 对 system_server 的包名约定（android / system）
        if ("android".equals(pkg) || "system".equals(pkg) || "system_server".equals(lpparam.processName)) {
            hookSystemServerCountry(lpparam);
        }
    }

    private static int getPhoneIdFromLocaleTracker(Object localeTracker) {
        try {
            Object phone = XposedHelpers.getObjectField(localeTracker, "mPhone");
            if (phone != null) {
                return (int) XposedHelpers.callMethod(phone, "getPhoneId");
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 从 ImsSenderRxr 或 ImsServiceSub 提取卡槽 ID。
     */
    private static int extractPhoneId(Object obj) {
        if (obj == null) {
            return -1;
        }
        try {
            Object pid = XposedHelpers.getObjectField(obj, "mPhoneId");
            if (pid instanceof Number) {
                return ((Number) pid).intValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String getSpoofTargetForSlot(int slotIndex) {
        return CountrySpoofer.shouldSpoofSlot(slotIndex)
                ? CountrySpoofer.getTargetIsoForSlot(slotIndex)
                : null;
    }

    /**
     * 判断当前调用栈是否源自系统时区检测或 NITZ 链路，命中时放行真实网络国家码。
     */
    private static boolean isTimeZoneStack() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : trace) {
            String cls = frame.getClassName();
            if (cls == null || cls.startsWith("io.iniwex.vowifix.")) {
                continue;
            }
            String lower = cls.toLowerCase(Locale.US);
            if (lower.contains("timezonedetector")
                    || lower.contains("nitz")
                    || lower.contains("timezone")) {
                return true;
            }
        }
        return false;
    }

    private static String extractMcc(String plmn) {
        if (plmn == null || plmn.length() < 3) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(plmn.charAt(i))) {
                return null;
            }
        }
        return plmn.substring(0, 3);
    }

    /**
     * 提取指定卡槽在多卡属性中的 3 位数字 MCC。
     */
    private static String extractMccFromPropSlot(String propName, int slotIndex) {
        if (slotIndex < 0) {
            return null;
        }
        String raw = CountrySpoofer.getSystemProperty(propName, "");
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (slotIndex >= parts.length) {
            return null;
        }
        return extractMcc(parts[slotIndex].trim());
    }

    /**
     * 读取设备当前真实蜂窝驻网 MCC（优先本槽，本槽无驻网时回退到其他已驻网槽位）。
     */
    private static String resolveDeviceNetMcc(int slotIndex) {
        String direct = extractMccFromPropSlot("gsm.operator.numeric", slotIndex);
        if (direct != null) {
            return direct;
        }
        String raw = CountrySpoofer.getSystemProperty("gsm.operator.numeric", "");
        if (raw.isEmpty()) {
            return null;
        }
        for (String part : raw.split(",", -1)) {
            String mcc = extractMcc(part.trim());
            if (mcc != null) {
                return mcc;
            }
        }
        return null;
    }

    /**
     * 判断指定卡槽是否处于跨国漫游伪装态（飞回归属国本地驻网时返回 false）。
     */
    private static boolean isDeviceCrossCountryRoamingForSlot(int slotIndex) {
        String simMcc = extractMccFromPropSlot("gsm.sim.operator.numeric", slotIndex);
        String netMcc = resolveDeviceNetMcc(slotIndex);
        return simMcc != null && netMcc != null && !simMcc.equals(netMcc);
    }

    /**
     * 通过 AOSP MccTable 将驻网 MCC 转为真实漫游地国家码（兼容 String / int 重载）。
     */
    private static String resolveRealCountryFromNumeric(String operatorNumeric, ClassLoader cl) {
        try {
            if (operatorNumeric != null && operatorNumeric.length() >= 3) {
                Class<?> mccTable = XposedHelpers.findClassIfExists("com.android.internal.telephony.MccTable", cl);
                if (mccTable == null && cl != null) {
                    mccTable = XposedHelpers.findClassIfExists("com.android.internal.telephony.MccTable", null);
                }
                if (mccTable != null) {
                    String mccStr = operatorNumeric.substring(0, 3);
                    Object res = null;
                    try {
                        res = XposedHelpers.callStaticMethod(mccTable, "countryCodeForMcc", mccStr);
                    } catch (Throwable ignored) {
                        res = XposedHelpers.callStaticMethod(mccTable, "countryCodeForMcc", Integer.parseInt(mccStr));
                    }
                    if (res instanceof String && !((String) res).isEmpty()) {
                        return ((String) res).toLowerCase(Locale.US);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 通过系统 TimeZoneFinder 检查给定 IANA 时区 ID 是否归属于目标国家 ISO。
     */
    private static boolean isZoneInCountry(String zoneId, String isoLower, ClassLoader cl) {
        if (zoneId == null || zoneId.isEmpty() || isoLower == null || isoLower.isEmpty()) {
            return false;
        }
        String iso = isoLower.toLowerCase(Locale.US);
        String[] finderClasses = {
                "android.timezone.TimeZoneFinder",
                "com.android.i18n.timezone.TimeZoneFinder",
                "libcore.timezone.TimeZoneFinder"
        };
        for (String clsName : finderClasses) {
            try {
                Class<?> finderCls = XposedHelpers.findClassIfExists(clsName, cl);
                if (finderCls == null && cl != null) {
                    finderCls = XposedHelpers.findClassIfExists(clsName, null);
                }
                if (finderCls == null) {
                    continue;
                }
                Object finder = XposedHelpers.callStaticMethod(finderCls, "getInstance");
                if (finder == null) {
                    continue;
                }
                Object ctz = XposedHelpers.callMethod(finder, "lookupCountryTimeZones", iso);
                if (ctz == null) {
                    continue;
                }
                try {
                    Object defId = XposedHelpers.callMethod(ctz, "getDefaultTimeZoneId");
                    if (zoneId.equals(defId)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    Object defTz = XposedHelpers.callMethod(ctz, "getDefaultTimeZone");
                    if (defTz != null && zoneId.equals(XposedHelpers.callMethod(defTz, "getID"))) {
                        return true;
                    }
                }
                Object mappings = XposedHelpers.callMethod(ctz, "getTimeZoneMappings");
                if (mappings instanceof List) {
                    for (Object mapping : (List<?>) mappings) {
                        if (mapping != null && zoneId.equals(XposedHelpers.callMethod(mapping, "getTimeZoneId"))) {
                            return true;
                        }
                    }
                }
                return false;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void hookLocaleTracker(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> ltClass = XposedHelpers.findClassIfExists("com.android.internal.telephony.LocaleTracker", lpparam.classLoader);
        if (ltClass == null) {
            XposedBridge.log(TAG + "LocaleTracker not found");
            return;
        }

        // 不修改 mCountryOverride 与 getLastKnownCountryIso，避免污染 gsm.operator.iso-country 与 NITZ 自动时区
        XposedHelpers.findAndHookMethod(ltClass, "getCurrentCountry", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int phoneId = getPhoneIdFromLocaleTracker(param.thisObject);
                applyNetworkIsoIfNotTimeZone(param, getSpoofTargetForSlot(phoneId));
            }
        });

        XposedHelpers.findAndHookMethod(ltClass, "getCountryOverride", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int phoneId = getPhoneIdFromLocaleTracker(param.thisObject);
                String target = getSpoofTargetForSlot(phoneId);
                if (target != null) {
                    param.setResult(target.toLowerCase(Locale.US));
                }
            }
        });

        XposedBridge.log(TAG + "Hooked LocaleTracker (timezone-safe)");
    }

    private static void hookTelephonyCountryDetector(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> tcdClass = XposedHelpers.findClassIfExists("com.android.internal.telephony.TelephonyCountryDetector", lpparam.classLoader);
        if (tcdClass == null) {
            return;
        }

        XposedHelpers.findAndHookMethod(tcdClass, "getCurrentNetworkCountryIso", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String target = CountrySpoofer.getGlobalTargetIso();
                if (target != null) {
                    param.setResult(Collections.singletonList(target.toUpperCase(Locale.US)));
                }
            }
        });

        XposedHelpers.findAndHookMethod(tcdClass, "getCachedLocationCountryIsoInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String target = CountrySpoofer.getGlobalTargetIso();
                if (target != null) {
                    param.setResult(new Pair<>(target.toUpperCase(Locale.US), SystemClock.elapsedRealtimeNanos()));
                }
            }
        });

        XposedHelpers.findAndHookMethod(tcdClass, "getCachedNetworkCountryIsoInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String target = CountrySpoofer.getGlobalTargetIso();
                if (target != null) {
                    param.setResult(Collections.singletonMap(target.toUpperCase(Locale.US), SystemClock.elapsedRealtimeNanos()));
                }
            }
        });

        XposedBridge.hookAllMethods(tcdClass, "getNetworkCountryIsoForPhone", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                    int phoneId = (int) XposedHelpers.callMethod(param.args[0], "getPhoneId");
                    String target = getSpoofTargetForSlot(phoneId);
                    if (target != null) {
                        param.setResult(target.toLowerCase(Locale.US));
                    }
                }
            }
        });

        XposedBridge.hookAllMethods(tcdClass, "onNetworkCountryCodeChanged", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2 && param.args[0] != null) {
                    int phoneId = (int) XposedHelpers.callMethod(param.args[0], "getPhoneId");
                    String target = getSpoofTargetForSlot(phoneId);
                    if (target != null) {
                        param.args[1] = target.toLowerCase(Locale.US);
                    }
                }
            }
        });

        XposedBridge.hookAllMethods(tcdClass, "setLocationCountryCode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String target = CountrySpoofer.getGlobalTargetIso();
                if (target != null && param.args != null && param.args.length >= 1) {
                    param.args[0] = new Pair<>(target.toUpperCase(Locale.US), SystemClock.elapsedRealtimeNanos());
                }
            }
        });

        XposedBridge.log(TAG + "Hooked TelephonyCountryDetector");
    }

    /**
     * 仅在非系统时区调用栈下将伪装国家码写入返回值，防止 NITZ/时区检测误读归属国。
     */
    private static void applyNetworkIsoIfNotTimeZone(XC_MethodHook.MethodHookParam param, String target) {
        if (target != null && !isTimeZoneStack()) {
            param.setResult(target.toLowerCase(Locale.US));
        }
    }

    private static void hookPhoneInterfaceManager(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> pim = XposedHelpers.findClassIfExists("com.android.phone.PhoneInterfaceManager", lpparam.classLoader);
        if (pim == null) {
            return;
        }
        XposedBridge.hookAllMethods(pim, "getNetworkCountryIsoForPhone", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                    int phoneId = (int) param.args[0];
                    String target = (phoneId == Integer.MAX_VALUE)
                            ? CountrySpoofer.getGlobalTargetIso()
                            : getSpoofTargetForSlot(phoneId);
                    applyNetworkIsoIfNotTimeZone(param, target);
                }
            }
        });
    }

    private static void hookTelephonyManager(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> tmClass = XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", lpparam.classLoader);
        if (tmClass == null) {
            return;
        }

        XposedHelpers.findAndHookMethod(tmClass, "getNetworkCountryIso", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyNetworkIsoIfNotTimeZone(param, CountrySpoofer.getGlobalTargetIso());
            }
        });

        XposedHelpers.findAndHookMethod(tmClass, "getNetworkCountryIso", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int slotIndex = (int) param.args[0];
                applyNetworkIsoIfNotTimeZone(param, getSpoofTargetForSlot(slotIndex));
            }
        });

        try {
            XposedHelpers.findAndHookMethod(tmClass, "getNetworkCountryIsoForPhone", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int phoneId = (int) param.args[0];
                    applyNetworkIsoIfNotTimeZone(param, getSpoofTargetForSlot(phoneId));
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookQtiImsGeolocation(XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook geoHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int phoneId = extractPhoneId(param.thisObject);
                String target = (phoneId >= 0) ? getSpoofTargetForSlot(phoneId) : CountrySpoofer.getGlobalTargetIso();
                if (target == null) {
                    return;
                }
                CountrySpoofer.GeoProfile profile = CountrySpoofer.getGeoProfile(target);
                if (profile.hasGeo) {
                    param.args[0] = profile.lat;
                    param.args[1] = profile.lon;
                }
                Address origAddr = (param.args.length > 2 && param.args[2] instanceof Address) ? (Address) param.args[2] : null;
                param.args[2] = CountrySpoofer.sanitizeAddress(origAddr, target);
                XposedBridge.log(TAG + "Spoofed IMS Geolocation [slot=" + phoneId + "] -> " + profile.isoUpper
                        + (profile.hasGeo ? " (" + profile.lat + ", " + profile.lon + ")" : " (ISO only)"));
            }
        };

        Class<?> senderRxr = XposedHelpers.findClassIfExists("org.codeaurora.ims.ImsSenderRxr", lpparam.classLoader);
        if (senderRxr != null) {
            XposedBridge.hookAllMethods(senderRxr, "sendGeolocationInfo", geoHook);
            XposedBridge.log(TAG + "Hooked ImsSenderRxr.sendGeolocationInfo");
        }

        Class<?> serviceSub = XposedHelpers.findClassIfExists("org.codeaurora.ims.ImsServiceSub", lpparam.classLoader);
        if (serviceSub != null) {
            XposedBridge.hookAllMethods(serviceSub, "sendGeolocationResponse", geoHook);
            XposedBridge.log(TAG + "Hooked ImsServiceSub.sendGeolocationResponse");
        }
    }

    private static void hookQtiCne(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> retriever = XposedHelpers.findClassIfExists("com.qualcomm.qti.cne.relay.WlanStaInfoRelay$CountryCodeRetriever", lpparam.classLoader);
        if (retriever != null) {
            XposedHelpers.findAndHookMethod(retriever, "retrieveCountryCode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String target = CountrySpoofer.getGlobalTargetIso();
                    if (target != null) {
                        XposedHelpers.setBooleanField(param.thisObject, "countryCodeRetrieved", true);
                        XposedBridge.log(TAG + "WlanStaInfoRelay.retrieveCountryCode -> " + target);
                        param.setResult(target.toLowerCase(Locale.US));
                    }
                }
            });

            XposedHelpers.findAndHookMethod(retriever, "retrieveCountryFromLocation", Location.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String target = CountrySpoofer.getGlobalTargetIso();
                    if (target != null) {
                        param.setResult(target.toUpperCase(Locale.US));
                    }
                }
            });
            XposedBridge.log(TAG + "Hooked WlanStaInfoRelay$CountryCodeRetriever");
        }

        Class<?> relay = XposedHelpers.findClassIfExists("com.qualcomm.qti.cne.relay.WlanStaInfoRelay", lpparam.classLoader);
        if (relay != null) {
            XposedHelpers.findAndHookMethod(relay, "cacheCountryCode", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String target = CountrySpoofer.getGlobalTargetIso();
                    if (target != null) {
                        param.args[0] = target.toLowerCase(Locale.US);
                    }
                }
            });
        }
    }

    private static void hookSystemServerCountry(XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook countryObjHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String target = CountrySpoofer.getGlobalTargetIso();
                if (target != null && !isTimeZoneStack()) {
                    Class<?> countryCls = XposedHelpers.findClass("android.location.Country", lpparam.classLoader);
                    Object spoofed = XposedHelpers.newInstance(countryCls, target.toUpperCase(Locale.US), COUNTRY_SOURCE_SIM);
                    param.setResult(spoofed);
                }
            }
        };

        Class<?> cd = XposedHelpers.findClassIfExists("android.location.CountryDetector", lpparam.classLoader);
        if (cd != null) {
            XposedBridge.hookAllMethods(cd, "detectCountry", countryObjHook);
        }

        Class<?> cds = XposedHelpers.findClassIfExists("com.android.server.CountryDetectorService", lpparam.classLoader);
        if (cds != null) {
            XposedBridge.hookAllMethods(cds, "detectCountry", countryObjHook);
            XposedBridge.log(TAG + "Hooked CountryDetectorService");
        }

        Class<?> ccd = XposedHelpers.findClassIfExists("com.android.server.location.countrydetector.ComprehensiveCountryDetector", lpparam.classLoader);
        if (ccd != null) {
            XposedBridge.hookAllMethods(ccd, "detectCountry", countryObjHook);
        }

        Class<?> wcc = XposedHelpers.findClassIfExists("com.android.server.wifi.WifiCountryCode", lpparam.classLoader);
        if (wcc != null) {
            XC_MethodHook upperIsoHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String target = CountrySpoofer.getGlobalTargetIso();
                    if (target != null) {
                        param.setResult(target.toUpperCase(Locale.US));
                    }
                }
            };
            XC_MethodHook replaceCountryArgHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String target = CountrySpoofer.getGlobalTargetIso();
                    if (target != null && param.args != null && param.args.length > 0
                            && param.args[0] instanceof String && !((String) param.args[0]).isEmpty()) {
                        param.args[0] = target.toUpperCase(Locale.US);
                    }
                }
            };
            XposedBridge.hookAllMethods(wcc, "getCountryCode", upperIsoHook);
            XposedBridge.hookAllMethods(wcc, "getCurrentDriverCountryCode", upperIsoHook);
            XposedBridge.hookAllMethods(wcc, "pickCountryCode", upperIsoHook);
            XposedBridge.hookAllMethods(wcc, "setDefaultCountryCode", replaceCountryArgHook);
            XposedBridge.hookAllMethods(wcc, "setTelephonyCountryCodeAndUpdate", replaceCountryArgHook);
            XposedBridge.log(TAG + "Hooked WifiCountryCode");
        }

        hookTimeZoneDetectorGuard(lpparam);
    }

    /**
     * 终极兜底：拦截跨国漫游伪装期间误送入系统时区策略的归属国时区建议。
     */
    private static void hookTimeZoneDetectorGuard(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> strategyCls = XposedHelpers.findClassIfExists(
                "com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl", lpparam.classLoader);
        if (strategyCls == null) {
            return;
        }
        XposedBridge.hookAllMethods(strategyCls, "suggestTelephonyTimeZone", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                    return;
                }
                try {
                    Object suggestion = param.args[0];
                    Object slotObj = XposedHelpers.callMethod(suggestion, "getSlotIndex");
                    Object zoneObj = XposedHelpers.callMethod(suggestion, "getZoneId");
                    if (!(slotObj instanceof Number) || !(zoneObj instanceof String)) {
                        return;
                    }
                    int slotIndex = ((Number) slotObj).intValue();
                    String zoneId = (String) zoneObj;
                    if (zoneId.isEmpty() || !isDeviceCrossCountryRoamingForSlot(slotIndex)) {
                        return;
                    }
                    String spoofIso = CountrySpoofer.getTargetIsoForSlot(slotIndex);
                    if (spoofIso == null || !isZoneInCountry(zoneId, spoofIso, lpparam.classLoader)) {
                        return;
                    }
                    // 若真实驻网国家自身也使用同一时区（如跨国共享 IANA 时区），则放行避免误杀
                    String netMcc = resolveDeviceNetMcc(slotIndex);
                    String realIso = resolveRealCountryFromNumeric(netMcc, lpparam.classLoader);
                    if (!realIso.isEmpty() && isZoneInCountry(zoneId, realIso, lpparam.classLoader)) {
                        return;
                    }
                    XposedBridge.log(TAG + "Blocked spoofed telephony timezone suggestion [slot="
                            + slotIndex + ", zone=" + zoneId + ", spoofIso=" + spoofIso + ", realIso=" + realIso + "]");
                    param.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "TimeZone guard err: " + t);
                }
            }
        });
        XposedBridge.log(TAG + "Hooked TimeZoneDetectorStrategyImpl guard");
    }
}
