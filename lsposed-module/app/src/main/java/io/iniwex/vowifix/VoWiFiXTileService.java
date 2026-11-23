package io.iniwex.vowifix;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 控制中心快捷磁贴服务。
 * 仅通过只读系统属性获取当前卡 VoWiFi / 漫游状态，绝不触碰 Root 或 /dev/diag。
 */
public class VoWiFiXTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        String[] display = TelephonyProbe.buildTileDisplay();
        boolean active = display != null && display.length > 0 && "1".equals(display[0]);
        String label = (display != null && display.length > 1 && display[1] != null)
                ? display[1] : "VoWiFiX";
        String subtitle = (display != null && display.length > 2 && display[2] != null)
                ? display[2] : "";

        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
        }
        tile.updateTile();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Override
    public void onClick() {
        super.onClick();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    /**
     * 请求系统主动刷新一次快捷磁贴展示。
     */
    public static void requestTileRefresh(Context ctx) {
        try {
            TileService.requestListeningState(
                    ctx, new ComponentName(ctx, VoWiFiXTileService.class));
        } catch (Throwable ignored) {
        }
    }
}
