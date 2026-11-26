package myapp.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Lightweight UI-only animation based on server/client conversation state.
 * Not speech detection, voice processing, or a local inference engine. */
final class GeneralVoiceVisual extends View {
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int accent = Color.rgb(24, 100, 148);
  private int animation = 0;  // 0 pulse, 1 bars, 2 ring, 3 static
  private String state = "READY";
  private boolean active = false;
  private boolean avatarPresent = false;
  GeneralVoiceVisual(Context context) {
    super(context);
    setContentDescription("Voice agent state: ready");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }
  void configure(int accentColor, int mode) {
    accent = accentColor;
    animation = GeneralVoiceProfile.option(mode, 4);
    invalidate();
  }
  void setAvatarPresent(boolean value) { avatarPresent = value; invalidate(); }
  void state(String next) {
    if (next == null) return;
    state = next;
    active = "LISTENING".equals(next) || "RECORDING_LOCAL".equals(next)
        || "THINKING".equals(next) || "BUFFERING".equals(next)
        || "SPEAKING".equals(next) || "TRANSCRIBING".equals(next);
    setContentDescription("Voice agent state: " + next.toLowerCase(java.util.Locale.ROOT).replace('_',' '));
    invalidate();
  }
  private float cycle() {
    return (android.os.SystemClock.uptimeMillis() % 1400L) / 1400f;
  }
  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight();
    float radius = Math.min(w,h) * .29f, phase = active ? cycle() : .12f;
    float cx = w*.5f, cy = h*.5f;
    ink.setColor(accent);
    // The chosen image must remain visible. Draw state ring around an avatar,
    // never an opaque circle or bar pattern over its face.
    if (avatarPresent) {
      ink.setStyle(Paint.Style.STROKE);
      ink.setStrokeWidth(Math.max(3f, Math.min(w,h) * .065f));
      ink.setAlpha(235);
      canvas.drawCircle(cx,cy,radius*(active ? (1f+.12f*phase) : 1f),ink);
      ink.setAlpha(255);
      ink.setStyle(Paint.Style.FILL);
      if (active && animation!=3 && isAttachedToWindow()) postInvalidateDelayed(75);
      return;
    }
    if (animation == 1) {
      float barW = Math.max(3f, Math.min(w, h) * .07f);
      for (int i=0;i<5;i++) {
        float t = active ? (float)Math.abs(Math.sin((phase + i*.16f) * Math.PI * 2)) : .2f;
        float bh = Math.min(h*.76f, h*(.20f + .52f*t));
        float bx = cx + (i - 2) * barW * 1.7f;
        ink.setAlpha(i==2 ? 255 : 190);
        canvas.drawRoundRect(bx-barW/2, cy-bh/2,bx+barW/2,cy+bh/2,barW/2,barW/2,ink);
      }
      ink.setAlpha(255);
    } else if (animation == 2) {
      ink.setStyle(Paint.Style.STROKE);
      ink.setStrokeWidth(Math.max(3f,Math.min(w,h)*.055f));
      canvas.drawCircle(cx,cy,radius*(active?(1f+.18f*phase):1f),ink);
      ink.setStyle(Paint.Style.FILL);
      canvas.drawCircle(cx,cy,radius*.17f,ink);
    } else {
      if (animation == 0 && active) {
        ink.setAlpha((int)(90*(1f-phase)));
        canvas.drawCircle(cx,cy,radius*(1.2f+1.2f*phase),ink);
      }
      ink.setAlpha(255);
      canvas.drawCircle(cx,cy,radius*(animation == 0 && active ? 1f + .07f * (float)Math.sin(phase*6.28f):1f),ink);
    }
    if (active && animation != 3 && isAttachedToWindow())
      postInvalidateDelayed(75);
  }
}
