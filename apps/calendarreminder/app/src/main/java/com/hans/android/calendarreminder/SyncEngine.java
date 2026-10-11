package com.hans.android.calendarreminder;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class SyncEngine {
    private static final Object MUTEX=new Object();

    static String run(Context context) {
        synchronized (MUTEX) {
            CalendarStore store=new CalendarStore(context);
            if(!store.configured()) {
                store.markError("Connect to Raspi in Settings.");
                return "Connect to Raspi in Settings.";
            }
            HttpURLConnection http=null;
            try {
                URL url=new URL(store.server()+"/mobile-calendar/v1/events");
                if(!"https".equals(url.getProtocol()))
                    throw new IOException("HTTPS connection required");
                http=(HttpURLConnection)url.openConnection();
                http.setInstanceFollowRedirects(false);
                http.setConnectTimeout(10000);http.setReadTimeout(16000);
                http.setRequestMethod("GET");
                http.setRequestProperty("Accept","application/json");
                http.setRequestProperty("Authorization","Bearer "+store.token());
                http.setRequestProperty("Cache-Control","no-store");
                int status=http.getResponseCode();
                if(status==401) throw new IOException("Pairing token rejected. Check Settings.");
                if(status!=200) throw new IOException("Raspi returned HTTP "+status);
                if(http.getContentLengthLong()>6*1024*1024)
                    throw new IOException("Response too large");
                byte[] content;
                try(InputStream in=http.getInputStream()) {
                    content=CalendarStore.readAll(in,6*1024*1024);
                }
                JSONObject response=new JSONObject(new String(content,StandardCharsets.UTF_8));
                List<CalendarEvent> previous=store.events();
                // If the response is invalid, leave the last good cached events and alarms intact.
                store.replaceSnapshot(response);
                List<CalendarEvent> next=store.events();
                int scheduled=ReminderScheduler.reschedule(context,previous,next);
                return "Synced "+next.size()+" appointments; "+scheduled+" reminders scheduled";
            } catch (Exception ex) {
                String reason=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
                if(reason.length()>180) reason=reason.substring(0,180);
                store.markError(reason);
                return "Sync failed: "+reason;
            } finally {
                if(http!=null) http.disconnect();
            }
        }
    }
}
