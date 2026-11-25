package org.island.nativevalidation;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Shared original-mesh preview used by the inventory and item information page. */
final class ItemMeshPreview {
    final int picpos;
    final MeshPreview mesh;
    private ItemMeshPreview(int picpos,MeshPreview mesh){this.picpos=picpos;this.mesh=mesh;}
    static ItemMeshPreview load(Activity activity,int picpos)throws Exception{
        Bitmap skin=OriginalUi.bitmap(activity,"preview-"+picpos);
        int[] texels=new int[skin.getWidth()*skin.getHeight()];
        skin.getPixels(texels,0,skin.getWidth(),0,0,skin.getWidth(),skin.getHeight());
        byte[] bytes;
        try(InputStream in=activity.getAssets().open("ui/preview-"+picpos+".mesh");
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            bytes=out.toByteArray();
        }
        ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count=data.getInt();float[] vertices=new float[count*5];
        data.asFloatBuffer().get(vertices);
        return new ItemMeshPreview(picpos,new MeshPreview(vertices,texels,skin.getWidth(),skin.getHeight()));
    }
    void render(Bitmap target,long time){
        int size=target.getWidth();
        int[] pixels=mesh.render(size,size,time*.00055f);
        target.setPixels(pixels,0,size,0,0,size,size);
    }
    static final class Viewport extends View {
        private final Activity activity;private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private int requested=-1;private ItemMeshPreview preview;private Bitmap image;private long last;
        Viewport(Activity activity){super(activity);this.activity=activity;setContentDescription("原版物品模型预览");}
        void item(int picpos){requested=picpos;preview=null;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);if(requested<0)return;
            try{
                if(preview==null||preview.picpos!=requested)preview=ItemMeshPreview.load(activity,requested);
                long now=android.os.SystemClock.uptimeMillis();
                if(image==null)image=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
                if(now-last>=80){preview.render(image,now);last=now;}
                canvas.drawBitmap(image,null,new android.graphics.RectF(0,0,getWidth(),getHeight()),paint);
                postInvalidateDelayed(80);
            }catch(Exception error){android.util.Log.e("IslandUi","Item model preview "+requested,error);}
        }
    }
}
