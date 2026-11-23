package io.iniwex.vowifix;

import android.location.Address;
import android.location.Location;
import android.os.SystemClock;
import android.util.Pair;

import java.util.Collections;
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

    /** 保存 LocaleTracker.updateLocale 执行期间对应的真实网络 MCC 国家码，防止污染 NITZ 自动时区。 */
    private static final ThreadLocal<String> REAL_COUNTRY_FOR_NITZ = new ThreadLocal<>();

    private LocationGeoHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;

        // 全作用域通用 TelephonyManager 拦截（P0）
        hookTelephonyManager(lpparam);

        if ("com.android.phone".equals(pkg)) {
            hookLocaleTracker(lpparam);
            hookNitzStateMachine(lpparam);
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
     * 通过 AOSP MccTable 将驻网 MCC 转为真实漫游地国家码。
     */
    private static String resolveRealCountryFromNumeric(String operatorNumeric) {
        try {
            if (operatorNumeric != null && operatorNumeric.length() >= 3) {
                Class<?> mccTable = XposedHelpers.findClassIfExists("com.android.internal.telephony.MccTable", null);
                if (mccTable != null) {
                    Object res = XposedHelpers.callStaticMethod(mccTable, "countryCodeForMcc", operatorNumeric.substring(0, 3));
                    if (res instanceof String && !((String) res).isEmpty()) {
                        return ((String) res).toLowerCase(Locale.US);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void hookLocaleTracker(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> ltClass = XposedHelpers.findClassIfExists("com.android.internal.telephony.LocaleTracker", lpparam.classLoader);
        if (ltClass == null) {
            XposedBridge.log(TAG + "LocaleTracker not found");
            return;
        }

        XposedHelpers.findAndHookMethod(ltClass, "getCurrentCountry", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int phoneId = getPhoneIdFromLocaleTracker(param.thisObject);
                String target = getSpoofTargetForSlot(phoneId);
                if (target != null) {
                    param.setResult(target.toLowerCase(Locale.US));
                }
            }
        });

        XposedHelpers.findAndHookMethod(ltClass, "getLastKnownCountryIso", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int phoneId = getPhoneIdFromLocaleTracker(param.thisObject);
                String target = getSpoofTargetForSlot(phoneId);
                if (target != null) {
                    param.setResult(target.toLowerCase(Locale.US));
                }
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

        XposedHelpers.findAndHookMethod(ltClass, "updateLocale", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int phoneId = getPhoneIdFromLocaleTracker(param.thisObject);
                String target = getSpoofTargetForSlot(phoneId);
                if (target != null) {
                    String numeric = (String) XposedHelpers.getObjectField(param.thisObject, "mOperatorNumeric");
                    String realCountry = resolveRealCountryFromNumeric(numeric);
                    if (!target.equalsIgnoreCase(realCountry)) {
                        REAL_COUNTRY_FOR_NITZ.set(realCountry);
                        XposedHelpers.setObjectField(param.thisObject, "mCountryOverride", target.toLowerCase(Locale.US));
                        return;
                    }
                }
                XposedHelpers.setObjectField(param.thisObject, "mCountryOverride", null);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                REAL_COUNTRY_FOR_NITZ.remove();
            }
        });

        XposedBridge.log(TAG + "Hooked LocaleTracker");
    }

    private static void hookNitzStateMachine(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> nitzImpl = XposedHelpers.findClassIfExists("com.android.internal.telephony.nitz.NitzStateMachineImpl", lpparam.classLoader);
        if (nitzImpl == null) {
            return;
        }
        XposedHelpers.findAndHookMethod(nitzImpl, "handleCountryDetected", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String real = REAL_COUNTRY_FOR_NITZ.get();
                if (real != null) {
                    if (real.isEmpty()) {
                        param.setResult(null);
                    } else {
                        param.args[0] = real;
                    }
                }
            }
        });
        XposedBridge.log(TAG + "Hooked NitzStateMachineImpl");
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
                    if (target != null) {
                        param.setResult(target.toLowerCase(Locale.US));
                    }
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
                String target = CountrySpoofer.getGlobalTargetIso();
                if (target != null) {
                    param.setResult(target.toLowerCase(Locale.US));
                }
            }
        });

        XposedHelpers.findAndHookMethod(tmClass, "getNetworkCountryIso", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int slotIndex = (int) param.args[0];
                String target = getSpoofTargetForSlot(slotIndex);
                if (target != null) {
                    param.setResult(target.toLowerCase(Locale.US));
                }
            }
        });

        try {
            XposedHelpers.findAndHookMethod(tmClass, "getNetworkCountryIsoForPhone", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int phoneId = (int) param.args[0];
                    String target = getSpoofTargetForSlot(phoneId);
                    if (target != null) {
                        param.setResult(target.toLowerCase(Locale.US));
                    }
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
                if (target != null) {
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
    }
}
