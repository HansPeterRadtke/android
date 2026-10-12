package com.hans.android.calendarreminder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * User-visible, continuous calendar change listener.
 * Android exact alarms remain locally scheduled if this connection disappears.
 */
public final class LiveSyncService extends Service {
    private static final String NOTIFICATION_CHANNEL = "raspi_calendar_live";
    private static final int NOTIFICATION_ID = 13112;
    public static final String ACTION_SYNC_COMPLETE = "com.hans.android.calendarreminder.SYNC_COMPLETE";
    private volatile HttpURLConnection activeConnection;
    private volatile boolean stopped = false;
    private volatile Thread worker;
    private final Object threadLock = new Object();

    public static void ensureRunning(Context context) {
        CalendarStore store=new CalendarStore(context);
        if(!store.configured() || !store.liveEnabled()) return;
        Intent launch=new Intent(context,LiveSyncService.class);
        if(Build.VERSION.SDK_INT>=26) {
            context.startForegroundService(launch);
        } else {
            context.startService(launch);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context,LiveSyncService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL, "Calendar live synchronization", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Required to watch Raspi for immediate appointment changes");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification statusNotification() {
        Intent open = new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,13112,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Raspi Calendar")
            .setContentText("Automatic appointment updates active")
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        CalendarStore store=new CalendarStore(this);
        if(!store.configured() || !store.liveEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if(Build.VERSION.SDK_INT>=34) {
                startForeground(NOTIFICATION_ID,statusNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID,statusNotification());
            }
        } catch (SecurityException | IllegalStateException ex) {
            store.setLiveStatus("Android blocked continuous synchronization");
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized(threadLock) {
            if(worker==null || !worker.isAlive()) {
                stopped=false;
                worker=new Thread(this::liveLoop,"raspi-calendar-watch");
                worker.start();
            } else {
                // Existing worker will reconnect using current saved credentials.
                HttpURLConnection previous=activeConnection;
                if(previous!=null) previous.disconnect();
                worker.interrupt();
            }
        }
        return START_STICKY;
    }

    private void announceUpdate() {
        Intent update=new Intent(ACTION_SYNC_COMPLETE);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }

    private boolean refresh() {
        String message=SyncEngine.run(getApplicationContext());
        announceUpdate();
        return !message.startsWith("Sync failed:") && !message.startsWith("Connect ");
    }

    private void liveLoop() {
        long retryMillis=1000;
        CalendarStore store=new CalendarStore(this);
        store.setLiveStatus("Connecting to Raspi");
        boolean first=true;
        while(!stopped) {
            if(!store.configured() || !store.liveEnabled()) break;
            HttpURLConnection http=null;
            try {
                String revision=store.lastRevision();
                if(first || !revision.matches("[a-f0-9]{64}") || retryMillis>1000) {
                    if(!refresh()) throw new IOException("Initial synchronization failed");
                    revision=store.lastRevision();
                    first=false;
                }
                if(!revision.matches("[a-f0-9]{64}")) {
                    throw new IOException("Raspi does not provide change notifications");
                }
                String url=store.server()+"/mobile-calendar/v1/watch?since="+revision+"&wait=28";
                http=(HttpURLConnection)new URL(url).openConnection();
                activeConnection=http;
                http.setInstanceFollowRedirects(false);
                http.setConnectTimeout(10000);
                http.setReadTimeout(40000);
                http.setRequestMethod("GET");
                http.setRequestProperty("Accept","application/json");
                http.setRequestProperty("Cache-Control","no-store");
                http.setRequestProperty("Authorization","Bearer "+store.token());
                int code=http.getResponseCode();
                if(code==401) throw new IOException("Pairing expired or rejected");
                if(code!=200) throw new IOException("Raspi watch HTTP "+code);
                byte[] raw;
                try(InputStream input=http.getInputStream()) {
                    raw=CalendarStore.readAll(input,4096);
                }
                JSONObject update=new JSONObject(new String(raw,StandardCharsets.UTF_8));
                if(!update.has("changed") || !update.optString("revision").matches("[a-f0-9]{64}"))
                    throw new IOException("Invalid Raspi watch response");
                if(update.optBoolean("changed")) {
                    // Re-fetch complete authoritative event snapshot and schedule local alarms.
                    if(!refresh()) throw new IOException("Changed-event synchronization failed");
                }
                store.setLiveStatus("Connected; appointment changes automatic");
                retryMillis=1000;
            } catch (Exception exception) {
                if(stopped) break;
                String reason=exception.getMessage()==null?
                    exception.getClass().getSimpleName():exception.getMessage();
                if(reason.length()>90) reason=reason.substring(0,90);
                store.setLiveStatus("Reconnecting: "+reason);
                try {
                    Thread.sleep(retryMillis);
                } catch(InterruptedException ignored) {
                    Thread.interrupted();
                }
                retryMillis=Math.min(60000,retryMillis*2);
            } finally {
                if(http!=null) http.disconnect();
                if(activeConnection==http) activeConnection=null;
            }
        }
        store.setLiveStatus("Live updates stopped");
        stopSelf();
    }

    @Override public void onDestroy() {
        stopped=true;
        HttpURLConnection connection=activeConnection;
        if(connection!=null) connection.disconnect();
        Thread t=worker;
        if(t!=null) t.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
