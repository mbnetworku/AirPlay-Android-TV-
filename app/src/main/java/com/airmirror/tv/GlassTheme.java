package com.airmirror.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared "Liquid Glass" look (iOS 26-style): vibrant, blurred-looking colour glow behind
 * translucent, highlight-edged panels. There's no real-time blur available on this device's API
 * level/GPU, so the glass effect is faked with layered translucency and soft radial-gradient
 * glow blobs instead of an actual background blur - it reads the same from normal TV viewing
 * distance and costs nothing to render.
 */
final class GlassTheme {

    static final int ACCENT = Color.rgb(10, 132, 255);   // iOS systemBlue
    static final int ACCENT_PURPLE = Color.rgb(191, 90, 242);
    static final int ACCENT_PINK = Color.rgb(255, 69, 97);
    static final int GREEN = Color.rgb(50, 215, 75);
    static final int AMBER = Color.rgb(255, 159, 10);
    static final int RED = Color.rgb(255, 69, 58);
    static final int INK = Color.rgb(5, 6, 16);

    static final int TEXT_PRIMARY = Color.WHITE;
    static final int TEXT_SECONDARY = Color.argb(225, 230, 235, 248);
    static final int TEXT_TERTIARY = Color.argb(185, 210, 216, 235);

    private GlassTheme() {
    }

    /**
     * The backdrop the glass panels float over. Real "Liquid Glass" has no background of its
     * own - it blurs and tints whatever is actually behind it (the device's wallpaper/content).
     * This device can't do a live blur, so the closest honest approximation is to actually show
     * the system wallpaper (same as Apple TV's own idle screen does) rather than paint a made-up
     * background of our own; a plain black fallback keeps this safe if no wallpaper is set.
     */
    static View buildBackground(Context ctx) {
        FrameLayout canvas = new FrameLayout(ctx);
        ImageView wallpaper = new ImageView(ctx);
        wallpaper.setImageResource(R.drawable.wallpaper_default);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        canvas.addView(wallpaper, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // A bright/busy wallpaper can wash out white text through translucent glass. Real
        // Liquid Glass UIs dim/adapt under their content for this exact reason - a flat, modest
        // scrim here keeps every wallpaper legible without hiding it.
        View scrim = new View(ctx);
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        canvas.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return canvas;
    }

    /**
     * A translucent glass card: a dark tint (so text stays legible over any wallpaper, not just
     * a dark one) with a thin bright sheen right at the top edge, the way light catches real
     * glass, and a bright hairline around the whole edge.
     */
    static GradientDrawable panel(Context ctx, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(80, 255, 255, 255), Color.argb(150, 8, 10, 20), Color.argb(165, 8, 10, 20)});
        d.setCornerRadius(dp(ctx, radiusDp));
        d.setStroke(Math.max(1, dp(ctx, 1)), Color.argb(90, 255, 255, 255));
        return d;
    }

    /** A dimmer glass card for recessed/inset content (rows inside a panel, chips, etc). */
    static GradientDrawable panelDim(Context ctx, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(50, 255, 255, 255), Color.argb(110, 8, 10, 20), Color.argb(120, 8, 10, 20)});
        d.setCornerRadius(dp(ctx, radiusDp));
        d.setStroke(Math.max(1, dp(ctx, 1)), Color.argb(55, 255, 255, 255));
        return d;
    }

    /** A glass card tinted with the accent colour, for a selected/active state. */
    static GradientDrawable panelAccent(Context ctx, float radiusDp, int accent) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent))});
        d.setCornerRadius(dp(ctx, radiusDp));
        d.setStroke(dp(ctx, 1.5f), Color.argb(190, 255, 255, 255));
        return d;
    }

    /** A remote-focusable glass pill: neutral glass normally, a bright accent glow when focused. */
    static StateListDrawable pillButton(Context ctx, float radiusDp) {
        GradientDrawable normal = panel(ctx, radiusDp);
        GradientDrawable focused = panelAccent(ctx, radiusDp, ACCENT);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{android.R.attr.state_pressed}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** Same idea as {@link #pillButton}, for a row rather than a compact pill (e.g. settings list rows). */
    static StateListDrawable focusableRow(Context ctx, float radiusDp) {
        GradientDrawable normal = panelDim(ctx, radiusDp);
        GradientDrawable focused = panelAccent(ctx, radiusDp, ACCENT);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{android.R.attr.state_pressed}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static TextView text(Context ctx, float sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    static View space(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(ctx, heightDp)));
        return v;
    }

    static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
