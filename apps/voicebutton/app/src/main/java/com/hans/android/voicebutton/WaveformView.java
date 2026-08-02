package com.hans.android.voicebutton;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

public final class WaveformView extends ImageView {
    public WaveformView(Context context) { super(context); }
    public WaveformView(Context context, AttributeSet attrs) { super(context, attrs); }
    public WaveformView(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
