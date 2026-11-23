package org.island.nativevalidation;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.media.AudioAttributes;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.util.*;

/** Play original MPEG background tracks with Android's native decoder. */
public final class GameAudio {
    private final Context context;
    private MediaPlayer music;
    private int track=-1;
    private boolean muted;
    private final SoundPool effects;
    private final Map<String,Integer> effectIds=new HashMap<>();
    private final Set<Integer> loaded=new HashSet<>();
    private final Map<String,Long> lastEffect=new HashMap<>();
    private final Map<Integer,String> pending=new HashMap<>();
    private String[] mapEffects=new String[0];
    private boolean paused;
    private boolean clipPlaying;
    private float musicLevel=1,effectLevel=1;
    public GameAudio(Context context){this.context=context.getApplicationContext();musicLevel=context.getSharedPreferences("audio",0).getFloat("music",1);effectLevel=context.getSharedPreferences("audio",0).getFloat("effects",1);effects=new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();effects.setOnLoadCompleteListener((pool,id,status)->{synchronized(this){if(status==0){loaded.add(id);String name=pending.remove(id);if(name!=null)effect(name);}}});}
    public synchronized void scene(int number){
        try(InputStream in=context.getAssets().open("sound/soundlist"+number+".txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[2048];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);mapEffects=out.toString("UTF-8").trim().split("\\s+");
        }catch(Exception e){mapEffects=new String[0];Log.e("IslandAudio","sound list "+number,e);}
    }
    public synchronized void effect(int index){if(index>=0&&index<mapEffects.length)effect(mapEffects[index]);}
    public synchronized void effect(String name){
        if(muted||paused)return;long now=SystemClock.uptimeMillis();if(now-lastEffect.getOrDefault(name,0L)<250)return;
        try{
            Integer id=effectIds.get(name);if(id==null){File file=new File(context.getCacheDir(),"effect-"+name);if(!file.exists())try(InputStream in=context.getAssets().open("sound/"+name);FileOutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}id=effects.load(file.getAbsolutePath(),1);effectIds.put(name,id);pending.put(id,name);return;}
            if(loaded.contains(id)){effects.play(id,.65f*effectLevel,.65f*effectLevel,1,0,1);lastEffect.put(name,now);Log.i("IslandAudio","effect "+name);}
        }catch(Exception e){Log.e("IslandAudio","effect "+name,e);}
    }
    public synchronized void title(){playMusic(-2,"logo",1f);}
    public synchronized void music(int id){playMusic(id,"level"+id,.45f);}
    private void playMusic(int id,String name,float volume){
        if(track==id&&music!=null)return;
        close();track=id;
        try{
            File file=new File(context.getCacheDir(),"island-"+name+".mp3");
            if(!file.exists())try(InputStream input=context.getAssets().open("music/"+name+".mp3");FileOutputStream output=new FileOutputStream(file)){
                byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);
            }
            music=new MediaPlayer();music.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());music.setDataSource(file.getAbsolutePath());music.setLooping(true);musicVolume=volume;applyVolume();music.prepare();syncMusic();Log.i("IslandAudio","playing original music "+name+" looping=true volume="+volume*musicLevel);
        }catch(Exception error){Log.e("IslandAudio","cannot play track "+id,error);close();}
    }
    private float musicVolume=.45f;
    public synchronized void pause(){paused=true;effects.autoPause();syncMusic();}
    public synchronized void resume(){paused=false;effects.autoResume();syncMusic();}
    public synchronized void clipPlaying(boolean playing) {
        clipPlaying = playing;
        syncMusic();
    }
    private void syncMusic() {
        if (music == null) return;
        boolean shouldPlay = !paused && !clipPlaying;
        if (shouldPlay && !music.isPlaying()) music.start();
        else if (!shouldPlay && music.isPlaying()) music.pause();
    }
    private void applyVolume(){if(music!=null)music.setVolume(muted?0:musicVolume*musicLevel,muted?0:musicVolume*musicLevel);}
    public synchronized float musicLevel(){return musicLevel;}public synchronized float effectLevel(){return effectLevel;}
    public synchronized void volume(int channel,float value){if(channel==0)effectLevel=value;else musicLevel=value;applyVolume();context.getSharedPreferences("audio",0).edit().putFloat("music",musicLevel).putFloat("effects",effectLevel).apply();}
    public synchronized void toggle(){muted=!muted;applyVolume();}
    public synchronized void close(){if(music!=null){music.release();music=null;}}
    public synchronized void release(){close();effects.release();}
}
