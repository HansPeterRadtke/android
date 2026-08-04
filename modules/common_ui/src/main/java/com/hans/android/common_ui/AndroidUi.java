package com.hans.android.common_ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.widget.*;

public final class AndroidUi {
    public static final int BG = Color.rgb(248, 249, 251);
    public static final int SURFACE = Color.WHITE;
    public static final int INK = Color.rgb(28, 33, 40);
    public static final int MUTED = Color.rgb(92, 103, 115);
    public static final int BLUE = Color.rgb(32, 95, 210);
    public static final int GREEN = Color.rgb(24, 128, 72);
    public static final int ORANGE = Color.rgb(181, 103, 21);
    public static final int RED = Color.rgb(184, 54, 54);

    private AndroidUi() {}

    public static int dp(Context c, int v) { return (int)(v * c.getResources().getDisplayMetrics().density + 0.5f); }

    public static TextView title(Context c, String text) {
        TextView v = text(c, text, 28, true, INK);
        v.setPadding(0, dp(c, 4), 0, dp(c, 2));
        return v;
    }

    public static TextView subtitle(Context c, String text) { return text(c, text, 14, false, MUTED); }
    public static TextView section(Context c, String text) { return text(c, text, 20, true, INK); }
    public static TextView body(Context c, String text) { return text(c, text, 15, false, INK); }
    public static TextView small(Context c, String text) { return text(c, text, 12, false, MUTED); }

    public static TextView text(Context c, String text, int sp, boolean bold, int color) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(0, dp(c, 4), 0, dp(c, 4));
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    public static Button button(Context c, String text) {
        return secondaryButton(c, text);
    }

    public static Button primaryButton(Context c, String text) {
        Button button = baseButton(c, text);
        button.setTextColor(textStates(Color.WHITE, Color.WHITE,
                Color.rgb(135, 140, 148)));
        button.setTextSize(17);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(c, 56));
        button.setBackground(buttonStates(c, BLUE, Color.rgb(22, 75, 174),
                Color.rgb(224, 227, 232), BLUE, dp(c, 14)));
        return button;
    }

    public static Button secondaryButton(Context c, String text) {
        Button button = baseButton(c, text);
        button.setTextColor(textStates(BLUE, Color.rgb(18, 65, 150),
                Color.rgb(145, 150, 158)));
        button.setBackground(buttonStates(c, Color.WHITE, Color.rgb(232, 238, 248),
                Color.rgb(238, 240, 243), Color.rgb(201, 211, 224), dp(c, 12)));
        return button;
    }

    public static Button dangerButton(Context c, String text) {
        Button button = baseButton(c, text);
        button.setTextColor(textStates(RED, Color.rgb(132, 31, 31),
                Color.rgb(145, 150, 158)));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(buttonStates(c, Color.WHITE, Color.rgb(250, 230, 230),
                Color.rgb(238, 240, 243), RED, dp(c, 12)));
        return button;
    }

    public static Button toolbarButton(Context c, String text) {
        Button button = baseButton(c, text);
        button.setTextColor(textStates(INK, Color.BLACK,
                Color.rgb(145, 150, 158)));
        button.setTextSize(13);
        button.setMinHeight(dp(c, 44));
        button.setBackground(buttonStates(c, Color.TRANSPARENT,
                Color.rgb(232, 235, 240), Color.rgb(238, 240, 243),
                Color.rgb(218, 224, 232), dp(c, 12)));
        return button;
    }

    private static Button baseButton(Context c, String text) {
        Button button = new Button(c);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(c, 48));
        button.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setElevation(0f);
        if (Build.VERSION.SDK_INT >= 21) button.setStateListAnimator(null);
        return button;
    }

    public static void stableLine(Context c, TextView view, int minimumHeightDp) {
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int scaledTextHeight = (int)Math.ceil(view.getTextSize() * 1.45f)
                + dp(c, 8);
        int stableHeight = Math.max(dp(c, minimumHeightDp), scaledTextHeight);
        view.setMinHeight(stableHeight);
        view.setMaxHeight(stableHeight);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
    }

    public static Button modeButton(Context c, String text, boolean selected) {
        Button b = button(c, text);
        b.setTextColor(selected ? Color.WHITE : BLUE);
        GradientDrawable g = round(selected ? BLUE : Color.WHITE, selected ? BLUE : Color.rgb(205, 214, 225), dp(c, 18));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 44), 1);
        lp.setMargins(dp(c, 3), dp(c, 6), dp(c, 3), dp(c, 6));
        b.setLayoutParams(lp);
        return b;
    }

    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 8), 0, dp(c, 8));
        box.setLayoutParams(lp);
        box.setBackground(round(SURFACE, Color.rgb(225, 230, 236), dp(c, 16)));
        return box;
    }

    public static LinearLayout banner(Context c, int color) {
        LinearLayout box = card(c);
        box.setBackground(round(tint(color), color, dp(c, 16)));
        return box;
    }

    public static LinearLayout metric(Context c, String label, String value, int color) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        box.setBackground(round(tint(color), Color.rgb(228, 232, 238), dp(c, 14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        box.setLayoutParams(lp);
        box.addView(text(c, value, 22, true, color));
        box.addView(text(c, label, 12, false, MUTED));
        return box;
    }

    public static GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(1, stroke);
        return g;
    }

    private static int tint(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Color.rgb((r + 255 * 7) / 8, (g + 255 * 7) / 8, (b + 255 * 7) / 8);
    }
    private static ColorStateList textStates(int normal, int pressed, int disabled) {
        return new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_pressed},
                new int[]{}
        }, new int[]{disabled, pressed, normal});
    }

    private static StateListDrawable buttonStates(Context c, int normal,
                                                   int pressed, int disabled,
                                                   int stroke, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                round(disabled, Color.rgb(210, 214, 220), radius));
        states.addState(new int[]{android.R.attr.state_pressed},
                round(pressed, stroke, radius));
        states.addState(new int[]{}, round(normal, stroke, radius));
        return states;
    }

}
