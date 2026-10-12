package myapp.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.rule.ServiceTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeoutException;

/** Exercise the exact binder/Activity lifecycle boundary, not a mock Service. */
@RunWith(AndroidJUnit4.class)
public class VoiceForegroundOwnershipTest {
  @org.junit.Before public void resetFixture() { VoiceUiTestState.reset(); }
  @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);
  @Rule public ServiceTestRule serviceRule = new ServiceTestRule();

  @Test public void serviceBoundWithoutScreenOwnsIndependentNetworkFacade() throws Exception {
    Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    VoiceForegroundService service=bind(app);
    assertNotNull(service.facade());
    assertNotNull(service.microphoneLabel());
    assertFalse(service.isCapturing());
    // Recreating the Activity must not destruct or reconstruct the binder.
    try(ActivityScenario<MainActivity> first=ActivityScenario.launch(MainActivity.class)) {
      first.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
    }
    assertNotNull(service.facade());
    assertFalse(service.isCapturing());
  }

  @Test public void microphoneContinuesAcrossActivityDestructionWhenDeviceSupportsIt() throws Exception {
    Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    app.getSharedPreferences("voice_agent_history",Context.MODE_PRIVATE).edit()
        .putBoolean("transcribe_automatically_v1",true)
        .putBoolean("send_automatically_v3",false).commit();
    VoiceForegroundService service=bind(app);
    try {
      Intent start=new Intent(app,VoiceForegroundService.class)
          .setAction(VoiceForegroundService.ACTION_START);
      androidx.core.content.ContextCompat.startForegroundService(app,start);
      assertTrue("service refused microphone start",
          service.startCapture(com.hans.android.audio.AudioInputOption.DEFAULT_DEVICE_ID,false));
      SystemClock.sleep(950);
      assertTrue("microphone capture failed before Activity lifecycle test: "
          +service.captureError(),service.isCapturing());
      try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)) {
        screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
      }
      SystemClock.sleep(500);
      assertTrue("Activity shutdown incorrectly stopped service microphone: "
          +service.captureError(),service.isCapturing());
    } finally {
      service.stopCapture();
      SystemClock.sleep(100);
    }
  }

  private VoiceForegroundService bind(Context app) throws TimeoutException {
    IBinder binder=serviceRule.bindService(new Intent(app,VoiceForegroundService.class));
    assertNotNull(binder);
    return ((VoiceForegroundService.LocalBinder)binder).service();
  }

  @Test public void renderedPlaybackPausesResumesAndSurvivesScreenDestruction() throws Exception {
    Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    VoiceForegroundService service=bind(app);
    VoicePcmPlayback playback=service.assistantPlayback();
    playback.startStream(true,true,false);
    // The emulator can take tens of seconds to launch/recreate a screen. Keep
    // this fixture longer than that transition; short completion is tested below.
    playback.append(new byte[16000*2*90]);
    playback.endStream();
    try {
      long deadline=SystemClock.elapsedRealtime()+4000;
      while(playback.currentMs()<100 && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);
      assertTrue("AudioTrack did not render the clip",playback.currentMs()>=100);
      playback.toggle();
      int paused=playback.currentMs();
      SystemClock.sleep(250);
      assertEquals("Pause advanced beyond heard audio",paused,playback.currentMs());
      playback.toggle();
      try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)) {
        screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
      }
      SystemClock.sleep(250);
      assertSame(playback,service.assistantPlayback());
      assertTrue("Screen destruction stopped audio",playback.currentMs()>paused);
      assertTrue(playback.isPlaying());
      playback.seekToMs(0);
      assertTrue("Seek did not rewind rendered position",playback.currentMs()<300);
    } finally {playback.cancelStream();}
  }

  @Test public void shortReplyCompletesOnlyAfterItsSamplesRender() throws Exception {
    VoiceForegroundService service=bind(InstrumentationRegistry.getInstrumentation().getTargetContext());
    VoicePcmPlayback playback=service.assistantPlayback();
    java.util.concurrent.CountDownLatch completed=new java.util.concurrent.CountDownLatch(1);
    playback.setCompletionListener(completed::countDown);
    playback.startStream(true,false,true);
    playback.append(new byte[3200]);
    playback.endStream();
    try {
      assertTrue("Short audio remained stuck waiting for a full device buffer",
          completed.await(3,java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(100,playback.currentMs());
      assertFalse(playback.visible());
    } finally {playback.cancelStream();}
  }
}
