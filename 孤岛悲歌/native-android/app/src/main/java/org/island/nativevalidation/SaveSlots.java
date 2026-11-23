package org.island.nativevalidation;

import android.app.Activity;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Independent slots, committed atomically; imports the old single save once. */
final class SaveSlots {
    static final int COUNT=8,QUICK=0;
    private final SharedPreferences prefs;
    SaveSlots(Activity activity){prefs=activity.getPreferences(0);
        if(!prefs.contains("slot-migration")&&prefs.contains("game-save"))prefs.edit().putString(key(1),prefs.getString("game-save","")).putBoolean("slot-migration",true).commit();
    }
    private String key(int slot){return "save-slot-"+slot;}
    synchronized String read(int slot){return prefs.getString(key(slot),null);}
    synchronized boolean any(){for(int i=0;i<=COUNT;i++)if(read(i)!=null)return true;return false;}
    synchronized boolean write(int slot,String text,int level){return prefs.edit().putString(key(slot),text).putLong(key(slot)+"-time",System.currentTimeMillis()).putInt(key(slot)+"-level",level).commit();}
    synchronized String label(int slot){String title=slot==QUICK?"即时存档":"存档 "+slot;
        if(read(slot)==null)return title+"  ·  空";
        long time=prefs.getLong(key(slot)+"-time",0);int level=prefs.getInt(key(slot)+"-level",0);
        if(level==0)try{java.util.Properties p=new java.util.Properties();p.load(new java.io.StringReader(read(slot)));level=GameEngine.num(p.getProperty("level"),1);}catch(Exception ignored){}
        return title+"  ·  场景 "+level+(time==0?"  ·  原有进度":"  ·  "+new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(time)));
    }
}
