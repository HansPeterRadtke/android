package myapp.app;

/** Conservative local VAD used only for UI/diagnostic hints. It never owns audio transport. */
final class LocalVadGate {
  static final class Result {
    final boolean started; final boolean stopped;
    Result(boolean started, boolean stopped) { this.started=started; this.stopped=stopped; }
  }
  private final int startFrames, stopFrames;
  private final double startDbfs=-38.0, stopDbfs=-46.0;
  private boolean active=false; private int above=0, below=0;
  LocalVadGate(int frameMs) {
    startFrames=Math.max(2,(int)Math.ceil(180.0/frameMs));
    stopFrames=Math.max(3,(int)Math.ceil(600.0/frameMs));
  }
  Result accept(byte[] pcm, double dbfs) {
    boolean started=false, stopped=false;
    if (!active) {
      above=dbfs>=startDbfs ? above+1 : 0;
      if (above>=startFrames) { active=true; started=true; below=0; }
    } else {
      below=dbfs<stopDbfs ? below+1 : 0;
      if (below>=stopFrames) { active=false; stopped=true; above=0; below=0; }
    }
    return new Result(started,stopped);
  }
  boolean active(){ return active; }
}
