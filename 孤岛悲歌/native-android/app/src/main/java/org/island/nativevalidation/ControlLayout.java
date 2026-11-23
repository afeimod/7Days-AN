package org.island.nativevalidation;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
final class ControlLayout {
    static final class Spec {
        final String id,label;float x,y,size,alpha;
        Spec(String id,String label,float x,float y,float size,float alpha){this.id=id;this.label=label;this.x=x;this.y=y;this.size=size;this.alpha=alpha;}
    }
    final LinkedHashMap<String,Spec> specs=new LinkedHashMap<>();
    private final SharedPreferences prefs;boolean floating;
    ControlLayout(Context context){prefs=context.getSharedPreferences("touch-controls",0);load();}
    private void add(String id,String label,float x,float y,float size,float alpha){specs.put(id,new Spec(id,label,x,y,size,alpha));}
    void defaults(){
        specs.clear();floating=false;
        add("stick","摇杆",.12f,.77f,132,.68f);
        add("5","5 / OK · 调查 / 使用 / 确认",.90f,.82f,86,.90f);
        add("1","1 / 3 · 跳跃",.90f,.61f,56,.82f);add("7","7 / 9 · 下蹲",.835f,.655f,54,.82f);
        add("inventory","物品栏",.055f,.11f,44,.78f);add("menu","暂停菜单",.90f,.11f,44,.78f);add("settings","设置",.955f,.11f,44,.78f);
        add("*","* · 弹弓模式",.805f,.82f,54,.82f);add("0","0 · 地图",.055f,.25f,42,.78f);add("#","# · 多用镐",.835f,.975f,52,.82f);
    }
    void load(){defaults();floating=prefs.getInt("layout-version",0)>=2&&prefs.getBoolean("floating",false);for(Spec s:specs.values()){
        float px=prefs.getFloat(s.id+".x",s.x),py=prefs.getFloat(s.id+".y",s.y),size=prefs.getFloat(s.id+".size",s.size);
        float[] legacy=legacy(s.id);boolean migrate=prefs.getInt("layout-version",0)<3&&legacy!=null&&Math.abs(px-legacy[0])<.001f&&Math.abs(py-legacy[1])<.001f;
        if(!migrate){s.x=clamp(px,0,1);s.y=clamp(py,0,1);}if(migrate&&Math.abs(size-legacy[2])<.001f)size=s.size;
        s.size=clamp(size,s.id.equals("stick")?80:28,s.id.equals("stick")?220:112);s.alpha=clamp(prefs.getFloat(s.id+".alpha",s.alpha),.15f,1);
    }}
    private static float[] legacy(String id){switch(id){case "5":return new float[]{.84f,.72f,68};case "1":return new float[]{.89f,.55f,54};case "7":return new float[]{.75f,.86f,54};case "*":return new float[]{.75f,.59f,52};case "#":return new float[]{.89f,.89f,52};default:return null;}}
    void save(){SharedPreferences.Editor e=prefs.edit().putInt("layout-version",3).putBoolean("floating",floating);for(Spec s:specs.values())e.putFloat(s.id+".x",s.x).putFloat(s.id+".y",s.y).putFloat(s.id+".size",s.size).putFloat(s.id+".alpha",s.alpha);e.apply();}
    static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
}
