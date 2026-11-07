package com.hans.android.adaptivekeyboard;

/** Pure-Java gesture accumulator shared by Android touch UI and regressions. */
public final class SpaceSwipe {
    private float lastX, carried;
    private boolean active;
    public SpaceSwipe(float downX) {lastX=downX;}
    public void activate(float currentX) {active=true;lastX=currentX;carried=0;}
    public boolean active() {return active;}
    public int move(float x,float pixelsPerCharacter) {
        if(!active || pixelsPerCharacter<=0)return 0;
        carried+=(x-lastX);lastX=x;
        int steps=(int)(carried/pixelsPerCharacter);
        carried-=steps*pixelsPerCharacter;
        return Math.max(-40,Math.min(40,steps));
    }
}
