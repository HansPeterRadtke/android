package com.hans.android.voicebutton;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class ThorStudioClient {
    interface Progress {
        void onProgress(String phase, long done, long total);
    }
    static final class Result {
        final File file; final String renderId; final float speed; final String engine;
        Result(File file,String renderId,float speed,String engine){this.file=file;this.renderId=renderId;this.speed=speed;this.engine=engine;}
    }

    private final Context context;
    private final String base;
    private final String token;

    ThorStudioClient(Context context) {
        this.context=context.getApplicationContext();
        base=BuildConfig.THOR_PLAYER_BASE_URL;
        token=BuildConfig.THOR_PLAYER_TOKEN;
    }

    Result prepare(PlayerSource source,float speed,Progress progress) throws Exception {
        File root=new File(context.getCacheDir(),"studio-player");
        if(!root.isDirectory()&&!root.mkdirs())throw new java.io.IOException("Could not create studio cache");
        File local=copyAndHash(source,root,progress);
        String sha=sha256(local); String uploadId=sha.substring(0,32);
        ensureUploaded(source, local, sha, uploadId, progress);
        JSONObject request=new JSONObject();request.put("upload_id",uploadId);request.put("speed",speed);
        progress.onProgress("Rubber Band R3 rendering",0,0);
        JSONObject render=postJson("/v1/render",request,1800_000);
        String renderId=render.getString("render_id");
        File target=new File(root,renderId); File temp=new File(root,renderId+".part");
        if(!target.isFile()||target.length()!=render.optLong("bytes",-1L)){
            download("/v1/media?id="+enc(renderId),temp,progress);
            if(target.exists()&&!target.delete())throw new java.io.IOException("Could not replace studio cache");
            if(!temp.renameTo(target))throw new java.io.IOException("Could not publish studio cache");
        }
        return new Result(target,renderId,(float)render.getDouble("speed"),render.optString("engine","Rubber Band R3 fine"));
    }

    private void ensureUploaded(PlayerSource source, File local, String sha,
                                String uploadId, Progress progress) throws Exception {
        long total = local.length();
        JSONObject status = getJson("/v1/upload/status?id=" + enc(uploadId)
                + "&sha256=" + sha + "&bytes=" + total);
        long offset = status.optLong("durable_offset", 0L);
        int partBytes = 1024 * 1024;
        try (FileInputStream in = new FileInputStream(local)) {
            skipFully(in, offset);
            byte[] buffer = new byte[partBytes];
            while (offset < total) {
                checkInterrupted();
                int wanted = (int)Math.min(buffer.length, total - offset);
                int count = readFully(in, buffer, wanted);
                if (count <= 0) throw new java.io.EOFException("Studio source ended early");
                JSONObject ack = postBytes("/v1/upload/part?id=" + enc(uploadId)
                        + "&sha256=" + sha + "&bytes=" + total
                        + "&offset=" + offset + "&name=" + enc(source.title),
                        buffer, count);
                offset = ack.getLong("durable_offset");
                progress.onProgress("Uploading to Thor", offset, total);
            }
        }
    }

    private File copyAndHash(PlayerSource source,File root,Progress progress)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        File temp=new File(root,"source-copy-" + Thread.currentThread().getId()
                + "-" + System.nanoTime() + ".part");
        long done=0L;
        InputStream raw=context.getContentResolver().openInputStream(source.uri);
        if(raw==null)throw new java.io.FileNotFoundException("Source cannot be opened");
        try(InputStream sourceIn=raw;BufferedInputStream in=new BufferedInputStream(sourceIn);
            FileOutputStream fileOut=new FileOutputStream(temp);BufferedOutputStream out=new BufferedOutputStream(fileOut)){
            byte[] buffer=new byte[1024*1024];int read;
            while((read=in.read(buffer))!=-1){checkInterrupted();out.write(buffer,0,read);digest.update(buffer,0,read);done+=read;progress.onProgress("Preparing source",done,source.bytes);}
            out.flush();fileOut.getFD().sync();
        } catch (Exception failure) {
            temp.delete();
            throw failure;
        }
        String sha=hex(digest.digest());File finalFile=new File(root,"source-"+sha+".bin");
        if(finalFile.isFile()&&finalFile.length()==temp.length())temp.delete();
        else{if(finalFile.exists()&&!finalFile.delete())throw new java.io.IOException("Could not replace source cache");if(!temp.renameTo(finalFile))throw new java.io.IOException("Could not publish source cache");}
        return finalFile;
    }

    private JSONObject getJson(String path)throws Exception{return requestJson("GET",path,null,30_000);}
    private JSONObject postJson(String path,JSONObject value,int timeout)throws Exception{return requestJson("POST",path,value.toString().getBytes(StandardCharsets.UTF_8),timeout);}
    private JSONObject postBytes(String path,byte[] bytes,int count)throws Exception{
        HttpURLConnection c=open(path,"POST",60_000);try{c.setDoOutput(true);c.setRequestProperty("Content-Type","application/octet-stream");c.setFixedLengthStreamingMode(count);try(java.io.OutputStream out=c.getOutputStream()){out.write(bytes,0,count);}return readJson(c);}finally{c.disconnect();}}
    private JSONObject requestJson(String method,String path,byte[] body,int timeout)throws Exception{
        HttpURLConnection c=open(path,method,timeout);try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(body.length);try(java.io.OutputStream out=c.getOutputStream()){out.write(body);}}return readJson(c);}finally{c.disconnect();}}
    private HttpURLConnection open(String path,String method,int timeout)throws Exception{checkInterrupted();HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15_000);c.setReadTimeout(timeout);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/json, audio/wav");c.setRequestProperty("User-Agent","VoiceButton/"+BuildConfig.VERSION_NAME+" Android");return c;}
    private JSONObject readJson(HttpURLConnection c)throws Exception{int code=c.getResponseCode();InputStream raw=code>=400?c.getErrorStream():c.getInputStream();String text=new String(readAll(raw),StandardCharsets.UTF_8);if(code<200||code>=300)throw new java.io.IOException("Thor studio HTTP "+code+": "+text);return new JSONObject(text);}
    private void download(String path,File target,Progress progress)throws Exception{HttpURLConnection c=open(path,"GET",1800_000);try{int code=c.getResponseCode();if(code!=200)throw new java.io.IOException("Studio download HTTP "+code);long total=c.getContentLengthLong(),done=0;try(InputStream in=c.getInputStream();FileOutputStream fileOut=new FileOutputStream(target);BufferedOutputStream out=new BufferedOutputStream(fileOut)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){checkInterrupted();out.write(b,0,n);done+=n;progress.onProgress("Downloading studio audio",done,total);}out.flush();fileOut.getFD().sync();}}finally{c.disconnect();}}
    File prepareWaveform(PlayerSource source, Progress progress) throws Exception {
        File root = new File(context.getCacheDir(), "studio-player");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new java.io.IOException("Could not create studio cache");
        }
        File local = copyAndHash(source, root, progress);
        String sha = sha256(local);
        String uploadId = sha.substring(0, 32);
        ensureUploaded(source, local, sha, uploadId, progress);
        File target = new File(root, "waveform-" + uploadId + ".png");
        if (!target.isFile() || target.length() < 100L) {
            File temporary = new File(root, target.getName() + ".part");
            download("/v1/waveform?id=" + enc(uploadId), temporary, progress);
            if (target.exists() && !target.delete()) {
                throw new java.io.IOException("Could not replace waveform cache");
            }
            if (!temporary.renameTo(target)) {
                throw new java.io.IOException("Could not publish waveform cache");
            }
        }
        return target;
    }

    static long cacheBytes(Context context){return bytes(new File(context.getCacheDir(),"studio-player"));}
    static void clearCache(Context context) throws Exception {
        File root = new File(context.getCacheDir(), "studio-player");
        if (root.exists()) deleteRecursively(root);
    }
    private static void deleteRecursively(File file) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (file.exists() && !file.delete()) {
            throw new java.io.IOException("Could not delete studio cache item " + file.getName());
        }
    }
    private static long bytes(File f){if(!f.exists())return 0;if(f.isFile())return f.length();long n=0;File[] kids=f.listFiles();if(kids!=null)for(File k:kids)n+=bytes(k);return n;}
    private static int readFully(InputStream in,byte[] b,int wanted)throws Exception{int total=0;while(total<wanted){int n=in.read(b,total,wanted-total);if(n<0)break;total+=n;}return total;}
    private static void skipFully(InputStream in,long count)throws Exception{long left=count;while(left>0){long n=in.skip(left);if(n<=0){if(in.read()<0)throw new java.io.EOFException();n=1;}left-=n;}}
    private static byte[] readAll(InputStream in)throws Exception{if(in==null)return new byte[0];try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=x.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private static String sha256(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.US,"%02x",x&255));return s.toString();}
    private static String enc(String s)throws Exception{return URLEncoder.encode(s,StandardCharsets.UTF_8.name());}
    private static void checkInterrupted()throws java.io.InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Studio operation cancelled");}
}
