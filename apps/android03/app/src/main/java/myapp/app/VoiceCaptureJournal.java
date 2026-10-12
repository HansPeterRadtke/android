package myapp.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Append-only PCM evidence, synced before a frame is offered to transport. */
final class VoiceCaptureJournal implements AutoCloseable {
  private final File directory;
  private final long maxBytes;
  private final int segmentBytes;
  private final File manualFile;
  private File current;
  private FileOutputStream output;
  private long totalBytes;
  private int currentBytes;
  private boolean closed;

  VoiceCaptureJournal(File directory,long maxBytes,int segmentBytes,File manualFile) throws IOException {
    if(maxBytes<segmentBytes || segmentBytes<=0)throw new IllegalArgumentException("invalid recording quota");
    this.directory=directory;this.maxBytes=maxBytes;this.segmentBytes=segmentBytes;this.manualFile=manualFile;
    if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Cannot create recording archive");
    File[] existing=directory.listFiles((dir,name)->name.endsWith(".pcm"));
    if(existing==null)throw new IOException("Cannot inspect recording archive");
    for(File item:existing)totalBytes+=item.length();
    if(manualFile!=null && manualFile.exists()) {
      File archived=new File(directory,"capture-"+UUID.randomUUID()+".pcm");
      long size=manualFile.length();
      if(!manualFile.renameTo(archived))throw new IOException("Cannot preserve previous recording");
      totalBytes+=size;
    }
    if(totalBytes>=maxBytes)throw new IOException("Saved recordings are full; review or delete a recording");
  }
  synchronized void append(byte[] pcm) throws IOException {
    if(closed)throw new IOException("Recording journal is closed");
    if(pcm==null || pcm.length==0 || (pcm.length&1)!=0)throw new IllegalArgumentException("invalid PCM");
    if(totalBytes+pcm.length>maxBytes)throw new IOException("Saved recordings are full; review or delete a recording");
    if(manualFile!=null && currentBytes+pcm.length>segmentBytes)
      throw new IOException("Recording limit reached; the complete recording is saved");
    if(output==null || currentBytes+pcm.length>segmentBytes) {
      closeOutput();currentBytes=0;
      current=manualFile!=null?manualFile:new File(directory,"capture-"+UUID.randomUUID()+".pcm");
      output=new FileOutputStream(current);
    }
    output.write(pcm);output.getFD().sync();
    currentBytes+=pcm.length;totalBytes+=pcm.length;
  }
  synchronized File currentFile(){return current;}
  @Override public synchronized void close() throws IOException {
    closed=true;
    closeOutput();
  }
  private void closeOutput() throws IOException {
    if(output!=null) {try {output.getFD().sync();}finally {output.close();output=null;}}
  }
}
