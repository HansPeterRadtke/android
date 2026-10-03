package com.hans.android.voicebutton;

/** Process-wide exclusion: playback must pause before microphone capture begins. */
final class CapturePlaybackGate {
    private static volatile boolean capturing;
    private static volatile VlcAudioPlayer player;
    private CapturePlaybackGate() {}
    static void register(VlcAudioPlayer value){player=value;}
    static void unregister(VlcAudioPlayer value){if(player==value)player=null;}
    static boolean isCapturing(){return capturing;}
    static void begin() throws java.io.IOException {
        capturing=true;
        try {VlcAudioPlayer current=player;if(current!=null)current.pauseForCapture();}
        catch(java.io.IOException failure){capturing=false;throw failure;}
    }
    static void end(){capturing=false;}
}
