package io.iniwex.vowifix;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 纯原生 View 样式与配色辅助类，零三方 UI 依赖。
 * 根据系统浅色/深色模式自动解析中性克制的现代控制台配色。
 */
public final class UiHelper {

    private UiHelper() {
    }

    /**
     * 运行时主题调色板，覆盖页面背景、卡片、描边与语义状态色。
     */
    public static final class Palette {
        public final boolean isDark;
        public final int bgPage;
        public final int bgCard;
        public final int bgInset;
        public final int border;
        public final int textPrimary;
        public final int textSecondary;
        public final int textMuted;
        public final int accent;
        public final int accentTextOn;
        public final int accentBg;
        public final int success;
        public final int successBg;
        public final int warning;
        public final int warningBg;
        public final int danger;
        public final int dangerBg;
        public final int info;
        public final int infoBg;
        public final int neutral;
        public final int neutralBg;
        public final int ripple;

        private Palette(boolean isDark) {
            this.isDark = isDark;
            if (isDark) {
                this.bgPage = 0xFF0E1116;
                this.bgCard = 0xFF161B22;
                this.bgInset = 0xFF0B0E14;
                this.border = 0xFF262D38;
                this.textPrimary = 0xFFF0F6FC;
                this.textSecondary = 0xFFA5B1C2;
                this.textMuted = 0xFF6E7781;
                this.accent = 0xFF3B82F6;
                this.accentTextOn = 0xFFFFFFFF;
                this.accentBg = 0xFF17253D;
                this.success = 0xFF34D399;
                this.successBg = 0xFF102A22;
                this.warning = 0xFFFBBF24;
                this.warningBg = 0xFF2D210B;
                this.danger = 0xFFF87171;
                this.dangerBg = 0xFF311619;
                this.info = 0xFF60A5FA;
                this.infoBg = 0xFF16243A;
                this.neutral = 0xFF9CA3AF;
                this.neutralBg = 0xFF21262D;
                this.ripple = 0x26FFFFFF;
            } else {
                this.bgPage = 0xFFF4F6F9;
                this.bgCard = 0xFFFFFFFF;
                this.bgInset = 0xFFF0F2F5;
                this.border = 0xFFE2E6ED;
                this.textPrimary = 0xFF111827;
                this.textSecondary = 0xFF4B5563;
                this.textMuted = 0xFF6B7280;
                this.accent = 0xFF2563EB;
                this.accentTextOn = 0xFFFFFFFF;
                this.accentBg = 0xFFEFF6FF;
                this.success = 0xFF059669;
                this.successBg = 0xFFECFDF5;
                this.warning = 0xFFD97706;
                this.warningBg = 0xFFFFFBEB;
                this.danger = 0xFFDC2626;
                this.dangerBg = 0xFFFEF2F2;
                this.info = 0xFF2563EB;
                this.infoBg = 0xFFEFF6FF;
                this.neutral = 0xFF4B5563;
                this.neutralBg = 0xFFE5E7EB;
                this.ripple = 0x1A000000;
            }
        }

        /**
         * 从当前 Context 的系统深色模式状态解析调色板。
         */
        public static Palette resolve(Context ctx) {
            int night = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return new Palette(night == Configuration.UI_MODE_NIGHT_YES);
        }
    }

    /**
     * dp 转整型像素。
     */
    public static int dp(Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }

    /**
     * dp 转浮点像素。
     */
    public static float dpF(Context ctx, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    /**
     * 构建圆角矩形 Drawable，可选描边。
     */
    public static GradientDrawable makeRoundRect(
            int fillColor, float radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    /**
     * 构建带按下水波纹效果的圆角背景。
     */
    public static Drawable makeRippleRoundRect(
            Context ctx, Palette p, int fillColor, int radiusDp, int strokeColor) {
        float r = dpF(ctx, radiusDp);
        int strokePx = strokeColor == Color.TRANSPARENT ? 0 : Math.max(1, dp(ctx, 1));
        GradientDrawable content = makeRoundRect(fillColor, r, strokePx, strokeColor);
        GradientDrawable mask = makeRoundRect(0xFFFFFFFF, r, 0, Color.TRANSPARENT);
        return new RippleDrawable(ColorStateList.valueOf(p.ripple), content, mask);
    }

    /**
     * 创建标准垂直圆角卡片容器。
     */
    public static LinearLayout makeCard(Context ctx, Palette p) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeRoundRect(
                p.bgCard, dpF(ctx, 14), Math.max(1, dp(ctx, 1)), p.border));
        int pad = dp(ctx, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(lp);
        return card;
    }

    /**
     * 创建卡片内分组标题。
     */
    public static TextView makeSectionTitle(Context ctx, Palette p, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(p.textPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 10);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * 创建细分隔线。
     */
    public static View makeDivider(Context ctx, Palette p, int verticalMarginDp) {
        View v = new View(ctx);
        v.setBackgroundColor(p.border);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        int m = dp(ctx, verticalMarginDp);
        lp.topMargin = m;
        lp.bottomMargin = m;
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * 创建胶囊语义状态徽章。
     */
    public static TextView makeBadge(
            Context ctx, String text, int textColor, int bgColor) {
        TextView badge = new TextView(ctx);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        int ph = dp(ctx, 8);
        int pv = dp(ctx, 3);
        badge.setPadding(ph, pv, ph, pv);
        styleBadge(ctx, badge, text, textColor, bgColor);
        return badge;
    }

    /**
     * 更新已有徽章的文案与颜色。
     */
    public static void styleBadge(
            Context ctx, TextView badge, String text, int textColor, int bgColor) {
        badge.setText(text);
        badge.setTextColor(textColor);
        badge.setBackground(makeRoundRect(bgColor, dpF(ctx, 99), 0, Color.TRANSPARENT));
    }

    /**
     * 创建紧凑操作按钮（支持主操作、次操作、危险操作三种风格）。
     */
    public static TextView makeActionButton(
            Context ctx, Palette p, String text, int styleType) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        int ph = dp(ctx, 12);
        int pv = dp(ctx, 10);
        btn.setPadding(ph, pv, ph, pv);
        btn.setClickable(true);
        btn.setFocusable(true);
        applyButtonStyle(ctx, p, btn, styleType, true);
        return btn;
    }

    public static final int BTN_PRIMARY = 0;
    public static final int BTN_SECONDARY = 1;
    public static final int BTN_DANGER = 2;

    /**
     * 根据启用态动态应用按钮背景与文字颜色。
     */
    public static void applyButtonStyle(
            Context ctx, Palette p, TextView btn, int styleType, boolean enabled) {
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.48f);
        if (styleType == BTN_PRIMARY) {
            btn.setTextColor(p.accentTextOn);
            btn.setBackground(makeRippleRoundRect(ctx, p, p.accent, 10, Color.TRANSPARENT));
        } else if (styleType == BTN_DANGER) {
            btn.setTextColor(p.danger);
            btn.setBackground(makeRippleRoundRect(ctx, p, p.dangerBg, 10, p.danger));
        } else {
            btn.setTextColor(p.textPrimary);
            btn.setBackground(makeRippleRoundRect(ctx, p, p.bgInset, 10, p.border));
        }
    }

    /**
     * 创建顶栏小型胶囊按钮。
     */
    public static TextView makeHeaderChip(Context ctx, Palette p, String text) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextColor(p.textPrimary);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        chip.setBackground(makeRippleRoundRect(ctx, p, p.bgCard, 99, p.border));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }
}
