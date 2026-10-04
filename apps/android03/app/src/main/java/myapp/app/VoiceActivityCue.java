package myapp.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Short, soft nonverbal cues for voice-gate transitions. */
final class VoiceActivityCue {
  private static final int RATE = 48000;
  private static byte[] tone(double hz, int ms, double gain) {
    int n = RATE * ms / 1000; byte[] pcm = new byte[n * 2];
    for (int i=0;i<n;i++) {
      double envelope = Math.sin(Math.PI * i / Math.max(1,n-1));
      short v=(short)Math.round(Math.sin(2*Math.PI*hz*i/RATE)*envelope*gain*32767.0);
      pcm[i*2]=(byte)(v&255); pcm[i*2+1]=(byte)((v>>8)&255);
    }
    return pcm;
  }
  private static void play(double hz, int ms) {
    byte[] pcm=tone(hz,ms,0.08);
    new Thread(() -> {
      AudioTrack track=null;
      try {
        track=new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length).build();
        if (track.getState()!=AudioTrack.STATE_INITIALIZED) return;
        track.write(pcm,0,pcm.length); track.play(); Thread.sleep(ms+30L);
      } catch (Exception ignored) {} finally { if(track!=null) try{track.release();}catch(Exception ignored){} }
    },"voice-activity-cue").start();
  }
  static void start(){ play(1050.0,55); }
  static void stop(){ play(760.0,45); }
}
