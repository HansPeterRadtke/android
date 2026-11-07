package com.hans.android.adaptivekeyboard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Only ever called after the user explicitly opens GIF search. No typing telemetry. */
public final class GiphySearch {
    public static final class Gif {
        public final String title, thumbnail, original, link, attribution;
        Gif(String t, String thumb, String image, String page) {
            this(t,thumb,image,page,"");
        }
        Gif(String t, String thumb, String image, String page, String credit) {
            title=t; thumbnail=thumb; original=image; link=page; attribution=credit;
        }
    }
    private GiphySearch() { }
    public static List<Gif> search(String apiKey, String query) throws Exception {
        // Openverse's anonymous image search is the default; GIPHY remains optional.
        if (apiKey == null || apiKey.trim().isEmpty()) return searchOpenverse(query);
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
    /** Anonymous, documented Openverse endpoint; no API key, tracking ID or typing history. */
    static List<Gif> searchOpenverse(String query) throws Exception {
        List<Gif> out=new ArrayList<>();
        if(query==null || query.trim().isEmpty())return out;
        String q=URLEncoder.encode(query.substring(0,Math.min(65,query.length())),"UTF-8");
        String url="https://api.openverse.org/v1/images/?q="+q+
            "&extension=gif&source=wikimedia&page_size=10";
        JSONObject response;
        try {
            response=new JSONObject(new String(request(url,2_000_000),StandardCharsets.UTF_8));
        } catch (Exception providerError) {
            // Openverse can rate-limit anonymous searches; Wikimedia Commons has
            // its own public, MIME-filtered search API. Never keep retrying Openverse.
            return searchCommons(query);
        }
        JSONArray images=response.optJSONArray("results");
        if(images==null)return searchCommons(query);
        for(int i=0;i<images.length();i++) {
            JSONObject record=images.optJSONObject(i);
            if(record==null || !"gif".equalsIgnoreCase(record.optString("filetype")))continue;
            String image=record.optString("url");
            String thumbnail=wikimediaThumbnailUrl(image,120);
            if(thumbnail==null)thumbnail=record.optString("thumbnail");
            if(!allowMedia(image) || !allowMedia(thumbnail))continue;
            String attribution=record.optString("creator")+" · "+record.optString("license");
            out.add(new Gif(record.optString("title","GIF"),thumbnail,image,
                record.optString("foreign_landing_url"),attribution));
        }
        return out.isEmpty()?searchCommons(query):out;
    }

    /** Wikimedia's upload server produces small GIF thumbnails reliably. */
    public static String wikimediaThumbnailUrl(String original,int width) {
        if(width<64 || width>320)return null;
        try {
            URI uri=new URI(original);
            if(!"https".equalsIgnoreCase(uri.getScheme()) ||
                !"upload.wikimedia.org".equalsIgnoreCase(uri.getHost()))return null;
            String path=uri.getRawPath(),base="/wikipedia/commons/";
            if(path==null || !path.startsWith(base) || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".gif"))return null;
            String filename=path.substring(path.lastIndexOf('/')+1);
            if(filename.length()>180)return null;
            return "https://upload.wikimedia.org/wikipedia/commons/thumb/"+
                path.substring(base.length())+"/"+width+"px-"+filename;
        } catch(Exception invalid) {return null;}
    }

    /** Independent no-key fallback using Commons' official image-info search API. */
    static List<Gif> searchCommons(String query) throws Exception {
        List<Gif> gifs=new ArrayList<>();
        if(query==null || query.trim().isEmpty())return gifs;
        String terms=query.trim().substring(0,Math.min(65,query.trim().length()));
        String url="https://commons.wikimedia.org/w/api.php?action=query&generator=search"+
            "&gsrsearch="+URLEncoder.encode(terms+" filemime:image/gif","UTF-8")+
            "&gsrnamespace=6&gsrlimit=12&prop=imageinfo&iiprop=url%7Cmime%7Csize"+
            "&iiurlwidth=120&format=json&formatversion=2";
        JSONObject root=new JSONObject(new String(request(url,1_500_000),StandardCharsets.UTF_8));
        JSONObject result=root.optJSONObject("query");
        if(result==null)return gifs;
        JSONArray pages=result.optJSONArray("pages");
        if(pages==null)return gifs;
        for(int i=0;i<pages.length();i++) {
            JSONObject page=pages.optJSONObject(i);
            if(page==null)continue;
            JSONArray infos=page.optJSONArray("imageinfo");
            if(infos==null || infos.length()==0)continue;
            JSONObject info=infos.optJSONObject(0);
            if(info==null || !"image/gif".equalsIgnoreCase(info.optString("mime")))continue;
            String original=info.optString("url"),thumbnail=info.optString("thumburl");
            if(thumbnail.isEmpty())thumbnail=wikimediaThumbnailUrl(original,120);
            if(!allowMedia(original)||!allowMedia(thumbnail))continue;
            String title=page.optString("title","GIF").replaceFirst("^File:","");
            gifs.add(new Gif(title,thumbnail,original,info.optString("descriptionurl"),"Wikimedia Commons"));
        }
        return gifs;
    }
    public static String userErrorMessage(Exception error) {
        String message=error.getMessage()==null?"":error.getMessage();
        if(message.contains("429"))return "GIF-Dienst begrenzt Anfragen. Später erneut suchen.";
        if(message.contains("HTTP 403"))return "GIF-Dienst verweigert diese Anfrage (403).";
        if(message.contains("HTTP 5"))return "GIF-Dienst momentan nicht erreichbar.";
        if(message.contains("exceeds limit"))return "GIF zu groß. Ein anderes GIF wählen.";
        if(error instanceof java.net.UnknownHostException || error instanceof java.net.SocketTimeoutException ||
            error instanceof java.net.ConnectException)
            return "Keine stabile Internetverbindung für die GIF-Suche.";
        return "GIF-Laden fehlgeschlagen. Verbindung prüfen und neu suchen.";
    }
    private static boolean allowMedia(String url) {
        try {
            URL u = new URL(url);
            String host=u.getHost().toLowerCase(java.util.Locale.ROOT);
            return u.getProtocol().equals("https") &&
                (host.equals("giphy.com") || host.endsWith(".giphy.com") ||
                 host.equals("upload.wikimedia.org") || host.equals("api.openverse.org"));
        } catch (Exception e) {return false;}
    }
    public static byte[] request(String url, int maxSize) throws Exception {
        return requestWithTimeout(url,maxSize,5000,7000);
    }
    public static byte[] requestPreview(String url,int maxSize) throws Exception {
        return requestWithTimeout(url,maxSize,2800,4000);
    }
    private static byte[] requestWithTimeout(String url,int maxSize,int connectMs,int readMs) throws Exception {
        if (!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS required");
        HttpURLConnection con = (HttpURLConnection)new URL(url).openConnection();
        con.setConnectTimeout(connectMs); con.setReadTimeout(readMs); con.setInstanceFollowRedirects(false);
        con.setRequestProperty("User-Agent", "KontextTastatur/0.5 (+https://github.com/HansPeterRadtke/android)");
        con.setRequestProperty("Accept", "application/json, image/gif, image/*;q=0.9, */*;q=0.5");
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
