package org.island.nativevalidation;

/** Retracting follow camera; player coordinates always remain gameplay eye coordinates. */
public final class ThirdPersonCamera {
    public float x,y,z,distance;
    private boolean initialized;
    public void reset(){initialized=false;}
    public void firstPerson(float px,float py,float pz){x=px;y=py;z=pz;distance=0;initialized=false;}
    public void update(WorldPhysics world,float px,float py,float pz,float yaw,float pitch,float dt){
        float a=(float)Math.toRadians(yaw),p=(float)Math.toRadians(pitch),cp=(float)Math.cos(p);
        float dx=-(float)Math.cos(a)*cp*110,dy=-(float)Math.sin(a)*cp*110,dz=-(float)Math.sin(p)*110;
        WorldPhysics.Hit hit=world.cameraTrace(px,py,pz,px+dx,py+dy,pz+dz,4);
        float safe=Math.max(0,hit.fraction*110-2);
        // Immediately retract, smoothly extend. Never interpolate through walls.
        distance=!initialized||safe<distance?safe:Math.min(safe,distance+150*dt);
        x=px+dx*distance/110;y=py+dy*distance/110;z=pz+dz*distance/110;initialized=true;
    }
}
