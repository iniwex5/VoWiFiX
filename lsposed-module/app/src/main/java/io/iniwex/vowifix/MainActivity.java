package io.iniwex.vowifix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VoWiFiX 控制台主界面：纯原生单页卡片流，自适应系统浅/深色主题。
 * 无 Root 时自动降级为只读状态展示模式；授权 Root 后提供一键热适配、热刷新、备份还原与切卡守护开关。
 */
public class MainActivity extends Activity {

    private static final String LAUNCHER_ALIAS_CLASS = "io.iniwex.vowifix.LauncherAlias";
    private static final String NOTICE_LOADING_EFS = "正在读取双卡与 EFS 状态...";
    private static final long REFRESH_COOLDOWN_MS = 45_000L;
    private static final long POST_RESET_GUARD_MS = 20_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private UiHelper.Palette palette;

    private TextView btnRefreshHeader;
    private TextView btnToggleLauncher;

    private TextView badgeLsposed;
    private TextView badgeRoot;
    private TextView badgeDiag;
    private TextView badgeWatcher;
    private TextView tvBaseband;
    private LinearLayout bannerNoRoot;
    private LinearLayout bannerNoLsposed;
    private Switch switchWatcher;
    private TextView tvWatcherHint;

    private Switch switchCellExemptCn;
    private final SlotViews[] slotViews = new SlotViews[2];

    private TextView tvProgressBanner;
    private TextView btnHotAdapt;
    private TextView btnRefreshModem;
    private TextView btnRestoreCellular;
    private TextView btnRestoreEfs;
    private TextView btnRadioInfo;

    private TextView btnClearLog;
    private TextView tvLogBody;

    private boolean busy = false;
    private boolean suppressSwitchEvent = false;
    private boolean firstResumeDone = false;
    private long lastRefreshElapsedMs = 0L;
    private TelephonyProbe.FullSnapshot latestSnapshot;
    private Runnable countdownRunnable;
    private Runnable postRefreshAutoProbeRunnable;
    private final Runnable[] postCellRefreshRunnables = new Runnable[3];

