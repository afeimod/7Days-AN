package org.hebdomad.android;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.TextView;
import android.view.Gravity;
import java.io.*;
import java.security.MessageDigest;

/** Installs and verifies the bundled user-supplied resource pack in private storage. */
public final class LauncherActivity extends Activity {
    private TextView status;
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        status=new TextView(this);status.setGravity(Gravity.CENTER);status.setTextColor(0xffeeeeee);
        status.setBackgroundColor(0xff141414);status.setTextSize(20);status.setText("正在准备七夜游戏数据…");
        setContentView(status);
        new Thread(new Runnable(){public void run(){
            try {
                prepare("res.spk",51798123L,"501dac61d826dd5587c7a12f50590e62167592bff89897ed44bdf5b19ccb5435");
                prepare("engine.arm",3976092L,"2d4833b0203ad4b3285ae2757e3a1fee0981976051666539c1ef27155e10117d");
                runOnUiThread(new Runnable(){public void run(){startActivity(new Intent(LauncherActivity.this,GameActivity.class));finish();}});
            } catch(final Exception e){runOnUiThread(new Runnable(){public void run(){status.setText("游戏数据准备失败\n"+e.getMessage());}});}
        }},"Hebdomad-install").start();
    }
    private void prepare(String name,long length,String digest) throws Exception {
        File target=new File(getFilesDir(),name);
        android.content.SharedPreferences verified=getSharedPreferences("verified-assets",0);
        String identity=digest+":"+length+":"+target.lastModified();
        if(target.isFile()&&target.length()==length){
            if(identity.equals(verified.getString(name,"")))return;
            if(digest==null||digest.equals(hash(target))){verified.edit().putString(name,identity).commit();return;}
        }
        File temporary=new File(getFilesDir(),name+".tmp");
        try(InputStream in=getAssets().open(name);OutputStream out=new FileOutputStream(temporary)){
            byte[] block=new byte[256*1024];int count;while((count=in.read(block))!=-1)out.write(block,0,count);
        }
        if(temporary.length()!=length || (digest!=null && !digest.equals(hash(temporary))))throw new IOException(name+" 校验失败");
        if(target.exists()&&!target.delete())throw new IOException("无法更新 "+name);
        if(!temporary.renameTo(target))throw new IOException("无法保存 "+name);
        verified.edit().putString(name,digest+":"+length+":"+target.lastModified()).commit();
    }
    private String hash(File file) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[256*1024];int n;while((n=in.read(b))!=-1)sha.update(b,0,n);}
        StringBuilder result=new StringBuilder();for(byte v:sha.digest())result.append(String.format("%02x",v&255));return result.toString();
    }
}
