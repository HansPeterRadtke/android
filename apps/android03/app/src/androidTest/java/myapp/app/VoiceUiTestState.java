package myapp.app;

import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;

/** Fresh-screen fixtures must not inherit another test's service-owned audio. */
final class VoiceUiTestState {
  static void reset() {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.stopService(new Intent(context,VoiceForegroundService.class));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    context.getSharedPreferences("voice_agent_history",Context.MODE_PRIVATE).edit().clear().commit();
    java.io.File recording=new java.io.File(context.getFilesDir(),"voice_unsent_manual.pcm");
    if(recording.exists() && !recording.delete())throw new AssertionError("Cannot reset recording fixture");
  }
}
