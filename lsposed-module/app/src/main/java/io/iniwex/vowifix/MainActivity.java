package io.iniwex.vowifix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
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

    private final SlotViews[] slotViews = new SlotViews[2];

    private TextView tvProgressBanner;
    private TextView btnHotAdapt;
    private TextView btnRefreshModem;
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

    private static final class SlotViews {
        LinearLayout box;
        TextView tvTitle;
        TextView badgeIms;
        TextView tvOperatorLine;
        TextView tvRoamingLine;
        TextView tvIniStatus;
        TextView tvSeStatus;
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

        TextView badgeVer = UiHelper.makeBadge(this, "v1.0.4", palette.accent, palette.accentBg);
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

        for (int i = 0; i < 2; i++) {
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
        btnRestoreEfs = UiHelper.makeActionButton(this, palette, "还原原始 EFS 备份", UiHelper.BTN_DANGER);
        btnRadioInfo = UiHelper.makeActionButton(this, palette, "打开系统 RadioInfo", UiHelper.BTN_SECONDARY);

        btnHotAdapt.setOnClickListener(v -> onHotAdaptClicked());
        btnRefreshModem.setOnClickListener(v -> onRefreshModemClicked());
        btnRestoreEfs.setOnClickListener(v -> onRestoreEfsClicked());
        btnRadioInfo.setOnClickListener(v -> onOpenRadioInfoClicked());

        card.addView(makeButtonRow(btnHotAdapt, btnRefreshModem, 10));
        card.addView(makeButtonRow(btnRestoreEfs, btnRadioInfo, 0));
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
        final TelephonyProbe.EfsSlotStatus[] prevEfs = latestSnapshot != null ? latestSnapshot.efs : null;
        if (!preserveExistingEfs) {
            setBusy(true, queryEfs ? "正在读取双卡与 EFS 状态..." : null);
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
        suppressSwitchEvent = false;
        switchWatcher.setEnabled(!busy && snap.hasRoot && snap.moduleInstalled);
        if (!snap.hasRoot) {
            tvWatcherHint.setText("授予 Root 权限后可控制后台切卡守护");
        } else if (!snap.watcherEnabled) {
            tvWatcherHint.setText("已暂停后台自动监听，仍可下方手动热适配");
        } else if (snap.hasDiagBackend()) {
            tvWatcherHint.setText("插拔或切换 SIM 卡时自动修补并热刷新");
        } else {
            tvWatcherHint.setText("未开放 Diag 通道，切卡时自动校准 siminfo 策略");
        }

        for (int i = 0; i < 2; i++) {
            renderSlot(slotViews[i], snap.slots[i], snap.efs[i], snap.hasRoot);
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
        if ("READ_ERROR".equals(state)) {
            return palette.danger;
        }
        if ("UNPATCHED".equals(state)) {
            return palette.warning;
        }
        if (TelephonyProbe.EfsSlotStatus.STATE_NO_DIAG.equals(state)) {
            return palette.textMuted;
        }
        return palette.textSecondary;
    }

    private void renderSlot(
            SlotViews sv, TelephonyProbe.SlotSnapshot slot,
            TelephonyProbe.EfsSlotStatus efs, boolean hasRoot) {
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
            if (slot.isRoamingOnCellular()) {
                String hostNet = slot.netAlpha.isEmpty() ? slot.netNumeric : slot.netAlpha + " (" + slot.netNumeric + ")";
                sv.tvRoamingLine.setText("漫游状态: 国际漫游中 · 驻留 " + hostNet);
                sv.tvRoamingLine.setTextColor(palette.warning);
            } else {
                sv.tvRoamingLine.setText("漫游状态: 本地网络");
                sv.tvRoamingLine.setTextColor(palette.textSecondary);
            }
        }

        if (!hasRoot) {
            sv.tvIniStatus.setText("配置合并: 需要 Root 权限");
            sv.tvIniStatus.setTextColor(palette.textMuted);
            sv.tvSeStatus.setText("漫游解锁: 需要 Root 权限");
            sv.tvSeStatus.setTextColor(palette.textMuted);
            return;
        }

        sv.tvIniStatus.setText(efs.getIniSummaryText());
        sv.tvIniStatus.setTextColor(resolveEfsLineColor(efs.iniState));

        sv.tvSeStatus.setText(efs.getSeSummaryText());
        sv.tvSeStatus.setTextColor(resolveEfsLineColor(efs.seState));
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
                if (!r.isSuccess()) {
                    Toast.makeText(this, "切换守护状态失败: " + r.stderr, Toast.LENGTH_SHORT).show();
                } else {
                    hideProgressNotice();
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
                        + "OUT=$(\"$BIN\" patch all --no-lock --backup-dir '" + RootShell.DATA_DIR + "' 2>&1)\n"
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
                        + "  [ \"$RRC\" -eq 0 ] && echo 'VOWIFIX_REFRESHED=1'\n"
                        + "  exit $RRC\n"
                        + "elif [ \"$HAS_SIM_CHG\" -eq 1 ]; then\n"
                        + "  echo 'VOWIFIX_PHASE:REFRESH_START'\n"
                        + "  ROUT=$(\"$BIN\" refresh --no-lock 2>&1)\n"
                        + "  RRC=$?\n"
                        + "  printf '%s\\n' \"$ROUT\"\n"
                        + "  echo \"[$(date '+%Y-%m-%d %H:%M:%S')] INFO: [manual] siminfo-only refresh RRC=$RRC | $(echo \"$ROUT\" | tr '\\n' '; ')\" >> '" + RootShell.LOG_PATH + "'\n"
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
        new AlertDialog.Builder(this)
                .setTitle("还原原始 EFS 备份")
                .setMessage("确定要将修改过的配置与漫游开关字节恢复为首次备份的原始状态吗？\n\n若发生写入变更，将严格等待 6 秒落盘后再尝试热刷新基带。")
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
                    + "OUT=$(\"$BIN\" restore all --no-lock --backup-dir '" + RootShell.DATA_DIR + "' 2>&1)\n"
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
        if (!r.isSuccess()) {
            String err = r.stderr.isEmpty() ? r.stdout : r.stderr;
            setBusy(false, actionName + "失败 (exit=" + r.exitCode + "): " + summarizeLastLine(err));
            refreshStateAsync(!refreshed, refreshed);
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
            refreshStateAsync(true, false);
        } else if (r.stdout.contains("VOWIFIX_ALREADY_CLEAN=1")) {
            setBusy(false, "当前状态已对齐，无需改动（若需强制重载可点“仅触发基带热刷新”）");
            refreshStateAsync(true, false);
        } else {
            setBusy(false, actionName + "已执行完成");
            refreshStateAsync(true, false);
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
        boolean canModem = !busy && hasRoot && hasBin;
        boolean canRestore = canModem && hasDiag;

        if (btnRestoreEfs != null) {
            btnRestoreEfs.setText((hasRoot && hasBin && !hasDiag) ? "还原备份 (无 Diag)" : "还原原始 EFS 备份");
        }

        UiHelper.applyButtonStyle(this, palette, btnHotAdapt, UiHelper.BTN_PRIMARY, canModem);
        UiHelper.applyButtonStyle(this, palette, btnRefreshModem, UiHelper.BTN_SECONDARY, canModem);
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
