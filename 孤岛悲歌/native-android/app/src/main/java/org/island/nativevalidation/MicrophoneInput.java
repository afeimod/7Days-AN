package org.island.nativevalidation;
import android.media.*;
import android.os.*;

/** Real PCM audio drives blowing strength and short/long Morse pulses. */
final class MicrophoneInput {
    interface Listener {void level(float strength);void pulse(char symbol);void error(String message);}
    private volatile boolean running;private AudioRecord record;private final Handler main=new Handler(Looper.getMainLooper());
    void start(Listener listener){
        if(running)return;
        int rate=16000,min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        try{record=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(4096,min));if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException();record.startRecording();}
        catch(Exception e){stop();listener.error("无法打开麦克风，请检查系统权限和音频设备。");return;}
        running=true;AudioRecord source=record;
        new Thread(()->{short[] samples=new short[1024];long started=SystemClock.elapsedRealtime(),pulseStart=0,lastSound=0;float floor=.006f;
            while(running&&record==source){int n;try{n=source.read(samples,0,samples.length);}catch(Exception e){if(running)main.post(()->listener.error("麦克风录音已中断。"));break;}if(n<=0){if(running)main.post(()->listener.error("麦克风录音已中断。"));break;}
                double sum=0;for(int i=0;i<n;i++)sum+=(double)samples[i]*samples[i];float rms=(float)Math.sqrt(sum/n)/32768f;long now=SystemClock.elapsedRealtime();
                if(now-started<500){floor=floor*.8f+rms*.2f;continue;}
                float threshold=Math.max(.025f,floor*3.5f),strength=ControlLayout.clamp((rms-threshold)/Math.max(.04f,threshold*2),0,1);
                if(rms>threshold){if(pulseStart==0)pulseStart=now;lastSound=now;}else if(pulseStart>0&&now-lastSound>110){long duration=lastSound-pulseStart;char symbol=duration<350?'.':'-';pulseStart=0;if(duration>=55)main.post(()->{if(running)listener.pulse(symbol);});}
                main.post(()->{if(running)listener.level(strength);});
            }
            synchronized(this){if(record==source)stop();}
        },"IslandMicrophone").start();
    }
    synchronized void stop(){running=false;AudioRecord previous=record;record=null;if(previous!=null){try{previous.stop();}catch(Exception ignored){}previous.release();}}
}
