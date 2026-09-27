package myapp.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OfflineConversationUiTest {
  @Test public void cachedLongHistoryStartsAtLatestAndLatestReturnsToBottom() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue("offline acceptance requires host-side network block",
        "1".equals(args.getString("offlineLatestAcceptance")));
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    StringBuilder history = new StringBuilder();
    for (int i = 0; i < 180; i++) {
      history.append("User: cached message ").append(i)
          .append(" with enough content to fill the conversation viewport.\n\n")
          .append("Assistant: cached answer ").append(i).append(".\n\n");
    }
    context.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
        .clear()
        .putString("history_text_v2", history.toString())
        .commit();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      Thread.sleep(500L);
      scenario.onActivity(activity -> {
        TextView conversation = activity.findViewById(R.id.voice_conversation);
        ScrollView scroll = (ScrollView) conversation.getParent();
        MaterialButton latest = activity.findViewById(R.id.voice_latest);
        assertTrue(conversation.getText().toString().contains("cached message 179"));
        assertTrue(scroll.canScrollVertically(-1));
        assertEquals(View.VISIBLE, latest.getVisibility());
        View child = scroll.getChildAt(0);
        int remaining = child.getBottom() - (scroll.getScrollY() + scroll.getHeight());
        assertTrue("cached history did not open at latest", remaining <= activity.getResources().getDisplayMetrics().density * 48f);
        scroll.scrollTo(0, 0);
      });
      Thread.sleep(150L);
      scenario.onActivity(activity -> {
        MaterialButton latest = activity.findViewById(R.id.voice_latest);
        assertEquals(View.VISIBLE, latest.getVisibility());
        assertTrue(latest.performClick());
      });
      Thread.sleep(200L);
      scenario.onActivity(activity -> {
        ScrollView scroll = (ScrollView) activity.findViewById(R.id.voice_conversation).getParent();
        View child = scroll.getChildAt(0);
        int remaining = child.getBottom() - (scroll.getScrollY() + scroll.getHeight());
        assertTrue("Latest did not return to bottom", remaining <= activity.getResources().getDisplayMetrics().density * 48f);
      });
    } finally {
      context.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit().clear().commit();
    }
  }
}
