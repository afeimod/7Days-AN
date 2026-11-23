package org.island.nativevalidation;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Original movie playback on a compositable, aspect-preserving Android surface. */
final class ClipPlayback implements TextureView.SurfaceTextureListener {
    private final Context context;
    private final String name;
    private final Runnable finished;
    private final TextureView video;
    private MediaPlayer player;
    private Surface surface;
    private boolean stopped;
    private boolean paused;
    private boolean prepared;
    private boolean completionSent;
    private int resumePosition;

    ClipPlayback(FrameLayout container, String name, boolean initiallyPaused, Runnable finished)
            throws IOException {
        context=container.getContext();this.name=name;this.finished=finished;paused=initiallyPaused;
        cachedFile(context,name);
        video=new TextureView(context);video.setOpaque(false);video.setSurfaceTextureListener(this);
        container.addView(video,new FrameLayout.LayoutParams(-1,-1));
        if(video.isAvailable())onSurfaceTextureAvailable(video.getSurfaceTexture(),video.getWidth(),video.getHeight());
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
        if(stopped||player!=null)return;
        surface=new Surface(texture);player=new MediaPlayer();
        try{
            player.setDataSource(context,Uri.fromFile(cachedFile(context,name)));player.setSurface(surface);
            player.setOnVideoSizeChangedListener((p,w,h)->fit());
            player.setOnPreparedListener(p->{
                if(stopped)return;prepared=true;fit();
                if(resumePosition>0)p.seekTo(resumePosition,MediaPlayer.SEEK_CLOSEST);
                Log.i("IslandVideo","prepared "+name+" size="+p.getVideoWidth()+"x"+p.getVideoHeight()+" duration="+p.getDuration());
                if(!paused)p.start();
            });
            player.setOnInfoListener((p,event,extra)->{if(event==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)Log.i("IslandVideo","first-frame "+name);return false;});
            player.setOnCompletionListener(p->{Log.i("IslandVideo","completed "+name);finish();});
            player.setOnErrorListener((p,what,extra)->{Log.e("IslandVideo","failure "+name+" "+what+"/"+extra);finish();return true;});
            player.prepareAsync();
        }catch(IOException|RuntimeException error){Log.e("IslandVideo","cannot prepare "+name,error);finish();}
    }

    private void fit(){
        if(player==null||video.getWidth()==0||video.getHeight()==0||player.getVideoWidth()==0||player.getVideoHeight()==0)return;
        float videoAspect=(float)player.getVideoWidth()/player.getVideoHeight(),viewAspect=(float)video.getWidth()/video.getHeight();
        float sx=videoAspect<viewAspect?videoAspect/viewAspect:1f,sy=videoAspect>viewAspect?viewAspect/videoAspect:1f;
        Matrix matrix=new Matrix();matrix.setScale(sx,sy,video.getWidth()/2f,video.getHeight()/2f);video.setTransform(matrix);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){fit();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){releasePlayer();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}


    void pause(){
        if(stopped||paused)return;paused=true;
        if(prepared&&player!=null){resumePosition=player.getCurrentPosition();player.pause();}
    }
    void resume(){
        if(stopped||!paused)return;paused=false;
        if(prepared&&player!=null){if(resumePosition>0){player.seekTo(resumePosition,MediaPlayer.SEEK_CLOSEST);resumePosition=0;}player.start();}
    }
    void stop(){if(stopped)return;stopped=true;releasePlayer();}
    private void finish(){if(stopped)return;stop();if(!completionSent){completionSent=true;finished.run();}}
    private void releasePlayer(){
        prepared=false;if(player!=null){try{if(player.isPlaying())player.pause();}catch(IllegalStateException ignored){}player.release();player=null;}
        if(surface!=null){surface.release();surface=null;}
    }

    static synchronized File cachedFile(Context context,String name)throws IOException{
        File file=new File(context.getCacheDir(),"clip-"+name+".mp4");
        if(file.isFile()&&file.length()>0)return file;
        File temp=File.createTempFile("clip-",".tmp",context.getCacheDir());
        try{
            try(InputStream in=context.getAssets().open("movies/"+name+".mp4");FileOutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[32768];for(int count;(count=in.read(buffer))!=-1;)out.write(buffer,0,count);
            }
            if(temp.length()==0||!temp.renameTo(file))throw new IOException("Cannot cache clip "+name);return file;
        }finally{if(temp.exists())temp.delete();}
    }
}
