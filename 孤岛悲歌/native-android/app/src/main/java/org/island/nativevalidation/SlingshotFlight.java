package org.island.nativevalidation;

/** Visible pellet flight starts when the draw animation releases the pouch. */
final class SlingshotFlight {
    float sx,sy,sz,tx,ty,tz,time=-.30f,duration;
    boolean impact,sounded;
    SlingshotFlight(float sx,float sy,float sz,float tx,float ty,float tz,boolean impact){
        this.sx=sx;this.sy=sy;this.sz=sz;this.tx=tx;this.ty=ty;this.tz=tz;this.impact=impact;
        duration=Math.max(.08f,(float)Math.sqrt((tx-sx)*(tx-sx)+(ty-sy)*(ty-sy)+(tz-sz)*(tz-sz))/1300);
    }
    float progress(){return Math.max(0,Math.min(1,time/duration));}
    boolean finished(){return time>=duration+(impact?.16f:0);}
    String snapshot(){return sx+","+sy+","+sz+","+tx+","+ty+","+tz+","+time+","+duration+","+impact+","+sounded;}
    static SlingshotFlight restore(String value){String[] a=value.split(",");if(a.length!=10)return null;SlingshotFlight p=new SlingshotFlight(GameEngine.flt(a[0]),GameEngine.flt(a[1]),GameEngine.flt(a[2]),GameEngine.flt(a[3]),GameEngine.flt(a[4]),GameEngine.flt(a[5]),Boolean.parseBoolean(a[8]));p.time=GameEngine.flt(a[6]);p.duration=Math.max(.08f,GameEngine.flt(a[7]));p.sounded=Boolean.parseBoolean(a[9]);return p;}
}
