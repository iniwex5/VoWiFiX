package io.iniwex.vowifix;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 单卡禁蜂窝纯 VoWiFi 跨品牌通用拦截 Hook（仅注入 com.android.phone 进程）：
 * 保留完整 3GPP/4G/5G 基带能力，通过非持久化手动锁空网 PLMN (00101) 与自动选网防回退拦截实现零蜂窝驻网，
 * 实时豁免国内卡 (MCC=460)，并在对外通知出口净化状态栏与信号显示；
 * 彻底消除 3GPP2 纯数据制式导致的高通 Modem VoWiFi 拨号拒呼，且完全不干预 CarrierConfig/MBN/EFS 流程。
 */
public final class CellBlockHook {

    private static final String TAG = "[VoWiFiX-CellBlock] ";
    private static final long LOCK_DEBOUNCE_MS = 65_000L;
    private static final long SELF_AUTO_RESTORE_WINDOW_MS = 5_000L;
    private static final long CALL_IDLE_RESYNC_POLL_MS = 5_000L;

    private static final Object STATE_LOCK = new Object();
    private static final AtomicBoolean OBSERVER_REGISTERED = new AtomicBoolean(false);
    private static final int[] LAST_REPORTED_RAF = new int[]{-1, -1};
    private static final boolean[] MANAGED_BLOCKED = new boolean[]{false, false};
    private static final long[] LAST_LOCK_TRIGGER_MS = new long[]{0L, 0L};
    private static final long[] SELF_AUTO_RESTORE_UNTIL_MS = new long[]{0L, 0L};
    private static final boolean[] PENDING_RESYNC_AFTER_CALL = new boolean[]{false, false};
    private static final long[] LAST_SANITIZE_SYNC_MS = new long[]{0L, 0L};
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile Runnable pendingSyncRunnable;
    private static volatile Runnable pendingCallResyncRunnable;
    private static final Runnable[] PENDING_REFRESH_FAST = new Runnable[2];
    private static final Runnable[] PENDING_REFRESH_SLOW = new Runnable[2];

