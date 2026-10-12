package myapp.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.ByteArrayOutputStream;

/** Service-owned audio timeline. UI attachment has no effect on capture or sound. */
final class VoicePcmPlayback {
  private static final int SAMPLE_WIDTH = 2;
  private static final int CHANNELS = 1;
  private static final class PcmBuffer extends ByteArrayOutputStream {
    PcmBuffer(int initial) { super(initial); }
    synchronized boolean appendBounded(byte[] value, int maxBytes) {
      if (value == null || value.length == 0) return true;
      if (count + value.length > maxBytes) return false;
      write(value, 0, value.length);
      return true;
    }
    synchronized int copyFrom(int offset, byte[] target) {
      if (offset < 0 || offset >= count) return 0;
      int length = Math.min(target.length, count - offset);
      System.arraycopy(buf, offset, target, 0, length);
      return length;
    }
    synchronized int bytes() { return count; }
    synchronized byte[] snapshot() { return toByteArray(); }
    synchronized void replace(byte[] value) {
      reset();
      if (value != null) write(value, 0, value.length);
    }
  }

  private final int sampleRate;
  private final int maxBytes;
  private final Object lock = new Object();
  private final PcmBuffer buffer;
  private volatile boolean playing, streamOpen, remoteCancelable, autoHideAfterNaturalFinish;
  private volatile boolean naturallyFinished;
  private volatile boolean communicationPlayback;
  private volatile int positionBytes;
  private int writeBytes, trackBaseBytes;
  private int drainPaddingBytes;
  private volatile long serial;
  private AudioTrack track;
  private Thread thread;
  private VoiceStreamingPlaybackPolicy autoStartPolicy = new VoiceStreamingPlaybackPolicy(false);
  private volatile Runnable completionListener;
  private volatile Runnable remoteStopListener;
  private volatile java.util.function.Consumer<String> errorListener;

