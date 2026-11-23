package org.island.nativevalidation;

import java.util.Arrays;

/** Small depth-buffered renderer for the original UISD2 inventory meshes. */
final class MeshPreview {
    private final float[] vertices;private final int[] texture;private final int tw,th;
    private final float cx,cy,cz,radius;
    MeshPreview(float[] vertices,int[] texture,int tw,int th){this.vertices=vertices;this.texture=texture;this.tw=tw;this.th=th;
        float[] lo={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},hi={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        for(int i=0;i<vertices.length;i+=5)for(int j=0;j<3;j++){lo[j]=Math.min(lo[j],vertices[i+j]);hi[j]=Math.max(hi[j],vertices[i+j]);}
        cx=(lo[0]+hi[0])/2;cy=(lo[1]+hi[1])/2;cz=(lo[2]+hi[2])/2;float r=0;
        for(int i=0;i<vertices.length;i+=5)r=Math.max(r,(float)Math.sqrt(sq(vertices[i]-cx)+sq(vertices[i+1]-cy)+sq(vertices[i+2]-cz)));radius=Math.max(.001f,r);
    }
    private static float sq(float x){return x*x;}
    int[] render(int width,int height,float angle){
        int[] pixels=new int[width*height];float[] depth=new float[pixels.length];Arrays.fill(depth,Float.POSITIVE_INFINITY);
        float c=(float)Math.cos(angle),s=(float)Math.sin(angle),focal=Math.min(width,height)*1.55f;
        float[] p=new float[vertices.length];
        for(int i=0;i<vertices.length;i+=5){float x=(vertices[i]-cx)/radius,y=(vertices[i+1]-cy)/radius,z=(vertices[i+2]-cz)/radius;
            float rx=x*c-y*s,ry=x*s+y*c,d=3.5f+ry*.985f-z*.174f,up=z*.985f+ry*.174f;
            p[i]=width*.5f+rx*focal/d;p[i+1]=height*.5f-up*focal/d;p[i+2]=d;p[i+3]=vertices[i+3];p[i+4]=vertices[i+4];
        }
        for(int i=0;i+14<p.length;i+=15){float ax=p[i],ay=p[i+1],bx=p[i+5],by=p[i+6],dx=p[i+10],dy=p[i+11];
            float area=(bx-ax)*(dy-ay)-(by-ay)*(dx-ax);if(Math.abs(area)<.0001f)continue;
            int x0=Math.max(0,(int)Math.floor(Math.min(ax,Math.min(bx,dx)))),x1=Math.min(width-1,(int)Math.ceil(Math.max(ax,Math.max(bx,dx))));
            int y0=Math.max(0,(int)Math.floor(Math.min(ay,Math.min(by,dy)))),y1=Math.min(height-1,(int)Math.ceil(Math.max(ay,Math.max(by,dy))));
            for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){
                float px=x+.5f,py=y+.5f,a=((bx-px)*(dy-py)-(by-py)*(dx-px))/area,b=((dx-px)*(ay-py)-(dy-py)*(ax-px))/area,d=1-a-b;
                if(a<-.0001f||b<-.0001f||d<-.0001f)continue;
                float inverse=a/p[i+2]+b/p[i+7]+d/p[i+12],z=1/inverse;int pos=y*width+x;if(z>=depth[pos])continue;
                float u=(a*p[i+3]/p[i+2]+b*p[i+8]/p[i+7]+d*p[i+13]/p[i+12])/inverse;
                float v=(a*p[i+4]/p[i+2]+b*p[i+9]/p[i+7]+d*p[i+14]/p[i+12])/inverse;
                int tx=Math.min(tw-1,Math.max(0,(int)(u*tw))),ty=Math.min(th-1,Math.max(0,(int)((1-v)*th)));int color=texture[ty*tw+tx];
                if((color>>>24)<128)continue;pixels[pos]=color;depth[pos]=z;
            }
        }return pixels;
    }
}
