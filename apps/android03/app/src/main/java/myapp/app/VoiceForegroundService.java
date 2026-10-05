package myapp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public final class VoiceForegroundService extends Service {
  public static final String ACTION_START = "myapp.app.voice.START";
  public static final String ACTION_STOP = "myapp.app.voice.STOP";
  private static final String CHANNEL_ID = "voice_session";
  private static final int NOTIFICATION_ID = 1704;

  @Override public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          CHANNEL_ID, "Voice session", NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Keeps the active voice conversation listening in the background.");
      NotificationManager manager = getSystemService(NotificationManager.class);
      if (manager != null) manager.createNotificationChannel(channel);
    }
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    startForeground(NOTIFICATION_ID, notification());
    return START_NOT_STICKY;
  }

  private Notification notification() {
    Intent open = new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
    PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_voice_agent)
        .setContentTitle("Voice Agent")
        .setContentText("Voice conversation is active")
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(pending)
        .build();
  }

  @Override public IBinder onBind(Intent intent) {
    return null;
  }
}
