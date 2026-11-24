package io.iniwex.vowifix;

import android.os.PersistableBundle;
import android.text.TextUtils;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Layer 2：CarrierConfig 动态注入与平台/资格校验解锁。
 */
public final class CarrierConfigHook {

    private static final String TAG = "[VoWiFiX][CarrierConfig] ";

    private CarrierConfigHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookCarrierConfigLoader(lpparam);
        hookCarrierConfigManager(lpparam);
        hookImsManagers(lpparam);
        if ("com.android.phone".equals(lpparam.packageName)) {
            hookWfcCarrierNameFallback(lpparam);
        }
    }

    /**
     * 注入完整的 VoLTE / VoWiFi、去 Entitlement 及 Home PLMN ePDG 优先配置。
     */
    private static void injectWfcKeys(PersistableBundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            return;
        }
        // VoLTE / VoWiFi 总开关
        bundle.putBoolean("carrier_volte_available_bool", true);
        bundle.putBoolean("carrier_wfc_ims_available_bool", true);
        bundle.putBoolean("carrier_wfc_supports_wifi_only_bool", true);
        bundle.putBoolean("carrier_wfc_supports_ims_preferred_bool", true);

        // 允许手动切换优先模式并默认设为 Wi-Fi 优先 (2 = WIFI_PREFERRED)
        bundle.putBoolean("editable_wfc_mode_bool", true);
        bundle.putBoolean("editable_wfc_roaming_mode_bool", true);
        bundle.putBoolean("carrier_default_wfc_ims_enabled_bool", true);
        bundle.putInt("carrier_default_wfc_ims_mode_int", 2);
        bundle.putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", true);
        bundle.putInt("carrier_default_wfc_ims_roaming_mode_int", 2);

        // 绕过运营商云端资格校验 (Entitlement / Provisioning)
        bundle.putBoolean("carrier_wfc_provisioning_required_bool", false);
        bundle.putBoolean("carrier_volte_provisioning_required_bool", false);
        bundle.putBoolean("carrier_volte_override_wfc_provisioning_bool", false);
        bundle.putBoolean("using_ims_provision_as_cap_bool", false);
        bundle.putBoolean("imsserviceentitlement.ims_provisioning_bool", false);
        bundle.putBoolean("imsserviceentitlement.skip_wfc_activation_bool", true);
        bundle.putBoolean("imsserviceentitlement.default_service_entitlement_status_bool", true);
        bundle.putBoolean("hide_ims_apn_bool", false);

        // 优先使用静态 ePDG 地址，缺失时回退 Home PLMN (0 = STATIC, 1 = PLMN)
        bundle.putIntArray("iwlan.epdg_address_priority_int_array", new int[]{0, 1});

        // ColorOS / Oplus 专有显隐及模式解锁键
        bundle.putInt("vowifi_preferred_hide_mode", 0);
        bundle.putInt("vowifi_visible", 1);
        bundle.putInt("vowifi_status", 1);
        bundle.putInt("volte_visible", 1);
        bundle.putInt("volte_status", 1);
        bundle.putInt("vowifi_preferred_mode", 2);
        bundle.putInt("vowifi_roaming_preferred_mode", 2);

        // VoWiFi 激活且 SIM 无 EF_SPN 时优先采用归属 EF_PNN 名称，避免漫游时显示基站名称
        bundle.putBoolean("wfc_carrier_name_override_by_pnn_bool", true);
    }

    private static void hookCarrierConfigLoader(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.phone".equals(lpparam.packageName)) {
            return;
        }
        XC_MethodHook bundlePatchHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object res = param.getResult();
                if (res instanceof PersistableBundle) {
                    injectWfcKeys((PersistableBundle) res);
                }
            }
        };

        // 实机 CarrierConfigLoader 子类：com.android.phone.OplusCarrierConfigLoader
        Class<?> oplusLoader = XposedHelpers.findClassIfExists("com.android.phone.OplusCarrierConfigLoader", lpparam.classLoader);
        if (oplusLoader != null) {
            XposedBridge.hookAllMethods(oplusLoader, "getConfigForSubIdWithFeature", bundlePatchHook);
            XposedBridge.hookAllMethods(oplusLoader, "getConfigSubsetForSubIdWithFeature", bundlePatchHook);
            XposedBridge.hookAllMethods(oplusLoader, "broadcastConfigChangedIntent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applyToOverrideArray(param.thisObject, (int) param.args[0], true);
                }
            });
            XposedBridge.log(TAG + "Hooked OplusCarrierConfigLoader");
        }

        // 基类兜底：com.android.phone.CarrierConfigLoader
        Class<?> baseLoader = XposedHelpers.findClassIfExists("com.android.phone.CarrierConfigLoader", lpparam.classLoader);
        if (baseLoader != null) {
            XposedBridge.hookAllMethods(baseLoader, "getConfigForSubIdWithFeature", bundlePatchHook);
            XposedBridge.hookAllMethods(baseLoader, "getConfigSubsetForSubIdWithFeature", bundlePatchHook);
            XposedBridge.hookAllMethods(baseLoader, "dump", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int numPhones = XposedHelpers.getIntField(param.thisObject, "mNumPhones");
                        for (int i = 0; i < numPhones; i++) {
                            applyToOverrideArray(param.thisObject, i, oplusLoader != null && oplusLoader.isInstance(param.thisObject));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "Hooked CarrierConfigLoader");
        }
    }

    /**
     * 将注入键同步写入 mOverrideConfigs[phoneId]，使 dumpsys carrier_config 与底层缓存一致可见。
     */
    private static void applyToOverrideArray(Object loader, int phoneId, boolean isOplusLoader) {
        try {
            if (phoneId < 0) {
                return;
            }
            PersistableBundle[] overrides = (PersistableBundle[]) XposedHelpers.getObjectField(loader, "mOverrideConfigs");
            if (overrides != null && phoneId < overrides.length) {
                PersistableBundle target = overrides[phoneId];
                if (target == null) {
                    target = new PersistableBundle();
                    overrides[phoneId] = target;
                }
                // 使用占位确保 isEmpty() 为 false 后注入全部键
                target.putBoolean("carrier_wfc_ims_available_bool", true);
                injectWfcKeys(target);
                if (isOplusLoader) {
                    XposedHelpers.setBooleanField(loader, "mShouldOverrideConfig", true);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "applyToOverrideArray err: " + t);
        }
    }

    private static void hookCarrierConfigManager(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> ccm = XposedHelpers.findClassIfExists("android.telephony.CarrierConfigManager", lpparam.classLoader);
        if (ccm == null) {
            return;
        }
        XC_MethodHook clientHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object res = param.getResult();
                if (res instanceof PersistableBundle) {
                    injectWfcKeys((PersistableBundle) res);
                }
            }
        };
        XposedBridge.hookAllMethods(ccm, "getConfigForSubId", clientHook);
        XposedBridge.hookAllMethods(ccm, "getConfig", clientHook);
        XposedBridge.hookAllMethods(ccm, "getConfigByComponentForSubId", clientHook);
    }

    private static void hookImsManagers(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 实机主要 IMS 管理类 com.android.ims.ImsManager (来自 ims-common.jar)
        Class<?> internalImsMgr = XposedHelpers.findClassIfExists("com.android.ims.ImsManager", lpparam.classLoader);
        if (internalImsMgr != null) {
            XposedBridge.hookAllMethods(internalImsMgr, "isWfcEnabledByPlatform", XC_MethodReplacement.returnConstant(true));
            XposedBridge.hookAllMethods(internalImsMgr, "isWfcProvisionedOnDevice", XC_MethodReplacement.returnConstant(true));
            XposedBridge.hookAllMethods(internalImsMgr, "isVolteEnabledByPlatform", XC_MethodReplacement.returnConstant(true));
            XposedBridge.hookAllMethods(internalImsMgr, "isVolteProvisionedOnDevice", XC_MethodReplacement.returnConstant(true));
            XposedBridge.hookAllMethods(internalImsMgr, "isWfcProvisioned", XC_MethodReplacement.returnConstant(true));
            XposedBridge.hookAllMethods(internalImsMgr, "isMmTelProvisioningRequired", XC_MethodReplacement.returnConstant(false));
            XposedBridge.log(TAG + "Hooked com.android.ims.ImsManager in " + lpparam.packageName);
        }

        // 2. 兼容其他 ROM 可能使用的 android.telephony.ims.ImsManager
        Class<?> publicImsMgr = XposedHelpers.findClassIfExists("android.telephony.ims.ImsManager", lpparam.classLoader);
        if (publicImsMgr != null) {
            for (String m : new String[]{"isWfcEnabledByPlatform", "isWfcProvisionedOnDevice"}) {
                try {
                    XposedBridge.hookAllMethods(publicImsMgr, m, XC_MethodReplacement.returnConstant(true));
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. ColorOS 扩展实现 com.android.ims.ImsManagerExtImpl (来自 oplus-framework.jar)
        Class<?> extImpl = XposedHelpers.findClassIfExists("com.android.ims.ImsManagerExtImpl", lpparam.classLoader);
        if (extImpl != null) {
            XposedHelpers.findAndHookMethod(extImpl, "isFeatureEnabledByPlatform", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int feature = (int) param.args[0];
                    if (feature == 0 || feature == 2) {
                        param.setResult(true);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(extImpl, "isFeatureProvisionedOnDevice", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int feature = (int) param.args[0];
                    if (feature == 0 || feature == 2) {
                        param.setResult(true);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(extImpl, "isFeatureEnabledByPlatformExt", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int feature = (int) param.args[0];
                    if (feature == 0 || feature == 2) {
                        param.setResult(true);
                    }
                }
            });
            XposedBridge.log(TAG + "Hooked com.android.ims.ImsManagerExtImpl in " + lpparam.packageName);
        }
    }

    /**
     * 判断指定卡槽当前是否处于 VoWiFi 或跨卡呼叫（Cross-SIM Calling）注册激活状态。
     */
    private static boolean isWfcOrCrossSimActive(int phoneId, ClassLoader cl) {
        if (phoneId < 0) {
            return false;
        }
        String prop = CountrySpoofer.getSystemProperty("gsm.ims.type" + phoneId, "").trim();
        if ("vowifi".equalsIgnoreCase(prop)) {
            return true;
        }
        try {
            Class<?> pf = XposedHelpers.findClassIfExists("com.android.internal.telephony.PhoneFactory", cl);
            if (pf == null) {
                return false;
            }
            Object phone = XposedHelpers.callStaticMethod(pf, "getPhone", phoneId);
            if (phone == null) {
                return false;
            }
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(phone, "isImsRegistered"))) {
                return false;
            }
            try {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(phone, "isWifiCallingEnabled"))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            Object imsPhone = XposedHelpers.callMethod(phone, "getImsPhone");
            if (imsPhone != null) {
                try {
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(imsPhone, "isWifiCallingEnabled"))) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
                Object tech = XposedHelpers.callMethod(imsPhone, "getImsRegistrationTech");
                return tech instanceof Number && ((Number) tech).intValue() == 2;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 为无 EF_SPN 且 EF_PNN 也为空的冷门 SIM 卡提供 VoWiFi 归属运营商名称防空兜底。
     */
    private static void hookWfcCarrierNameFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        // 兜底 A：Legacy ServiceStateTracker 路径防 getPnnHomeName() 返回空导致拼出 "null VoWifi"
        Class<?> iccRecords = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.uicc.IccRecords", lpparam.classLoader);
        if (iccRecords != null) {
            XposedHelpers.findAndHookMethod(iccRecords, "getPnnHomeName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof CharSequence && !TextUtils.isEmpty((CharSequence) res)) {
                        return;
                    }
                    try {
                        Object parentApp = XposedHelpers.getObjectField(param.thisObject, "mParentApp");
                        if (parentApp == null) {
                            return;
                        }
                        int phoneId = (int) XposedHelpers.callMethod(parentApp, "getPhoneId");
                        if (CountrySpoofer.shouldSpoofSlot(phoneId)
                                && isWfcOrCrossSimActive(phoneId, lpparam.classLoader)) {
                            String fallback = CountrySpoofer.getHomeOperatorNameForSlot(phoneId);
                            if (!TextUtils.isEmpty(fallback)) {
                                param.setResult(fallback);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "Hooked IccRecords.getPnnHomeName");
        }

        // 兜底 B：新架构 CarrierDisplayNameResolver 路径在 EF_SPN/EF_PNN 均为空时用归属名替换 PLMN 基站名
        Class<?> cdnrClass = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.cdnr.CarrierDisplayNameResolver", lpparam.classLoader);
        final Class<?> builderClass = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.cdnr.CarrierDisplayNameData$Builder", lpparam.classLoader);
        if (cdnrClass != null && builderClass != null) {
            XC_MethodHook cdnrHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                        return;
                    }
                    try {
                        Object data = param.args[0];
                        Object spnObj = XposedHelpers.callMethod(data, "getSpn");
                        CharSequence spn = spnObj instanceof CharSequence ? (CharSequence) spnObj : null;
                        if (!TextUtils.isEmpty(spn)) {
                            return;
                        }
                        Object efPnn = XposedHelpers.callMethod(param.thisObject, "getEfPnn");
                        if (efPnn instanceof List && !((List<?>) efPnn).isEmpty()) {
                            return;
                        }
                        Object phone = XposedHelpers.getObjectField(param.thisObject, "mPhone");
                        if (phone == null) {
                            return;
                        }
                        int phoneId = (int) XposedHelpers.callMethod(phone, "getPhoneId");
                        if (!CountrySpoofer.shouldSpoofSlot(phoneId)) {
                            return;
                        }
                        String homeName = CountrySpoofer.getHomeOperatorNameForSlot(phoneId);
                        if (TextUtils.isEmpty(homeName)) {
                            return;
                        }
                        Object dataSpnObj = XposedHelpers.callMethod(data, "getDataSpn");
                        String dataSpn = dataSpnObj instanceof String ? (String) dataSpnObj : null;
                        boolean showSpn = Boolean.TRUE.equals(XposedHelpers.callMethod(data, "shouldShowSpn"));
                        boolean showPlmn = Boolean.TRUE.equals(XposedHelpers.callMethod(data, "shouldShowPlmn"));
                        Object builder = XposedHelpers.newInstance(builderClass);
                        if (spn != null) {
                            XposedHelpers.callMethod(builder, "setSpn", spn.toString());
                        }
                        if (dataSpn != null) {
                            XposedHelpers.callMethod(builder, "setDataSpn", dataSpn);
                        }
                        XposedHelpers.callMethod(builder, "setShowSpn", showSpn);
                        XposedHelpers.callMethod(builder, "setPlmn", homeName);
                        XposedHelpers.callMethod(builder, "setShowPlmn", showPlmn);
                        param.args[0] = XposedHelpers.callMethod(builder, "build");
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(cdnrClass, "getCarrierDisplayNameFromWifiCallingOverride", cdnrHook);
            XposedBridge.hookAllMethods(cdnrClass, "getCarrierDisplayNameFromCrossSimCallingOverride", cdnrHook);
            XposedBridge.log(TAG + "Hooked CarrierDisplayNameResolver WFC/CrossSIM override");
        }
    }
}
