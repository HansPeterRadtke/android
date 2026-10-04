package myapp.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Conservative local transport VAD. Server VAD remains authoritative. */
final class LocalVadGate {
  static final class Result {
    final boolean started; final boolean stopped; final List<byte[]> frames;
    Result(boolean started, boolean stopped, List<byte[]> frames) { this.started=started; this.stopped=stopped; this.frames=frames; }
  }
  private final int startFrames, stopFrames, preRollFrames;
  private final double startDbfs, stopDbfs;
  private final ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
  private boolean active=false; private int above=0, below=0;
  LocalVadGate(int frameMs) {
    startFrames=Math.max(2,(int)Math.ceil(180.0/frameMs)); stopFrames=Math.max(3,(int)Math.ceil(600.0/frameMs)); preRollFrames=Math.max(startFrames,(int)Math.ceil(300.0/frameMs));
    startDbfs=-38.0; stopDbfs=-46.0;
  }
  Result accept(byte[] pcm, double dbfs) {
    ArrayList<byte[]> out=new ArrayList<>(); boolean started=false, stopped=false;
    if (!active) {
      preRoll.addLast(pcm.clone()); while(preRoll.size()>preRollFrames) preRoll.removeFirst();
      above = dbfs >= startDbfs ? above+1 : 0;
      if (above>=startFrames) { active=true; started=true; below=0; out.addAll(preRoll); preRoll.clear(); }
    } else {
      out.add(pcm); below = dbfs < stopDbfs ? below+1 : 0;
      if (below>=stopFrames) { active=false; stopped=true; above=0; below=0; preRoll.clear(); }
    }
    return new Result(started,stopped,out);
  }
  boolean active(){ return active; }
}
