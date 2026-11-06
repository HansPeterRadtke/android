package com.hans.android.adaptivekeyboard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Only ever called after the user explicitly opens GIF search. No typing telemetry. */
public final class GiphySearch {
    public static final class Gif {
        public final String title, thumbnail, original, link;
        Gif(String t, String thumb, String image, String page) {
            title=t; thumbnail=thumb; original=image; link=page;
        }
    }
    private GiphySearch() { }
    public static List<Gif> search(String apiKey, String query) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalStateException("GIPHY API-Schlüssel fehlt");
        if (query == null || query.trim().isEmpty()) return new ArrayList<>();
        String encoded = URLEncoder.encode(query.substring(0,Math.min(50,query.length())), "UTF-8");
        String url = "https://api.giphy.com/v1/gifs/search?api_key="+
            URLEncoder.encode(apiKey.trim(),"UTF-8")+"&q="+encoded+"&limit=12&rating=pg&lang=de";
        JSONObject response = new JSONObject(new String(request(url,1_500_000),StandardCharsets.UTF_8));
        JSONArray data = response.getJSONArray("data");
        List<Gif> out = new ArrayList<>();
        for (int i=0;i<data.length();i++) {
            JSONObject obj = data.getJSONObject(i), images = obj.optJSONObject("images");
            if (images == null) continue;
            JSONObject small = images.optJSONObject("fixed_width_small"), full=images.optJSONObject("original");
            if (small==null||full==null) continue;
            String th = small.optString("url"), orig = full.optString("url"), link=obj.optString("url");
            if (allowMedia(th) && allowMedia(orig))
                out.add(new Gif(obj.optString("title","GIF"),th,orig,link));
        }
        return out;
    }
    private static boolean allowMedia(String url) {
        try {
            URL u = new URL(url);
            String host=u.getHost().toLowerCase(java.util.Locale.ROOT);
            return u.getProtocol().equals("https") && (host.equals("giphy.com") || host.endsWith(".giphy.com"));
        } catch (Exception e) {return false;}
    }
    public static byte[] request(String url, int maxSize) throws Exception {
        if (!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS required");
        HttpURLConnection con = (HttpURLConnection)new URL(url).openConnection();
        con.setConnectTimeout(6500); con.setReadTimeout(8500); con.setInstanceFollowRedirects(false);
        try {
            if (con.getResponseCode() != 200) throw new java.io.IOException("HTTP "+con.getResponseCode());
            try(InputStream in=con.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] buf=new byte[16384]; int n;
                while((n=in.read(buf))>=0) {
                    bytes.write(buf,0,n);
                    if(bytes.size()>maxSize) throw new java.io.IOException("Media exceeds limit");
                }
                return bytes.toByteArray();
            }
        } finally {con.disconnect();}
    }
    public static File cacheGif(File cacheDir, Gif g) throws Exception {
        if (!allowMedia(g.original)) throw new java.io.IOException("Unexpected GIF host");
        byte[] bytes = request(g.original, 10_000_000);
        if (bytes.length<6 || bytes[0]!='G'||bytes[1]!='I'||bytes[2]!='F'||bytes[3]!='8'||
            (bytes[4]!='7' && bytes[4]!='9')||bytes[5]!='a')
            throw new java.io.IOException("Not a GIF image");
        File dir = new File(cacheDir,"gifs");
        if (!dir.isDirectory()&&!dir.mkdirs()) throw new java.io.IOException("Cache directory error");
        File file=File.createTempFile("share-",".gif",dir);
        try(FileOutputStream stream=new FileOutputStream(file)) {stream.write(bytes);}
        return file;
    }
}
