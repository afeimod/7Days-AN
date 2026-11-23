package org.hebdomad.android;

import android.graphics.*;
import java.util.ArrayList;

/** Reconstruct stroke contours, then rasterize once at display scale. */
final class NativeText {
    private static int[] texturedText(int[] pixels,int width,int height,int scale){
        return coverageRaster(pixels,width,height,scale);
    }
    private static boolean ink(int[] p,int w,int h,int x,int y){return x>=0&&y>=0&&x<w&&y<h&&(p[y*w+x]>>>24)>=128;}
    private static float distance(PointF p,PointF a,PointF b){
        float dx=b.x-a.x,dy=b.y-a.y,length=dx*dx+dy*dy;
        float t=length==0?0:Math.max(0,Math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/length));
        dx=p.x-a.x-t*dx;dy=p.y-a.y-t*dy;return dx*dx+dy*dy;
    }
    private static void simplify(ArrayList<PointF> points,int first,int last,boolean[] keep){
        if(last<=first+1)return;float max=.8f*.8f;int split=-1;
        for(int i=first+1;i<last;i++){float d=distance(points.get(i),points.get(first),points.get(last));if(d>max){max=d;split=i;}}
        if(split>=0){keep[split]=true;simplify(points,first,split,keep);simplify(points,split,last,keep);}
    }
    private static void contour(Path output,ArrayList<PointF> points){
        if(points.size()<3){points.clear();return;}
        PointF first=points.get(0),last=points.get(points.size()-1);
        if(first.x!=last.x||first.y!=last.y)points.add(new PointF(first.x,first.y));
        int split=1;float far=0;
        for(int i=1;i<points.size()-1;i++){float d=distance(points.get(i),first,first);if(d>far){far=d;split=i;}}
        boolean[] keep=new boolean[points.size()];keep[0]=keep[split]=keep[points.size()-1]=true;
        simplify(points,0,split,keep);simplify(points,split,points.size()-1,keep);
        output.moveTo(first.x,first.y);for(int i=1;i<points.size()-1;i++)if(keep[i])output.lineTo(points.get(i).x,points.get(i).y);output.close();points.clear();
    }
    private static Path smooth(Region region){
        Path original=region.getBoundaryPath();
        if(android.os.Build.VERSION.SDK_INT<26)return original;
        float[] values=original.approximate(.05f);Path result=new Path();
        ArrayList<PointF> points=new ArrayList<>();
        for(int i=0;i<values.length;i+=3){
            if(i>0&&values[i]==values[i-3])contour(result,points);
            points.add(new PointF(values[i+1],values[i+2]));
        }
        contour(result,points);return result;
    }
    static int[] rasterize(int[] pixels,int width,int height,int scale){
        if(height==15||(width==320&&(height==240||height==211||height<=64))||(width==480&&height==320))
            return texturedText(pixels,width,height,scale);
        return reconstruct(pixels,width,height,scale);
    }
    // Re-rasterize the low-resolution mask into true high-resolution pixels.
    // Each output pixel integrates a 4x4 sub-sample footprint instead of
    // copying source pixels or relying on a post-scale blur.
    private static int[] coverageRaster(int[] pixels,int width,int height,int scale){
        boolean opaque=true;
        for(int c:pixels)if((c>>>24)!=255){opaque=false;break;}
        if(opaque)return opaqueSurface(pixels,width,height,scale);
        int outW=width*scale,outH=height*scale;int[] result=new int[outW*outH];
        final float[] samples={.125f,.375f,.625f,.875f};
        for(int oy=0;oy<outH;oy++){
            float sy=(oy+.5f)/scale-.5f;int y0=(int)Math.floor(sy);float fy=sy-y0;
            for(int ox=0;ox<outW;ox++){
                float sx=(ox+.5f)/scale-.5f;int x0=(int)Math.floor(sx);float fx=sx-x0;
                float coverage=0;int colour=pixels[Math.max(0,Math.min(height-1,y0))*width+Math.max(0,Math.min(width-1,x0))];
                for(float vy:samples)for(float vx:samples){
                    int ix=(int)Math.floor(sx+vx-.5f),iy=(int)Math.floor(sy+vy-.5f);
                    if(ix>=0&&iy>=0&&ix<width&&iy<height&&(pixels[iy*width+ix]>>>24)>=96)coverage+=1f/16f;
                }
                // Preserve solid stems while retaining only a narrow, clean edge
                // band. This is a re-rasterized mask, rather than a blurred copy.
                float shaped=coverage<=0.08f?0:coverage>=0.62f?1f:(coverage-0.08f)/0.54f;
                int alpha=Math.max(0,Math.min(255,Math.round(shaped*255f)));
                if(alpha==0){result[oy*outW+ox]=0;continue;}
                result[oy*outW+ox]=(alpha<<24)|(colour&0x00ffffff);
            }
        }
        return result;
    }
    // Full-screen UI panels are opaque RGB surfaces. Use a high-resolution
    // bicubic-like reconstruction with a mild unsharp pass; treating every
    // pixel as glyph ink would destroy the panel and icons.
    private static int[] opaqueSurface(int[] pixels,int width,int height,int scale){
        int outW=width*scale,outH=height*scale;int[] result=new int[outW*outH];
        for(int oy=0;oy<outH;oy++)for(int ox=0;ox<outW;ox++){
            float sx=(ox+.5f)/scale-.5f,sy=(oy+.5f)/scale-.5f;
            int x=(int)Math.floor(sx),y=(int)Math.floor(sy);float fx=sx-x,fy=sy-y;
            int[] c=new int[4];
            for(int j=0;j<2;j++)for(int i=0;i<2;i++){
                int xx=Math.max(0,Math.min(width-1,x+i)),yy=Math.max(0,Math.min(height-1,y+j));
                int v=pixels[yy*width+xx];float w=((i==0)?1-fx:fx)*((j==0)?1-fy:fy);
                c[0]+=Math.round(((v>>16)&255)*w);c[1]+=Math.round(((v>>8)&255)*w);c[2]+=Math.round((v&255)*w);
            }
            result[oy*outW+ox]=0xff000000|(Math.min(255,Math.max(0,c[0]))<<16)|(Math.min(255,Math.max(0,c[1]))<<8)|Math.min(255,Math.max(0,c[2]));
        }
        // One restrained unsharp pass restores the high-frequency stroke edges
        // lost when a precomposed RGB panel is rebuilt at display resolution.
        int[] copy=result.clone();
        for(int y=1;y<outH-1;y++)for(int x=1;x<outW-1;x++){
            int center=copy[y*outW+x], cr=(center>>16)&255,cg=(center>>8)&255,cb=center&255;
            int sumr=0,sumg=0,sumb=0;
            for(int yy=-1;yy<=1;yy++)for(int xx=-1;xx<=1;xx++){int v=copy[(y+yy)*outW+x+xx];sumr+=(v>>16)&255;sumg+=(v>>8)&255;sumb+=v&255;}
            int rr=Math.max(0,Math.min(255,cr*2-sumr/9)),gg=Math.max(0,Math.min(255,cg*2-sumg/9)),bb=Math.max(0,Math.min(255,cb*2-sumb/9));
            result[y*outW+x]=0xff000000|(rr<<16)|(gg<<8)|bb;
        }
        return result;
    }
    private static int[] reconstruct(int[] pixels,int width,int height,int scale){
        // Atlas glyphs use the same coverage rasterizer; this removes the
        // opaque-background fallback that made the previous reconstruction
        // look like nearest-neighbour pixel art.
        return coverageRaster(pixels,width,height,scale);
        /*
        int[] colours=new int[pixels.length];
        Bitmap source=Bitmap.createBitmap(colours,width,height,Bitmap.Config.ARGB_8888);
        Bitmap output=Bitmap.createBitmap(width*scale,height*scale,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output);canvas.scale(scale/3f,scale/3f);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader=new BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        Matrix matrix=new Matrix();matrix.setScale(3,3);shader.setLocalMatrix(matrix);paint.setShader(shader);
        paint.setPathEffect(new CornerPathEffect(.65f));
        // Independent atlas cells bound path complexity and prevent neighbouring
        // glyphs from joining. Threefold edge reconstruction connects diagonals.
        int tile=width==256&&height>=1024?14:width;
        int tileHeight=tile==14?14:height;
        for(int ty=0;ty<height;ty+=tileHeight)for(int tx=0;tx<width;tx+=tile){
            int tw=Math.min(tile,width-tx),th=Math.min(tileHeight,height-ty);
            boolean[] expanded=new boolean[tw*th*9];int ew=tw*3;
            for(int y=0;y<th;y++)for(int x=0;x<tw;x++){
                int xx=tx+x,yy=ty+y;
                boolean a=ink(pixels,width,height,xx-1,yy-1),b=ink(pixels,width,height,xx,yy-1),c=ink(pixels,width,height,xx+1,yy-1);
                boolean d=ink(pixels,width,height,xx-1,yy),e=ink(pixels,width,height,xx,yy),f=ink(pixels,width,height,xx+1,yy);
                boolean g=ink(pixels,width,height,xx-1,yy+1),h=ink(pixels,width,height,xx,yy+1),i=ink(pixels,width,height,xx+1,yy+1);
                boolean[] p={e,e,e,e,e,e,e,e,e};
                if(b!=h&&d!=f){
                    p[0]=d==b?d:e;p[1]=((d==b&&e!=c)||(b==f&&e!=a))?b:e;p[2]=b==f?f:e;
                    p[3]=((d==b&&e!=g)||(d==h&&e!=a))?d:e;p[5]=((b==f&&e!=i)||(h==f&&e!=c))?f:e;
                    p[6]=d==h?d:e;p[7]=((d==h&&e!=i)||(h==f&&e!=g))?h:e;p[8]=h==f?f:e;
                }
                for(int v=0;v<3;v++)for(int u=0;u<3;u++)expanded[(y*3+v)*ew+x*3+u]=p[v*3+u];
            }
            Region region=new Region();
            for(int y=0;y<th*3;y++){int start=-1;for(int x=0;x<=ew;x++){boolean filled=x<ew&&expanded[y*ew+x];if(filled&&start<0)start=x;if(!filled&&start>=0){region.op(tx*3+start,ty*3+y,tx*3+x,ty*3+y+1,Region.Op.UNION);start=-1;}}}
            if(!region.isEmpty())canvas.drawPath(smooth(region),paint);
        }
        int[] result=new int[width*height*scale*scale];
        output.getPixels(result,0,width*scale,0,0,width*scale,height*scale);
        for(int y=0;y<height*scale;y++)for(int x=0;x<width*scale;x++){
            int index=y*width*scale+x;if((result[index]>>>24)==0)result[index]=colours[(y/scale)*width+x/scale]&0xffffff;
        }
        source.recycle();output.recycle();return result;*/
    }
}
