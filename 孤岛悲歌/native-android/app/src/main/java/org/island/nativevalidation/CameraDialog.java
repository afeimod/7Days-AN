package org.island.nativevalidation;
import android.app.*;
import android.graphics.*;
import android.hardware.Camera;
import android.os.Handler;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Device camera preview and capture; no substitute stock photograph. */
final class CameraDialog extends Dialog implements TextureView.SurfaceTextureListener {
    interface Listener {void brightness(int value);void captured(File file);}
    private final Activity activity;private final Listener listener;private final TextureView preview;private final TextView status;
    private Camera camera;private final boolean selfie;private final Handler main=new Handler();
    CameraDialog(Activity activity,boolean selfie,Listener listener){super(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen);this.activity=activity;this.selfie=selfie;this.listener=listener;
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        preview=new TextureView(activity);preview.setSurfaceTextureListener(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(activity);status.setText("摄像头");status.setGravity(Gravity.CENTER);status.setTextColor(Color.WHITE);root.addView(status);
        Button capture=OriginalUi.button(activity,"OK · 拍照",null,this::capture);root.addView(capture,new LinearLayout.LayoutParams(-1,OriginalUi.dp(activity,48)));setContentView(root);
        setOnKeyListener((d,key,e)->{if(key==23||key==66||key==96||key==KeyEvent.KEYCODE_CAMERA){if(e.getAction()==KeyEvent.ACTION_UP)capture();return true;}return false;});
    }
    private void open(SurfaceTexture texture){
        if(camera!=null)return;
        try{int count=Camera.getNumberOfCameras();if(count==0)throw new IllegalStateException();int index=0;for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing==(selfie?Camera.CameraInfo.CAMERA_FACING_FRONT:Camera.CameraInfo.CAMERA_FACING_BACK)){index=i;break;}}
            camera=Camera.open(index);camera.setPreviewTexture(texture);Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(index,info);int rotation=activity.getWindowManager().getDefaultDisplay().getRotation()*90;
            camera.setDisplayOrientation(info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(info.orientation+rotation)%360)%360:(info.orientation-rotation+360)%360);
            Camera.Parameters parameters=camera.getParameters();parameters.setRotation(info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(info.orientation-rotation+360)%360:(info.orientation+rotation)%360);camera.setParameters(parameters);
            camera.setPreviewCallback((bytes,c)->{Camera.Size size=c.getParameters().getPreviewSize();int limit=Math.min(bytes.length,size.width*size.height);long sum=0;int n=0;for(int i=0;i<limit;i+=32){sum+=bytes[i]&255;n++;}if(n>0){int value=(int)Math.max(0,Math.min(100,(sum/n-16)*100/219));main.post(()->{status.setText("光线  "+value+"%");listener.brightness(value);});}});camera.startPreview();
        }catch(Exception e){close();status.setText("未能打开摄像头，请检查系统权限和设备。");}
    }
    private void capture(){if(camera==null)return;try{camera.takePicture(null,null,(data,c)->{try{File file=new File(activity.getFilesDir(),selfie?"portrait.jpg":"camera-photo.jpg");try(FileOutputStream out=new FileOutputStream(file)){out.write(data);}listener.captured(file);dismiss();}catch(IOException e){status.setText("照片保存失败，请重试。");c.startPreview();}});}catch(Exception e){status.setText("暂时无法拍照，请重试。");}}
    private void close(){if(camera!=null){camera.setPreviewCallback(null);camera.release();camera=null;}}
    void pauseCamera(){close();}
    void resumeCamera(){if(isShowing()&&preview.isAvailable())open(preview.getSurfaceTexture());}
    @Override protected void onStop(){close();super.onStop();}
    @Override protected void onStart(){super.onStart();if(preview.isAvailable())open(preview.getSurfaceTexture());}
    public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){open(t);}public boolean onSurfaceTextureDestroyed(SurfaceTexture t){close();return true;}public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){}public void onSurfaceTextureUpdated(SurfaceTexture t){}
}
