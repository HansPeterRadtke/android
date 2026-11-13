package com.hans.android.voicebutton;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class PlayerLoadedUiTest {
    private Context context;
    private File wav;
    private boolean previousAutomaticWaveform;

    @Before public void prepareLocalWavNoRemoteStudio() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        previousAutomaticWaveform = AppSettings.preferences(context)
                .getBoolean("automatic_waveform", true);
        assertTrue(AppSettings.preferences(context).edit()
                .putBoolean("automatic_waveform", false).commit());
        context.stopService(new Intent(context, PlayerPlaybackService.class));
        wav = File.createTempFile("voicebutton-ui-playback-", ".wav",
                context.getCacheDir());
        int sampleRate = 16000;
        int samples = sampleRate * 3;
        int payloadLength = samples * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + payloadLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + payloadLength);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short)1);
        buffer.putShort((short)1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short)2);
        buffer.putShort((short)16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(payloadLength);
        for (int i = 0; i < samples; i++) {
            short pcm = (short)(1000.0 * Math.sin(2.0 * Math.PI * i * 220.0 / sampleRate));
            buffer.putShort(pcm);
        }
        try (FileOutputStream output = new FileOutputStream(wav)) {
            output.write(buffer.array());
            output.flush();
            output.getFD().sync();
        }
    }

    @After public void cleanup() {
        if (context != null) {
            context.stopService(new Intent(context, PlayerPlaybackService.class));
            AppSettings.preferences(context).edit()
                    .putBoolean("automatic_waveform", previousAutomaticWaveform)
                    .commit();
        }
        if (wav != null) wav.delete();
    }

    @Test public void localWavOpensWithUsablePlayerControlsWithoutThor() {
        PlayerSource source = new PlayerSource(
                Uri.fromFile(wav), "QA local WAV", PlayerSource.KIND_DOCUMENT,
                wav.length(), "", "", null);
        Intent intent = new Intent(context, PlayerActivity.class);
        source.put(intent);
        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(intent)) {
            AssertionError last = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                try {
                    scenario.onActivity(activity -> {
                        TextView title = activity.findViewById(R.id.voicebutton_player_title);
                        Button play = activity.findViewById(R.id.voicebutton_player_play);
                        assertEquals("QA local WAV", title.getText().toString());
                        assertTrue("Local file should become playable", play.isEnabled());
                        assertTrue(activity.findViewById(R.id.voicebutton_player_library).isShown());
                        assertFalse("Waveform is disabled; no loading bar should be shown",
                                activity.findViewById(
                                        R.id.voicebutton_player_waveform_progress).isShown());
                    });
                    last = null;
                    break;
                } catch (AssertionError failure) {
                    last = failure;
                    SystemClock.sleep(100L);
                }
            }
            if (last != null) throw last;
        }
    }
}
