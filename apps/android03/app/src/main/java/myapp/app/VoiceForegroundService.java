package myapp.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.AudioRouteController;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Independent foreground owner for the live voice microphone and network.
 *
 * The working Voice Button's recorder does not belong to MainActivity. This
 * service adopts that ownership boundary for continuous conversational audio.
 * An Activity may detach, die or be recreated without stopping capture.
 * Mic reads do no network or filesystem work. Network sending has its own
 * bounded queue and cannot silently drop a previously captured PCM block.
 *
 * This is NOT a second SWAAG agent. Its only responsibilities are capture,
 * transport, background audio playback and app-visible lifecycle state.
 */
public final class VoiceForegroundService extends Service {
  public static final String ACTION_START = "myapp.app.voice.START";
  public static final String ACTION_STOP = "myapp.app.voice.STOP";
  private static final String CHANNEL_ID = "voice_session";
  private static final int NOTIFICATION_ID = 1704;
  private static final int PCM_BUFFER_SECONDS = 120;
  private static final int MIC_NO_DATA_GRACE_MS = 3000;
  private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
  private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;

  public interface CaptureObserver {
    void onCaptureChanged(boolean capturing, String microphone, double levelDbfs, String error);
  }
  public final class LocalBinder extends Binder {
    public VoiceForegroundService service() { return VoiceForegroundService.this; }
  }
  private final IBinder binder = new LocalBinder();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Object sessionLock = new Object();
  private final AtomicBoolean capturing = new AtomicBoolean(false);
  private VoicePcmPlayback assistantPlayback;
  private VoicePcmPlayback userPlayback;
  private volatile VoicePcmPlayback activePlayback;
  private volatile int playbackGeneration=-1;
  private volatile String playbackTurn="";
  private volatile String playbackStream="";
  private volatile long playbackOffset;
  private volatile boolean playbackFramed;
  private final java.util.LinkedHashMap<String,String> workerSnapshots=new java.util.LinkedHashMap<>();
  private volatile String questionInventorySnapshot="";
  private volatile boolean serverCanCommit;
  VoicePcmPlayback assistantPlayback() { return assistantPlayback; }
  VoicePcmPlayback userPlayback() { return userPlayback; }

  private volatile WebSocketListener activityListener;
  private volatile CaptureObserver captureObserver;
  private volatile long activityGeneration;
  private volatile WebSocket networkSocket;
  private volatile boolean networkConnected;
  private volatile boolean networkConnecting;
  private volatile boolean connectionDesired;
  private volatile long networkGeneration;
  private volatile boolean foreground;
  private volatile String conversationId = "";
  private volatile String lastHello = "";
  private volatile String lastHistory = "";
  private volatile String lastState = "";
  private volatile String lastAudioStart = "";
  private volatile String microphoneLabel = "";
  private volatile double inputLevelDbfs = -120.0;
  private volatile String captureError = "";
  private volatile boolean captureTransportFailed;
  private volatile boolean automaticCapture=true;
  private volatile boolean manualReady;
  boolean isManualCapture() {return !automaticCapture;}
  boolean hasFinishedManualCapture() {return manualReady;}
  boolean retainRecoveredManualRecording(byte[] data) {
    // Recovery and service binding run on the main looper, like startCapture.
    // Mark the media as an unsent recording so a replacement Activity can adopt
    // it instead of mistaking it for unrelated user-history playback.
    if (capturing.get() || data == null || data.length == 0) return false;
    userPlayback.setStaticPcm(data);
    manualReady = true;
    return true;
  }
  void clearRecoveredManualRecording() {
    if (capturing.get()) return;
    manualReady = false;
    userPlayback.setStaticPcm(null);
  }
  private volatile boolean voiceActivityCues = true;
  private volatile int selectedMicrophoneId = AudioInputOption.DEFAULT_DEVICE_ID;
  private volatile int frameSeq;
  private volatile VoicePcmFrameQueue uplink;
  private volatile Thread captureThread;
  private volatile Thread sendThread;
  private volatile PowerManager.WakeLock captureWakeLock;
  private volatile OkHttpClient http;
  private volatile VoiceAppConfig config;
  private volatile boolean terminating;

