package com.hans.android.common_ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;

public final class AndroidUi {
    private AndroidUi() {}

    public static TextView title(Context c, String text) {
        TextView v = text(c, text, 26, true);
        v.setPadding(0, 10, 0, 12);
        return v;
    }

    public static TextView section(Context c, String text) {
        TextView v = text(c, text, 20, true);
        v.setPadding(0, 22, 0, 8);
        return v;
    }

    public static TextView body(Context c, String text) {
        return text(c, text, 14, false);
    }

    public static TextView small(Context c, String text) {
        return text(c, text, 12, false);
    }

    public static TextView status(Context c, String text, boolean ok) {
        TextView v = text(c, text, 15, true);
        v.setPadding(18, 14, 18, 14);
        v.setTextColor(ok ? Color.rgb(20, 100, 45) : Color.rgb(160, 50, 35));
        return v;
    }

    public static TextView text(Context c, String text, int sp, boolean bold) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(sp);
        v.setPadding(0, 6, 0, 6);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    public static Button button(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18, 18, 18, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 10, 0, 10);
        box.setLayoutParams(lp);
        box.setBackgroundColor(Color.rgb(245, 245, 245));
        return box;
    }
}
