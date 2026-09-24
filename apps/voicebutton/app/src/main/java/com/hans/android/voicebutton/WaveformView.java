package com.hans.android.voicebutton;

import android.content.Context;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatImageView;

public final class WaveformView extends AppCompatImageView {
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
