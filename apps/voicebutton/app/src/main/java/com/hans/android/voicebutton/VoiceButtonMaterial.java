package com.hans.android.voicebutton;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.Button;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

final class VoiceButtonMaterial {
    private VoiceButtonMaterial() {}

    static Button primaryButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        configure(button, text, 56);
        return button;
    }

    static Button secondaryButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        configure(button, text, 48);
        return button;
    }

    static Button toolbarButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        configure(button, text, 48);
        button.setMinWidth(0);
        return button;
    }

    static Button dangerButton(Context context, String text) {
        MaterialButton button = (MaterialButton) secondaryButton(context, text);
        int error = MaterialColors.getColor(button,
                com.google.android.material.R.attr.colorError, 0xffb3261e);
        button.setTextColor(error);
        button.setStrokeColor(ColorStateList.valueOf(error));
        return button;
    }

    private static void configure(MaterialButton button, String text, int minHeightDp) {
        button.setText(text);
        button.setAllCaps(false);
        float density = button.getResources().getDisplayMetrics().density;
        button.setMinHeight(Math.round(minHeightDp * density));
    }
}
