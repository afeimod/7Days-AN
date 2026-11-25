package org.island.nativevalidation;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.animation.ValueAnimator;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;

/** Original logo, title, new-game film, then gameplay. No scene runs behind it. */
public final class StartupFlow {
    private final Activity activity;
    private final Runnable start,load;
    private final boolean hasSave;
    private final GameAudio audio;
    private Dialog dialog;
    private FrameLayout root;
    private ClipPlayback video;
    private AnimatorSet studioLogoAnimation;
    private ImageView titlePrompt;
    private final android.os.Handler pulseHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pulse=new Runnable(){public void run(){if(titlePrompt==null||paused||released)return;titlePrompt.setAlpha(titlePrompt.getAlpha()>.5f?.05f:1f);pulseHandler.postDelayed(this,650);}};
    private void stopPulse(){pulseHandler.removeCallbacks(pulse);titlePrompt=null;}
    private boolean introSkippable;
    private int page;
    private final Runnable settings;
    private SeaVideo sea;
    private boolean paused;
    private boolean released;
    private final Runnable releaseGamePause;
    public StartupFlow(Activity activity,Runnable start,Runnable load,boolean hasSave,GameAudio audio,Runnable releaseGamePause,Runnable settings){this.activity=activity;this.start=start;this.load=load;this.hasSave=hasSave;this.audio=audio;this.releaseGamePause=releaseGamePause;this.settings=settings;introSkippable=activity.getPreferences(0).getBoolean("intro-completed",false);}
    public void show(){
        dialog=new Dialog(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        root=new FrameLayout(activity);root.setBackgroundColor(Color.BLACK);dialog.setContentView(root);dialog.setCancelable(false);dialog.show();
        dialog.setOnKeyListener((d,key,event)->{
            if(page==2&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)&&event.getAction()==KeyEvent.ACTION_DOWN){
                View focused=dialog.getWindow().getDecorView().findFocus();
                View next=focused==null?null:focused.focusSearch(key==KeyEvent.KEYCODE_DPAD_UP?View.FOCUS_UP:View.FOCUS_DOWN);
                if(next!=null){next.requestFocus();return true;}
            }
            if(key!=KeyEvent.KEYCODE_BUTTON_A&&key!=KeyEvent.KEYCODE_DPAD_CENTER&&key!=KeyEvent.KEYCODE_ENTER&&key!=KeyEvent.KEYCODE_BUTTON_START)return false;
            boolean handled=page<2;if(event.getAction()==KeyEvent.ACTION_UP){if(page==0&&introSkippable)completeIntro();else if(page==1)menu();else if(page==2){View focused=dialog.getWindow().getDecorView().findFocus();if(focused!=null)focused.performClick();}}return handled||page==2;
        });
        dialog.getWindow().setLayout(-1,-1);dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.getWindow().getDecorView().setSystemUiVisibility(immersiveFlags());
        GameWindow.apply(dialog.getWindow());
        audio.title();
        animateStudioLogo();
    }
    private int immersiveFlags(){return View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;}
    private ImageView picture(String name){
        ImageView image=new ImageView(activity);
        try(InputStream in=activity.getAssets().open("startup/"+name+".png")){image.setImageBitmap(BitmapFactory.decodeStream(in));}
        catch(IOException e){throw new IllegalStateException(e);}
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);return image;
    }
    private void background(){
        stopPulse();
        // Keep the same decoder playing when the title becomes the menu.
        // The source is the supplied animated sea, never a captured still.
        if(sea==null){stopVideo();audio.title();root.removeAllViews();sea=new SeaVideo();root.addView(sea,new FrameLayout.LayoutParams(-1,-1));}
        else while(root.getChildCount()>1)root.removeViewAt(1);
    }
    private void title(){
        View pad=dialog.findViewById(android.R.id.content).findViewWithTag("virtual-menu-pad");if(pad!=null)pad.setVisibility(View.GONE);
        page=1;
        background();int width=activity.getResources().getDisplayMetrics().widthPixels,height=activity.getResources().getDisplayMetrics().heightPixels;float scale=Math.min(width/240f,height/320f);
        ImageView logo=picture("menulogo");FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,Math.round(scale*142),Gravity.TOP);lp.topMargin=Math.round((height-scale*320)/2+scale*85);root.addView(logo,lp);
        ImageView press=picture("presskey");press.setContentDescription("按 OK 键进入");FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,Math.round(scale*22),Gravity.TOP);pp.topMargin=Math.round((height-scale*320)/2+scale*284);root.addView(press,pp);
        titlePrompt=press;pulseHandler.postDelayed(pulse,650);
        root.setOnClickListener(v->{audio.effect("ok.wav");menu();});
    }
    private void menu(){
        page=2;
        background();root.setOnClickListener(null);
        LinearLayout menu=new LinearLayout(activity){
            float swipeStart;boolean tracking;
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event){
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){swipeStart=event.getY();tracking=true;return false;}
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&tracking&&Math.abs(event.getY()-swipeStart)>24*activity.getResources().getDisplayMetrics().density)return true;
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)tracking=false;
                return false;
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent event){
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP&&tracking){
                    tracking=false;int direction=event.getY()<swipeStart?1:-1;
                    View focused=findFocus();int index=indexOfChild(focused);
                    for(int step=1;step<=getChildCount();step++){
                        int candidate=(Math.max(0,index)+direction*step+getChildCount()*2)%getChildCount();
                        View child=getChildAt(candidate);
                        if(child.isEnabled()){child.requestFocus();child.setSelected(true);break;}
                    }
                }
                return true;
            }
        };menu.setOrientation(LinearLayout.VERTICAL);menu.setGravity(Gravity.CENTER);
        int width=activity.getResources().getDisplayMetrics().widthPixels,height=activity.getResources().getDisplayMetrics().heightPixels;float scale=Math.min(width/240f,height/320f);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.round(scale*104),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.topMargin=Math.round((height-scale*320)/2+scale*60);root.addView(menu,lp);
        originalButton(menu,"新的开始","new",()->movie("head",()->{finish();start.run();}));
        // Keep the animated sea/menu window alive behind the translucent slot list.
        Button resume=originalButton(menu,"读取存档","load",()->{hideMenuForLoad();load.run();});resume.setEnabled(hasSave);resume.setAlpha(hasSave?1:.45f);
        originalButton(menu,"游戏设置","settings",settings);
        originalButton(menu,"游戏帮助","help",this::help);
        originalButton(menu,"开发团队","credits",this::credits);
        originalButton(menu,"正版激活","activate",()->optionalEntry("正版激活","此原生移植版无需联网激活。"));
        originalButton(menu,"访问官网","website",()->optionalEntry("访问官网","原版网站服务已停止，游戏内容已包含在本地安装包中。"));
        originalButton(menu,"退出游戏","exit",activity::finish);
        menu.setFocusable(true);
        menu.post(()->{if(menu.getChildCount()>0)menu.getChildAt(0).requestFocus();});
    }
    private void hideMenuForLoad(){
        if(root==null)return;
        // Keep the running sea video as the background; remove the title/menu
        // sprites so they cannot show through the save-slot overlay.
        for(int i=root.getChildCount()-1;i>=0;i--){View child=root.getChildAt(i);if(child!=sea)root.removeViewAt(i);}
    }
    public void restoreMenu(){if(!released&&page==2)menu();}
    private Button originalButton(LinearLayout parent,String label,String sprite,Runnable task){Button b=OriginalUi.button(activity,label,sprite,()->{audio.effect("ok.wav");task.run();});float scale=Math.min(activity.getResources().getDisplayMetrics().widthPixels/240f,activity.getResources().getDisplayMetrics().heightPixels/320f);parent.addView(b,new LinearLayout.LayoutParams(-1,Math.round(scale*22)));return b;}
    private void help(){
        HelpDialog d=new HelpDialog(activity);d.show();GameWindow.apply(d.getWindow());MenuControls.install(d,true);
    }
    private void credits(){
        ImageView image=OriginalUi.picture(activity,"about");image.setAdjustViewBounds(true);ScrollView scroll=new ScrollView(activity);scroll.addView(image);
        AlertDialog d=new AlertDialog.Builder(activity).setView(scroll).setPositiveButton("返回",null).create();d.show();OriginalUi.skin(d);MenuControls.install(d,false);
    }
    private void optionalEntry(String title,String message){
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("返回",null).create();
        d.show();OriginalUi.skin(d);MenuControls.install(d,false);
    }
    private void movie(String name,Runnable complete){
        View pad=dialog.findViewById(android.R.id.content).findViewWithTag("virtual-menu-pad");if(pad!=null)pad.setVisibility(View.GONE);
        page=name.equals("recall")?0:3;
        if (!name.equals("logo")) audio.close();
        stopVideo();
        root.removeAllViews();root.setOnClickListener(null);
        if(name.equals("recall")&&introSkippable)root.setOnClickListener(v->completeIntro());
        try{
            video = new ClipPlayback(root, name, paused, complete);
        }catch(IOException error){
            android.util.Log.e("IslandVideo", "cannot open " + name, error);
            complete.run();
        }
    }
    private void animateStudioLogo(){
        page=0;
        int width=activity.getResources().getDisplayMetrics().widthPixels,height=activity.getResources().getDisplayMetrics().heightPixels;
        root.removeAllViews();root.setBackgroundColor(Color.BLACK);
        root.setOnClickListener(introSkippable?v->completeIntro():null);
        ImageView logo=OriginalUi.picture(activity,"logotop");logo.setContentDescription(null);
        float aspect=(float)OriginalUi.bitmap(activity,"logotop").getWidth()/OriginalUi.bitmap(activity,"logotop").getHeight();
        int logoWidth=Math.round(Math.min(width*.42f,height*.42f*aspect)),logoHeight=Math.round(logoWidth/aspect);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(logoWidth,logoHeight,Gravity.CENTER);root.addView(logo,lp);
        logo.setAlpha(0);logo.setScaleX(.82f);logo.setScaleY(.82f);logo.setTranslationY(height*.10f);
        AnimatorSet enter=new AnimatorSet();enter.playTogether(ObjectAnimator.ofFloat(logo,View.ALPHA,0,1),ObjectAnimator.ofFloat(logo,View.SCALE_X,.82f,1),ObjectAnimator.ofFloat(logo,View.SCALE_Y,.82f,1),ObjectAnimator.ofFloat(logo,View.TRANSLATION_Y,height*.10f,0));enter.setDuration(2000);
        ObjectAnimator leave=ObjectAnimator.ofFloat(logo,View.ALPHA,1,0);leave.setDuration(550);
        studioLogoAnimation=new AnimatorSet();studioLogoAnimation.playSequentially(enter,leave);studioLogoAnimation.setStartDelay(180);
        studioLogoAnimation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){if(studioLogoAnimation==animation){studioLogoAnimation=null;if(!released)movie("recall",StartupFlow.this::completeIntro);}}});
        if(paused)studioLogoAnimation.pause();else studioLogoAnimation.start();
    }
    private void completeIntro(){if(released||page!=0)return;activity.getPreferences(0).edit().putBoolean("intro-completed",true).apply();stopVideo();title();}
    private void stopVideo(){stopPulse();if(studioLogoAnimation!=null){AnimatorSet old=studioLogoAnimation;studioLogoAnimation=null;old.cancel();}if(video!=null){video.stop();video=null;}if(sea!=null){sea.release();sea=null;}}
    private void finish(){if(released)return;stopVideo();audio.close();dialog.dismiss();releaseGamePause.run();released=true;}
    public void closeForLoad(){finish();}
    public void pause(){paused=true;pulseHandler.removeCallbacks(pulse);if(studioLogoAnimation!=null&&studioLogoAnimation.isStarted())studioLogoAnimation.pause();if(video!=null)video.pause();if(sea!=null)sea.pause();}
    public void resume(){paused=false;if(titlePrompt!=null){pulseHandler.removeCallbacks(pulse);pulseHandler.postDelayed(pulse,650);}if(studioLogoAnimation!=null&&studioLogoAnimation.isPaused())studioLogoAnimation.resume();if(video!=null)video.resume();if(sea!=null)sea.resume();}
    public void destroy(){released=true;stopVideo();if(dialog!=null)dialog.dismiss();releaseGamePause.run();}

    /** Native video texture allows title/buttons to remain above the moving sea. */
    private final class SeaVideo extends TextureView implements TextureView.SurfaceTextureListener {
        private MediaPlayer player;
        private boolean prepared;
        SeaVideo(){super(activity);setSurfaceTextureListener(this);}
        @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
            Surface surface=new Surface(texture);
            try{
                player=new MediaPlayer();player.setDataSource(ClipPlayback.cachedFile(activity,"logo").getAbsolutePath());player.setSurface(surface);player.setLooping(true);player.setVolume(0,0);
                player.setOnVideoSizeChangedListener((p,w,h)->crop());
                player.setOnPreparedListener(p->{prepared=true;crop();if(!paused)p.start();android.util.Log.i("IslandVideo","title-sea-prepared looping=true duration="+p.getDuration());});
                player.setOnInfoListener((p,what,extra)->{if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)android.util.Log.i("IslandVideo","title-sea-first-frame");return false;});
                player.setOnErrorListener((p,what,extra)->{android.util.Log.e("IslandVideo","title-sea-failure "+what+"/"+extra);release();return true;});
                player.prepareAsync();
            }catch(IOException e){release();android.util.Log.e("IslandVideo","Cannot play title background",e);}
            finally{surface.release();}
        }
        private void crop(){
            if(player==null||getWidth()==0||getHeight()==0||player.getVideoWidth()==0||player.getVideoHeight()==0)return;
            float videoAspect=(float)player.getVideoWidth()/player.getVideoHeight(),viewAspect=(float)getWidth()/getHeight();
            // Fill the title backdrop while preserving the video aspect ratio.
            // TextureView transforms its surface about the view center, so only
            // the axis whose source aspect exceeds the viewport is cropped.
            float scaleX=Math.max(1,videoAspect/viewAspect),scaleY=Math.max(1,viewAspect/videoAspect);
            Matrix matrix=new Matrix();matrix.setScale(scaleX,scaleY,getWidth()/2f,getHeight()/2f);setTransform(matrix);
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){crop();}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){release();return true;}
        @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        void pause(){if(prepared&&player!=null)player.pause();}
        void resume(){if(prepared&&player!=null)player.start();}
        void release(){prepared=false;if(player!=null){player.release();player=null;}}
    }
}
