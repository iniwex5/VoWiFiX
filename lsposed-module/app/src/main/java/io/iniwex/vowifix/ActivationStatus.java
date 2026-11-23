package io.iniwex.vowifix;

/**
 * 模块自身激活状态标记；由 LSPosed 在 VoWiFiXEntry 内动态 Hook 为 true。
 */
public final class ActivationStatus {

    private ActivationStatus() {
    }

    public static boolean isModuleActive() {
        return false;
    }
}
