package com.hans.android.voicebutton;
import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class, shadows={GuidelineRegressionTest.AuditOs.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GuidelineRegressionTest {
    @org.robolectric.annotation.Implements(android.system.Os.class)
    public static class AuditOs {
        @org.robolectric.annotation.Implementation
        protected static void rename(String from,String to)throws android.system.ErrnoException {
            try{java.nio.file.Files.move(java.nio.file.Paths.get(from),java.nio.file.Paths.get(to),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(IOException e){throw new android.system.ErrnoException("rename",android.system.OsConstants.EIO,e);}
        }
    }
    private static final File OUT = new File(System.getProperty("voicebutton.gui.output", "build/reports/guidelines"));
    static { OUT.mkdirs(); }
    @Test public void renderRealPlayerLayout() throws Exception {
        Class<?>[] types={PlayerActivity.class,MainActivity.class,AudioLibraryActivity.class};
        String[] labels={"player","main","library"};
        int[][] sizes={{393,803},{803,345},{800,1232}};
        for(int i=0;i<types.length;i++)for(int j=0;j<sizes.length;j++)for(float scale:new float[]{1f,2f})
            capture((Class<? extends Activity>)types[i],labels[i]+"-"+sizes[j][0]+"x"+sizes[j][1]+"-font"+(int)(scale*100),sizes[j][0],sizes[j][1],scale);
    }
    @Test public void renderAdditionalOperationalStates()throws Exception{
        for(String state:new String[]{"recording","paused","failure","saving"})
          for(int[] size:new int[][]{{393,803},{803,345}})
            capture(MainActivity.class,"main-"+state+"-"+size[0]+"x"+size[1]+"-font200",size[0],size[1],2f);
        capture(MainActivity.class,"main-queue-393x803-font200",393,803,2f);
        capture(PlayerActivity.class,"player-long-title-393x803-font200",393,803,2f);
    }
    @Test public void renderSettingsAndMenus() throws Exception {
        for(int[] size:new int[][]{{393,803},{803,345}})for(float font:new float[]{1f,2f}) {
            String suffix="-"+size[0]+"x"+size[1]+"-font"+(int)(font*100);
            capture(PlayerActivity.class,"player-dialog-settings"+suffix,size[0],size[1],font);
            capture(PlayerActivity.class,"player-dialog-menu"+suffix,size[0],size[1],font);
            capture(MainActivity.class,"main-dialog-menu"+suffix,size[0],size[1],font);
            capture(MainActivity.class,"main-dialog-automation"+suffix,size[0],size[1],font);
            capture(MainActivity.class,"main-dialog-limits"+suffix,size[0],size[1],font);
            capture(AudioLibraryActivity.class,"library-dialog-menu"+suffix,size[0],size[1],font);
        }
    }
    @Test public void preservesDurabilityAndRejectsInvalidProof() throws Exception {
        android.content.Context c=RuntimeEnvironment.getApplication();
        com.hans.android.audio.reliable.ReliableSessionStore store=new com.hans.android.audio.reliable.ReliableSessionStore(c);
        com.hans.android.audio.reliable.ReliableSessionManifest m=store.createSession("Synthetic audit microphone",-1);
        File dir=store.sessionDirectory(m.sessionId), pcm=new File(dir,"segment_000000_48000.pcm");
        java.nio.file.Files.write(pcm.toPath(),new byte[96000]);
        store.commitPcmJournal(m.sessionId,0,pcm,48000,96000,1000,1,1);
        store.markPaused(m.sessionId);store.renameSession(m.sessionId,"Synthetic paused backup");
        File primary=new File(dir,"manifest.json"), backup=new File(dir,"manifest.json.bak");
        java.nio.file.Files.copy(primary.toPath(),backup.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.write(primary.toPath(),"{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.Assert.assertTrue(com.hans.android.audio.reliable.ReliableSessionStore.openForBrowsing(c).load(m.sessionId).paused);
        com.hans.android.audio.reliable.ReliableSessionManifest recovered=new com.hans.android.audio.reliable.ReliableSessionStore(c).load(m.sessionId);
        org.junit.Assert.assertTrue(recovered.paused);
        org.junit.Assert.assertEquals("Synthetic paused backup",recovered.displayName);
        try{PlayerSettings.clamp(Float.NaN,.25f,8f);org.junit.Assert.fail("NaN accepted");}catch(IllegalArgumentException expected){}
        org.junit.Assert.assertEquals(RecordingPlaybackPolicy.Action.BLOCK_CAPTURE,RecordingPlaybackPolicy.decide(recovered,true,true));

        com.hans.android.audio.reliable.ReliableSessionManifest n=store.createSession("Synthetic ack audit",-1);
        File nDir=store.sessionDirectory(n.sessionId), mp3=new File(nDir,"segment_000000.mp3");
        java.nio.file.Files.write(mp3.toPath(),new byte[]{1,2,3,4});
        store.commitMp3Segment(n.sessionId,0,mp3,1000);
        store.markRecordingFinished(n.sessionId,"audit");
        store.markConversionFinished(n.sessionId,mp3);
        Class<?> client=com.hans.android.network.reliable.ReliableUploadClient.class;
        Method parse=client.getDeclaredMethod("parseStatus",JSONObject.class);parse.setAccessible(true);
        Object status=parse.invoke(null,new JSONObject("{\"committed\":true,\"received_compact\":[]}"));
        com.hans.android.network.reliable.ReliableUploader u=new com.hans.android.network.reliable.ReliableUploader(c,store,"http://127.0.0.1:1",new com.hans.android.network.reliable.ReliableUploader.Listener(){
          public void onState(String s,String h){}public void onChanged(){}
          public void onDiagnostic(String a,String b,String d,String e,JSONObject f,Throwable g){}
        });
        Method apply=u.getClass().getDeclaredMethod("applyStatus",com.hans.android.audio.reliable.ReliableSessionManifest.class,com.hans.android.network.reliable.ReliableUploadClient.Status.class);apply.setAccessible(true);
        try{apply.invoke(u,store.load(n.sessionId),status);org.junit.Assert.fail("Unproved commit accepted");}
        catch(InvocationTargetException expected){org.junit.Assert.assertTrue(expected.getCause() instanceof com.hans.android.network.reliable.ReliableUploadClient.ProtocolException);}
        com.hans.android.audio.reliable.ReliableSessionManifest accepted=store.load(n.sessionId);
        org.junit.Assert.assertFalse(accepted.remoteCommitted);
        org.junit.Assert.assertFalse(accepted.segments.get(0).remoteAccepted);
        org.junit.Assert.assertEquals("",accepted.remoteServerId);
        java.nio.file.Files.write(mp3.toPath(),new byte[]{1,2,3});
        try{store.trustLocalMp3SegmentFile(n.sessionId,0);org.junit.Assert.fail("Truncated audio adopted");}catch(IOException expected){}
        org.junit.Assert.assertEquals(4L,store.load(n.sessionId).segments.get(0).mp3Bytes);
        File frameFile=new File(c.getCacheDir(),"synthetic-48khz.mp3");
        byte[] frame=new byte[576];frame[0]=(byte)0xff;frame[1]=(byte)0xfb;frame[2]=(byte)0xb4;frame[3]=(byte)0xc0;
        try(FileOutputStream out=new FileOutputStream(frameFile)){for(int i=0;i<10;i++)out.write(frame);}
        com.hans.android.audio.reliable.Mp3Frames.Stats stats=com.hans.android.audio.reliable.Mp3Frames.copyFrames(frameFile,new ByteArrayOutputStream());
        org.junit.Assert.assertEquals(10L,stats.frames);
        org.junit.Assert.assertEquals(240L,stats.durationMs);
        JSONObject evidence=new JSONObject().put("mpeg1_48khz_10frames_expected_ms",240).put("reported_ms",stats.durationMs).put("validBackupPaused",true).put("recoveredPaused",recovered.paused).put("recoveredTitle",recovered.displayName).put("nanAccepted",false).put("activeRecordingExistingFileAction","BLOCK_CAPTURE").put("commitWithoutChunkProofAccepted",false).put("emptyServerIdentityAccepted",false).put("truncatedMp3BecameNewTrustedBaseline",false);
        try(FileWriter out=new FileWriter(new File(OUT,"fault-probes.json"))){out.write(evidence.toString(2));}
    }
    private void capture(Class<? extends Activity> type,String name,int width,int height,float font) throws Exception {
        RuntimeEnvironment.setQualifiers("w"+width+"dp-h"+height+"dp-"+(width>height?"land":"port")+"-mdpi");
        RuntimeEnvironment.setFontScale(font);
        Activity a=Robolectric.buildActivity(type).get();
        a.setTheme(R.style.Theme_VoiceButton);
        if (a instanceof PlayerActivity) {
            Field f=type.getDeclaredField("settings");f.setAccessible(true);f.set(a,new PlayerSettings(a));
        }
        Method build=type.getDeclaredMethod("buildScreen");build.setAccessible(true);build.invoke(a);
        if(a instanceof MainActivity){
            Method render=type.getDeclaredMethod("render",RecordingService.Snapshot.class);render.setAccessible(true);render.invoke(a,RecordingService.Snapshot.initial());
            if(name.contains("-recording-")||name.contains("-paused-")||name.contains("-failure-")||name.contains("-saving-")){
                boolean rec=name.contains("-recording-"),paused=name.contains("-paused-"),failed=name.contains("-failure-");
                String state=rec?"RECORDING":paused?"PAUSED":failed?"FAILED":"FINISHING";
                com.hans.android.audio.reliable.ReliableSessionManifest m=new com.hans.android.audio.reliable.ReliableSessionManifest();
                m.sessionId="audit-session";m.folderId="default";m.folderName="Synthetic audit folder";m.paused=paused;m.state=state;
                RecordingService.Snapshot v=new RecordingService.Snapshot(state,"Synthetic audit state",rec,paused,3601000L,96000L,-20f,-10f,600,true,0L,0L,0L,0,0,0,"idle","",-1,0L,0L,0,0L,"Built-in microphone","Built-in microphone",java.util.Collections.singletonList(m),failed?m:null,m,m.sessionId,failed,failed,failed?"Microphone disconnected":"",0);
                render.invoke(a,v);
            }
            Class<?> client=com.hans.android.network.reliable.ReliableUploadClient.class;
            Method parse=client.getDeclaredMethod("parseTranscriptionStatus",JSONObject.class);parse.setAccessible(true);
            Object status=parse.invoke(null,new JSONObject("{\"complete_count\":0,\"not_transcribed_count\":0,\"pending\":[]}"));
            Method apply=type.getDeclaredMethod("applyTranscriptionStatus",com.hans.android.network.reliable.ReliableUploadClient.TranscriptionStatus.class,Exception.class);apply.setAccessible(true);
            if(name.contains("-queue-")){
                JSONArray pending=new JSONArray();
                for(int i=0;i<30;i++)pending.put(new JSONObject().put("session_id","audit-"+i).put("display_name","A very long recording title with details that distinguish this file from another recording "+i+".mp3").put("state","RUNNING").put("phase","transcribing").put("frames_done",200).put("frames_total",1000).put("percent",20));
                status=parse.invoke(null,new JSONObject().put("pending",pending).put("not_transcribed_count",30));
            }
            apply.invoke(a,status,null);apply.invoke(a,null,new IOException("synthetic offline test"));
        }
        if(name.contains("-long-title-")){
            Field f=type.getDeclaredField("titleText");f.setAccessible(true);((TextView)f.get(a)).setText("Recording a long technical discussion about the application guidelines, durability requirements, microphone routing and playback controls that must remain visible.mp3");
        }
        ViewGroup content=a.findViewById(android.R.id.content);
        View root=content.getChildAt(0);
        int viewportWidth=width,viewportHeight=height;
        android.app.Dialog dialog=null;
        if(name.contains("-dialog-")) {
            if(name.contains("-automation-"))AppSettings.show(a,()->{});
            else if(name.contains("-limits-")) {
                Method show=AppSettings.class.getDeclaredMethod("showLimits",Activity.class);show.setAccessible(true);show.invoke(null,a);
            } else {
                String method=name.contains("-settings-")?"showSettings":a instanceof PlayerActivity?"showPlayerMenu":a instanceof MainActivity?"showMoreMenu":"showLibraryMenu";
                Method show=type.getDeclaredMethod(method);show.setAccessible(true);show.invoke(a);
            }
            dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();
            org.junit.Assert.assertNotNull(dialog);
            root=dialog.getWindow().getDecorView();
            width-=48;height-=48;
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,dialog==null?View.MeasureSpec.EXACTLY:View.MeasureSpec.AT_MOST));
        if(dialog!=null)height=root.getMeasuredHeight();
        root.layout(0,0,width,height);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        root.jumpDrawablesToCurrentState();
        if(dialog!=null) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(300,java.util.concurrent.TimeUnit.MILLISECONDS);
            width=root.getWidth();height=root.getHeight();
            org.junit.Assert.assertTrue(name+": dialog exceeds viewport",width<=viewportWidth&&height<=viewportHeight);
            for(int id:new int[]{android.R.id.button1,android.R.id.button2,android.R.id.button3}) {
                View button=dialog.findViewById(id);
                if(button!=null&&button.getVisibility()==View.VISIBLE) {
                    android.graphics.Rect visible=new android.graphics.Rect();
                    org.junit.Assert.assertTrue(name+": hidden dialog action",button.getGlobalVisibleRect(visible));
                    org.junit.Assert.assertTrue(name+": clipped dialog action "+visible,visible.width()>=48&&visible.height()>=48);
                }
            }
        }
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        try(FileOutputStream out=new FileOutputStream(new File(OUT,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        JSONArray nodes=new JSONArray();survey(root,0,0,width,height,false,nodes);
        JSONObject report=new JSONObject().put("name",name).put("widthDp",width).put("heightDp",height).put("fontScale",font).put("nodes",nodes);
        try(FileWriter out=new FileWriter(new File(OUT,name+".json"))){out.write(report.toString(2));}
        System.out.println("GUI_CASE "+name);
        for(int i=0;i<nodes.length();i++) {
            JSONObject node=nodes.getJSONObject(i);
            if(node.optBoolean("clickable"))org.junit.Assert.assertFalse(name+": undersized control "+node,node.optBoolean("below48dp"));
            if(node.optBoolean("clickable")&&!node.optBoolean("scrollableAncestor")) {
                org.junit.Assert.assertFalse(name+": unreachable control "+node,node.optBoolean("outsideViewport"));
                org.junit.Assert.assertFalse(name+": undersized control "+node,node.optBoolean("below48dp"));
            }
        }
        if(dialog!=null)dialog.dismiss();
        for(Field f:type.getDeclaredFields()) {
            if(ExecutorService.class.isAssignableFrom(f.getType())){f.setAccessible(true);ExecutorService x=(ExecutorService)f.get(a);if(x!=null)x.shutdownNow();}
        }
    }
    private void survey(View v,int px,int py,int width,int height,boolean scrolling,JSONArray nodes)throws Exception{
        if(v.getVisibility()!=View.VISIBLE)return;
        int x=px+v.getLeft(),y=py+v.getTop();
        boolean inScroll=scrolling||v instanceof ScrollView||v instanceof androidx.core.widget.NestedScrollView||v instanceof android.widget.AbsListView;
        String text=v instanceof TextView?((TextView)v).getText().toString():"";
        if(v.isClickable()||!text.isEmpty()){
            JSONObject n=new JSONObject().put("class",v.getClass().getSimpleName()).put("text",text).put("clickable",v.isClickable()).put("enabled",v.isEnabled()).put("x",x).put("y",y).put("width",v.getWidth()).put("height",v.getHeight()).put("scrollableAncestor",scrolling).put("outsideViewport",x<0||y<0||x+v.getWidth()>width||y+v.getHeight()>height);
            if(v.isClickable()) {
                n.put("below48dp",v.getWidth()<48||v.getHeight()<48);
                JSONArray parents=new JSONArray();
                for(android.view.ViewParent parent=v.getParent();parent instanceof View;parent=parent.getParent()) {
                    View pv=(View)parent;parents.put(pv.getClass().getSimpleName()+":"+pv.getWidth()+"x"+pv.getHeight()+" alpha="+pv.getAlpha());
                }
                n.put("parents",parents);
            }
            if(v instanceof TextView&&((TextView)v).getLayout()!=null){android.text.Layout l=((TextView)v).getLayout();boolean ellipsis=false;for(int i=0;i<l.getLineCount();i++)ellipsis|=l.getEllipsisCount(i)>0;n.put("ellipsized",ellipsis);}
            nodes.put(n);
        }
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)survey(g.getChildAt(i),x-g.getScrollX(),y-g.getScrollY(),width,height,inScroll,nodes);}
    }
}
