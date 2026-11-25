package org.island.nativevalidation;

/** Convex BSP collision, gravity, wall sliding and a 20-unit stair step. */
public final class WorldPhysics {
    public float[][] planes=new float[0][];
    public int[][] brushes=new int[0][];
    public float verticalSpeed;
    public boolean grounded;
    public boolean crouched;
    public Iterable<GameEngine.Entity> objects;
    public GameEngine.Entity held;
    public GameEngine.Entity support;
    public static final class Hit {public float fraction=1;public float nx,ny,nz;public boolean solid;public GameEngine.Entity entity;}
    public Hit trace(float x,float y,float z,float tx,float ty,float tz,float rx,float ry,float rz){
        Hit hit=new Hit();
        for(int[] brush:brushes){
            float enter=-1,leave=1,nx=0,ny=0,nz=0;boolean outside=false,miss=false;
            for(int index:brush){
                float[] p=planes[index];float expand=Math.abs(p[0])*rx+Math.abs(p[1])*ry+Math.abs(p[2])*rz;
                float a=p[0]*x+p[1]*y+p[2]*z-p[3]-expand,b=p[0]*tx+p[1]*ty+p[2]*tz-p[3]-expand;
                // Treat boundary contact as outside. A spawn can sit exactly on
                // an expanded floor plane; classifying it as start-solid tunnels.
                if(a>=-.03125f)outside=true;
                if(a>0&&b>=a){miss=true;break;}
                if((a<-.03125f&&b<=0)||Math.abs(a-b)<.000001f)continue;
                if(a>b){float t=Math.max(0,(a-.03125f)/(a-b));if(t>enter){enter=t;nx=p[0];ny=p[1];nz=p[2];}}
                else leave=Math.min(leave,(a+.03125f)/(a-b));
            }
            if(miss)continue;
            if(!outside){hit.solid=true;continue;}
            if(enter<leave&&enter>=0&&enter<hit.fraction){hit.fraction=Math.max(0,enter);hit.nx=nx;hit.ny=ny;hit.nz=nz;}
        }
        return hit;
    }
    private Hit body(float x,float y,float z,float tx,float ty,float tz){
        float eye=crouched?20:32,rz=crouched?16:28;
        Hit result=trace(x,y,z-eye,tx,ty,tz-eye,10,10,rz);
        if(objects!=null)for(GameEngine.Entity e:objects){
            if(e==held||e.removed||e.bounds==null||!e.solid)continue;
            if(e.floorTriangles!=null)terrainFloor(result,e,x,y,z-eye-rz,tx,ty,tz-eye-rz);
            if(!e.virtual&&!e.furniture)continue;
            if(e.door&&(Math.abs(e.rotation)>35||e.open>.5f))continue;
            float[] b=e.bounds;float[] low={b[0]+e.dx-10,b[1]+e.dy-10,b[2]+e.dz-rz},high={b[3]+e.dx+10,b[4]+e.dy+10,b[5]+e.dz+rz};
            float[] start={x,y,z-eye},delta={tx-x,ty-y,tz-z};float enter=-1,leave=1;int axis=-1;float sign=0;boolean miss=false,inside=true;
            for(int i=0;i<3;i++){
                if(start[i]<=low[i]+.001f||start[i]>=high[i]-.001f)inside=false;
                if(Math.abs(delta[i])<.000001f){if(start[i]<low[i]||start[i]>high[i]){miss=true;break;}continue;}
                float a=(low[i]-start[i])/delta[i],c=(high[i]-start[i])/delta[i];float near=Math.min(a,c),far=Math.max(a,c);
                if(near>enter){enter=near;axis=i;sign=delta[i]>0?-1:1;}leave=Math.min(leave,far);
            }
            if(!miss&&!inside&&axis>=0&&enter<=leave&&leave>=0&&enter>=-.0001f&&enter<result.fraction){result.fraction=Math.max(0,enter-.0001f);result.nx=axis==0?sign:0;result.ny=axis==1?sign:0;result.nz=axis==2?sign:0;result.entity=e;}
        }
        return result;
    }
    private void terrainFloor(Hit hit,GameEngine.Entity e,float x,float y,float z,float tx,float ty,float tz){
        float[] a=e.floorTriangles;
        for(int i=0;i+8<a.length;i+=9){
            float ax=a[i]+e.dx,ay=a[i+1]+e.dy,az=a[i+2]+e.dz;
            float ux=a[i+3]-a[i],uy=a[i+4]-a[i+1],uz=a[i+5]-a[i+2],vx=a[i+6]-a[i],vy=a[i+7]-a[i+1],vz=a[i+8]-a[i+2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;if(Math.abs(nz)<.00001f)continue;
            float start=z-az+(nx*(x-ax)+ny*(y-ay))/nz,end=tz-az+(nx*(tx-ax)+ny*(ty-ay))/nz;
            if(start<-.04f||end>=start||end>0)continue;
            float t=Math.max(0,(start-.03125f)/(start-end));if(t>=hit.fraction)continue;
            float px=x+(tx-x)*t-ax,py=y+(ty-y)*t-ay;
            float b=(px*vy-py*vx)/nz,c=(ux*py-uy*px)/nz;
            if(b<-.0001f||c<-.0001f||b+c>1.0001f)continue;
            float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz),sign=nz<0?-1:1;
            hit.fraction=t;hit.nx=nx/length*sign;hit.ny=ny/length*sign;hit.nz=nz/length*sign;hit.entity=e;
        }
    }
    private float[] slide(float x,float y,float z,float dx,float dy){
        for(int i=0;i<3;i++){
            Hit h=body(x,y,z,x+dx,y+dy,z);x+=dx*h.fraction;y+=dy*h.fraction;
            if(h.fraction>=1)break;
            float left=1-h.fraction,dot=dx*h.nx+dy*h.ny;dx=(dx-h.nx*dot)*left;dy=(dy-h.ny*dot)*left;
        }
        return new float[]{x,y,z};
    }
    public float[] move(float x,float y,float z,float dx,float dy,float dt){
        boolean followSteps=grounded&&verticalSpeed<=0;
        // Imported older saves may already place the actor inside furniture
        // that previously lacked a collider. Resolve to the nearest side.
        if(objects!=null)for(GameEngine.Entity e:objects){
            if(e==held||e.removed||!e.solid||e.bounds==null||(!e.virtual&&!e.furniture)||(e.door&&(Math.abs(e.rotation)>35||e.open>.5f)))continue;
            float[] b=e.bounds;float eye=crouched?20:32,rz=crouched?16:28,bodyZ=z-eye;
            if(bodyZ+rz<=b[2]+e.dz+.05f||bodyZ-rz>=b[5]+e.dz-.05f)continue;
            float loX=b[0]+e.dx-10,hiX=b[3]+e.dx+10,loY=b[1]+e.dy-10,hiY=b[4]+e.dy+10;
            if(x<=loX+.05f||x>=hiX-.05f||y<=loY+.05f||y>=hiY-.05f)continue;
            float[] gaps={x-loX,hiX-x,y-loY,hiY-y};int side=0;for(int i=1;i<4;i++)if(gaps[i]<gaps[side])side=i;
            if(side==0)x=loX-.06f;else if(side==1)x=hiX+.06f;else if(side==2)y=loY-.06f;else y=hiY+.06f;
        }
        float ox=x,oy=y,oz=z;
        float[] flat=slide(x,y,z,dx,dy);
        if((flat[0]-x)*(flat[0]-x)+(flat[1]-y)*(flat[1]-y)<dx*dx+dy*dy-.001f){
            Hit up=body(x,y,z,x,y,z+20);
            if(up.fraction>.99f){
                float[] step=slide(x,y,z+20,dx,dy);Hit down=body(step[0],step[1],step[2],step[0],step[1],z-3);
                step[2]-=23*down.fraction;
                if(down.fraction<1&&down.nz>.65f&&(down.entity==null||down.entity.floorTriangles!=null)&&(step[0]-x)*(step[0]-x)+(step[1]-y)*(step[1]-y)>(flat[0]-x)*(flat[0]-x)+(flat[1]-y)*(flat[1]-y))flat=step;
            }
        }
        x=flat[0];y=flat[1];z=flat[2];verticalSpeed=Math.max(-650,verticalSpeed-650*dt);
        float dz=verticalSpeed*dt;Hit floor=body(x,y,z,x,y,z+dz);z+=dz*floor.fraction;
        grounded=floor.fraction<1&&floor.nz>.65f;
        // A descending tread is within the same step height as an ascending
        // tread. Keep contact instead of briefly switching to the jump pose.
        // Never snap a deliberate jump or a drop taller than a stair step.
        if(!grounded&&followSteps){
            Hit down=body(x,y,z,x,y,z-20);
            if(down.fraction<1&&down.nz>.65f){z-=20*down.fraction;grounded=true;floor=down;}
        }
        support=grounded?floor.entity:null;
        if(floor.fraction<1)verticalSpeed=0;
        if(!Float.isFinite(z))return new float[]{ox,oy,oz};
        return new float[]{x,y,z};
    }
    public boolean visible(float x,float y,float z,float tx,float ty,float tz){return trace(x,y,z,tx,ty,tz,0,0,0).fraction>.98f;}
    public Hit cameraTrace(float x,float y,float z,float tx,float ty,float tz,float radius){
        Hit result=trace(x,y,z,tx,ty,tz,radius,radius,radius);
        if(objects!=null)for(GameEngine.Entity e:objects){
            if(e.removed||!e.solid||e.bounds==null||(!e.virtual&&!e.furniture))continue;
            if(e.door&&(Math.abs(e.rotation)>35||e.open>.5f))continue;
            float[] b=e.bounds,start={x,y,z},delta={tx-x,ty-y,tz-z};
            float[] lo={b[0]+e.dx-radius,b[1]+e.dy-radius,b[2]+e.dz-radius};
            float[] hi={b[3]+e.dx+radius,b[4]+e.dy+radius,b[5]+e.dz+radius};
            float enter=0,leave=1;boolean miss=false,inside=true;
            for(int i=0;i<3;i++){
                if(start[i]<=lo[i]||start[i]>=hi[i])inside=false;
                if(Math.abs(delta[i])<.000001f){if(start[i]<lo[i]||start[i]>hi[i]){miss=true;break;}}
                else{float a=(lo[i]-start[i])/delta[i],c=(hi[i]-start[i])/delta[i];enter=Math.max(enter,Math.min(a,c));leave=Math.min(leave,Math.max(a,c));}
            }
            if(!inside&&!miss&&enter<leave&&leave>=0&&enter<result.fraction){result.fraction=Math.max(0,enter-.001f);result.entity=e;}
        }
        return result;
    }
    public static float[] surfacePoint(float x,float y,float z,GameEngine.Entity target){
        if(target.bounds==null)return new float[]{target.x+target.dx,target.y+target.dy,target.z+target.dz};
        float[] b=target.bounds;
        return new float[]{Math.max(b[0]+target.dx,Math.min(x,b[3]+target.dx)),Math.max(b[1]+target.dy,Math.min(y,b[4]+target.dy)),Math.max(b[2]+target.dz,Math.min(z,b[5]+target.dz))};
    }
    public boolean visibleTo(float x,float y,float z,GameEngine.Entity target){
        // Test the near surface, rather than a model's buried origin. A door
        // frame's origin can sit behind its own movable door panels.
        float[] point=surfacePoint(x,y,z,target);
        return visibleToPoint(x,y,z,point[0],point[1],point[2],target);
    }
    public boolean visibleToPoint(float x,float y,float z,float tx,float ty,float tz,GameEngine.Entity target){
        if(!visible(x,y,z,tx,ty,tz))return false;
        if(objects!=null)for(GameEngine.Entity e:objects){
            if(e==target||e.removed||!e.solid||e.bounds==null||(!e.virtual&&!e.furniture)||(e.door&&(e.open>.5f||Math.abs(e.rotation)>35)))continue;
            float[] b=e.bounds;float[] p={x,y,z},delta={tx-x,ty-y,tz-z},lo={b[0]+e.dx,b[1]+e.dy,b[2]+e.dz},hi={b[3]+e.dx,b[4]+e.dy,b[5]+e.dz};
            // A shelf's coarse box includes its open compartments. Original
            // loose items placed inside it must remain reachable. Script-hidden
            // items are filtered separately; other intervening objects still block.
            float[] tb=target.bounds;
            // Anonymous solid virobjects are collision proxies around some
            // visible controls (the map 14 pillar lever is one). Their physical
            // volume remains solid, but must not occlude their own enclosed
            // real object from investigation.
            if(e.virtual&&e.originalId<0&&e.auto==0&&!target.virtual&&tb!=null){
                float[] offset={target.dx,target.dy,target.dz};boolean enclosed=true;
                for(int i=0;i<3;i++)if(tb[i]+offset[i]<lo[i]-.01f||tb[i+3]+offset[i]>hi[i]+.01f)enclosed=false;
                if(enclosed)continue;
            }
            float cx=target.x+target.dx,cy=target.y+target.dy,cz=target.z+target.dz;
            if(!target.virtual&&!target.furniture&&tb!=null&&tb[3]-tb[0]<=32&&tb[4]-tb[1]<=32&&tb[5]-tb[2]<=32
                &&cx>=lo[0]&&cx<=hi[0]&&cy>=lo[1]&&cy<=hi[1]&&cz>=lo[2]&&cz<=hi[2])continue;
            float enter=0,exit=1;boolean miss=false;
            for(int i=0;i<3;i++){
                if(Math.abs(delta[i])<.0001f){if(p[i]<lo[i]||p[i]>hi[i]){miss=true;break;}}
                else{float a=(lo[i]-p[i])/delta[i],c=(hi[i]-p[i])/delta[i];enter=Math.max(enter,Math.min(a,c));exit=Math.min(exit,Math.max(a,c));}
            }
            if(!miss&&enter<exit&&enter>.001f&&enter<.98f)return false;
        }
        return true;
    }
    public float groundEye(float x,float y,float preferred){Hit h=body(x,y,preferred+72,x,y,preferred-160);return h.fraction<1&&h.nz>.65f?preferred+72-232*h.fraction:preferred;}
    public void jump(){if(grounded){verticalSpeed=285;grounded=false;}}
    public boolean canStand(float x,float y,float z){return !trace(x,y,z-32,x,y,z-32,10,10,28).solid;}
}