    private CellBlockHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !"com.android.phone".equals(lpparam.packageName)) {
            return;
        }
        hookNetworkSelectionControl(lpparam.classLoader);
        hookSignalAndDisplaySanitizer(lpparam.classLoader);
    }

    /**
     * 锚定选网控制与 ServiceState 状态变化出口：
     * 开启禁蜂窝时自动下发非持久化手动锁网 00101，并拦截一切外部/OOS/重启恢复自动选网请求；
     * 关闭开关或切入国内 460 豁免卡时自动切回全自动选网。
     */
    private static void hookNetworkSelectionControl(final ClassLoader cl) {
        Class<?> phoneCls = XposedHelpers.findClassIfExists("com.android.internal.telephony.Phone", cl);
        final XC_MethodHook serviceStateChangedHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Integer slotId = extractPhoneId(param.thisObject);
                Context ctx = safeGetContext(param.thisObject);
                if (slotId == null || slotId < 0 || slotId >= 2 || ctx == null) {
                    return;
                }
                int raf = safeGetModemRaf(param.thisObject);
                ensureObserverAndReportRaf(ctx, slotId, raf);

                ServiceState ss = (param.args != null && param.args.length > 0 && param.args[0] instanceof ServiceState)
                        ? (ServiceState) param.args[0] : null;
                reconcileSlotSelectionIfNeeded(cl, ctx, param.thisObject, slotId, ss);
            }
        };

        if (phoneCls != null) {
            XposedBridge.hookAllMethods(phoneCls, "getRadioAccessFamily", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (!(res instanceof Number)) {
                        return;
                    }
                    int raf = ((Number) res).intValue();
                    if (raf <= 0) {
                        return;
                    }
                    Integer slotId = extractPhoneId(param.thisObject);
                    Context ctx = safeGetContext(param.thisObject);
                    if (slotId != null && ctx != null) {
                        ensureObserverAndReportRaf(ctx, slotId, raf);
                    }
                }
            });

            // 仅 Hook 基类 notifyServiceStateChangedP，避免与子类 notifyServiceStateChanged 重复双触发
            XposedBridge.hookAllMethods(phoneCls, "notifyServiceStateChangedP", serviceStateChangedHook);

            XposedBridge.hookAllMethods(phoneCls, "setNetworkSelectionModeAutomatic", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Integer slotId = extractPhoneId(param.thisObject);
                    Context ctx = safeGetContext(param.thisObject);
                    if (slotId == null || slotId < 0 || slotId >= 2 || ctx == null) {
                        return;
                    }
                    if (isInSelfAutoRestore(slotId)) {
                        return;
                    }
                    if (shouldInterceptSlot(ctx, slotId)) {
                        completeMessageSuccess(findMessageArg(param.args));
                        param.setResult(null);
                        XposedBridge.log(TAG + "slot" + slotId + " blocked Phone.setNetworkSelectionModeAutomatic");
                    }
                }
            });
        }

        Class<?> gsmCdmaPhoneCls = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.GsmCdmaPhone", cl);
        if (gsmCdmaPhoneCls != null) {
            final XC_MethodHook callIdleHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Integer slotId = extractPhoneId(param.thisObject);
                    if (slotId == null || slotId < 0 || slotId >= 2) {
                        return;
                    }
                    boolean pending;
                    synchronized (STATE_LOCK) {
                        pending = PENDING_RESYNC_AFTER_CALL[slotId];
                    }
                    if (pending && isPhoneIdle(param.thisObject)) {
                        synchronized (STATE_LOCK) {
                            PENDING_RESYNC_AFTER_CALL[slotId] = false;
                        }
                        XposedBridge.log(TAG + "slot" + slotId + " call ended, syncing deferred cell-block rule");
                        scheduleSyncAllPhones(cl);
                    }
                }
            };
            XposedBridge.hookAllMethods(gsmCdmaPhoneCls, "notifyPreciseCallStateChanged", callIdleHook);
            XposedBridge.hookAllMethods(gsmCdmaPhoneCls, "notifyPhoneStateChanged", callIdleHook);
        }

        Class<?> rilCls = XposedHelpers.findClassIfExists("com.android.internal.telephony.RIL", cl);
        if (rilCls != null) {
            XposedBridge.hookAllMethods(rilCls, "setNetworkSelectionModeAutomatic", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Integer slotId = extractPhoneId(param.thisObject);
                    if (slotId == null || slotId < 0 || slotId >= 2) {
                        return;
                    }
                    if (isInSelfAutoRestore(slotId)) {
                        return;
                    }
                    Object phone = getPhoneBySlot(cl, slotId);
                    Context ctx = safeGetContext(phone);
                    if (ctx != null && shouldInterceptSlot(ctx, slotId)) {
                        completeMessageSuccess(findMessageArg(param.args));
                        param.setResult(null);
                        XposedBridge.log(TAG + "slot" + slotId + " blocked RIL.setNetworkSelectionModeAutomatic");
                    }
                }
            });
        }

        XposedBridge.log(TAG + "Hooked network selection control (PLMN " + CellBlockConfig.BLOCK_PLMN + ")");
    }

    private static void reconcileSlotSelectionIfNeeded(
            ClassLoader cl, Context ctx, Object phone, int slotId, ServiceState ss) {
        boolean shouldBlock = shouldInterceptSlot(ctx, slotId);
        if (!isPhoneIdle(phone)) {
            if ((shouldBlock && !isAlreadyLockedOnBlockPlmn(phone, ss))
                    || (!shouldBlock && isManagedByCellBlock(phone, slotId))) {
                markPendingResyncAfterCall(cl, slotId);
            }
            return;
        }
        if (shouldBlock) {
            if (isAlreadyLockedOnBlockPlmn(phone, ss)) {
                synchronized (STATE_LOCK) {
                    MANAGED_BLOCKED[slotId] = true;
                }
                return;
            }
            long now = System.currentTimeMillis();
            boolean shouldTrigger;
            synchronized (STATE_LOCK) {
                shouldTrigger = (now - LAST_LOCK_TRIGGER_MS[slotId]) >= LOCK_DEBOUNCE_MS;
                if (shouldTrigger) {
                    LAST_LOCK_TRIGGER_MS[slotId] = now;
                }
            }
            if (shouldTrigger) {
                MAIN_HANDLER.post(() -> {
                    Object p = getPhoneBySlot(cl, slotId);
                    if (p == null) {
                        return;
                    }
                    Context c = safeGetContext(p);
                    if (c == null) {
                        return;
                    }
                    if (!isPhoneIdle(p)) {
                        markPendingResyncAfterCall(cl, slotId);
                        return;
                    }
                    if (shouldInterceptSlot(c, slotId) && !isAlreadyLockedOnBlockPlmn(p, null)) {
                        applyManualBlockPlmn(p, slotId);
                    }
                });
            }
        } else if (isManagedByCellBlock(phone, slotId)) {
            MAIN_HANDLER.post(() -> {
                Object p = getPhoneBySlot(cl, slotId);
                if (p == null) {
                    return;
                }
                Context c = safeGetContext(p);
                if (c == null) {
                    return;
                }
                if (!isPhoneIdle(p)) {
                    markPendingResyncAfterCall(cl, slotId);
                    return;
                }
                if (!shouldInterceptSlot(c, slotId) && isManagedByCellBlock(p, slotId)) {
                    restoreAutoNetworkSelection(p, slotId);
                }
            });
        }
    }

    /**
     * 对目标槽位执行非持久化手动选网锁定空网 PLMN 00101。
     */
    private static void applyManualBlockPlmn(Object phone, int slotId) {
        if (phone == null || slotId < 0 || slotId >= 2 || !isPhoneIdle(phone)) {
            return;
        }
        synchronized (STATE_LOCK) {
            SELF_AUTO_RESTORE_UNTIL_MS[slotId] = 0L;
            PENDING_RESYNC_AFTER_CALL[slotId] = false;
            LAST_LOCK_TRIGGER_MS[slotId] = System.currentTimeMillis();
            MANAGED_BLOCKED[slotId] = true;
        }

        ClassLoader cl = phone.getClass().getClassLoader();
        Object opInfo = createBlockOperatorInfo(cl);
        boolean invoked = false;
        if (opInfo != null) {
            try {
                XposedHelpers.callMethod(phone, "selectNetworkManually", opInfo, false, (Message) null);
                invoked = true;
                XposedBridge.log(TAG + "slot" + slotId + " selectNetworkManually(" + CellBlockConfig.BLOCK_PLMN + ", persist=false)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "slot" + slotId + " selectNetworkManually failed: " + t);
            }
        }
        if (!invoked) {
            try {
                Object ci = XposedHelpers.getObjectField(phone, "mCi");
                if (ci != null) {
                    XposedHelpers.callMethod(ci, "setNetworkSelectionModeManual",
                            CellBlockConfig.BLOCK_PLMN, 0, (Message) null);
                    invoked = true;
                    XposedBridge.log(TAG + "slot" + slotId + " mCi.setNetworkSelectionModeManual(" + CellBlockConfig.BLOCK_PLMN + ")");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "slot" + slotId + " RIL manual selection fallback failed: " + t);
            }
        }

        if (invoked) {
            scheduleExternalRefresh(cl, slotId);
        }
    }

    /**
     * 安全切回全自动选网，并触发对外状态栏与信号刷新。
     */
    private static void restoreAutoNetworkSelection(Object phone, int slotId) {
        if (phone == null || slotId < 0 || slotId >= 2 || !isPhoneIdle(phone)) {
            return;
        }
        synchronized (STATE_LOCK) {
            MANAGED_BLOCKED[slotId] = false;
            LAST_LOCK_TRIGGER_MS[slotId] = 0L;
            PENDING_RESYNC_AFTER_CALL[slotId] = false;
            // 覆盖 RIL 异步回调 EVENT 38 -> setNetworkSelectionModeAutomatic 的时间窗口
            SELF_AUTO_RESTORE_UNTIL_MS[slotId] = SystemClock.elapsedRealtime() + SELF_AUTO_RESTORE_WINDOW_MS;
        }
        try {
            XposedHelpers.callMethod(phone, "setNetworkSelectionModeAutomatic", (Message) null);
            try {
                XposedHelpers.setObjectField(phone, "mManualNetworkSelectionPlmn", null);
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + "slot" + slotId + " restored automatic network selection");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "slot" + slotId + " restore automatic selection failed: " + t);
        }
        ClassLoader cl = phone.getClass().getClassLoader();
        scheduleExternalRefresh(cl, slotId);
    }

    private static Object createBlockOperatorInfo(ClassLoader cl) {
        Class<?> opCls = XposedHelpers.findClassIfExists("com.android.internal.telephony.OperatorInfo", cl);
        if (opCls == null) {
            opCls = XposedHelpers.findClassIfExists("com.android.internal.telephony.OperatorInfo",
                    CellBlockHook.class.getClassLoader());
        }
        if (opCls == null) {
            return null;
        }
        String p = CellBlockConfig.BLOCK_PLMN;
        try {
            Constructor<?> c4 = opCls.getConstructor(String.class, String.class, String.class, String.class);
            return c4.newInstance(p, p, p, "unknown");
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> c3 = opCls.getConstructor(String.class, String.class, String.class);
            return c3.newInstance(p, p, p);
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> c5 = opCls.getConstructor(String.class, String.class, String.class, String.class, int.class);
            return c5.newInstance(p, p, p, "unknown", 0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Message findMessageArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Message) {
                return (Message) arg;
            }
        }
        return null;
    }

    private static void completeMessageSuccess(Message msg) {
        if (msg == null) {
            return;
        }
        try {
            Class<?> arCls = Class.forName("android.os.AsyncResult");
            arCls.getMethod("forMessage", Message.class, Object.class, Throwable.class)
                    .invoke(null, msg, null, null);
        } catch (Throwable ignored) {
        }
        try {
            msg.sendToTarget();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isPhoneIdle(Object phone) {
        if (phone == null) {
            return true;
        }
        try {
            Object st = XposedHelpers.callMethod(phone, "getState");
            if (st != null && !"IDLE".equals(st.toString())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object imsPhone = XposedHelpers.callMethod(phone, "getImsPhone");
            if (imsPhone != null) {
                Object imsSt = XposedHelpers.callMethod(imsPhone, "getState");
                if (imsSt != null && !"IDLE".equals(imsSt.toString())) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 在对外状态通知与信号查询出口净化显示：
     * 保持 Phone.getServiceState() 内部在网状态供 VoWiFi 拨号/短信使用，
     * 仅对开启禁蜂窝且 WWAN 真实脱网的槽位清零信号强度、抹除假 4G 标并广播无服务状态。
     */
    private static void hookSignalAndDisplaySanitizer(final ClassLoader cl) {
        Class<?> sscCls = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.SignalStrengthController", cl);
        if (sscCls != null) {
            XposedBridge.hookAllMethods(sscCls, "getSignalStrength", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object phone = null;
                    try {
                        phone = XposedHelpers.getObjectField(param.thisObject, "mPhone");
                    } catch (Throwable ignored) {
                    }
                    Integer slotId = extractPhoneId(phone);
                    Context ctx = safeGetContext(phone);
                    if (slotId != null && slotId >= 0 && slotId < 2 && ctx != null
                            && shouldInterceptSlot(ctx, slotId)
                            && isPhoneWwanOutOfService(phone)) {
                        SignalStrength empty = createEmptySignalStrength();
                        if (empty != null) {
                            param.setResult(empty);
                        }
                    }
                }
            });
        }

        Class<?> trmCls = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyRegistryManager", cl);
        if (trmCls == null) {
            trmCls = XposedHelpers.findClassIfExists(
                    "android.telephony.TelephonyRegistryManager",
                    CellBlockHook.class.getClassLoader());
        }
        if (trmCls == null) {
            XposedBridge.log(TAG + "TelephonyRegistryManager not found, skip external sanitizer");
            return;
        }
        XposedBridge.hookAllMethods(trmCls, "notifyServiceStateChanged", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3
                        || !(param.args[0] instanceof Integer)
                        || !(param.args[2] instanceof ServiceState)) {
                    return;
                }
                final int slotId = (Integer) param.args[0];
                if (slotId < 0 || slotId >= 2) {
                    return;
                }
                final Object phone = getPhoneBySlot(cl, slotId);
                Context ctx = safeGetContext(phone);
                ServiceState orig = (ServiceState) param.args[2];
                if (ctx == null || !shouldInterceptSlot(ctx, slotId)
                        || !isCellularWwanOutOfService(orig)) {
                    return;
                }
                // 必须克隆副本修改对外语音驻网状态，防止污染 com.android.phone 内部语音/短信判定
                ServiceState sanitized = new ServiceState(orig);
                try {
                    XposedHelpers.callMethod(sanitized, "setVoiceRegState",
                            ServiceState.STATE_OUT_OF_SERVICE);
                    param.args[2] = sanitized;
                } catch (Throwable ignored) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - LAST_SANITIZE_SYNC_MS[slotId] > 2000L) {
                    LAST_SANITIZE_SYNC_MS[slotId] = now;
                    MAIN_HANDLER.postDelayed(() -> {
                        if (phone != null) {
                            try {
                                XposedHelpers.callMethod(phone, "notifySignalStrength");
                            } catch (Throwable ignored) {
                            }
                            refreshDisplayInfoForPhone(phone);
                        }
                    }, 120L);
                }
            }
        });

        XposedBridge.hookAllMethods(trmCls, "notifySignalStrengthChanged", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3
                        || !(param.args[0] instanceof Integer)
                        || !(param.args[2] instanceof SignalStrength)) {
                    return;
                }
                int slotId = (Integer) param.args[0];
                if (slotId < 0 || slotId >= 2) {
                    return;
                }
                Object phone = getPhoneBySlot(cl, slotId);
                Context ctx = safeGetContext(phone);
                if (ctx != null && shouldInterceptSlot(ctx, slotId)
                        && isPhoneWwanOutOfService(phone)) {
                    SignalStrength empty = createEmptySignalStrength();
                    if (empty != null) {
                        param.args[2] = empty;
                    }
                }
            }
        });

        XposedBridge.hookAllMethods(trmCls, "notifyDisplayInfoChanged", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3
                        || !(param.args[0] instanceof Integer)
                        || !(param.args[2] instanceof TelephonyDisplayInfo)) {
                    return;
                }
                int slotId = (Integer) param.args[0];
                if (slotId < 0 || slotId >= 2) {
                    return;
                }
                Object phone = getPhoneBySlot(cl, slotId);
                Context ctx = safeGetContext(phone);
                if (ctx == null || !shouldInterceptSlot(ctx, slotId)
                        || !isPhoneWwanOutOfService(phone)) {
                    return;
                }
                TelephonyDisplayInfo cur = (TelephonyDisplayInfo) param.args[2];
                if (cur.getNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN
                        || cur.getOverrideNetworkType() != TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE) {
                    TelephonyDisplayInfo emptyInfo = createEmptyDisplayInfo();
                    if (emptyInfo != null) {
                        param.args[2] = emptyInfo;
                    }
                }
            }
        });

        XposedBridge.log(TAG + "Hooked SignalStrengthController & TelephonyRegistryManager sanitizers");
    }

    /**
     * 注册 Settings.Global 监听器并实时同步对应物理卡槽的 modemRaf 至遥测键。
     */
    private static void ensureObserverAndReportRaf(final Context ctx, final int slotId, final int modemRaf) {
        if (slotId >= 0 && slotId < 2 && modemRaf > 0 && LAST_REPORTED_RAF[slotId] != modemRaf) {
            LAST_REPORTED_RAF[slotId] = modemRaf;
            try {
                Settings.Global.putInt(ctx.getContentResolver(), CellBlockConfig.modemRafKey(slotId), modemRaf);
            } catch (Throwable ignored) {
            }
        }

        if (!OBSERVER_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            ContentResolver cr = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext().getContentResolver()
                    : ctx.getContentResolver();
            final ClassLoader phoneCl = ctx.getClassLoader();
            ContentObserver obs = new ContentObserver(MAIN_HANDLER) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    scheduleSyncAllPhones(phoneCl);
                }
            };
            cr.registerContentObserver(Settings.Global.getUriFor(CellBlockConfig.KEY_BLOCK_SLOT0), false, obs);
            cr.registerContentObserver(Settings.Global.getUriFor(CellBlockConfig.KEY_BLOCK_SLOT1), false, obs);
            cr.registerContentObserver(Settings.Global.getUriFor(CellBlockConfig.KEY_EXEMPT_CN), false, obs);
            XposedBridge.log(TAG + "Registered Settings.Global cell-block observer");
            scheduleSyncAllPhones(phoneCl);
        } catch (Throwable t) {
            OBSERVER_REGISTERED.set(false);
            XposedBridge.log(TAG + "Failed to register observer: " + t);
        }
    }

    private static boolean isInSelfAutoRestore(int slotId) {
        if (slotId < 0 || slotId >= 2) {
            return false;
        }
        synchronized (STATE_LOCK) {
            return SELF_AUTO_RESTORE_UNTIL_MS[slotId] > SystemClock.elapsedRealtime();
        }
    }

    private static void markPendingResyncAfterCall(ClassLoader cl, int slotId) {
        if (slotId < 0 || slotId >= 2) {
            return;
        }
        synchronized (STATE_LOCK) {
            PENDING_RESYNC_AFTER_CALL[slotId] = true;
        }
        scheduleCallIdleResync(cl);
    }

    private static void scheduleCallIdleResync(final ClassLoader cl) {
        if (pendingCallResyncRunnable != null) {
            MAIN_HANDLER.removeCallbacks(pendingCallResyncRunnable);
        }
        pendingCallResyncRunnable = new Runnable() {
            @Override
            public void run() {
                boolean[] snapshotPending = new boolean[2];
                synchronized (STATE_LOCK) {
                    snapshotPending[0] = PENDING_RESYNC_AFTER_CALL[0];
                    snapshotPending[1] = PENDING_RESYNC_AFTER_CALL[1];
                }
                if (!snapshotPending[0] && !snapshotPending[1]) {
                    return;
                }
                boolean anyReady = false;
                for (int i = 0; i < 2; i++) {
                    if (!snapshotPending[i]) {
                        continue;
                    }
                    Object p = getPhoneBySlot(cl, i);
                    if (p == null || isPhoneIdle(p)) {
                        synchronized (STATE_LOCK) {
                            PENDING_RESYNC_AFTER_CALL[i] = false;
                        }
                        anyReady = true;
                    }
                }
                if (anyReady) {
                    scheduleSyncAllPhones(cl);
                }
                boolean stillPending;
                synchronized (STATE_LOCK) {
                    stillPending = PENDING_RESYNC_AFTER_CALL[0] || PENDING_RESYNC_AFTER_CALL[1];
                }
                if (stillPending) {
                    MAIN_HANDLER.postDelayed(this, CALL_IDLE_RESYNC_POLL_MS);
                }
            }
        };
        MAIN_HANDLER.postDelayed(pendingCallResyncRunnable, CALL_IDLE_RESYNC_POLL_MS);
    }

    private static ServiceState safeGetServiceState(Object phone) {
        if (phone == null) {
            return null;
        }
        try {
            Object ss = XposedHelpers.callMethod(phone, "getServiceState");
            return ss instanceof ServiceState ? (ServiceState) ss : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getManualSelectionPlmn(Object phone) {
        if (phone == null) {
            return "";
        }
        try {
            Object res = XposedHelpers.callMethod(phone, "getManualNetworkSelectionPlmn");
            if (res != null) {
                return res.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object raw = XposedHelpers.getObjectField(phone, "mManualNetworkSelectionPlmn");
            if (raw != null) {
                return raw.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 仅将由本模块下发管理的空网锁定（或进程重启后残留的 00101 手动锁网态）纳入自动恢复范围，避免误重置用户自选运营商。
     */
    private static boolean isManagedByCellBlock(Object phone, int slotId) {
        if (slotId < 0 || slotId >= 2) {
            return false;
        }
        boolean managed;
        synchronized (STATE_LOCK) {
            managed = MANAGED_BLOCKED[slotId];
        }
        if (managed) {
            return true;
        }
        return isPhoneManualSelection(phone)
                && CellBlockConfig.BLOCK_PLMN.equals(getManualSelectionPlmn(phone));
    }

    private static boolean isAlreadyLockedOnBlockPlmn(Object phone, ServiceState ss) {
        ServiceState cur = ss != null ? ss : safeGetServiceState(phone);
        if (cur == null || !cur.getIsManualSelection() || !isCellularWwanOutOfService(cur)) {
            return false;
        }
        String plmn = getManualSelectionPlmn(phone);
        return plmn.isEmpty() || CellBlockConfig.BLOCK_PLMN.equals(plmn);
    }

    private static void scheduleSyncAllPhones(final ClassLoader cl) {
        if (pendingSyncRunnable != null) {
            MAIN_HANDLER.removeCallbacks(pendingSyncRunnable);
        }
        pendingSyncRunnable = () -> {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 2; i++) {
                try {
                    Object phone = getPhoneBySlot(cl, i);
                    Context ctx = safeGetContext(phone);
                    if (phone == null || ctx == null) {
                        continue;
                    }
                    boolean shouldBlock = shouldInterceptSlot(ctx, i);
                    if (!isPhoneIdle(phone)) {
                        if ((shouldBlock && !isAlreadyLockedOnBlockPlmn(phone, null))
                                || (!shouldBlock && isManagedByCellBlock(phone, i))) {
                            markPendingResyncAfterCall(cl, i);
                        }
                        continue;
                    }
                    if (shouldBlock) {
                        if (isAlreadyLockedOnBlockPlmn(phone, null)) {
                            synchronized (STATE_LOCK) {
                                MANAGED_BLOCKED[i] = true;
                            }
                            continue;
                        }
                        boolean inFlight;
                        synchronized (STATE_LOCK) {
                            inFlight = MANAGED_BLOCKED[i] && (now - LAST_LOCK_TRIGGER_MS[i]) < LOCK_DEBOUNCE_MS;
                        }
                        if (!inFlight) {
                            applyManualBlockPlmn(phone, i);
                        }
                    } else if (isManagedByCellBlock(phone, i)) {
                        restoreAutoNetworkSelection(phone, i);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        MAIN_HANDLER.postDelayed(pendingSyncRunnable, 150L);
    }

    private static void scheduleExternalRefresh(final ClassLoader cl, final int slotId) {
        if (slotId < 0 || slotId >= 2) {
            return;
        }
        if (PENDING_REFRESH_FAST[slotId] != null) {
            MAIN_HANDLER.removeCallbacks(PENDING_REFRESH_FAST[slotId]);
        }
        if (PENDING_REFRESH_SLOW[slotId] != null) {
            MAIN_HANDLER.removeCallbacks(PENDING_REFRESH_SLOW[slotId]);
        }
        Runnable fast = () -> refreshExternalTelephonyState(cl, slotId);
        Runnable slow = () -> refreshExternalTelephonyState(cl, slotId);
        PENDING_REFRESH_FAST[slotId] = fast;
        PENDING_REFRESH_SLOW[slotId] = slow;
        MAIN_HANDLER.postDelayed(fast, 800L);
        MAIN_HANDLER.postDelayed(slow, 2500L);
    }

    private static boolean isPhoneManualSelection(Object phone) {
        ServiceState ss = safeGetServiceState(phone);
        return ss != null && ss.getIsManualSelection();
    }

    /**
     * 规则变更后主动同步对外 ServiceState、信号强度与 DisplayInfo，优先直达 mNotifier 避免惊动内部状态机或重入 Hook。
     */
    private static void refreshExternalTelephonyState(ClassLoader cl, int slotId) {
        Object phone = getPhoneBySlot(cl, slotId);
        Context ctx = safeGetContext(phone);
        if (phone == null || ctx == null) {
            return;
        }
        if (!shouldInterceptSlot(ctx, slotId)) {
            try {
                Object ssc = XposedHelpers.callMethod(phone, "getSignalStrengthController");
                if (ssc != null) {
                    XposedHelpers.callMethod(ssc, "getSignalStrengthFromCi");
                }
            } catch (Throwable ignored) {
            }
        }
        boolean notifiedDirect = false;
        try {
            Object notifier = XposedHelpers.getObjectField(phone, "mNotifier");
            if (notifier != null) {
                XposedHelpers.callMethod(notifier, "notifyServiceState", phone);
                notifiedDirect = true;
            }
        } catch (Throwable ignored) {
        }
        if (!notifiedDirect) {
            try {
                Object ss = XposedHelpers.callMethod(phone, "getServiceState");
                if (ss != null) {
                    XposedHelpers.callMethod(phone, "notifyServiceStateChanged", ss);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            XposedHelpers.callMethod(phone, "notifySignalStrength");
        } catch (Throwable ignored) {
        }
        refreshDisplayInfoForPhone(phone);
    }

    private static void refreshDisplayInfoForPhone(Object phone) {
        if (phone == null) {
            return;
        }
        try {
            Object dic = XposedHelpers.callMethod(phone, "getDisplayInfoController");
            if (dic != null) {
                try {
                    XposedHelpers.callMethod(dic, "updateTelephonyDisplayInfo");
                } catch (Throwable ignored) {
                }
                Object info = XposedHelpers.callMethod(dic, "getTelephonyDisplayInfo");
                if (info instanceof TelephonyDisplayInfo) {
                    XposedHelpers.callMethod(phone, "notifyDisplayInfoChanged", info);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 判定当前 ServiceState 的蜂窝 WWAN（CS 与 PS 域）是否均未注册（仅 IWLAN 在线也视作脱网）。
     */
    static boolean isCellularWwanOutOfService(ServiceState ss) {
        if (ss == null) {
            return false;
        }
        try {
            Object csInfo = XposedHelpers.callMethod(ss, "getNetworkRegistrationInfo", 1, 1);
            Object psInfo = XposedHelpers.callMethod(ss, "getNetworkRegistrationInfo", 2, 1);
            if (csInfo == null && psInfo == null) {
                return false;
            }
            if (isRegInfoRegistered(csInfo) || isRegInfoRegistered(psInfo)) {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRegInfoRegistered(Object info) {
        if (info == null) {
            return false;
        }
        try {
            Object stateObj = XposedHelpers.callMethod(info, "getRegistrationState");
            if (stateObj instanceof Number) {
                int state = ((Number) stateObj).intValue();
                // 1=REGISTRATION_STATE_HOME, 5=REGISTRATION_STATE_ROAMING
                return state == 1 || state == 5;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isPhoneWwanOutOfService(Object phone) {
        if (phone == null) {
            return false;
        }
        ServiceState ss = null;
        try {
            Object sst = XposedHelpers.callMethod(phone, "getServiceStateTracker");
            if (sst != null) {
                Object raw = XposedHelpers.getObjectField(sst, "mSS");
                if (raw instanceof ServiceState) {
                    ss = (ServiceState) raw;
                }
            }
        } catch (Throwable ignored) {
        }
        if (ss == null) {
            try {
                Object raw = XposedHelpers.callMethod(phone, "getServiceState");
                if (raw instanceof ServiceState) {
                    ss = (ServiceState) raw;
                }
            } catch (Throwable ignored) {
            }
        }
        return isCellularWwanOutOfService(ss);
    }

    private static SignalStrength createEmptySignalStrength() {
        SignalStrength empty;
        try {
            Constructor<SignalStrength> ctor = SignalStrength.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            empty = ctor.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
        try {
            Object wrapper = XposedHelpers.callMethod(empty, "getWrapper");
            if (wrapper != null) {
                try {
                    XposedHelpers.callMethod(wrapper, "setOemLevel", 0, 0);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.callMethod(wrapper, "setFake", false);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return empty;
    }

    private static TelephonyDisplayInfo createEmptyDisplayInfo() {
        try {
            Constructor<TelephonyDisplayInfo> c5 = TelephonyDisplayInfo.class.getDeclaredConstructor(
                    int.class, int.class, boolean.class, boolean.class, boolean.class);
            c5.setAccessible(true);
            return c5.newInstance(0, 0, false, false, false);
        } catch (Throwable ignored) {
        }
        try {
            Constructor<TelephonyDisplayInfo> c3 = TelephonyDisplayInfo.class.getDeclaredConstructor(
                    int.class, int.class, boolean.class);
            c3.setAccessible(true);
            return c3.newInstance(0, 0, false);
        } catch (Throwable ignored) {
        }
        try {
            Constructor<TelephonyDisplayInfo> c2 = TelephonyDisplayInfo.class.getDeclaredConstructor(
                    int.class, int.class);
            c2.setAccessible(true);
            return c2.newInstance(0, 0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 判断当前物理槽位是否处于应拦截蜂窝驻网的状态：规则开启 + SIM 在位 + 未命中国内 460 自动豁免。
     */
    static boolean shouldInterceptSlot(Context ctx, int slotId) {
        if (ctx == null || slotId < 0 || slotId >= 2) {
            return false;
        }
        ContentResolver cr = ctx.getContentResolver();
        int blockEnabled;
        try {
            blockEnabled = Settings.Global.getInt(cr, CellBlockConfig.slotBlockKey(slotId), 0);
        } catch (Throwable t) {
            return false;
        }
        if (blockEnabled != 1) {
            return false;
        }

        String simState = getSlotProp("gsm.sim.state", slotId);
        if (!"LOADED".equalsIgnoreCase(simState)) {
            return false;
        }

        int exemptCn = 1;
        try {
            exemptCn = Settings.Global.getInt(cr, CellBlockConfig.KEY_EXEMPT_CN, 1);
        } catch (Throwable ignored) {
        }
        if (exemptCn == 1) {
            String plmn = getSlotProp("gsm.sim.operator.numeric", slotId);
            if (plmn.isEmpty()) {
                plmn = getSimOperatorNumericFallback(ctx, slotId);
            }
            // SIM 尚在初始化读号时保守放行，防止在获取到真实 PLMN 前误锁国内 460 卡
            if (plmn.isEmpty() || CellBlockConfig.isChinaMainlandPlmn(plmn)) {
                return false;
            }
        }
        return true;
    }

    private static String getSimOperatorNumericFallback(Context ctx, int slotId) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null) {
                Object v = XposedHelpers.callMethod(tm, "getSimOperatorNumericForPhone", slotId);
                if (v != null) {
                    return v.toString().trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static int safeGetModemRaf(Object phone) {
        if (phone == null) {
            return 0;
        }
        try {
            Object raf = XposedHelpers.callMethod(phone, "getRadioAccessFamily");
            return raf instanceof Number ? ((Number) raf).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Object getPhoneBySlot(ClassLoader cl, int slotId) {
        try {
            Class<?> pf = XposedHelpers.findClassIfExists("com.android.internal.telephony.PhoneFactory", cl);
            if (pf != null) {
                return XposedHelpers.callStaticMethod(pf, "getPhone", slotId);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context safeGetContext(Object phone) {
        if (phone == null) {
            return null;
        }
        try {
            Object c = XposedHelpers.callMethod(phone, "getContext");
            return c instanceof Context ? (Context) c : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer extractPhoneId(Object target) {
        if (target == null) {
            return null;
        }
        try {
            Object id = XposedHelpers.callMethod(target, "getPhoneId");
            if (id instanceof Number) {
                return ((Number) id).intValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object id = XposedHelpers.getObjectField(target, "mPhoneId");
            if (id instanceof Number) {
                return ((Number) id).intValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getSlotProp(String key, int slotId) {
        String raw = CountrySpoofer.getSystemProperty(key, "");
        if (raw.isEmpty()) {
            return "";
        }
        String[] parts = raw.split(",", -1);
        return slotId < parts.length ? parts[slotId].trim() : "";
    }
}
