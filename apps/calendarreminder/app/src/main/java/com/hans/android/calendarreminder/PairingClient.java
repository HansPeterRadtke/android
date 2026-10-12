package com.hans.android.calendarreminder;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Locale;

final class PairingClient {
    private static final String SYMBOLS="23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static boolean looksLikeCode(String input) {
        if(input==null) return false;
        String value=input.trim().replace("-","").replace(" ","").toUpperCase(Locale.ROOT);
        if(value.length()!=16) return false;
        for(int i=0;i<value.length();i++) if(SYMBOLS.indexOf(value.charAt(i))<0) return false;
        return true;
    }
    static String redeem(String server, String pairingCode) throws Exception {
        if(!looksLikeCode(pairingCode)) throw new IOException("Invalid pairing code");
        String base=CalendarStore.validatedServer(server);
        URL url=new URL(base+"/mobile-calendar/v1/pair");
        if(!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS is required");
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("Cache-Control","no-store");
        String normalized=pairingCode.trim().replace("-","").replace(" ","").toUpperCase(Locale.ROOT);
        byte[] content=new JSONObject().put("code",normalized).toString().getBytes(StandardCharsets.UTF_8);
        try {
            try(OutputStream out=conn.getOutputStream()) { out.write(content); }
            int status=conn.getResponseCode();
            if(status==403) throw new IOException("Pairing code expired, incorrect or already used");
            if(status!=200) throw new IOException("Raspi pairing failed: HTTP "+status);
            byte[] result;
            try(InputStream in=conn.getInputStream()) { result=CalendarStore.readAll(in,4096); }
            JSONObject body=new JSONObject(new String(result,StandardCharsets.UTF_8));
            String credential=body.getString("token");
            if(credential.length()<32 || credential.length()>256)
                throw new IOException("Raspi returned an invalid device credential");
            return credential;
        } finally {
            conn.disconnect();
        }
    }
}
