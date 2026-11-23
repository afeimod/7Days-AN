package org.island.nativevalidation;

/** Separate camera actor for the original call 2 scripts. Player coordinates never change. */
final class CinematicCamera {
    boolean active;float x,y,z,tx,ty,tz;
    void begin(ThirdPersonCamera camera,float px,float py,float pz,float yaw,float pitch){
        if(active)return;active=true;x=camera.distance>0?camera.x:px;y=camera.distance>0?camera.y:py;z=camera.distance>0?camera.z:pz;
        double a=Math.toRadians(yaw),p=Math.toRadians(pitch);tx=x+(float)(Math.cos(a)*Math.cos(p))*100;ty=y+(float)(Math.sin(a)*Math.cos(p))*100;tz=z+(float)Math.sin(p)*100;
    }
    float[] destination(float px,float py,float pz,float height,float distance){
        float vx=px-x,vy=py-y,length=(float)Math.hypot(vx,vy);
        if(length<.001f){vx=tx-x;vy=ty-y;length=Math.max(.001f,(float)Math.hypot(vx,vy));}
        return new float[]{px+vx/length*distance,py+vy/length*distance,pz+height};
    }
    String snapshot(){return active+","+x+","+y+","+z+","+tx+","+ty+","+tz;}
    void restore(String s){String[] a=s.split(",");if(a.length!=7)return;active=Boolean.parseBoolean(a[0]);x=GameEngine.flt(a[1]);y=GameEngine.flt(a[2]);z=GameEngine.flt(a[3]);tx=GameEngine.flt(a[4]);ty=GameEngine.flt(a[5]);tz=GameEngine.flt(a[6]);}
}
