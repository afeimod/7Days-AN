package org.island.nativevalidation;
import java.nio.FloatBuffer;

/** Pose the original arm vertices around the shoulders; feet stay planted during weapon use. */
final class HandPose {
    static void vertex(float[] out,float x,float y,float z,float weight,boolean left,float angle){
        float px=left?-5:5,py=-1,pz=47;
        float a=angle*weight*(float)Math.PI/180,c=(float)Math.cos(a),s=(float)Math.sin(a);
        out[0]=x;out[1]=py+(y-py)*c-(z-pz)*s;out[2]=pz+(y-py)*s+(z-pz)*c;
    }
    static float angle(boolean sling,float elapsed){
        if(sling)return elapsed<.30f?105:75;
        if(elapsed<.08f)return 150;
        if(elapsed<.32f)return 150-115*(elapsed-.08f)/.24f;
        return 35*(1-Math.min(1,(elapsed-.32f)/.23f));
    }
    static float pickAngle(float elapsed){
        if(elapsed<.08f)return -20;
        if(elapsed<.32f)return -20-55*(elapsed-.08f)/.24f;
        return -75*(1-Math.min(1,(elapsed-.32f)/.23f));
    }
    static float draw(float elapsed){
        if(elapsed<0)return 0;
        if(elapsed<.30f)return elapsed/.30f;
        return Math.max(0,1-(elapsed-.30f)/.10f);
    }
    static void apply(FloatBuffer source,FloatBuffer target,float[][] weights,boolean sling,float elapsed){
        float a=angle(sling,elapsed);float[] p=new float[3];
        for(int i=0;i<weights.length;i++){
            int n=i*7;float x=source.get(n),y=source.get(n+1),z=source.get(n+2);
            float right=weights[i][0],left=weights[i][1];
            if(right>0||sling&&left>0){vertex(p,x,y,z,right>0?right:left,right==0,sling?105:a);x=p[0];y=p[1];z=p[2];
                if(sling&&left>0){x+=16*left;y-=(5+12*draw(elapsed))*left;z-=2*left;}}
            target.put(n,x);target.put(n+1,y);target.put(n+2,z);
            for(int j=3;j<7;j++)target.put(n+j,source.get(n+j));
        }target.position(0);
    }
    static float[] grip(FloatBuffer vertices,int[] markers){
        float[] p=new float[3];for(int marker:markers)for(int j=0;j<3;j++)p[j]+=vertices.get(marker*7+j)/markers.length;return p;
    }
}