  private final WebSocket facade = new WebSocket() {
    @Override public Request request() {
      WebSocket socket = networkSocket;
      return socket == null
          ? new Request.Builder().url(config.websocketUrl).build()
          : socket.request();
    }
    @Override public long queueSize() {
      WebSocket socket = networkSocket;
      return socket == null ? 0L : socket.queueSize();
    }
    @Override public boolean send(String text) { return sendText(text); }
    @Override public boolean send(ByteString bytes) {
      WebSocket socket = networkSocket;
      return socket != null && networkConnected && socket.send(bytes);
    }
    @Override public boolean close(int code, String reason) {
      requestReconnect("client_close_" + code);
      return true;
    }
    @Override public void cancel() { requestReconnect("client_cancel"); }
  };

  @Override public void onCreate() {
    super.onCreate();
    config = VoiceAppConfig.from(this);
    assistantPlayback=new VoicePcmPlayback(config.sampleRate,config.playerMaxBytes);
    userPlayback=new VoicePcmPlayback(config.sampleRate,config.playerMaxBytes);
    assistantPlayback.setRemoteStopListener(()->sendControl("stop_audio","user_stopped_playback",-1));
    userPlayback.setRemoteStopListener(()->sendControl("stop_audio","user_stopped_replay",-1));
    assistantPlayback.setErrorListener(this::playbackFailed);
    userPlayback.setErrorListener(this::playbackFailed);
    http = new OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15L, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          CHANNEL_ID, "Voice session", NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Protected microphone capture and voice-agent connection");
      NotificationManager manager = getSystemService(NotificationManager.class);
      if (manager != null) manager.createNotificationChannel(channel);
    }
    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
    if (power != null) {
      captureWakeLock = power.newWakeLock(
          PowerManager.PARTIAL_WAKE_LOCK, "VoiceAgent:MicrophoneCapture");
      captureWakeLock.setReferenceCounted(false);
    }
    SharedPreferences prefs = getSharedPreferences("voice_agent_history", MODE_PRIVATE);
    conversationId = prefs.getString("conversation_id_v2", "");
    selectedMicrophoneId = prefs.getInt("microphone_device_id_v2",
        AudioInputOption.DEFAULT_DEVICE_ID);
  }

  @Override public IBinder onBind(Intent intent) { return binder; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopCapture();
      return START_NOT_STICKY;
    }
    ensureForeground();
    // Until restart-safe capture journaling is implemented, Android must not
    // restart this service into a false "recording" state after process death.
    return START_NOT_STICKY;
  }

  private void ensureForeground() {
    if (Build.VERSION.SDK_INT >= 29) {
      int type=android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
      if(capturing.get() && Build.VERSION.SDK_INT>=30)type|=android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
      startForeground(NOTIFICATION_ID,notification(capturing.get()?"Microphone active":"Voice connection active"),type);
    } else startForeground(NOTIFICATION_ID, notification("Voice connection active"));
    foreground = true;
  }

  private void updateNotification(String message) {
    if (!foreground) return;
    NotificationManager manager=getSystemService(NotificationManager.class);
    if (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,
        Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
    if (manager != null) manager.notify(NOTIFICATION_ID,notification(message));
  }

  private Notification notification(String detail) {
    Intent open=new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    int flags=PendingIntent.FLAG_UPDATE_CURRENT;
    flags |= PendingIntent.FLAG_IMMUTABLE;
    PendingIntent intent=PendingIntent.getActivity(this,0,open,flags);
    return new NotificationCompat.Builder(this,CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_voice_agent)
        .setContentTitle("Voice Agent")
        .setContentText(detail)
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(intent)
        .build();
  }

  public boolean isCapturing() { return capturing.get(); }
  public boolean isConnected() { return networkConnected; }
  public String microphoneLabel() { return microphoneLabel; }
  public String captureError() { return captureError; }
  public WebSocket facade() { return facade; }

  public void attach(WebSocketListener listener, CaptureObserver observer,
                     long uiGeneration, String requestedConversation) {
    synchronized (sessionLock) {
      activityListener = listener;
      captureObserver = observer;
      activityGeneration = uiGeneration;
      if (requestedConversation != null && !requestedConversation.isEmpty()
          && !requestedConversation.equals(conversationId)) {
        conversationId = requestedConversation;
        requestReconnect("conversation_identity_changed");
      }
      connectionDesired = true;
    }
    notifyCaptureObserver();
    if (networkConnected) {
      // A new Activity must see a full current-state snapshot, not rely on
      // events that arrived while it was absent.
      listener.onOpen(facade, null);
      forwardCachedState(listener);
      sendText("{\"type\":\"history_get\"}");
    } else {
      connect();
    }
  }

  public void detach(WebSocketListener listener) {
    if (activityListener == listener) {
      activityListener = null;
      captureObserver = null;

    }
  }

  private void forwardCachedState(WebSocketListener listener) {
    if (!lastHello.isEmpty()) listener.onMessage(facade,lastHello);
    if (!lastHistory.isEmpty()) listener.onMessage(facade,lastHistory);
    if (!lastState.isEmpty()) listener.onMessage(facade,lastState);
    if (!lastAudioStart.isEmpty()) listener.onMessage(facade,lastAudioStart);
    synchronized(workerSnapshots) {
      for(String snapshot:workerSnapshots.values())listener.onMessage(facade,snapshot);
    }
    if(!questionInventorySnapshot.isEmpty())listener.onMessage(facade,questionInventorySnapshot);
  }

  public boolean sendText(String payload) {
    WebSocket socket=networkSocket;
    return socket != null && networkConnected && socket.send(payload);
  }

  private void emitClientState() {
    SharedPreferences prefs=getSharedPreferences("voice_agent_history",MODE_PRIVATE);
    try {
      JSONObject state=new JSONObject();
      state.put("type","client_state");
      state.put("media_protocol",1);
      state.put("auto_commit_supported",true);
      state.put("build","Voice Agent background capture");
      state.put("conversation",conversationId);
      boolean autoTranscribe=prefs.getBoolean("transcribe_automatically_v1",true);
      state.put("auto_transcribe",autoTranscribe);
      state.put("auto_send",serverCanCommit && autoTranscribe && prefs.getBoolean("send_automatically_v3",true) && !captureTransportFailed);
      state.put("microphone_label",microphoneLabel);
      state.put("draft_claimed",prefs.getBoolean("draft_claimed_v1",false));
      sendText(state.toString());
    } catch(Exception ignored) {}
  }

  private String withUiMediaGeneration(String text) {
    try {
      JSONObject value=new JSONObject(text);
      if ("media_ack".equals(value.optString("type"))) {
        value.put("connection_generation",activityGeneration);
        return value.toString();
      }
    } catch(Exception ignored) {}
    return text;
  }

  private void notifyCaptureObserver() {
    CaptureObserver observer=captureObserver;
    if (observer == null) return;
    main.post(() -> {
      if (observer == captureObserver)
        observer.onCaptureChanged(capturing.get(),microphoneLabel,inputLevelDbfs,captureError);
    });
  }
  private void connect() {
    synchronized (sessionLock) {
      if (terminating || !connectionDesired || networkConnected
          || networkConnecting || http == null || conversationId.isEmpty()) return;
      networkConnecting=true;
      long generation=++networkGeneration;
      WebSocket old=networkSocket;
      networkSocket=null;
      if (old != null) old.cancel();
      try {
        String url=config.websocketUrl + "?conversation="
            + java.net.URLEncoder.encode(conversationId,"UTF-8")
            + "&generation=" + generation;
        if (BuildConfig.VOICE_AUTH_TOKEN == null
            || BuildConfig.VOICE_AUTH_TOKEN.isEmpty())
          throw new IllegalStateException("Missing voice transport credential");
        http.newWebSocket(new Request.Builder().url(url)
            .header("Authorization","Bearer "+BuildConfig.VOICE_AUTH_TOKEN).build(),
            new WebSocketListener() {
              @Override public void onOpen(WebSocket socket, Response response) {
                if (generation != networkGeneration || terminating) {
                  socket.cancel();
                  return;
                }
                networkSocket=socket;
                networkConnected=true;
                networkConnecting=false;
                playbackGeneration=-1;
                serverCanCommit=false;lastHello="";
                emitClientState();
                WebSocketListener listener=activityListener;
                if (listener != null) listener.onOpen(facade,response);
              }

              @Override public void onMessage(WebSocket socket, String text) {
                if (generation != networkGeneration || terminating) return;
                try {
                  JSONObject value=new JSONObject(text);
                  String type=value.optString("type");
                  if ("hello".equals(type)) {
                    lastHello=text;serverCanCommit=value.optInt("commit_protocol",0)==1;
                    emitClientState();
                  }
                  else if ("history".equals(type)) lastHistory=text;
                  else if ("state".equals(type)) lastState=text;
                  else if ("worker".equals(type)) {
                    if(value.optBoolean("question_inventory_complete",false)) {
                      questionInventorySnapshot=text;
                    } else synchronized(workerSnapshots) {
                      workerSnapshots.put(value.optString("worker_id","global"),text);
                      while(workerSnapshots.size()>128)workerSnapshots.remove(workerSnapshots.keySet().iterator().next());
                    }
                  }
                  if("turn_proposal".equals(type)) {
                    SharedPreferences prefs=getSharedPreferences("voice_agent_history",MODE_PRIVATE);
                    boolean eligible=serverCanCommit && prefs.getBoolean("transcribe_automatically_v1",true)
                        && prefs.getBoolean("send_automatically_v3",true)
                        && !prefs.getBoolean("draft_claimed_v1",false)
                        && prefs.getString("send_payload_v1","").isEmpty()
                        && VoiceSubmissionPersistence.SHARED.current() == null
                        && !captureTransportFailed;
                    if(eligible) {
                      JSONObject commit=new JSONObject();commit.put("type","commit_turn");
                      commit.put("proposal_id",value.getString("proposal_id"));
                      commit.put("text",value.getString("text"));sendText(commit.toString());
                    } else sendControl("draft_claim","client_retained_draft",-1);
                  }
                  if("audio".equals(type) || "replay".equals(type))handleMediaControl(value,text);

                } catch(Exception ignored) {}
                WebSocketListener listener=activityListener;
                if (listener != null) listener.onMessage(facade,withUiMediaGeneration(text));
              }

              @Override public void onMessage(WebSocket socket, ByteString data) {
                if (generation != networkGeneration || terminating) return;
                acceptMedia(data.toByteArray());
                WebSocketListener listener=activityListener;
                if(listener!=null)listener.onMessage(facade,data);
              }

              @Override public void onClosed(WebSocket socket, int code, String reason) {
                transportLost(generation,null,code,reason);
              }
              @Override public void onFailure(WebSocket socket, Throwable problem, Response response) {
                transportLost(generation,problem,1006,"network failure");
              }
            });
      } catch(Exception problem) {
        networkConnected=false;
        networkConnecting=false;
        scheduleReconnect(generation);
      }
    }
  }

  private void transportLost(long generation, Throwable failure, int code, String reason) {
    if (generation != networkGeneration || terminating) return;
    networkConnected=false;
    networkConnecting=false;
    networkSocket=null;
    if(capturing.get() && automaticCapture) {
      captureTransportFailed=true;
      captureError="Connection lost. Recording locally; stop and review Saved recordings.";
      notifyCaptureObserver();
    }
    WebSocketListener listener=activityListener;
    if (listener != null) {
      if (failure != null) listener.onFailure(facade,failure,null);
      else listener.onClosed(facade,code,reason);
    }
    scheduleReconnect(generation);
  }

  private void scheduleReconnect(long generation) {
    if (terminating || !connectionDesired) return;
    main.postDelayed(() -> {
      if (generation == networkGeneration && !networkConnected
          && !terminating && connectionDesired) connect();
    },1500L);
  }

  public void requestReconnect(String reason) {
    long generation;
    WebSocket old;
    synchronized(sessionLock) {
      networkConnected=false;
      networkConnecting=false;
      generation=++networkGeneration;
      old=networkSocket;
      networkSocket=null;
    }
    if (old != null) old.cancel();
    if (connectionDesired) main.post(() -> connect());
  }

  private AudioInputOption selectInput(int requestedId) {
    List<AudioInputOption> values=AudioInputCatalog.list(this);
    for (AudioInputOption value:values)
      if (value.getDeviceId()==requestedId) return value;
    for (AudioInputOption value:values)
      if (value.getCategory()==AudioInputOption.Category.BUILT_IN) return value;
    return values.isEmpty() ? AudioInputOption.systemDefault() : values.get(0);
  }

  private static final class RecordSetup {
    final AudioRecord recorder;
    final int sampleRate;
    RecordSetup(AudioRecord recorder,int sampleRate) {
      this.recorder=recorder;
      this.sampleRate=sampleRate;
    }
  }

  private RecordSetup createRecorder(AudioInputOption input) {
    if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)
        !=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission revoked");
    int source=input != null && input.isBluetooth()
        ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC;
    int[] rates=input != null && input.isBluetooth()
        ? new int[]{config.sampleRate,8000,32000,48000}
        : new int[]{config.sampleRate,48000,44100,32000};
    StringBuilder errors=new StringBuilder();
    for(int rate:rates) {
      int min=AudioRecord.getMinBufferSize(rate,CHANNEL_IN,FORMAT);
      if(min<=0) continue;
      AudioRecord recorder=null;
      try {
        recorder=new AudioRecord(source,rate,CHANNEL_IN,FORMAT,
            Math.max(min*2,rate*2*4));
        if(recorder.getState()==AudioRecord.STATE_INITIALIZED)
          return new RecordSetup(recorder,rate);
      } catch(Exception failure) {
        errors.append(rate).append(' ').append(failure.getClass().getSimpleName()).append("; ");
      }
      if (recorder != null) recorder.release();
    }
    throw new IllegalStateException("No usable microphone rate: "+errors);
  }

  public boolean startCapture(int microphoneId, boolean cues) {return startCapture(microphoneId,cues,true);}
  public boolean startCapture(int microphoneId, boolean cues,boolean automatic) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      captureError="Microphone permission is required";
      notifyCaptureObserver();
      return false;
    }
    if (!capturing.compareAndSet(false,true)) return true;
    try {ensureForeground();}catch(RuntimeException failure) {
      failCapture("Android could not start protected microphone capture");return false;
    }
    acquireWakeLock();
    if (!capturing.get()) return false;
    selectedMicrophoneId=microphoneId;
    automaticCapture=automatic;manualReady=false;
    if(!automatic)userPlayback.setStaticPcm(null);
    voiceActivityCues=cues;
    captureError="";
    frameSeq=0;
    captureTransportFailed=false;
    VoicePcmFrameQueue queue=new VoicePcmFrameQueue(
        (long)config.sampleRate*2L*PCM_BUFFER_SECONDS);
    uplink=queue;
    Thread sender=new Thread(() -> sendLoop(queue),"voice-agent-network-uplink");
    sendThread=sender;
    if(automatic)sender.start();
    Thread microphone=new Thread(() -> captureLoop(queue),"voice-agent-microphone");
    microphone.setPriority(Thread.MAX_PRIORITY);
    captureThread=microphone;
    microphone.start();
    updateNotification("Microphone active");
    notifyCaptureObserver();
    return true;
  }

  public void stopCapture() {
    capturing.set(false);
    notifyCaptureObserver();
    // The capture thread closes the queue after draining AudioRecord. The
    // uplink worker emits end_utterance after queued samples are sent.
  }

  private void failCapture(String explanation) {
    captureError=explanation;
    captureTransportFailed=true;
    capturing.set(false);
    updateNotification("Microphone error — open Voice Agent");
    notifyCaptureObserver();
  }

  private void acquireWakeLock() {
    PowerManager.WakeLock lock=captureWakeLock;
    if (lock == null) return;
    try {
      // Extend the deadline on each five-minute refresh. Leaving an old
      // timeout unchanged silently released capture halfway through a session.
      if (lock.isHeld()) lock.release();
      lock.acquire(10*60*1000L);
    } catch(RuntimeException failure) {
      failCapture("Could not maintain microphone CPU wake lock");
    }
  }

  private void releaseWakeLock() {
    PowerManager.WakeLock lock=captureWakeLock;
    if(lock!=null && lock.isHeld()) {
      try{lock.release();}catch(RuntimeException ignored){}
    }
  }

  private void sendControl(String type,String reason,int sequence) {
    try {
      JSONObject value=new JSONObject();
      value.put("type",type);
      value.put("reason",reason);
      value.put("seq",sequence);
      sendText(value.toString());
    } catch(Exception ignored){}
  }

  private void sendLoop(VoicePcmFrameQueue queue) {
    long disconnectedSince=0;
    try {
      while(!Thread.currentThread().isInterrupted()) {
        if(captureTransportFailed)return;
        byte[] frame=queue.awaitHead(100);
        if(frame==null) {
          if(queue.isClosed()) break;
          continue;
        }
        WebSocket socket=networkSocket;
        if(socket==null || !networkConnected ||
            socket.queueSize() > config.maxQueuedAudioBytes) {
          if(disconnectedSince==0) disconnectedSince=SystemClock.elapsedRealtime();
          Thread.sleep(25);
          continue;
        }
        disconnectedSince=0;
        if(!socket.send(ByteString.of(frame))) {
          requestReconnect("pcm_send_rejected");
          Thread.sleep(30);
          continue;
        }
        if(!queue.commitAcceptedHead(frame))
          throw new IllegalStateException("PCM transport queue ownership changed");
      }
      if(queue.isClosed() && queue.pendingBytes()==0 && !captureTransportFailed)
        sendControl("end_utterance","capture_stopped",frameSeq);
    } catch(InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch(Exception failure) {
      failCapture("Audio network sender failed: "+failure.getClass().getSimpleName());
    } finally {
      if(queue==uplink && !capturing.get()) {
        releaseWakeLock();
        // The service remains the reply and connection owner after Mic stops.
        if(foreground)main.post(this::ensureForeground);
      }
    }
  }
  private void captureLoop(VoicePcmFrameQueue queue) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
    AudioRecord recorder=null;
    AudioRouteController route=null;
    AcousticEchoCanceler echo=null;
    AutomaticGainControl gain=null;
    NoiseSuppressor noise=null;
    VoiceCaptureJournal journal=null;
    try {
      journal=new VoiceCaptureJournal(new java.io.File(getFilesDir(),"voice_recordings"),
          getResources().getInteger(R.integer.voice_capture_archive_max_bytes),config.manualRecordingMaxBytes,
          automaticCapture?null:new java.io.File(getFilesDir(),"voice_unsent_manual.pcm"));
      AudioInputOption input=selectInput(selectedMicrophoneId);
      route=new AudioRouteController(this);
      AudioDeviceInfo requested=route.prepare(this,input);
      RecordSetup setup=createRecorder(input);
      recorder=setup.recorder;
      int sampleRate=setup.sampleRate;
      if (input.getCategory()!=AudioInputOption.Category.BUILT_IN
          && !route.applyPreferredDevice(recorder,requested,input))
        throw new IllegalStateException("Selected microphone routing rejected by Android");
      int sid=recorder.getAudioSessionId();
      if(AcousticEchoCanceler.isAvailable()) {
        echo=AcousticEchoCanceler.create(sid);
        if(echo!=null) echo.setEnabled(true);
      }
      if(NoiseSuppressor.isAvailable()) {
        noise=NoiseSuppressor.create(sid);
        if(noise!=null) noise.setEnabled(true);
      }
      if(AutomaticGainControl.isAvailable()) {
        gain=AutomaticGainControl.create(sid);
        if(gain!=null) gain.setEnabled(true);
      }
      recorder.startRecording();
      if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
        throw new IllegalStateException("Android microphone did not begin recording");
      AudioDeviceInfo routed=route.waitForRoutedInput(recorder,requested,input);
      microphoneLabel=AudioInputCatalog.describe(routed);
      notifyCaptureObserver();
      sendControl("voice_activity","capture_ready",frameSeq);
      int wanted=Math.max(1,sampleRate*config.frameMs/1000);
      short[] capture=new short[wanted];
      VoiceCaptureReadPolicy readHealth=new VoiceCaptureReadPolicy(MIC_NO_DATA_GRACE_MS);
      LocalVadGate vad=new LocalVadGate(config.frameMs);
      long previousLevel=0;
      long lastWakeLockRenewed=SystemClock.elapsedRealtime();
      while(capturing.get()) {
        int count=0;
        while(capturing.get() && count<wanted) {
          int n=recorder.read(capture,count,wanted-count,AudioRecord.READ_BLOCKING);
          readHealth.observe(n,SystemClock.elapsedRealtime());
          if(n>0) count+=n;
          else if(n==0) SystemClock.sleep(20L);
        }
        if(count==0)break;
        byte[] pcm=convertPcm(capture,count,sampleRate,config.sampleRate);
        journal.append(pcm);
        inputLevelDbfs=pcmDbfs(pcm);
        LocalVadGate.Result activity=vad.accept(pcm,inputLevelDbfs);
        if(activity.started && automaticCapture) {
          sendControl("voice_activity","start",frameSeq);
          if(voiceActivityCues && input.getCategory()!=AudioInputOption.Category.BUILT_IN)
            VoiceActivityCue.start();
        }
        if(automaticCapture && !captureTransportFailed && !queue.offer(pcm,pcm.length)) {
          failCapture("Microphone network buffer full: speech could not be protected");
          break;
        }
        if(activity.stopped && automaticCapture) {
          sendControl("voice_activity","stop",frameSeq);
          if(voiceActivityCues && input.getCategory()!=AudioInputOption.Category.BUILT_IN)
            VoiceActivityCue.stop();
        }
        frameSeq++;
        long now=SystemClock.elapsedRealtime();
        if(now-previousLevel>=config.levelUpdateMs) {
          previousLevel=now;
          notifyCaptureObserver();
        }
        if(now-lastWakeLockRenewed>=5*60*1000L) {
          acquireWakeLock();
          lastWakeLockRenewed=now;
        }
      }
    } catch(Exception failure) {
      failCapture("Microphone stopped: "+failure.getClass().getSimpleName()
          +(failure.getMessage()==null ? "" : " — "+failure.getMessage()));
    } finally {
      try { if(recorder!=null && recorder.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)
        recorder.stop(); }catch(Exception ignored){}
      try {if(recorder!=null)recorder.release();}catch(Exception ignored){}
      try {if(route!=null)route.release();}catch(Exception ignored){}
      try {if(echo!=null)echo.release();}catch(Exception ignored){}
      try {if(noise!=null)noise.release();}catch(Exception ignored){}
      try {if(gain!=null)gain.release();}catch(Exception ignored){}
      capturing.set(false);
      queue.close();
      if(journal!=null)try {journal.close();}catch(java.io.IOException failure) {
        captureError="Recording storage failed; inspect saved audio";
      }
      if(!automaticCapture) {
        try {
          java.io.File recording=new java.io.File(getFilesDir(),"voice_unsent_manual.pcm");
          if(recording.isFile() && recording.length()>0 && recording.length()<=config.manualRecordingMaxBytes) {
            byte[] data=new byte[(int)recording.length()];
            try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(recording))) {in.readFully(data);}
            userPlayback.setStaticPcm(data);manualReady=true;
          }
        }catch(java.io.IOException failure) {captureError="Recording is saved but could not be opened";}
        releaseWakeLock();
      }
      notifyCaptureObserver();
    }
  }

  private static byte[] convertPcm(short[] input,int count,int inputRate,int outputRate) {
    int outputCount=inputRate==outputRate?count:
        Math.max(1,(int)Math.round(count*(outputRate/(double)inputRate)));
    byte[] pcm=new byte[outputCount*2];
    double step=inputRate/(double)outputRate;
    for(int i=0;i<outputCount;i++) {
      short value;
      if(inputRate==outputRate) value=input[i];
      else {
        double source=i*step;
        int left=Math.min(count-1,(int)Math.floor(source));
        int right=Math.min(count-1,left+1);
        double fraction=source-left;
        int mixed=(int)Math.round(input[left]*(1-fraction)+input[right]*fraction);
        value=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,mixed));
      }
      pcm[i*2]=(byte)(value & 0xff);
      pcm[i*2+1]=(byte)((value>>8)&0xff);
    }
    return pcm;
  }

  private static double pcmDbfs(byte[] pcm) {
    int n=pcm.length/2;
    if(n==0)return -120.0;
    double sum=0;
    for(int i=0;i<n;i++) {
      int lower=pcm[i*2]&255;
      int upper=pcm[i*2+1];
      short sample=(short)((upper<<8)|lower);
      sum+=(double)sample*sample;
    }
    return 20.0*Math.log10(Math.max(1.0,Math.sqrt(sum/n))/32768.0);
  }

  private void handleMediaControl(JSONObject value,String text) {
    String event=value.optString("event");
    int generation=value.optInt("generation",-1);
    if("cancel".equals(event)) {
      if(generation<playbackGeneration)return;
      assistantPlayback.cancelStream(); userPlayback.cancelStream();
      activePlayback=null; lastAudioStart="";
      return;
    }
    if("start".equals(event)) {
      if(generation<playbackGeneration)return;
      VoicePcmPlayback previous=activePlayback;
      if(previous!=null)previous.cancelStream();
      VoicePcmPlayback player="user".equals(value.optString("kind"))?userPlayback:assistantPlayback;
      playbackGeneration=generation; playbackTurn=value.optString("turn_id");
      playbackStream=value.optString("stream_id");playbackOffset=0;
      playbackFramed=value.optInt("media_protocol",0)==1;
      final String turn=playbackTurn;
      final String stream=playbackStream;
      final boolean reply="audio".equals(value.optString("type"));
      player.setCompletionListener(()->{
        if(reply) {
          try {
            JSONObject ack=new JSONObject(); ack.put("type","playback_completed");
            ack.put("turn_id",turn); ack.put("generation",generation);
            ack.put("stream_id",stream); sendText(ack.toString());
          } catch(Exception ignored) {}
        }
        if(activePlayback==player && playbackGeneration==generation && playbackStream.equals(stream))activePlayback=null;
        WebSocketListener listener=activityListener;
        if(reply && listener!=null)listener.onMessage(facade,"{\"type\":\"state\",\"state\":\"playback_completed\"}");
      });
      player.startStream(true,true,reply); activePlayback=player; lastAudioStart=text;
      main.post(this::ensureForeground);
    } else if("end".equals(event) && generation==playbackGeneration
        && (playbackStream.isEmpty() || playbackStream.equals(value.optString("stream_id")))) {
      VoicePcmPlayback player=activePlayback;
      if(player!=null)player.endStream();
      lastAudioStart="";
    }
  }
  private void acceptMedia(byte[] frame) {
    VoicePcmPlayback player=activePlayback;
    if(player==null)return;
    byte[] pcm=frame;
    if(playbackFramed) {
      try {
        VoiceMediaFrame.Decoded decoded=VoiceMediaFrame.decode(frame);
        if(!playbackStream.equals(decoded.streamId))return;
        if(decoded.offset<playbackOffset)return;
        if(decoded.offset!=playbackOffset)throw new IllegalArgumentException("missing audio frame");
        pcm=decoded.pcm;playbackOffset+=pcm.length;
      }catch(IllegalArgumentException invalid) {playbackFailed("Voice audio transport interrupted");return;}
    }
    if(!player.append(pcm))playbackFailed("Voice reply exceeds the playback buffer");
  }
  private void playbackFailed(String error) {
    VoicePcmPlayback player=activePlayback;
    if(player!=null)player.cancelStream();
    activePlayback=null; lastAudioStart="";
    updateNotification(error);
    sendControl("stop_audio","playback_failed",-1);
  }

  @Override public void onDestroy() {
    terminating=true;
    connectionDesired=false;
    capturing.set(false);
    WebSocket socket=networkSocket;
    if(socket!=null)socket.cancel();
    Thread microphone=captureThread;
    if(microphone!=null)microphone.interrupt();
    Thread sender=sendThread;
    if(sender!=null)sender.interrupt();
    assistantPlayback.cancelStream();
    userPlayback.cancelStream();
    releaseWakeLock();
    if(http!=null)http.dispatcher().executorService().shutdown();
    super.onDestroy();
  }
}
