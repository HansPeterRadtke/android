package com.hans.android.network.reliable;
import com.hans.android.audio.reliable.ReliableSessionManifest;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class DurableProofTest {
    private ReliableSessionManifest local(){ReliableSessionManifest m=new ReliableSessionManifest();m.recordingFinished=true;m.conversionFinished=true;ReliableSessionManifest.Segment s=new ReliableSessionManifest.Segment();s.seq=0;s.mp3Bytes=100;s.sha256="expected-hash";m.segments.add(s);return m;}
    private ReliableUploadClient.Status status(String server,JSONArray chunks)throws Exception{return ReliableUploadClient.parseStatus(new JSONObject().put("server_id",server).put("committed",true).put("manifest_revision",7).put("received_compact",chunks));}
    private JSONArray chunk(String hash,long bytes){return new JSONArray().put(new JSONArray().put(0).put(bytes).put(hash).put(10).put(11));}
    @Test public void emptyCommitCannotAcceptLocalAudio()throws Exception{try{ReliableUploader.validatedRemoteChunks(local(),status("jetson",new JSONArray()));fail("Empty proof accepted");}catch(ReliableUploadClient.ProtocolException expected){}}
    @Test public void missingServerCannotAcceptMatchingBytes()throws Exception{try{ReliableUploader.validatedRemoteChunks(local(),status("",chunk("expected-hash",100)));fail("Unknown server accepted");}catch(ReliableUploadClient.ProtocolException expected){}}
    @Test public void wrongDigestCannotAcceptMatchingLength()throws Exception{try{ReliableUploader.validatedRemoteChunks(local(),status("jetson",chunk("wrong-hash",100)));fail("Wrong digest accepted");}catch(ReliableUploadClient.ProtocolException expected){}}
    @Test public void matchingProofAcceptsExactlyLocalChunks()throws Exception{assertEquals(1,ReliableUploader.validatedRemoteChunks(local(),status("jetson",chunk("expected-hash",100))).size());}
    @Test public void acknowledgementNeedsServerIdentity()throws Exception{JSONObject ack=new JSONObject().put("complete",true).put("durable",true).put("sha256","expected-hash").put("bytes",100).put("durable_offset",100);try{ReliableUploadClient.ackFromCompleted(ack,local().segments.get(0));fail("Missing identity accepted");}catch(ReliableUploadClient.ProtocolException expected){}}
}
