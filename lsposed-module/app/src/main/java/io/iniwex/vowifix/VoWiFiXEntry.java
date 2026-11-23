package io.iniwex.vowifix;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * VoWiFiX LSPosed 模块主入口：负责自身激活自检、CarrierConfig 解锁与国家码/地理位置伪装。
 */
public class VoWiFiXEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[VoWiFiX] ";
    private static final String SELF_PACKAGE = "io.iniwex.vowifix";

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.phone",
            "com.android.settings",
            "com.oplus.wirelesssettings",
            "org.codeaurora.ims",
            "com.qualcomm.qti.cne",
            "android",
            "system",
            "vendor.qti.iwlan"
    ));

    private static final Set<String> CARRIER_CONFIG_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.phone",
            "com.android.settings",
            "com.oplus.wirelesssettings",
            "org.codeaurora.ims"
    ));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) {
            return;
        }
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        ActivationStatus.class.getName(),
                        lpparam.classLoader,
                        "isModuleActive",
                        XC_MethodReplacement.returnConstant(true)
                );
                XposedBridge.log(TAG + "Self-activation hook active in " + lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "Self-activation hook failed: " + t);
            }
            return;
        }

        if (!TARGET_PACKAGES.contains(lpparam.packageName) && !"system_server".equals(lpparam.processName)) {
            return;
        }
        XposedBridge.log(TAG + "Loaded into package: " + lpparam.packageName + " (process=" + lpparam.processName + ")");

        if (CARRIER_CONFIG_PACKAGES.contains(lpparam.packageName)) {
            try {
                CarrierConfigHook.init(lpparam);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "CarrierConfigHook failed in " + lpparam.packageName + ": " + t);
            }
        }

        try {
            LocationGeoHook.init(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "LocationGeoHook failed in " + lpparam.packageName + ": " + t);
        }
    }
}