  VoicePcmPlayback(int sampleRate, int maxBytes) {
    this.sampleRate=sampleRate; this.maxBytes=maxBytes;
    buffer=new PcmBuffer(Math.min(maxBytes,sampleRate*8));
  }
  void setCompletionListener(Runnable value) { completionListener=value; }
  void setRemoteStopListener(Runnable value) { remoteStopListener=value; }
  void setErrorListener(java.util.function.Consumer<String> value) { errorListener=value; }
  void setCommunicationPlayback(boolean value) { communicationPlayback=value; }
  boolean isPlaying() { return playing; }
  boolean visible() { return (hasAudio() || streamOpen) && !(naturallyFinished && autoHideAfterNaturalFinish); }
  boolean startStream(boolean autoPlay,boolean cancelable,boolean autoHide) {
    synchronized(lock) {
      playing=false; serial++; stopTrackLocked(); buffer.replace(null);
      positionBytes=0; writeBytes=0; streamOpen=true; remoteCancelable=cancelable;
      naturallyFinished=false; autoHideAfterNaturalFinish=autoHide;
      autoStartPolicy=new VoiceStreamingPlaybackPolicy(autoPlay);
    }
    return true;
  }
  boolean append(byte[] pcm) {
    boolean start;
    synchronized(lock) {
      if (!streamOpen || pcm == null || (pcm.length & 1) != 0) return false;
      if (!buffer.appendBounded(pcm,maxBytes)) return false;
      start=autoStartPolicy.shouldStart(buffer.bytes()>0);
    }
    if(start) startPlaying();
    return true;
  }
  void endStream() { streamOpen=false; }
  void cancelStream() {
    synchronized(lock) {
      updateRenderedLocked(); playing=false; streamOpen=false; remoteCancelable=false;
      serial++; stopTrackLocked(); autoStartPolicy=new VoiceStreamingPlaybackPolicy(false);
      naturallyFinished=true;
    }
  }
  void setStaticPcm(byte[] pcm) {
    if(pcm != null && (pcm.length>maxBytes || (pcm.length&1)!=0))
      throw new IllegalArgumentException("invalid recording size");
    synchronized(lock) {
      playing=false; serial++; stopTrackLocked(); streamOpen=false; remoteCancelable=false;
      autoHideAfterNaturalFinish=false; naturallyFinished=false;
      autoStartPolicy=new VoiceStreamingPlaybackPolicy(false); buffer.replace(pcm);
      positionBytes=0; writeBytes=0;
    }
  }
  byte[] snapshot() { return buffer.snapshot(); }
  boolean hasAudio() { return buffer.bytes()>0; }
  boolean isStreamOpen() { return streamOpen; }
  int availableMs() { return bytesToMs(buffer.bytes()); }
  int currentMs() { synchronized(lock) { updateRenderedLocked(); return bytesToMs(positionBytes); } }
  void toggle() { if(playing) pausePlaying(); else startPlaying(); }
  void startPlaying() {
    try {
      synchronized(lock) {
        if(playing || (buffer.bytes()==0 && !streamOpen))return;
        if(positionBytes>=buffer.bytes() && !streamOpen)positionBytes=0;
        serial++; stopTrackLocked(); naturallyFinished=false;
        initTrackLocked(); playing=true; track.play();
        long mine=serial; thread=new Thread(()->playbackLoop(mine),"voice-pcm-player"); thread.start();
      }
    } catch(RuntimeException failure) { fail("Audio output unavailable"); }
  }
  private void pausePlaying() {
    synchronized(lock) {
      try { if(track!=null)track.pause(); } catch(RuntimeException ignored) {}
      updateRenderedLocked(); playing=false; serial++; stopTrackLocked();
    }
  }
  void stopFromUser() {
    boolean notify=remoteCancelable && streamOpen;
    cancelStream(); positionBytes=0;
    Runnable callback=remoteStopListener;
    if(notify && callback!=null)callback.run();
  }
  void seekToMs(int value) {
    boolean restart;
    synchronized(lock) {
      restart=playing; playing=false; serial++; stopTrackLocked();
      positionBytes=VoicePlayerMath.msToBytes(VoicePlayerMath.clampMs(value,availableMs()),
          sampleRate,SAMPLE_WIDTH,CHANNELS,maxBytes);
      positionBytes-=positionBytes%2; naturallyFinished=false;
    }
    if(restart)startPlaying();
  }
  private void updateRenderedLocked() {
    if(track!=null) {
      try {
        positionBytes=VoicePlaybackPosition.renderedBytes(trackBaseBytes,writeBytes,
            Integer.toUnsignedLong(track.getPlaybackHeadPosition()),2);
      } catch(RuntimeException ignored) {}
    }
  }
  private void playbackLoop(long mine) {
    byte[] chunk=new byte[Math.max(960,sampleRate/10)];
    long stalledSince=android.os.SystemClock.elapsedRealtime();
    int lastRendered=-1;
    try {
      while(playing && serial==mine) {
        AudioTrack target; int got; boolean padding=false;
        synchronized(lock) {
          if(!playing || serial!=mine || track==null)return;
          target=track; updateRenderedLocked();
          if(positionBytes!=lastRendered) { lastRendered=positionBytes; stalledSince=android.os.SystemClock.elapsedRealtime(); }
          got=buffer.copyFrom(writeBytes,chunk);
          if(got==0 && !streamOpen && positionBytes>=buffer.bytes()) {
            playing=false; naturallyFinished=true; stopTrackLocked();
            Runnable callback=completionListener;
            if(callback!=null)callback.run();
            return;
          }
          // Older Android versions start only after the effective device buffer
          // fills. Pad a closed short clip with silence so its real samples drain.
          if(got==0 && !streamOpen && android.os.Build.VERSION.SDK_INT<31
              && writeBytes-trackBaseBytes+drainPaddingBytes<target.getBufferSizeInFrames()*2) {
            got=Math.min(chunk.length,target.getBufferSizeInFrames()*2
                -(writeBytes-trackBaseBytes+drainPaddingBytes));
            java.util.Arrays.fill(chunk,(byte)0);padding=true;
          }
        }
        if(got>0) {
          // Nonblocking writes let pause/cancel and stalled-device detection run.
          int n=target.write(chunk,0,got,AudioTrack.WRITE_NON_BLOCKING);
          synchronized(lock) {
            if(serial!=mine || track!=target)return;
            if(n<0)throw new IllegalStateException("AudioTrack write failed: "+n);
            if(padding)drainPaddingBytes+=n;else writeBytes+=n;
          }
        }
        if((got>0 || !streamOpen) && android.os.SystemClock.elapsedRealtime()-stalledSince>5000)
          throw new IllegalStateException("Audio device stopped rendering");
        Thread.sleep(10);
      }
    } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
      catch(RuntimeException failure) { if(serial==mine)fail("Audio playback stopped: "+failure.getMessage()); }
  }
  private void fail(String message) {
    cancelStream();
    java.util.function.Consumer<String> callback=errorListener;
    if(callback!=null)callback.accept(message);
  }
  private void initTrackLocked() {
    int minimum=AudioTrack.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
    if(minimum<=0)throw new IllegalStateException("unsupported output format");
    track=new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder().setUsage(communicationPlayback
            ?AudioAttributes.USAGE_VOICE_COMMUNICATION:AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(Math.max(minimum,sampleRate))
        .setTransferMode(AudioTrack.MODE_STREAM).build();
    trackBaseBytes=positionBytes; writeBytes=positionBytes;
    drainPaddingBytes=0;
    if(android.os.Build.VERSION.SDK_INT>=31)track.setStartThresholdInFrames(1);
  }
  private void stopTrackLocked() {
    AudioTrack oldTrack=track; track=null;
    if(oldTrack!=null) {
      try { oldTrack.pause(); } catch(RuntimeException ignored) {}
      try { oldTrack.flush(); } catch(RuntimeException ignored) {}
      try { oldTrack.release(); } catch(RuntimeException ignored) {}
    }
    Thread old=thread; thread=null;
    if(old!=null && old!=Thread.currentThread())old.interrupt();
  }
  private int bytesToMs(int bytes) { return VoicePlayerMath.bytesToMs(bytes,sampleRate,2,1); }
}
