package org.hebdomad.android;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.View;
import android.media.AudioManager;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import org.libsdl.app.SDLActivity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.os.Handler;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.hardware.input.InputManager;

public final class GameActivity extends SDLActivity {
    private TouchControls controls;
    private boolean editingBack;
    static native void touchKey(int scan,boolean down);
    static native void clearTouch();
    static native void controllerKey(int scan,boolean down);
    static native boolean quickState(boolean load,int slot);
    static native int quickStateResult();
    private final Handler handler=new Handler();
    private final Set<Integer> padKeys=new HashSet<>(),padAxes=new HashSet<>();
    private boolean statePending;
    private InputManager inputManager;
    private final InputManager.InputDeviceListener deviceListener=new InputManager.InputDeviceListener(){
        public void onInputDeviceAdded(int id){}
        public void onInputDeviceChanged(int id){releasePad();}
        public void onInputDeviceRemoved(int id){releasePad();}
    };
    static native void setFilter(int mode);
    static native void setRenderScale(int scale);
    static native void setAspect(int mode);
    static native void setLighting(int strength);
    static native void setBeginner(boolean enabled);
    static native void equipWeapon(int weapon);
    static native void setCostume(int costume);
    static native String renderInfo();
    static native int[] touchViewport();
    float touchAspectRatio(){
        switch(getPreferences(0).getInt("aspect",0)){
            case 1:return 16f/9f;
            case 2:return 4f/3f;
            case 3:return 5f/3f;
            default:return getResources().getDisplayMetrics().widthPixels/(float)Math.max(1,getResources().getDisplayMetrics().heightPixels);
        }
    }
    static int[] rasterizeText(int[] pixels,int w,int h,int scale){return NativeText.rasterize(pixels,w,h,scale);}
    static boolean writeStateSnapshot(String path,int[] pixels,int w,int h){return SaveSlots.writeSnapshot(path,pixels,w,h);}
    protected String[] getLibraries(){return new String[]{"SDL3","main"};}
    protected String[] getArguments(){return new String[]{getFilesDir().getAbsolutePath()};}
    protected void onCreate(Bundle state){
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        inputManager=(InputManager)getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(deviceListener,handler);
        setFilter(getPreferences(0).getInt("filter",0));
        setRenderScale(getPreferences(0).getInt("renderScale",1));
        setAspect(getPreferences(0).getInt("aspect",0));
        setLighting(getPreferences(0).getInt("lighting",0));
        setBeginner(getPreferences(0).getBoolean("beginner",false));
        setCostume(getPreferences(0).getInt("costume",0));
        if(mLayout!=null){controls=new TouchControls(this);mLayout.addView(controls,new ViewGroup.LayoutParams(-1,-1));}
    }
    void showDisplaySettings(){
        if(controls!=null)controls.releaseAll();
        final String[] options={"即时存档","即时读档",controls.buttonsVisible()?"隐藏虚拟按钮":"显示虚拟按钮","内部渲染分辨率","画面滤镜","画面比例","虚拟按键调节","柔光渲染强度","新手模式","武器选择（全部开放）","服装纹理选择","手柄操作说明"};
        ListView list=new ListView(this);list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,options));
        final AlertDialog settings=new AlertDialog.Builder(this).setTitle("游戏设置").setView(list).setNegativeButton("关闭",null).create();
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener(){public void onItemClick(android.widget.AdapterView<?> parent,View view,int which,long id){settings.dismiss();
            switch(which){case 0:runQuickState(false);break;case 1:runQuickState(true);break;case 2:controls.setButtonsVisible(!controls.buttonsVisible());break;case 3:showResolution();break;case 4:showFilters();break;case 5:showAspect();break;case 6:controls.setButtonsVisible(true);showTouchSettings();break;case 7:showLighting();break;case 8:showBeginner();break;case 9:showWeapons();break;case 10:showCostumes();break;default:new AlertDialog.Builder(GameActivity.this).setTitle("手柄操作").setMessage("方向键 / 左摇杆：移动、选择\nA：确认 / 交互\nB：物品 / 返回\nY：地图\nStart：设置（可恢复虚拟按钮）\nL1：即时存档 · R1：即时读档\n键盘 F5 存档，F9 读档，F10 显隐按钮").setPositiveButton("关闭",null).show();}
        }});
        settings.show();
    }
    private void showLighting(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(24*getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,0);
        final TextView label=new TextView(this);box.addView(label);
        SeekBar strength=new SeekBar(this);strength.setMax(100);
        int value=getPreferences(0).getInt("lighting",0);strength.setProgress(value);
        label.setText("柔光与光晕："+value+"%（0% 关闭特效）");box.addView(strength);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int v,boolean user){if(user){setLighting(v);getPreferences(0).edit().putInt("lighting",v).apply();label.setText("柔光与光晕："+v+"%（0% 关闭特效）");}}
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
        });
        new AlertDialog.Builder(this).setTitle("柔光渲染强度").setView(box).setPositiveButton("完成",null).show();
    }
    private void showBeginner(){
        new AlertDialog.Builder(this).setTitle("新手模式").setSingleChoiceItems(new String[]{"普通模式","新手模式（免受战斗伤害）"},getPreferences(0).getBoolean("beginner",false)?1:0,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface dialog,int which){boolean enabled=which==1;getPreferences(0).edit().putBoolean("beginner",enabled).apply();setBeginner(enabled);dialog.dismiss();}
        }).setNegativeButton("关闭",null).show();
    }
    private void showWeapons(){
        new AlertDialog.Builder(this).setTitle("武器选择（成人角色）").setItems(new String[]{"空手","小刀","西洋剑","手枪","沙鹰","黄金沙鹰（实验）"},new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface dialog,int which){equipWeapon(which);Toast.makeText(GameActivity.this,"成人角色进入场景后装备",Toast.LENGTH_SHORT).show();}
        }).setNegativeButton("关闭",null).show();
    }
    private void showCostumes(){
        final String[] names={"普通服装","黑色服装","白色面具服装","普通服装（B 款）","黑色服装（B 款）","白色面具服装（B 款）","邪恶 Ken","邪恶 Ken（黑白）"};
        new AlertDialog.Builder(this).setTitle("服装与隐藏外观（成人角色）").setSingleChoiceItems(names,getPreferences(0).getInt("costume",0),new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface dialog,int which){getPreferences(0).edit().putInt("costume",which).apply();setCostume(which);dialog.dismiss();Toast.makeText(GameActivity.this,"成人角色进入场景后应用外观",Toast.LENGTH_SHORT).show();}
        }).setNegativeButton("关闭",null).show();
    }
    private void showTouchSettings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(24*getResources().getDisplayMetrics().density);box.setPadding(pad,0,pad,0);
        final TextView alphaText=new TextView(this);alphaText.setText("不透明度："+(int)(controls.controlOpacity()*100)+"%");box.addView(alphaText);
        SeekBar alpha=new SeekBar(this);alpha.setMax(90);alpha.setProgress((int)(controls.controlOpacity()*100)-10);box.addView(alpha);
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean user){if(user){controls.setControlOpacity((v+10)/100f);alphaText.setText("不透明度："+(v+10)+"%");}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        final TextView sizeText=new TextView(this);sizeText.setText("大小："+(int)(controls.controlSize()*100)+"%");box.addView(sizeText);
        SeekBar size=new SeekBar(this);size.setMax(110);size.setProgress((int)(controls.controlSize()*100)-50);box.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean user){if(user){controls.setControlSize((v+50)/100f);sizeText.setText("大小："+(v+50)+"%");}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        new AlertDialog.Builder(this).setTitle("虚拟按键调节").setView(box).setPositiveButton("拖动位置",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){controls.editPositions();}}).setNeutralButton("恢复默认",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){controls.resetLayout();}}).setNegativeButton("完成",null).show();
    }
    private void showResolution(){
        new AlertDialog.Builder(this).setTitle("内部渲染："+renderInfo()).setSingleChoiceItems(new String[]{"1× 屏幕原生分辨率","2× 超采样（立即生效）"},getPreferences(0).getInt("renderScale",1)-1,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface dialog,int which){getPreferences(0).edit().putInt("renderScale",which+1).apply();setRenderScale(which+1);dialog.dismiss();}
        }).setNegativeButton("关闭",null).show();
    }
    private void showAspect(){
        new AlertDialog.Builder(this).setTitle("画面比例").setSingleChoiceItems(new String[]{"自动适配横屏","16:9","4:3（游戏原始视野）","5:3"},getPreferences(0).getInt("aspect",0),new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface dialog,int which){getPreferences(0).edit().putInt("aspect",which).apply();setAspect(which);dialog.dismiss();}
        }).setNegativeButton("关闭",null).show();
    }
    private void showFilters(){
        new AlertDialog.Builder(this).setTitle("画面滤镜").setSingleChoiceItems(new String[]{"原始画面","4xBR 边缘平滑","HQ4x 边缘平滑","Dot 点阵","扫描线","全局高清（超采样与抗锯齿）"},getPreferences(0).getInt("filter",0),new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface dialog,int which){setFilter(which);getPreferences(0).edit().putInt("filter",which).apply();dialog.dismiss();}
        }).setNegativeButton("关闭",null).show();
    }
    private void runQuickState(boolean load){
        final boolean loading=load;
        SaveSlots.show(this,load,new SaveSlots.Selection(){
            public void select(int slot){
                File save=new File(getFilesDir(),"slot"+(99+slot)+".json");
                if(loading&&!save.isFile()){Toast.makeText(GameActivity.this,"该槽位尚无存档",Toast.LENGTH_SHORT).show();return;}
                if(!loading&&save.isFile()){
                    final int selected=slot;
                    new AlertDialog.Builder(GameActivity.this).setTitle("覆盖槽位 "+(slot+1)+"？").setMessage("将用当前游戏进度替换此槽位的存档。").setPositiveButton("覆盖",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){runQuickState(false,selected);}}).setNegativeButton("取消",null).show();
                }else runQuickState(loading,slot);
            }
        });
    }
    private void runQuickState(boolean load,final int slot){
        if(statePending)return;
        releasePad();if(controls!=null)controls.releaseAll();
        if(!quickState(load,slot)){Toast.makeText(this,"存读档正在进行",Toast.LENGTH_SHORT).show();return;}
        statePending=true;
        final boolean loading=load;
        handler.postDelayed(new Runnable(){public void run(){
            if(isFinishing()){statePending=false;return;}
            int r=quickStateResult();if(r==0){handler.postDelayed(this,100);return;}
            statePending=false;
            Toast.makeText(GameActivity.this,r==1?("槽位 "+(slot+1)+(loading?" 读档完成":" 存档及快照已保存")):r==2?"进度已保存，快照生成失败":r==-1?"请先进入游戏再存档":r==-3?"该槽位尚无存档":r==-4?"场景正在加载，请稍后再试":"存读档失败",Toast.LENGTH_LONG).show();
        }},100);
    }
    private void updatePad(Set<Integer> source,Set<Integer> next){
        Set<Integer> previous=new HashSet<>(padKeys);previous.addAll(padAxes);source.clear();source.addAll(next);
        Set<Integer> current=new HashSet<>(padKeys);current.addAll(padAxes);
        for(int scan:previous)if(!current.contains(scan))controllerKey(scan,false);
        for(int scan:current)if(!previous.contains(scan))controllerKey(scan,true);
    }
    private int controllerScan(int key){
        switch(key){
            case KeyEvent.KEYCODE_DPAD_UP:return 26;
            case KeyEvent.KEYCODE_DPAD_DOWN:return 22;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 4;
            case KeyEvent.KEYCODE_DPAD_RIGHT:return 7;
            case KeyEvent.KEYCODE_BUTTON_A:return 13;
            case KeyEvent.KEYCODE_BUTTON_B:return 14;
            case KeyEvent.KEYCODE_BUTTON_X:return 15;
            case KeyEvent.KEYCODE_BUTTON_Y:return 16;
            case KeyEvent.KEYCODE_BUTTON_SELECT:return 41;
            default:return 0;
        }
    }
    private boolean handleControllerKey(KeyEvent event){
        int key=event.getKeyCode();
        boolean common=key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||KeyEvent.isGamepadButton(key);
        boolean source=(event.getSource()&(InputDevice.SOURCE_GAMEPAD|InputDevice.SOURCE_JOYSTICK|InputDevice.SOURCE_DPAD))!=0;
        if(!common&&!source)return false;
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(down&&controls!=null)controls.setButtonsVisibleFromController(false);
        int scan=controllerScan(key);
        if(scan!=0){
            Set<Integer> next=new HashSet<>(padKeys);
            if(down)next.add(scan);else next.remove(scan);
            updatePad(padKeys,next);return true;
        }
        if(key==KeyEvent.KEYCODE_BUTTON_START||key==KeyEvent.KEYCODE_BUTTON_MODE||key==KeyEvent.KEYCODE_BUTTON_L1||key==KeyEvent.KEYCODE_BUTTON_R1){
            if(down&&event.getRepeatCount()==0){
                if(key==KeyEvent.KEYCODE_BUTTON_L1)runQuickState(false);
                else if(key==KeyEvent.KEYCODE_BUTTON_R1)runQuickState(true);
                else showDisplaySettings();
            }
            return true;
        }
        return true;
    }
    // Some Android Bluetooth HID implementations bypass dispatchKeyEvent and
    // deliver directly through these callbacks, especially after the overlay
    // is hidden. Keep both paths on the same controller state machine.
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(handleControllerKey(event))return true;
        return super.onKeyDown(keyCode,event);
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent event){
        if(handleControllerKey(event))return true;
        return super.onKeyUp(keyCode,event);
    }
    private void releasePad(){for(int scan:padKeys)controllerKey(scan,false);for(int scan:padAxes)controllerKey(scan,false);padKeys.clear();padAxes.clear();}
    private float axis(MotionEvent e,int axis){InputDevice device=e.getDevice();InputDevice.MotionRange range=device==null?null:device.getMotionRange(axis,e.getSource());float v=e.getAxisValue(axis);return Math.abs(v)>Math.max(.20f,range==null?0:range.getFlat())?v:0;}
    public boolean dispatchGenericMotionEvent(MotionEvent e){
        int source=e.getSource();
        if((source&(InputDevice.SOURCE_JOYSTICK|InputDevice.SOURCE_GAMEPAD|InputDevice.SOURCE_DPAD))!=0&&e.getActionMasked()==MotionEvent.ACTION_MOVE){
            float x=axis(e,MotionEvent.AXIS_X),y=axis(e,MotionEvent.AXIS_Y);
            if(Math.abs(x)<.01f)x=axis(e,MotionEvent.AXIS_Z);
            if(Math.abs(y)<.01f)y=axis(e,MotionEvent.AXIS_RZ);
            float hx=axis(e,MotionEvent.AXIS_HAT_X),hy=axis(e,MotionEvent.AXIS_HAT_Y);
            if(controls!=null&&(Math.abs(x)>.3f||Math.abs(y)>.3f||Math.abs(hx)>.3f||Math.abs(hy)>.3f))controls.setButtonsVisibleFromController(false);
            Set<Integer> next=new HashSet<>();if(x<-.3f||hx<0)next.add(4);if(x>.3f||hx>0)next.add(7);if(y<-.3f||hy<0)next.add(26);if(y>.3f||hy>0)next.add(22);updatePad(padAxes,next);return true;
        }return super.dispatchGenericMotionEvent(e);
    }
    public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus){releasePad();if(controls!=null)controls.releaseAll();}}
    protected void onPause(){releasePad();if(controls!=null)controls.releaseAll();super.onPause();}
    protected void onDestroy(){if(inputManager!=null)inputManager.unregisterInputDeviceListener(deviceListener);handler.removeCallbacksAndMessages(null);super.onDestroy();}
    public boolean dispatchKeyEvent(android.view.KeyEvent event){
        int key=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(key==KeyEvent.KEYCODE_F5||key==KeyEvent.KEYCODE_F9||key==KeyEvent.KEYCODE_F10){if(down&&event.getRepeatCount()==0){if(key==KeyEvent.KEYCODE_F10)controls.setButtonsVisible(!controls.buttonsVisible());else runQuickState(key==KeyEvent.KEYCODE_F9);}return true;}
        if(handleControllerKey(event))return true;
        if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_BACK){
            if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&controls!=null&&controls.finishEditing())editingBack=true;
            if(editingBack){if(event.getAction()==android.view.KeyEvent.ACTION_UP)editingBack=false;return true;}
        }
        return super.dispatchKeyEvent(event);
    }
}