    private static final class SlotViews {
        LinearLayout box;
        TextView tvTitle;
        TextView badgeIms;
        TextView tvOperatorLine;
        TextView tvRoamingLine;
        TextView tvIniStatus;
        TextView tvSeStatus;
        TextView badgeCellBlock;
        Switch switchCellBlock;
        TextView tvCellBlockHint;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = UiHelper.Palette.resolve(this);
        applySystemBarsTheme();
        View content = buildContentView();
        setContentView(content);
        content.requestApplyInsets();
        updateLauncherToggleButton();
        refreshStateAsync(true, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!firstResumeDone) {
            firstResumeDone = true;
            return;
        }
        // 切回前台仅执行不触碰 Diag 通道的轻量刷新，避免高频读取基带
        if (!busy) {
            refreshStateAsync(false, true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCountdown();
        if (postRefreshAutoProbeRunnable != null) {
            mainHandler.removeCallbacks(postRefreshAutoProbeRunnable);
        }
        cancelPostCellRefresh();
        worker.shutdownNow();
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarsTheme() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setStatusBarColor(palette.bgPage);
        window.setNavigationBarColor(palette.bgPage);
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!applyLightBarsAppearance(decor)) {
                // DecorView 尚未 attach 时延迟到消息队列补设一次系统栏图标明暗
                decor.post(() -> applyLightBarsAppearance(decor));
            }
        } else {
            int flags = decor.getSystemUiVisibility();
            if (palette.isDark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private boolean applyLightBarsAppearance(View decor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        WindowInsetsController ctrl = decor.getWindowInsetsController();
        if (ctrl == null) {
            return false;
        }
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        ctrl.setSystemBarsAppearance(palette.isDark ? 0 : mask, mask);
        return true;
    }

    @SuppressWarnings("deprecation")
    private View buildContentView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.bgPage);
        scroll.setFillViewport(true);
        // 滚动裁切在系统栏安全区以内，避免内容向上滚动时穿过状态栏图标背后
        scroll.setClipToPadding(true);

        final int baseH = UiHelper.dp(this, 16);
        final int baseTop = UiHelper.dp(this, 14);
        final int baseBottom = UiHelper.dp(this, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(baseH, baseTop, baseH, baseBottom);

        root.addView(buildHeader());
        root.addView(buildOverviewCard());
        root.addView(buildSlotsCard());
        root.addView(buildActionsCard());
        root.addView(buildLogCard());

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, 0);
            root.setPadding(baseH, baseTop, baseH, baseBottom + bottom);
            return insets;
        });

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiHelper.dp(this, 14);
        bar.setLayoutParams(lp);

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.HORIZONTAL);
        titleGroup.setGravity(Gravity.CENTER_VERTICAL);
        titleGroup.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvApp = new TextView(this);
        tvApp.setText("VoWiFiX");
        tvApp.setTextColor(palette.textPrimary);
        tvApp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        tvApp.setTypeface(Typeface.DEFAULT_BOLD);
        titleGroup.addView(tvApp);

        TextView badgeVer = UiHelper.makeBadge(this, resolveVersionLabel(), palette.accent, palette.accentBg);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        verLp.leftMargin = UiHelper.dp(this, 8);
        badgeVer.setLayoutParams(verLp);
        titleGroup.addView(badgeVer);
        bar.addView(titleGroup);

        btnRefreshHeader = UiHelper.makeHeaderChip(this, palette, "刷新");
        btnRefreshHeader.setOnClickListener(v -> onManualRefreshClicked());
        bar.addView(btnRefreshHeader);

        btnToggleLauncher = UiHelper.makeHeaderChip(this, palette, "隐藏图标");
        LinearLayout.LayoutParams togLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        togLp.leftMargin = UiHelper.dp(this, 8);
        btnToggleLauncher.setLayoutParams(togLp);
        btnToggleLauncher.setOnClickListener(v -> onToggleLauncherIconClicked());
        bar.addView(btnToggleLauncher);

        return bar;
    }

    /**
     * 从当前应用包信息动态读取版本号标签（如 v1.0.10），避免界面硬编码滞后。
     */
    @SuppressWarnings("deprecation")
    private String resolveVersionLabel() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = pm.getPackageInfo(getPackageName(), 0);
            }
            if (info != null && !TextUtils.isEmpty(info.versionName)) {
                return info.versionName.startsWith("v") ? info.versionName : "v" + info.versionName;
            }
        } catch (Throwable ignored) {
        }
        return "v?";
    }

    private View buildOverviewCard() {
        LinearLayout card = UiHelper.makeCard(this, palette);
        card.addView(UiHelper.makeSectionTitle(this, palette, "运行环境与守护"));

        badgeLsposed = addStatusRow(card, "LSPosed 框架", "检测中...");
        badgeRoot = addStatusRow(card, "Root 与模块", "检测中...");
        badgeDiag = addStatusRow(card, "基带 Diag 通道", "检测中...");
        badgeWatcher = addStatusRow(card, "切卡守护进程", "检测中...");

        tvBaseband = new TextView(this);
        tvBaseband.setTextColor(palette.textMuted);
        tvBaseband.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvBaseband.setTypeface(Typeface.MONOSPACE);
        tvBaseband.setSingleLine(true);
        tvBaseband.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvBaseband.setText("基带: 读取中...");
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbLp.topMargin = UiHelper.dp(this, 4);
        tvBaseband.setLayoutParams(bbLp);
        card.addView(tvBaseband);

        bannerNoLsposed = makeNoticeBanner(
                palette.danger, palette.dangerBg,
                "LSPosed 模块尚未在当前进程生效。请在 LSPosed 管理器中启用 VoWiFiX，勾选推荐作用域并重启对应系统服务。");
        card.addView(bannerNoLsposed);

        bannerNoRoot = makeNoticeBanner(
                palette.warning, palette.warningBg,
                "当前未获得 Root 权限（只读状态模式）。如需修改 EFS、热刷新基带或控制守护进程，请在 KernelSU / APatch / Magisk 中为 VoWiFiX 授权 Root。");
        card.addView(bannerNoRoot);

        card.addView(UiHelper.makeDivider(this, palette, 10));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout switchTextCol = new LinearLayout(this);
        switchTextCol.setOrientation(LinearLayout.VERTICAL);
        switchTextCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvSwitchTitle = new TextView(this);
        tvSwitchTitle.setText("后台切卡自动监听");
        tvSwitchTitle.setTextColor(palette.textPrimary);
        tvSwitchTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvSwitchTitle.setTypeface(Typeface.DEFAULT_BOLD);
        switchTextCol.addView(tvSwitchTitle);

        tvWatcherHint = new TextView(this);
        tvWatcherHint.setText("插拔或切换 SIM 卡时自动修补并热刷新");
        tvWatcherHint.setTextColor(palette.textSecondary);
        tvWatcherHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        switchTextCol.addView(tvWatcherHint);

        switchWatcher = new Switch(this);
        switchWatcher.setOnCheckedChangeListener(this::onWatcherSwitchChanged);

        switchRow.addView(switchTextCol);
        switchRow.addView(switchWatcher);
        card.addView(switchRow);

        return card;
    }

    private TextView addStatusRow(LinearLayout parent, String label, String initialBadge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiHelper.dp(this, 8);
        row.setLayoutParams(lp);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(palette.textSecondary);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        TextView badge = UiHelper.makeBadge(this, initialBadge, palette.neutral, palette.neutralBg);
        row.addView(badge);
        parent.addView(row);
        return badge;
    }

    private LinearLayout makeNoticeBanner(int textColor, int bgColor, String msg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiHelper.makeRoundRect(bgColor, UiHelper.dpF(this, 10), 0, 0));
        int p = UiHelper.dp(this, 10);
        box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiHelper.dp(this, 10);
        box.setLayoutParams(lp);
        box.setVisibility(View.GONE);

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(tv);
        return box;
    }

    private View buildSlotsCard() {
        LinearLayout card = UiHelper.makeCard(this, palette);
        card.addView(UiHelper.makeSectionTitle(this, palette, "双卡与底层 EFS 状态"));

        LinearLayout exemptRow = new LinearLayout(this);
        exemptRow.setOrientation(LinearLayout.HORIZONTAL);
        exemptRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout exemptTextCol = new LinearLayout(this);
        exemptTextCol.setOrientation(LinearLayout.VERTICAL);
        exemptTextCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvExemptTitle = new TextView(this);
        tvExemptTitle.setText("自动豁免国内卡 (MCC=460)");
        tvExemptTitle.setTextColor(palette.textPrimary);
        tvExemptTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvExemptTitle.setTypeface(Typeface.DEFAULT_BOLD);
        exemptTextCol.addView(tvExemptTitle);

        TextView tvExemptHint = new TextView(this);
        tvExemptHint.setText("插入国内 460 卡自动恢复蜂窝，切回海外卡自动恢复屏蔽");
        tvExemptHint.setTextColor(palette.textSecondary);
        tvExemptHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        exemptTextCol.addView(tvExemptHint);

        switchCellExemptCn = new Switch(this);
        switchCellExemptCn.setChecked(true);
        switchCellExemptCn.setOnCheckedChangeListener(this::onCellExemptSwitchChanged);

        exemptRow.addView(exemptTextCol);
        exemptRow.addView(switchCellExemptCn);
        card.addView(exemptRow);
        card.addView(UiHelper.makeDivider(this, palette, 10));

        for (int i = 0; i < 2; i++) {
            final int slotIdx = i;
            SlotViews sv = new SlotViews();
            sv.box = new LinearLayout(this);
            sv.box.setOrientation(LinearLayout.VERTICAL);
            sv.box.setBackground(UiHelper.makeRoundRect(
                    palette.bgInset, UiHelper.dpF(this, 10),
                    Math.max(1, UiHelper.dp(this, 1)), palette.border));
            int p = UiHelper.dp(this, 12);
            sv.box.setPadding(p, p, p, p);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == 0) {
                boxLp.bottomMargin = UiHelper.dp(this, 10);
            }
            sv.box.setLayoutParams(boxLp);

            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            sv.tvTitle = new TextView(this);
            sv.tvTitle.setText("卡 " + (i + 1) + " · 检测中");
            sv.tvTitle.setTextColor(palette.textPrimary);
            sv.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sv.tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            sv.tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            topRow.addView(sv.tvTitle);

            sv.badgeIms = UiHelper.makeBadge(this, "等待", palette.neutral, palette.neutralBg);
            topRow.addView(sv.badgeIms);
            sv.box.addView(topRow);

            sv.tvOperatorLine = makeSlotSubLine(4);
            sv.tvRoamingLine = makeSlotSubLine(2);
            sv.tvIniStatus = makeSlotSubLine(4);
            sv.tvSeStatus = makeSlotSubLine(2);

            sv.box.addView(sv.tvOperatorLine);
            sv.box.addView(sv.tvRoamingLine);
            sv.box.addView(sv.tvIniStatus);
            sv.box.addView(sv.tvSeStatus);

            sv.box.addView(UiHelper.makeDivider(this, palette, 8));

            LinearLayout cellRow = new LinearLayout(this);
            cellRow.setOrientation(LinearLayout.HORIZONTAL);
            cellRow.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout cellLabelGroup = new LinearLayout(this);
            cellLabelGroup.setOrientation(LinearLayout.HORIZONTAL);
            cellLabelGroup.setGravity(Gravity.CENTER_VERTICAL);
            cellLabelGroup.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvCellTitle = new TextView(this);
            tvCellTitle.setText("禁蜂窝纯 VoWiFi");
            tvCellTitle.setTextColor(palette.textPrimary);
            tvCellTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvCellTitle.setTypeface(Typeface.DEFAULT_BOLD);
            cellLabelGroup.addView(tvCellTitle);

            sv.badgeCellBlock = UiHelper.makeBadge(this, "未开启", palette.neutral, palette.neutralBg);
            LinearLayout.LayoutParams cbBadgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cbBadgeLp.leftMargin = UiHelper.dp(this, 6);
            sv.badgeCellBlock.setLayoutParams(cbBadgeLp);
            cellLabelGroup.addView(sv.badgeCellBlock);

            sv.switchCellBlock = new Switch(this);
            sv.switchCellBlock.setOnCheckedChangeListener((btn, checked) -> onCellBlockSwitchChanged(slotIdx, checked));

            cellRow.addView(cellLabelGroup);
            cellRow.addView(sv.switchCellBlock);
            sv.box.addView(cellRow);

            sv.tvCellBlockHint = makeSlotSubLine(3);
            sv.tvCellBlockHint.setText("保持正常蜂窝自动选网（开启后将锁定空网 00101，保留完整 3GPP/VoWiFi 能力）");
            sv.tvCellBlockHint.setTextColor(palette.textMuted);
            sv.box.addView(sv.tvCellBlockHint);

            slotViews[i] = sv;
            card.addView(sv.box);
        }
        return card;
    }

    private TextView makeSlotSubLine(int topMarginDp) {
        TextView tv = new TextView(this);
        tv.setTextColor(palette.textSecondary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiHelper.dp(this, topMarginDp);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildActionsCard() {
        LinearLayout card = UiHelper.makeCard(this, palette);
        card.addView(UiHelper.makeSectionTitle(this, palette, "调试与维护"));

        tvProgressBanner = new TextView(this);
        tvProgressBanner.setTextColor(palette.info);
        tvProgressBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvProgressBanner.setTypeface(Typeface.DEFAULT_BOLD);
        tvProgressBanner.setBackground(UiHelper.makeRoundRect(
                palette.infoBg, UiHelper.dpF(this, 8), 0, 0));
        int bp = UiHelper.dp(this, 10);
        tvProgressBanner.setPadding(bp, bp, bp, bp);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = UiHelper.dp(this, 10);
        tvProgressBanner.setLayoutParams(pLp);
        tvProgressBanner.setVisibility(View.GONE);
        card.addView(tvProgressBanner);

        btnHotAdapt = UiHelper.makeActionButton(this, palette, "一键热适配", UiHelper.BTN_PRIMARY);
        btnRefreshModem = UiHelper.makeActionButton(this, palette, "仅触发基带热刷新", UiHelper.BTN_SECONDARY);
        btnRestoreCellular = UiHelper.makeActionButton(this, palette, "一键恢复全部蜂窝驻网", UiHelper.BTN_SECONDARY);
        btnRadioInfo = UiHelper.makeActionButton(this, palette, "打开系统 RadioInfo", UiHelper.BTN_SECONDARY);
        btnRestoreEfs = UiHelper.makeActionButton(this, palette, "还原原始 EFS 备份", UiHelper.BTN_DANGER);

        btnHotAdapt.setOnClickListener(v -> onHotAdaptClicked());
        btnRefreshModem.setOnClickListener(v -> onRefreshModemClicked());
        btnRestoreCellular.setOnClickListener(v -> onRestoreAllCellularClicked());
        btnRadioInfo.setOnClickListener(v -> onOpenRadioInfoClicked());
        btnRestoreEfs.setOnClickListener(v -> onRestoreEfsClicked());

        card.addView(makeButtonRow(btnHotAdapt, btnRefreshModem, 10));
        card.addView(makeButtonRow(btnRestoreCellular, btnRadioInfo, 10));
        btnRestoreEfs.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(btnRestoreEfs);
        return card;
    }

    private LinearLayout makeButtonRow(TextView left, TextView right, int bottomMarginDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.bottomMargin = UiHelper.dp(this, bottomMarginDp);
        row.setLayoutParams(rLp);

        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lLp.rightMargin = UiHelper.dp(this, 5);
        left.setLayoutParams(lLp);

        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rtLp.leftMargin = UiHelper.dp(this, 5);
        right.setLayoutParams(rtLp);

        row.addView(left);
        row.addView(right);
        return row;
    }

    private View buildLogCard() {
        LinearLayout card = UiHelper.makeCard(this, palette);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = UiHelper.dp(this, 10);
        header.setLayoutParams(hLp);

        TextView title = new TextView(this);
        title.setText("守护与调度日志");
        title.setTextColor(palette.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        btnClearLog = UiHelper.makeHeaderChip(this, palette, "清空日志");
        btnClearLog.setOnClickListener(v -> onClearLogClicked());
        header.addView(btnClearLog);

        card.addView(header);

        tvLogBody = new TextView(this);
        tvLogBody.setBackground(UiHelper.makeRoundRect(
                palette.bgInset, UiHelper.dpF(this, 10),
                Math.max(1, UiHelper.dp(this, 1)), palette.border));
        int p = UiHelper.dp(this, 10);
        tvLogBody.setPadding(p, p, p, p);
        tvLogBody.setTextColor(palette.textSecondary);
        tvLogBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvLogBody.setTypeface(Typeface.MONOSPACE);
        tvLogBody.setTextIsSelectable(true);
        tvLogBody.setText("正在加载日志...");
        card.addView(tvLogBody);

        return card;
    }

    private void onManualRefreshClicked() {
        if (busy) {
            return;
        }
        long sinceReset = SystemClock.elapsedRealtime() - lastRefreshElapsedMs;
        if (lastRefreshElapsedMs > 0 && sinceReset < POST_RESET_GUARD_MS) {
            long waitSec = (POST_RESET_GUARD_MS - sinceReset + 999L) / 1000L;
            showProgressNotice("基带热刷新稳定窗口中，已跳过 Diag 探测（" + waitSec + "s 后可完整读取）");
            refreshStateAsync(false, false);
            return;
        }
        hideProgressNotice();
        refreshStateAsync(true, false);
    }

    private void refreshStateAsync(boolean queryEfs, boolean preserveExistingEfs) {
        refreshStateAsync(queryEfs, preserveExistingEfs, false);
    }

    /**
     * 异步采集双卡与 EFS 状态并刷新界面；preserveNotice 为 true 时保留已有操作结果提示条。
     */
    private void refreshStateAsync(boolean queryEfs, boolean preserveExistingEfs, boolean preserveNotice) {
        final TelephonyProbe.EfsSlotStatus[] prevEfs = latestSnapshot != null ? latestSnapshot.efs : null;
        final boolean showLoadingNotice = !preserveExistingEfs && queryEfs && !preserveNotice;
        if (!preserveExistingEfs) {
            setBusy(true, showLoadingNotice ? NOTICE_LOADING_EFS : null);
        }
        worker.execute(() -> {
            TelephonyProbe.FullSnapshot snap = TelephonyProbe.collectSnapshot(queryEfs);
            if (!queryEfs && preserveExistingEfs && prevEfs != null && snap.hasRoot) {
                snap.efs = prevEfs;
            }
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                latestSnapshot = snap;
                if (!preserveExistingEfs) {
                    setBusy(false, null);
                    if (showLoadingNotice && tvProgressBanner != null) {
                        CharSequence cur = tvProgressBanner.getText();
                        if (cur != null && NOTICE_LOADING_EFS.equals(cur.toString())) {
                            hideProgressNotice();
                        }
                    }
                }
                renderSnapshot(snap);
                VoWiFiXTileService.requestTileRefresh(this);
            });
        });
    }

    private void renderSnapshot(TelephonyProbe.FullSnapshot snap) {
        if (snap.lsposedActive) {
            UiHelper.styleBadge(this, badgeLsposed, "已激活", palette.success, palette.successBg);
            bannerNoLsposed.setVisibility(View.GONE);
        } else {
            UiHelper.styleBadge(this, badgeLsposed, "未激活", palette.danger, palette.dangerBg);
            bannerNoLsposed.setVisibility(View.VISIBLE);
        }

        if (!snap.hasRoot) {
            UiHelper.styleBadge(this, badgeRoot, "无 Root (只读)", palette.warning, palette.warningBg);
            UiHelper.styleBadge(this, badgeDiag, "需 Root 探测", palette.neutral, palette.neutralBg);
            UiHelper.styleBadge(this, badgeWatcher, "需 Root 探测", palette.neutral, palette.neutralBg);
            bannerNoRoot.setVisibility(View.VISIBLE);
        } else {
            bannerNoRoot.setVisibility(View.GONE);
            if (snap.moduleInstalled && snap.binaryAvailable) {
                UiHelper.styleBadge(this, badgeRoot, "Root + 模块就绪", palette.success, palette.successBg);
            } else if (snap.moduleInstalled) {
                UiHelper.styleBadge(this, badgeRoot, "模块二进制缺失", palette.danger, palette.dangerBg);
            } else {
                UiHelper.styleBadge(this, badgeRoot, "Root 已授权 · 未装 KSU 模块", palette.warning, palette.warningBg);
            }

            if (!snap.binaryAvailable) {
                UiHelper.styleBadge(this, badgeDiag, "组件缺失", palette.danger, palette.dangerBg);
            } else if (snap.hasDiagBackend()) {
                UiHelper.styleBadge(this, badgeDiag, "已就绪 (" + snap.diagBackend + ")", palette.success, palette.successBg);
            } else {
                UiHelper.styleBadge(this, badgeDiag, "未开放 · 仅上层模式", palette.info, palette.infoBg);
            }

            if (!snap.watcherEnabled) {
                UiHelper.styleBadge(this, badgeWatcher, "已手动暂停", palette.warning, palette.warningBg);
            } else if (snap.watcherRunning) {
                UiHelper.styleBadge(this, badgeWatcher, "运行中", palette.success, palette.successBg);
            } else {
                UiHelper.styleBadge(this, badgeWatcher, "未运行", palette.danger, palette.dangerBg);
            }
        }

        String bbText = TextUtils.isEmpty(snap.baseband) ? "未就绪" : snap.baseband;
        tvBaseband.setText("基带: " + bbText);

        suppressSwitchEvent = true;
        switchWatcher.setChecked(snap.hasRoot ? snap.watcherEnabled : false);
        if (switchCellExemptCn != null) {
            switchCellExemptCn.setChecked(snap.cellExemptCn);
        }
        suppressSwitchEvent = false;
        switchWatcher.setEnabled(!busy && snap.hasRoot && snap.moduleInstalled);
        if (switchCellExemptCn != null) {
            switchCellExemptCn.setEnabled(!busy && snap.hasRoot);
        }
        if (!snap.hasRoot) {
            tvWatcherHint.setText("授予 Root 权限后可控制后台切卡守护");
        } else if (!snap.watcherEnabled) {
            tvWatcherHint.setText("已暂停后台自动监听，仍可下方手动热适配");
        } else if (snap.hasDiagBackend()) {
            tvWatcherHint.setText(snap.isHotRefreshUnsupported()
                    ? "切卡时自动合并 EFS 并落盘（本基带不支持热刷新，改动后需重启生效）"
                    : "插拔或切换 SIM 卡时自动修补并热刷新");
        } else {
            tvWatcherHint.setText(snap.isHotRefreshUnsupported()
                    ? "未开放 Diag 且无热刷新服务，切卡时自动校准 siminfo 策略"
                    : "未开放 Diag 通道，切卡时自动校准 siminfo 策略");
        }

        for (int i = 0; i < 2; i++) {
            renderSlot(slotViews[i], snap.slots[i], snap.efs[i], snap.cellBlock[i], snap.hasRoot);
        }

        if (!snap.hasRoot) {
            tvLogBody.setText("未获取 Root 权限，无法读取 " + RootShell.LOG_PATH);
        } else if (TextUtils.isEmpty(snap.serviceLog)) {
            tvLogBody.setText("暂无日志记录（" + RootShell.LOG_PATH + "）");
        } else {
            tvLogBody.setText(snap.serviceLog);
        }

        updateActionButtonsAvailability();
    }

    private int resolveEfsLineColor(String state) {
        if ("READ_ERROR".equals(state) || TelephonyProbe.EfsSlotStatus.STATE_READ_TIMEOUT.equals(state)) {
            return palette.danger;
        }
        if ("UNPATCHED".equals(state) || TelephonyProbe.EfsSlotStatus.STATE_LOCK_BUSY.equals(state)) {
            return palette.warning;
        }
        if (TelephonyProbe.EfsSlotStatus.STATE_NO_DIAG.equals(state)) {
            return palette.textMuted;
        }
        return palette.textSecondary;
    }

    private void renderSlot(
            SlotViews sv, TelephonyProbe.SlotSnapshot slot,
            TelephonyProbe.EfsSlotStatus efs,
            TelephonyProbe.CellBlockSlotStatus cb,
            boolean hasRoot) {
        int slotNo = slot.slotIndex + 1;
        if (slot.isSimAbsent()) {
            sv.tvTitle.setText("卡 " + slotNo + " · 未插卡");
            UiHelper.styleBadge(this, sv.badgeIms, "空卡槽", palette.neutral, palette.neutralBg);
            sv.tvOperatorLine.setText("SIM 状态: " + (slot.simState.isEmpty() ? "ABSENT" : slot.simState));
            sv.tvRoamingLine.setVisibility(View.GONE);
        } else {
            String alpha = slot.simAlpha.isEmpty() ? "未知运营商" : slot.simAlpha;
            String plmn = slot.simNumeric.isEmpty() ? "" : " (" + slot.simNumeric + ")";
            sv.tvTitle.setText("卡 " + slotNo + " · " + alpha + plmn);

            if (slot.isVoWiFi()) {
                UiHelper.styleBadge(this, sv.badgeIms, "VoWiFi", palette.success, palette.successBg);
            } else if (slot.isVoLte()) {
                UiHelper.styleBadge(this, sv.badgeIms, slot.getImsBadgeText(), palette.info, palette.infoBg);
            } else {
                UiHelper.styleBadge(this, sv.badgeIms, "IMS 未注册", palette.neutral, palette.neutralBg);
            }

            String iso = slot.simIso.isEmpty() ? "--" : slot.simIso.toUpperCase(Locale.US);
            String netType = slot.netType.isEmpty() ? "--" : slot.netType;
            sv.tvOperatorLine.setText("归属国家: " + iso + "  ·  SIM: " + slot.simState + "  ·  网络: " + netType);

            sv.tvRoamingLine.setVisibility(View.VISIBLE);
            if (hasRoot && cb.isZeroCellularRegistered()) {
                if (slot.isVoWiFi() || "IWLAN".equalsIgnoreCase(slot.netType)) {
                    sv.tvRoamingLine.setText("漫游状态: 未驻留蜂窝网络（仅 Wi-Fi / 零驻网）");
                    sv.tvRoamingLine.setTextColor(palette.textSecondary);
                } else {
                    sv.tvRoamingLine.setText("漫游状态: 未驻留蜂窝网络（无服务）");
                    sv.tvRoamingLine.setTextColor(palette.textMuted);
                }
            } else if (slot.isRoamingOnCellular()) {
                String hostNet = slot.netAlpha.isEmpty() ? slot.netNumeric : slot.netAlpha + " (" + slot.netNumeric + ")";
                sv.tvRoamingLine.setText("漫游状态: 国际漫游中 · 驻留 " + hostNet);
                sv.tvRoamingLine.setTextColor(palette.warning);
            } else if (slot.isHomeCellular()) {
                sv.tvRoamingLine.setText("漫游状态: 本地网络");
                sv.tvRoamingLine.setTextColor(palette.textSecondary);
            } else if (!slot.hasCellularNetwork()) {
                if (slot.isIwlanOnly()) {
                    sv.tvRoamingLine.setText("漫游状态: 未驻留蜂窝网络（仅 Wi-Fi）");
                    sv.tvRoamingLine.setTextColor(palette.textSecondary);
                } else {
                    sv.tvRoamingLine.setText("漫游状态: 未驻留蜂窝网络（无服务）");
                    sv.tvRoamingLine.setTextColor(palette.textMuted);
                }
            } else {
                String hostNet = slot.netAlpha.isEmpty() ? slot.netNumeric : slot.netAlpha + " (" + slot.netNumeric + ")";
                sv.tvRoamingLine.setText("漫游状态: 驻留 " + hostNet);
                sv.tvRoamingLine.setTextColor(palette.textSecondary);
            }
        }

        suppressSwitchEvent = true;
        sv.switchCellBlock.setChecked(hasRoot && cb.ruleEnabled);
        suppressSwitchEvent = false;
        sv.switchCellBlock.setEnabled(!busy && hasRoot);

        if (!hasRoot) {
            sv.tvIniStatus.setText("配置合并: 需要 Root 权限");
            sv.tvIniStatus.setTextColor(palette.textMuted);
            sv.tvSeStatus.setText("漫游解锁: 需要 Root 权限");
            sv.tvSeStatus.setTextColor(palette.textMuted);
            UiHelper.styleBadge(this, sv.badgeCellBlock, "需 Root", palette.neutral, palette.neutralBg);
            sv.tvCellBlockHint.setText("授予 Root 权限后可控制单卡禁蜂窝纯 VoWiFi");
            sv.tvCellBlockHint.setTextColor(palette.textMuted);
            return;
        }

        sv.tvIniStatus.setText(efs.getIniSummaryText());
        sv.tvIniStatus.setTextColor(resolveEfsLineColor(efs.iniState));

        sv.tvSeStatus.setText(efs.getSeSummaryText());
        sv.tvSeStatus.setTextColor(resolveEfsLineColor(efs.seState));

        renderCellBlockSlot(sv, slot, cb);
    }

    private void renderCellBlockSlot(
            SlotViews sv, TelephonyProbe.SlotSnapshot slot, TelephonyProbe.CellBlockSlotStatus cb) {
        if (!cb.ruleEnabled) {
            UiHelper.styleBadge(this, sv.badgeCellBlock, "未开启", palette.neutral, palette.neutralBg);
            sv.tvCellBlockHint.setText("保持正常蜂窝自动选网（开启后将锁定空网 "
                    + CellBlockConfig.BLOCK_PLMN + "，保留完整 3GPP/VoWiFi 能力）");
            sv.tvCellBlockHint.setTextColor(palette.textMuted);
            return;
        }

        if (cb.exemptCnActive) {
            UiHelper.styleBadge(this, sv.badgeCellBlock, "已豁免 (460)", palette.info, palette.infoBg);
            sv.tvCellBlockHint.setText("规则已开启 · 国内卡 (460) 自动豁免恢复蜂窝，切回海外卡自动锁定空网");
            sv.tvCellBlockHint.setTextColor(palette.info);
            return;
        }

        if (slot.isSimAbsent()) {
            UiHelper.styleBadge(this, sv.badgeCellBlock, "规则开启 · 无卡", palette.neutral, palette.neutralBg);
            sv.tvCellBlockHint.setText("物理卡槽已绑定禁蜂窝规则，插卡后将自动拦截非豁免海外卡");
            sv.tvCellBlockHint.setTextColor(palette.textMuted);
            return;
        }

        if (latestSnapshot != null && !latestSnapshot.lsposedActive) {
            UiHelper.styleBadge(this, sv.badgeCellBlock, "需启用 Hook", palette.warning, palette.warningBg);
            sv.tvCellBlockHint.setText("需在 LSPosed 启用 VoWiFiX 模块并勾选「电话服务」方可生效");
            sv.tvCellBlockHint.setTextColor(palette.warning);
            return;
        }

        if (cb.isManualZeroCellularLocked()) {
            UiHelper.styleBadge(this, sv.badgeCellBlock, "零驻网生效", palette.success, palette.successBg);
            sv.tvCellBlockHint.setText("已锁定空网 " + CellBlockConfig.BLOCK_PLMN + " · WWAN 零蜂窝附着（VoWiFi 通话正常）");
            sv.tvCellBlockHint.setTextColor(palette.success);
        } else if (cb.isZeroCellularRegistered()) {
            UiHelper.styleBadge(this, sv.badgeCellBlock, "规则已启用", palette.warning, palette.warningBg);
            sv.tvCellBlockHint.setText("当前无蜂窝信号 · 等待锁定手动空网 " + CellBlockConfig.BLOCK_PLMN);
            sv.tvCellBlockHint.setTextColor(palette.warning);
        } else {
            UiHelper.styleBadge(this, sv.badgeCellBlock, "规则已启用", palette.warning, palette.warningBg);
            String wwanDesc = !cb.wwanPlmn.isEmpty()
                    ? "WWAN 驻留 " + cb.wwanPlmn
                    : (!cb.wwanVoiceReg.isEmpty() || !cb.wwanDataReg.isEmpty()
                            ? "WWAN CS=" + cb.wwanVoiceReg + " PS=" + cb.wwanDataReg
                            : "正在锁定空网");
            sv.tvCellBlockHint.setText("正在锁定空网 " + CellBlockConfig.BLOCK_PLMN + " · " + wwanDesc);
            sv.tvCellBlockHint.setTextColor(palette.warning);
        }
    }

    private void onCellExemptSwitchChanged(CompoundButton buttonView, boolean isChecked) {
        if (suppressSwitchEvent || busy || latestSnapshot == null || !latestSnapshot.hasRoot) {
            return;
        }
        handleCellRuleChangeRequest(
                latestSnapshot.cellBlock[0].ruleEnabled,
                latestSnapshot.cellBlock[1].ruleEnabled,
                isChecked);
    }

    private void onCellBlockSwitchChanged(int slotIndex, boolean isChecked) {
        if (suppressSwitchEvent || busy || latestSnapshot == null || !latestSnapshot.hasRoot) {
            return;
        }
        boolean nextS0 = slotIndex == 0 ? isChecked : latestSnapshot.cellBlock[0].ruleEnabled;
        boolean nextS1 = slotIndex == 1 ? isChecked : latestSnapshot.cellBlock[1].ruleEnabled;
        handleCellRuleChangeRequest(nextS0, nextS1, latestSnapshot.cellExemptCn);
    }

    private void syncCellSwitchesFromSnapshot() {
        if (latestSnapshot == null) {
            return;
        }
        suppressSwitchEvent = true;
        if (switchCellExemptCn != null) {
            switchCellExemptCn.setChecked(latestSnapshot.cellExemptCn);
        }
        for (int i = 0; i < 2; i++) {
            if (slotViews[i] != null && slotViews[i].switchCellBlock != null) {
                slotViews[i].switchCellBlock.setChecked(
                        latestSnapshot.hasRoot && latestSnapshot.cellBlock[i].ruleEnabled);
            }
        }
        suppressSwitchEvent = false;
    }

    private void handleCellRuleChangeRequest(
            final boolean nextS0, final boolean nextS1, final boolean nextExemptCn) {
        boolean bothSlotsPresent = !latestSnapshot.slots[0].isSimAbsent()
                && !latestSnapshot.slots[1].isSimAbsent();
        boolean s0Exempt = nextExemptCn
                && latestSnapshot.slots[0].isSimLoaded()
                && CellBlockConfig.isChinaMainlandPlmn(latestSnapshot.slots[0].simNumeric);
        boolean s1Exempt = nextExemptCn
                && latestSnapshot.slots[1].isSimLoaded()
                && CellBlockConfig.isChinaMainlandPlmn(latestSnapshot.slots[1].simNumeric);

        if (bothSlotsPresent && nextS0 && nextS1 && !s0Exempt && !s1Exempt) {
            syncCellSwitchesFromSnapshot();
            new AlertDialog.Builder(this)
                    .setTitle("警告：双卡将同时断开蜂窝网络")
                    .setMessage("当前两张卡槽均未处于国内卡 (460) 豁免状态。\n\n"
                            + "同时开启双卡「禁蜂窝纯 VoWiFi」后，离开 Wi-Fi 期间整机双卡将彻底断网，无法接打电话、收发短信、使用移动数据，也无法为新基带提供跨卡流量。\n\n"
                            + "确定仍要同时对卡 1 与卡 2 开启禁蜂窝吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("仍然开启", (d, w) ->
                            applyCellBlockConfigAsync(nextS0, nextS1, nextExemptCn))
                    .show();
            return;
        }
        applyCellBlockConfigAsync(nextS0, nextS1, nextExemptCn);
    }

    private void applyCellBlockConfigAsync(
            final boolean slot0Block, final boolean slot1Block, final boolean exemptCn) {
        if (busy || latestSnapshot == null || !latestSnapshot.hasRoot) {
            return;
        }
        setBusy(true, "正在同步单卡禁蜂窝策略...");
        worker.execute(() -> {
            if (TelephonyProbe.isCallActive()) {
                mainHandler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false, "检测到正在通话或来电，已暂缓变更蜂窝选网状态");
                    syncCellSwitchesFromSnapshot();
                });
                return;
            }
            String script = CellBlockConfig.buildApplyRulesShellScript(slot0Block, slot1Block, exemptCn, "ui");
            RootShell.Result r = RootShell.execRoot(script, 20);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                setBusy(false, r.isSuccess()
                        ? "已更新单卡禁蜂窝规则"
                        : "更新禁蜂窝规则失败: " + summarizeLastLine(r.stderr));
                refreshStateAsync(false, true, true);
                schedulePostCellActionRefresh();
            });
        });
    }

    private void onRestoreAllCellularClicked() {
        if (busy || latestSnapshot == null || !latestSnapshot.hasRoot) {
            return;
        }
        setBusy(true, "正在检查通话状态并恢复全部蜂窝驻网...");
        worker.execute(() -> {
            if (TelephonyProbe.isCallActive()) {
                mainHandler.post(() -> {
                    if (!isDestroyed()) {
                        setBusy(false, "检测到正在通话或来电，已暂缓恢复自动选网");
                    }
                });
                return;
            }
            RootShell.Result r = RootShell.execRoot(CellBlockConfig.buildRestoreAllShellScript("ui"), 20);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                setBusy(false, r.isSuccess()
                        ? "已一键恢复全部卡槽全自动选网与正常蜂窝驻网"
                        : "恢复蜂窝驻网失败: " + summarizeLastLine(r.stderr));
                refreshStateAsync(false, true, true);
                schedulePostCellActionRefresh();
            });
        });
    }

    private void cancelPostCellRefresh() {
        for (int i = 0; i < postCellRefreshRunnables.length; i++) {
            if (postCellRefreshRunnables[i] != null) {
                mainHandler.removeCallbacks(postCellRefreshRunnables[i]);
                postCellRefreshRunnables[i] = null;
            }
        }
    }

    private void schedulePostCellActionRefresh() {
        cancelPostCellRefresh();
        final long[] delaysMs = new long[]{1500L, 4500L, 9000L};
        for (int i = 0; i < postCellRefreshRunnables.length && i < delaysMs.length; i++) {
            final int idx = i;
            postCellRefreshRunnables[idx] = () -> {
                postCellRefreshRunnables[idx] = null;
                if (!isDestroyed() && !busy) {
                    refreshStateAsync(false, true, true);
                }
            };
            mainHandler.postDelayed(postCellRefreshRunnables[idx], delaysMs[idx]);
        }
    }

    private void onWatcherSwitchChanged(CompoundButton buttonView, boolean isChecked) {
        if (suppressSwitchEvent || busy) {
            return;
        }
        setBusy(true, isChecked ? "正在启用后台切卡监听..." : "正在暂停后台切卡监听...");
        worker.execute(() -> {
            String ts = "$(date '+%Y-%m-%d %H:%M:%S')";
            String script;
            if (isChecked) {
                script = "rm -f '" + RootShell.DISABLED_FLAG + "' && "
                        + "mkdir -p '" + RootShell.DATA_DIR + "' && "
                        + "echo \"[" + ts + "] INFO: [ui] watcher enabled by user\" >> '" + RootShell.LOG_PATH + "'";
            } else {
                script = "mkdir -p '" + RootShell.DATA_DIR + "' && "
                        + ": > '" + RootShell.DISABLED_FLAG + "' && "
                        + "echo \"[" + ts + "] INFO: [ui] watcher paused by user\" >> '" + RootShell.LOG_PATH + "'";
            }
            RootShell.Result r = RootShell.execRoot(script, 8);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                setBusy(false, null);
                hideProgressNotice();
                if (!r.isSuccess()) {
                    Toast.makeText(this, "切换守护状态失败: " + r.stderr, Toast.LENGTH_SHORT).show();
                }
                refreshStateAsync(false, true);
            });
        });
    }

    private void onHotAdaptClicked() {
        if (busy || latestSnapshot == null || !latestSnapshot.hasRoot || !latestSnapshot.binaryAvailable) {
            return;
        }
        long remainCooldown = getRemainingRefreshCooldownSec();
        if (remainCooldown > 0) {
            showProgressNotice("冷却保护中：请等待 " + remainCooldown + "s 后再试，避免连续刷新打断基带");
            return;
        }

        final boolean hasDiag = latestSnapshot.hasDiagBackend();
        setBusy(true, hasDiag
                ? "正在检查通话状态并准备 EFS 热适配..."
                : "正在检查通话状态并准备上层与 QRTR 热适配...");
        worker.execute(() -> {
            if (TelephonyProbe.isCallActive()) {
                mainHandler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false, "检测到正在通话或来电，已拦截热适配以防断话");
                });
                return;
            }

            final String inner;
            if (hasDiag) {
                inner = RootShell.resolveBinarySnippet()
                        + "[ -n \"$BIN\" ] || { echo 'ERR_NO_BIN'; exit 1; }\n"
                        + "OUT=$(\"$BIN\" patch all --no-lock --timeout 30 --backup-dir '" + RootShell.DATA_DIR + "' 2>&1)\n"
                        + "PRC=$?\n"
                        + "printf '%s\\n' \"$OUT\"\n"
                        + "SIM_OUT=$(\n"
                        + TelephonyProbe.normalizeSiminfoShellSnippet()
                        + ")\n"
                        + "printf '%s\\n' \"$SIM_OUT\"\n"
                        + "echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] patch RC=$PRC | $(echo \"$OUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
                        + "[ \"$PRC\" -eq 0 ] || exit $PRC\n"
                        + "HAS_EFS_CHG=0\n"
                        + "echo \"$OUT\" | grep -qE 'CHANGED=[1-9]' && HAS_EFS_CHG=1\n"
                        + "HAS_SIM_CHG=0\n"
                        + "echo \"$SIM_OUT\" | grep -q 'SIMINFO_NORMALIZED=' && HAS_SIM_CHG=1\n"
                        + "if [ \"$HAS_EFS_CHG\" -eq 1 ]; then\n"
                        + "  echo 'VOWIFIX_PHASE:WAIT_FLUSH_6S'\n"
                        + "  sleep 6\n"
                        + "  if dumpsys telephony.registry 2>/dev/null | grep -qE 'mCallState=[12]'; then\n"
                        + "    echo 'ERR_CALL_DURING_WAIT'\n"
                        + "    exit 2\n"
                        + "  fi\n"
                        + "  echo 'VOWIFIX_PHASE:REFRESH_START'\n"
                        + "  ROUT=$(\"$BIN\" refresh --no-lock 2>&1)\n"
                        + "  RRC=$?\n"
                        + "  printf '%s\\n' \"$ROUT\"\n"
                        + "  echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] refresh RRC=$RRC | $(echo \"$ROUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
                        + "  if [ \"$RRC\" -eq 98 ] || printf '%s\\n' \"$ROUT\" | grep -q 'EFS_REFRESH_UNSUPPORTED=1'; then\n"
                        + "    echo 'VOWIFIX_EFS_NEED_REBOOT=1'\n"
                        + "    exit 0\n"
                        + "  fi\n"
                        + "  [ \"$RRC\" -eq 0 ] && echo 'VOWIFIX_REFRESHED=1'\n"
                        + "  exit $RRC\n"
                        + "elif [ \"$HAS_SIM_CHG\" -eq 1 ]; then\n"
                        + "  echo 'VOWIFIX_PHASE:REFRESH_START'\n"
                        + "  ROUT=$(\"$BIN\" refresh --no-lock 2>&1)\n"
                        + "  RRC=$?\n"
                        + "  printf '%s\\n' \"$ROUT\"\n"
                        + "  echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] siminfo-only refresh RRC=$RRC | $(echo \"$ROUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
                        + "  if [ \"$RRC\" -eq 98 ] || printf '%s\\n' \"$ROUT\" | grep -q 'EFS_REFRESH_UNSUPPORTED=1'; then\n"
                        + "    echo 'VOWIFIX_SIMINFO_NEED_REBOOT=1'\n"
                        + "    exit 0\n"
                        + "  fi\n"
                        + "  [ \"$RRC\" -eq 0 ] && echo 'VOWIFIX_REFRESHED=1'\n"
                        + "  exit $RRC\n"
                        + "else\n"
                        + "  echo 'VOWIFIX_ALREADY_CLEAN=1'\n"
                        + "fi\n";
            } else {
                // 无 Diag 通道（如 GKI 新平台）时降级为校准 siminfo + 纯内核 QRTR 热刷新
                inner = RootShell.resolveBinarySnippet()
                        + "[ -n \"$BIN\" ] || { echo 'ERR_NO_BIN'; exit 1; }\n"
                        + "SIM_OUT=$(\n"
                        + TelephonyProbe.normalizeSiminfoShellSnippet()
                        + ")\n"
                        + "printf '%s\\n' \"$SIM_OUT\"\n"
                        + "echo 'VOWIFIX_PHASE:REFRESH_START'\n"
                        + "ROUT=$(\"$BIN\" refresh --no-lock 2>&1)\n"
                        + "RRC=$?\n"
                        + "printf '%s\\n' \"$ROUT\"\n"
                        + "echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] upper-only adapt (backend=none) RRC=$RRC | $(echo \"$ROUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
                        + "if [ \"$RRC\" -eq 98 ] || printf '%s\\n' \"$ROUT\" | grep -q 'EFS_REFRESH_UNSUPPORTED=1'; then\n"
                        + "  echo 'VOWIFIX_SIMINFO_NEED_REBOOT=1'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "[ \"$RRC\" -eq 0 ] && echo 'VOWIFIX_REFRESHED=1'\n"
                        + "exit $RRC\n";
            }

            RootShell.Result r = RootShell.execRootStreaming(
                    RootShell.wrapWithDiagLock(inner),
                    65,
                    line -> mainHandler.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        if ("VOWIFIX_PHASE:WAIT_FLUSH_6S".equals(line)) {
                            startFlushCountdown("已写入并校验 EFS，严格等待落盘");
                        } else if ("VOWIFIX_PHASE:REFRESH_START".equals(line)) {
                            stopCountdown();
                            showProgressNotice("正在下发 QRTR QMI 基带热刷新...");
                        }
                    })
            );

            mainHandler.post(() -> finishModemTx(r, "一键热适配"));
        });
    }

    private void onRefreshModemClicked() {
        if (busy || latestSnapshot == null || !latestSnapshot.hasRoot || !latestSnapshot.binaryAvailable) {
            return;
        }
        long remainCooldown = getRemainingRefreshCooldownSec();
        if (remainCooldown > 0) {
            showProgressNotice("冷却保护中：请等待 " + remainCooldown + "s 后再试，避免连续刷新打断基带");
            return;
        }

        setBusy(true, "正在检查通话状态并触发基带热刷新...");
        worker.execute(() -> {
            if (TelephonyProbe.isCallActive()) {
                mainHandler.post(() -> {
                    if (!isDestroyed()) {
                        setBusy(false, "检测到正在通话或来电，已拦截刷新以防断话");
                    }
                });
                return;
            }

            String inner = RootShell.resolveBinarySnippet()
                    + "[ -n \"$BIN\" ] || { echo 'ERR_NO_BIN'; exit 1; }\n"
                    + "ROUT=$(\"$BIN\" refresh --no-lock 2>&1)\n"
                    + "RRC=$?\n"
                    + "printf '%s\\n' \"$ROUT\"\n"
                    + "echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] standalone refresh RRC=$RRC | $(echo \"$ROUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
                    + "[ \"$RRC\" -eq 0 ] && echo 'VOWIFIX_REFRESHED=1'\n"
                    + "exit $RRC\n";

            RootShell.Result r = RootShell.execRoot(RootShell.wrapWithDiagLock(inner), 35);
            mainHandler.post(() -> finishModemTx(r, "基带热刷新"));
        });
    }

    private void onRestoreEfsClicked() {
        if (busy || latestSnapshot == null || !latestSnapshot.hasRoot
                || !latestSnapshot.binaryAvailable || !latestSnapshot.hasDiagBackend()) {
            return;
        }
        String extraNote = latestSnapshot.isHotRefreshUnsupported()
                ? "\n\n当前基带不支持免重启热刷新，还原落盘后需重启手机 1 次生效。"
                : "\n\n若发生写入变更，将严格等待 6 秒落盘后再尝试热刷新基带。";
        new AlertDialog.Builder(this)
                .setTitle("还原原始 EFS 备份")
                .setMessage("确定要将修改过的配置与漫游开关字节恢复为首次备份的原始状态吗？" + extraNote)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定还原", (d, w) -> executeRestoreEfs())
                .show();
    }

    private void executeRestoreEfs() {
        setBusy(true, "正在检查通话状态并还原 EFS 原始备份...");
        worker.execute(() -> {
            if (TelephonyProbe.isCallActive()) {
                mainHandler.post(() -> {
                    if (!isDestroyed()) {
                        setBusy(false, "检测到正在通话或来电，已拦截还原以防断话");
                    }
                });
                return;
            }

            final boolean allowRefresh = getRemainingRefreshCooldownSec() <= 0;
            String inner = RootShell.resolveBinarySnippet()
                    + "[ -n \"$BIN\" ] || { echo 'ERR_NO_BIN'; exit 1; }\n"
                    + "DET=$(\"$BIN\" detect 2>/dev/null | head -n 1)\n"
                    + "BKD=\"${DET#BACKEND=}\"\n"
                    + "[ -n \"$BKD\" ] && [ \"$BKD\" != 'none' ] || { echo 'ERR_NO_DIAG'; exit 1; }\n"
                    + "OUT=$(\"$BIN\" restore all --no-lock --timeout 30 --backup-dir '" + RootShell.DATA_DIR + "' 2>&1)\n"
                    + "PRC=$?\n"
                    + "printf '%s\\n' \"$OUT\"\n"
                    + "echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] restore RC=$PRC | $(echo \"$OUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
                    + "[ \"$PRC\" -eq 0 ] || exit $PRC\n"
                    + "if echo \"$OUT\" | grep -qE 'CHANGED=[1-9]'; then\n"
                    + "  echo 'VOWIFIX_PHASE:WAIT_FLUSH_6S'\n"
                    + "  sleep 6\n"
                    + (allowRefresh
                    ? "  if dumpsys telephony.registry 2>/dev/null | grep -qE 'mCallState=[12]'; then\n"
                    + "    echo 'ERR_CALL_DURING_WAIT'; exit 2\n"
                    + "  fi\n"
                    + "  echo 'VOWIFIX_PHASE:REFRESH_START'\n"
                    + "  ROUT=$(\"$BIN\" refresh --no-lock 2>&1)\n"
                    + "  RRC=$?\n"
                    + "  printf '%s\\n' \"$ROUT\"\n"
                    + "  echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] post-restore refresh RRC=$RRC | $(echo \"$ROUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
                    + "  if [ \"$RRC\" -eq 98 ] || printf '%s\\n' \"$ROUT\" | grep -q 'EFS_REFRESH_UNSUPPORTED=1'; then\n"
                    + "    echo 'VOWIFIX_RESTORE_NEED_REBOOT=1'\n"
                    + "    exit 0\n"
                    + "  fi\n"
                    + "  [ \"$RRC\" -eq 0 ] && echo 'VOWIFIX_REFRESHED=1'\n"
                    + "  exit $RRC\n"
                    : "  echo 'VOWIFIX_RESTORED_COOLDOWN=1'\n")
                    + "else\n"
                    + "  echo 'VOWIFIX_ALREADY_CLEAN=1'\n"
                    + "fi\n";

            RootShell.Result r = RootShell.execRootStreaming(
                    RootShell.wrapWithDiagLock(inner),
                    65,
                    line -> mainHandler.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        if ("VOWIFIX_PHASE:WAIT_FLUSH_6S".equals(line)) {
                            startFlushCountdown("已还原备份并同步，严格等待落盘");
                        } else if ("VOWIFIX_PHASE:REFRESH_START".equals(line)) {
                            stopCountdown();
                            showProgressNotice("正在下发 QRTR QMI 基带热刷新...");
                        }
                    })
            );

            mainHandler.post(() -> finishModemTx(r, "还原 EFS 备份"));
        });
    }

    private void finishModemTx(RootShell.Result r, String actionName) {
        if (isDestroyed()) {
            return;
        }
        stopCountdown();
        boolean refreshed = r.stdout.contains("VOWIFIX_REFRESHED=1");
        if (refreshed) {
            lastRefreshElapsedMs = SystemClock.elapsedRealtime();
            schedulePostRefreshProbe();
        }

        if (r.stdout.contains("DIAG_LOCK_BUSY")) {
            setBusy(false, "后台守护进程正在访问基带通道，请稍后再试");
            refreshStateAsync(false, true);
            return;
        }
        if (r.stdout.contains("ERR_CALL_DURING_WAIT")) {
            setBusy(false, "落盘等待期间检测到通话，已暂缓触发热刷新");
            refreshStateAsync(false, true);
            return;
        }
        if (r.exitCode == RootShell.EXIT_TIMEOUT
                || r.stdout.contains("DIAG_READ_TIMEOUT")
                || r.stderr.contains("DIAG_READ_TIMEOUT")) {
            if (latestSnapshot != null) {
                latestSnapshot.efs[0].iniState = TelephonyProbe.EfsSlotStatus.STATE_READ_TIMEOUT;
                latestSnapshot.efs[0].seState = TelephonyProbe.EfsSlotStatus.STATE_READ_TIMEOUT;
                latestSnapshot.efs[1].iniState = TelephonyProbe.EfsSlotStatus.STATE_READ_TIMEOUT;
                latestSnapshot.efs[1].seState = TelephonyProbe.EfsSlotStatus.STATE_READ_TIMEOUT;
            }
            setBusy(false, actionName + "读取基带超时 (exit=" + r.exitCode
                    + ")，已释放锁；如设备不兼容可将 BACKEND=none 写入 " + RootShell.BACKEND_CONF_PATH);
            /* 避免刚发生 Diag 通道超时后又立刻自动发起一轮阻塞式 EFS 读取 */
            refreshStateAsync(false, false, true);
            return;
        }
        if (r.stdout.contains("VOWIFIX_EFS_NEED_REBOOT=1")) {
            setBusy(false, "EFS 已写入并完成 6s 落盘，当前基带不支持免重启热刷新（无 QMI_VS 0xe5），请重启手机 1 次生效");
            refreshStateAsync(true, false, true);
            return;
        }
        if (r.stdout.contains("VOWIFIX_RESTORE_NEED_REBOOT=1")) {
            setBusy(false, "已还原原始 EFS 备份并完成 6s 落盘，当前基带不支持免重启热刷新，请重启手机 1 次生效");
            refreshStateAsync(true, false, true);
            return;
        }
        if (r.stdout.contains("VOWIFIX_SIMINFO_NEED_REBOOT=1")) {
            setBusy(false, "已校准 siminfo 策略，当前基带不支持免重启热刷新，请重启手机或开关飞行模式生效");
            refreshStateAsync(false, true, true);
            return;
        }
        if (r.exitCode == RootShell.EXIT_REFRESH_UNSUPPORTED
                || r.stdout.contains("EFS_REFRESH_UNSUPPORTED=1")) {
            setBusy(false, "当前基带未提供 QMI_VS (0xe5) 服务，不支持免重启热刷新；修改 EFS 后重启手机 1 次即可生效");
            refreshStateAsync(false, true, true);
            return;
        }
        if (!r.isSuccess()) {
            String err = r.stderr.isEmpty() ? r.stdout : r.stderr;
            setBusy(false, actionName + "失败 (exit=" + r.exitCode + "): " + summarizeLastLine(err));
            refreshStateAsync(!refreshed, refreshed, true);
            return;
        }

        if (refreshed) {
            boolean hasDiag = latestSnapshot != null && latestSnapshot.hasDiagBackend();
            setBusy(false, hasDiag
                    ? actionName + "完成，基带正在重注册（20 秒后自动刷新 EFS 状态）"
                    : actionName + "完成（已校准 siminfo 并触发 QRTR 基带重载）");
            refreshStateAsync(false, true);
        } else if (r.stdout.contains("VOWIFIX_RESTORED_COOLDOWN=1")) {
            setBusy(false, "已还原原始备份并完成 6s 落盘（45s 刷新冷却中，请稍后手动刷新或重启）");
            refreshStateAsync(true, false, true);
        } else if (r.stdout.contains("VOWIFIX_ALREADY_CLEAN=1")) {
            boolean noHotRefresh = latestSnapshot != null && latestSnapshot.isHotRefreshUnsupported();
            setBusy(false, noHotRefresh
                    ? "当前 EFS 与 siminfo 状态均已对齐，无需改动（若刚修改过基带配置，请重启手机 1 次生效）"
                    : "当前状态已对齐，无需改动（若需强制重载可点“仅触发基带热刷新”）");
            refreshStateAsync(true, false, true);
        } else {
            setBusy(false, actionName + "已执行完成");
            refreshStateAsync(true, false, true);
        }
    }

    private void schedulePostRefreshProbe() {
        if (postRefreshAutoProbeRunnable != null) {
            mainHandler.removeCallbacks(postRefreshAutoProbeRunnable);
        }
        postRefreshAutoProbeRunnable = () -> {
            if (!isDestroyed() && !busy) {
                hideProgressNotice();
                refreshStateAsync(true, false);
            }
        };
        mainHandler.postDelayed(postRefreshAutoProbeRunnable, POST_RESET_GUARD_MS + 1000L);
    }

    private void onOpenRadioInfoClicked() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }

        if (latestSnapshot != null && latestSnapshot.hasRoot) {
            worker.execute(() -> {
                RootShell.Result r = RootShell.execRoot(
                        "am start -n com.android.phone/.settings.RadioInfo", 6);
                mainHandler.post(() -> {
                    if (!isDestroyed() && !r.isSuccess()) {
                        Toast.makeText(this, "无法直接打开 RadioInfo，可在拨号盘输入 *#*#4636#*#*", Toast.LENGTH_LONG).show();
                    }
                });
            });
        } else {
            Toast.makeText(this, "无权直接拉起系统页，可在拨号盘输入 *#*#4636#*#*", Toast.LENGTH_LONG).show();
        }
    }

    private void onClearLogClicked() {
        if (busy || latestSnapshot == null || !latestSnapshot.hasRoot) {
            return;
        }
        setBusy(true, null);
        worker.execute(() -> {
            RootShell.execRoot(
                    "mkdir -p '" + RootShell.DATA_DIR + "' && : > '" + RootShell.LOG_PATH + "'", 5);
            mainHandler.post(() -> {
                if (!isDestroyed()) {
                    setBusy(false, null);
                    refreshStateAsync(false, true);
                }
            });
        });
    }

    private void onToggleLauncherIconClicked() {
        boolean hidden = isLauncherIconHidden();
        if (!hidden) {
            new AlertDialog.Builder(this)
                    .setTitle("隐藏桌面图标")
                    .setMessage("隐藏后桌面启动器将不再显示 VoWiFiX 图标。\n\n后续可通过下拉控制中心“VoWiFiX”快捷磁贴，或在 LSPosed 管理器 -> 模块 -> VoWiFiX 点击设置图标重新打开本控制台。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定隐藏", (d, w) -> setLauncherIconHidden(true))
                    .show();
        } else {
            setLauncherIconHidden(false);
        }
    }

    private boolean isLauncherIconHidden() {
        int state = getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, LAUNCHER_ALIAS_CLASS));
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setLauncherIconHidden(boolean hide) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, LAUNCHER_ALIAS_CLASS),
                hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
        );
        updateLauncherToggleButton();
        Toast.makeText(this, hide ? "已隐藏桌面图标" : "已恢复桌面图标", Toast.LENGTH_SHORT).show();
    }

    private void updateLauncherToggleButton() {
        if (btnToggleLauncher != null) {
            btnToggleLauncher.setText(isLauncherIconHidden() ? "显示图标" : "隐藏图标");
        }
    }

    private long getRemainingRefreshCooldownSec() {
        if (lastRefreshElapsedMs <= 0L) {
            return 0L;
        }
        long elapsed = SystemClock.elapsedRealtime() - lastRefreshElapsedMs;
        if (elapsed >= REFRESH_COOLDOWN_MS) {
            return 0L;
        }
        return (REFRESH_COOLDOWN_MS - elapsed + 999L) / 1000L;
    }

    private void startFlushCountdown(final String prefix) {
        stopCountdown();
        final int[] sec = {6};
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed()) {
                    return;
                }
                if (sec[0] > 0) {
                    showProgressNotice(prefix + " (" + sec[0] + "s)...");
                    sec[0]--;
                    mainHandler.postDelayed(this, 1000L);
                }
            }
        };
        mainHandler.post(countdownRunnable);
    }

    private void stopCountdown() {
        if (countdownRunnable != null) {
            mainHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
    }

    private void showProgressNotice(String msg) {
        if (tvProgressBanner == null) {
            return;
        }
        tvProgressBanner.setText(msg);
        tvProgressBanner.setVisibility(View.VISIBLE);
    }

    private void hideProgressNotice() {
        if (tvProgressBanner != null) {
            tvProgressBanner.setVisibility(View.GONE);
        }
    }

    private void setBusy(boolean busyState, String notice) {
        this.busy = busyState;
        if (notice != null) {
            showProgressNotice(notice);
        }
        updateActionButtonsAvailability();
    }

    private void updateActionButtonsAvailability() {
        boolean hasRoot = latestSnapshot != null && latestSnapshot.hasRoot;
        boolean hasBin = latestSnapshot != null && latestSnapshot.binaryAvailable;
        boolean hasDiag = latestSnapshot != null && latestSnapshot.hasDiagBackend();
        boolean hotRefreshUnsupported = latestSnapshot != null && latestSnapshot.isHotRefreshUnsupported();
        boolean canModem = !busy && hasRoot && hasBin;
        boolean canRefreshModem = canModem && !hotRefreshUnsupported;
        boolean canRestore = canModem && hasDiag;

        if (btnHotAdapt != null) {
            btnHotAdapt.setText((hasRoot && hasBin && hasDiag && hotRefreshUnsupported) ? "一键适配 EFS" : "一键热适配");
        }
        if (btnRefreshModem != null) {
            btnRefreshModem.setText((hasRoot && hasBin && hotRefreshUnsupported) ? "热刷新 (基带不支持)" : "仅触发基带热刷新");
        }
        if (btnRestoreEfs != null) {
            btnRestoreEfs.setText((hasRoot && hasBin && !hasDiag) ? "还原备份 (无 Diag)" : "还原原始 EFS 备份");
        }

        UiHelper.applyButtonStyle(this, palette, btnHotAdapt, UiHelper.BTN_PRIMARY, canModem);
        UiHelper.applyButtonStyle(this, palette, btnRefreshModem, UiHelper.BTN_SECONDARY, canRefreshModem);
        UiHelper.applyButtonStyle(this, palette, btnRestoreCellular, UiHelper.BTN_SECONDARY, !busy && hasRoot);
        UiHelper.applyButtonStyle(this, palette, btnRestoreEfs, UiHelper.BTN_DANGER, canRestore);
        UiHelper.applyButtonStyle(this, palette, btnRadioInfo, UiHelper.BTN_SECONDARY, !busy);

        if (btnRefreshHeader != null) {
            btnRefreshHeader.setEnabled(!busy);
            btnRefreshHeader.setAlpha(busy ? 0.5f : 1.0f);
        }
        if (btnClearLog != null) {
            boolean canClear = !busy && hasRoot;
            btnClearLog.setEnabled(canClear);
            btnClearLog.setAlpha(canClear ? 1.0f : 0.45f);
        }
        if (switchWatcher != null && latestSnapshot != null) {
            switchWatcher.setEnabled(!busy && hasRoot && latestSnapshot.moduleInstalled);
        }
        if (switchCellExemptCn != null) {
            switchCellExemptCn.setEnabled(!busy && hasRoot);
        }
        for (int i = 0; i < 2; i++) {
            if (slotViews[i] != null && slotViews[i].switchCellBlock != null) {
                slotViews[i].switchCellBlock.setEnabled(!busy && hasRoot);
            }
        }
    }

    private static String summarizeLastLine(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "未知错误";
        }
        String[] lines = text.trim().split("\n");
        String last = lines[lines.length - 1].trim();
        return last.length() > 120 ? last.substring(0, 120) + "..." : last;
    }
}
