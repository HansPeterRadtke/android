package com.hans.android.calendarreminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CalendarStore {
    static final String DEFAULT_SERVER = "https://raspi.jonnyontherun.org";
    static final String PREFS = "calendar_settings";
    private final Context app;
    private final SharedPreferences prefs;
    private final AtomicFile data;

    CalendarStore(Context context) {
        app=context.getApplicationContext();
        prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        data=new AtomicFile(new File(app.getFilesDir(),"raspi_calendar_cache.json"));
    }

    String server() { return prefs.getString("server",DEFAULT_SERVER); }
    String token() { return prefs.getString("token",""); }
    boolean configured() { return token().length()>20 && server().startsWith("https://"); }
    int leadMinutes() { return prefs.getInt("leadMinutes",10); }
    long lastSuccessMs() { return prefs.getLong("lastSuccessMs",0); }
    String lastError() { return prefs.getString("lastError",""); }
    String remoteSync() { return prefs.getString("remoteSync",""); }
    int invalidCount() { return prefs.getInt("invalidCount",0); }

    static String validatedServer(String value) {
        String url=value.trim().replaceAll("/+$","");
        try {
            java.net.URI u=new java.net.URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo()!=null ||
                u.getHost()==null || u.getHost().isEmpty() || u.getQuery()!=null ||
                u.getFragment()!=null || u.getPort()!=-1)
                throw new IllegalArgumentException("Use an HTTPS server address without a password, query or port.");
            String path=u.getPath();
            if (path!=null && !path.isEmpty() && !"/".equals(path))
                throw new IllegalArgumentException("Enter only the server address, without /mobile-calendar.");
            return "https://"+u.getHost().toLowerCase(Locale.ROOT);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Enter a valid HTTPS server address.");
        }
    }

    synchronized void saveConnection(String server,String secret) {
        if (secret==null || secret.trim().length()<32) throw new IllegalArgumentException("Invalid connection token.");
        String target=validatedServer(server);
        String cleaned=secret.trim();
        boolean changed=!target.equals(server()) || !cleaned.equals(token());
        if(changed) {
            data.delete();
            prefs.edit().remove("delivered").commit();
        }
        prefs.edit().putString("server",target)
            .putString("token",cleaned).remove("lastError")
            .remove("remoteSync").remove("lastSuccessMs").commit();
    }
    void setLeadMinutes(int minutes) {
        if (minutes<0 || minutes>1440) throw new IllegalArgumentException("Invalid notification lead time");
        prefs.edit().putInt("leadMinutes",minutes).commit();
    }
    void markError(String error) {
        String cleaned=error==null?"Unknown sync error":error;
        prefs.edit().putString("lastError",cleaned.length()>220?cleaned.substring(0,220):cleaned).commit();
    }

    synchronized List<CalendarEvent> events() {
        try (FileInputStream in=data.openRead()) {
            byte[] raw=readAll(in, 6*1024*1024);
            JSONObject j=new JSONObject(new String(raw,StandardCharsets.UTF_8));
            return parseEvents(j);
        } catch (IOException|JSONException e) {
            return new ArrayList<>();
        }
    }

    static List<CalendarEvent> parseEvents(JSONObject snapshot) throws JSONException {
        if (snapshot.getInt("version")!=1) throw new JSONException("Unsupported feed version");
        JSONArray j=snapshot.getJSONArray("events");
        if (j.length()>5000) throw new JSONException("Too many events");
        ArrayList<CalendarEvent> results=new ArrayList<>(j.length());
        HashSet<String> keys=new HashSet<>();
        for (int i=0;i<j.length();i++) {
            CalendarEvent event=CalendarEvent.fromJson(j.getJSONObject(i));
            if (!keys.add(event.key)) throw new JSONException("Duplicate event key");
            results.add(event);
        }
        results.sort(Comparator.comparingLong((CalendarEvent x)->x.startMs).thenComparing(x->x.key));
        return results;
    }

    synchronized void replaceSnapshot(JSONObject snapshot) throws IOException, JSONException {
        // Validate every record before overwriting reliable offline cache.
        List<CalendarEvent> events=parseEvents(snapshot);
        if (snapshot.isNull("lastSync") || snapshot.optString("lastSync","").isEmpty())
            throw new JSONException("Server has not synchronized Google Calendar yet");
        byte[] raw=snapshot.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length>6*1024*1024) throw new IOException("Calendar response exceeds cache limit");
        FileOutputStream out=data.startWrite();
        try {
            out.write(raw); data.finishWrite(out);
        } catch (IOException e) {
            data.failWrite(out);
            throw e;
        }
        prefs.edit().putLong("lastSuccessMs",System.currentTimeMillis())
             .putString("remoteSync",snapshot.optString("lastSync",""))
             .putInt("invalidCount",snapshot.optInt("invalidEventCount",0))
             .remove("lastError").commit();
        pruneDelivered(events);
    }

    static byte[] readAll(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] chunk=new byte[8192]; int n;
        while ((n=in.read(chunk))!=-1) {
            if (out.size()+n>max) throw new IOException("Server response too large");
            out.write(chunk,0,n);
        }
        return out.toByteArray();
    }

    synchronized boolean isDelivered(CalendarEvent event) {
        return prefs.getStringSet("delivered",Collections.emptySet()).contains(event.deliveryKey(leadMinutes()));
    }
    synchronized void markDelivered(CalendarEvent event) {
        HashSet<String> delivered=new HashSet<>(prefs.getStringSet("delivered",Collections.emptySet()));
        delivered.add(event.deliveryKey(leadMinutes()));
        prefs.edit().putStringSet("delivered",delivered).commit();
    }
    private void pruneDelivered(List<CalendarEvent> events) {
        Set<String> old=prefs.getStringSet("delivered",Collections.emptySet());
        if (old.size()<1000) return;
        HashSet<String> valid=new HashSet<>();
        for (CalendarEvent e:events) valid.add(e.deliveryKey(leadMinutes()));
        valid.retainAll(old);
        prefs.edit().putStringSet("delivered",valid).commit();
    }
}
