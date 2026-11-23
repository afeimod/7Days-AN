package org.hebdomad.android;
import android.graphics.*;
import android.view.*;
import java.util.*;
import org.libsdl.app.SDLActivity;
final class TouchControls extends View {
    private static final float STICK_RADIUS=62f;
    private static final float STICK_TRAVEL=39f;
    private static final float STICK_DEAD_ZONE=12f;
    private static final class Button {
        float x,y;
        final float r;
        final int scan;
        Button(float x,float y,float r,int scan){this.x=x;this.y=y;this.r=r;this.scan=scan;}
    }
    private static final class GameTouch {
        final int device;
        float x,y;
        GameTouch(int device){this.device=device;}
    }
    private final ArrayList<Button> buttons=new ArrayList<>();
    private final Set<Integer> held=new HashSet<>();
    private final Map<Integer,GameTouch> forwarded=new HashMap<>();
    private final Map<Integer,Button> owners=new HashMap<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath=new Path();
    private final Typeface numberFace=Typeface.create("sans-serif-condensed",Typeface.BOLD);
    private float scale=1,width=960,height=540,jx=120,jy=420,dx,dy;
    private float stickX,stickY,downX,downY;
    private long stickDownTime;
    private boolean stickDragged;
    private int joystick=-1;
    private float controlSize=1,opacity=.8f;
    private boolean editing,dragJoystick;
    private boolean buttonsVisible=true;
    private Button settingsButton;
    boolean buttonsVisible(){return buttonsVisible;}
    void setButtonsVisible(boolean visible){
        if(buttonsVisible==visible)return;
        releaseAll();finishEditing();
        setButtonsVisibleInternal(visible);
    }
    // Controller activity must not clear the native controller state while
    // merely hiding the on-screen controls.
    void setButtonsVisibleFromController(boolean visible){
        if(buttonsVisible==visible)return;
        releasePointers();
        finishEditing();
        setButtonsVisibleInternal(visible);
    }
    private void setButtonsVisibleInternal(boolean visible){
        buttonsVisible=visible;
        if(settingsButton!=null){
            if(visible){
                settingsButton.x=prefs.getFloat("x0",(width-154)/width)*width;
                settingsButton.y=prefs.getFloat("y0",54/height)*height;
            }else{
                settingsButton.x=width-48;settingsButton.y=48;
            }
            clampPositions();
        }
        prefs.edit().putBoolean("visible",visible).apply();invalidate();
    }
    private Button dragButton;
    private int dragPointer=-1;
    private float offsetX,offsetY;
    private final android.content.SharedPreferences prefs;
    float controlSize(){return controlSize;}
    float controlOpacity(){return opacity;}
    void setControlSize(float value){releaseAll();controlSize=Math.max(.5f,Math.min(1.6f,value));clampPositions();prefs.edit().putFloat("size",controlSize).apply();savePositions();invalidate();}
    void setControlOpacity(float value){opacity=Math.max(.1f,Math.min(1,value));prefs.edit().putFloat("opacity",opacity).apply();invalidate();}
    void editPositions(){releaseAll();editing=true;invalidate();}
    boolean finishEditing(){if(!editing)return false;editing=false;dragPointer=-1;dragButton=null;dragJoystick=false;savePositions();invalidate();return true;}
    void resetLayout(){finishEditing();prefs.edit().clear().apply();controlSize=1;opacity=.8f;onSizeChanged(getWidth(),getHeight(),0,0);invalidate();}
    private float inside(float value,float radius,float edge){return Math.max(radius,Math.min(edge-radius,value));}
    private void clampPositions(){jx=inside(jx,STICK_RADIUS*controlSize,width);jy=inside(jy,STICK_RADIUS*controlSize,height);for(Button b:buttons){float r=(b.r+8)*controlSize;b.x=inside(b.x,r,width);b.y=inside(b.y,r,height);}}
    private void savePositions(){android.content.SharedPreferences.Editor e=prefs.edit().putFloat("jx",jx/width).putFloat("jy",jy/height);for(Button b:buttons){if(b.scan==0&&!buttonsVisible)continue;e.putFloat("x"+b.scan,b.x/width).putFloat("y"+b.scan,b.y/height);}e.apply();}
    private boolean hits(Button b,float x,float y){return Math.hypot(x-b.x,y-b.y)<(b.r+8)*controlSize;}
    private boolean inStickArea(float x,float y){
        return Math.hypot(x-jx,y-jy)<=STICK_RADIUS*controlSize;
    }
    private void forwardGameTouch(MotionEvent e,int index,int action,GameTouch touch){
        // Map the displayed viewport back to normalized SDL coordinates.
        int[] view=GameActivity.touchViewport();
        if(view==null||view.length<4||view[2]<2||view[3]<2){
            view=new int[]{0,0,Math.max(1,getWidth()),Math.max(1,getHeight())};
        }
        touch.x=Math.max(0,Math.min(1,(e.getX(index)-view[0])/view[2]));
        touch.y=Math.max(0,Math.min(1,(e.getY(index)-view[1])/view[3]));
        float pressure=Math.max(0,Math.min(1,e.getPressure(index)));
        SDLActivity.onNativeTouch(touch.device,e.getPointerId(index),action,touch.x,touch.y,pressure);
    }
    private boolean editTouch(MotionEvent e){
        int a=e.getActionMasked(),index=a==MotionEvent.ACTION_DOWN?0:e.findPointerIndex(dragPointer);
        if(index<0){if(a==MotionEvent.ACTION_CANCEL){dragPointer=-1;dragButton=null;dragJoystick=false;}return true;}
        float x=e.getX(index)/scale,y=e.getY(index)/scale;
        if(a==MotionEvent.ACTION_DOWN){
            if(x>width/2-180&&x<width/2+180&&y<65){finishEditing();return true;}
            dragPointer=e.getPointerId(0);
            dragButton=null;dragJoystick=false;
            for(Button b:buttons)if(hits(b,x,y)){dragButton=b;offsetX=x-b.x;offsetY=y-b.y;break;}
            if(dragButton==null&&inStickArea(x,y)){dragJoystick=true;offsetX=x-jx;offsetY=y-jy;}
        }else if(a==MotionEvent.ACTION_MOVE){
            if(dragButton!=null){float r=(dragButton.r+8)*controlSize;dragButton.x=inside(x-offsetX,r,width);dragButton.y=inside(y-offsetY,r,height);}
            else if(dragJoystick){float r=STICK_RADIUS*controlSize;jx=inside(x-offsetX,r,width);jy=inside(y-offsetY,r,height);}
        }else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL||(a==MotionEvent.ACTION_POINTER_UP&&e.getPointerId(e.getActionIndex())==dragPointer)){dragPointer=-1;dragButton=null;dragJoystick=false;savePositions();}
        invalidate();return true;
    }
    private final GameActivity activity;
    TouchControls(GameActivity c){super(c);activity=c;prefs=c.getSharedPreferences("touch",0);buttonsVisible=prefs.getBoolean("visible",true);controlSize=Math.max(.5f,Math.min(1.6f,prefs.getFloat("size",1)));opacity=Math.max(.1f,Math.min(1,prefs.getFloat("opacity",.8f)));setFocusable(false);setContentDescription("虚拟摇杆和游戏按键");}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        releaseAll();
        if(w<=0||h<=0)return;
        scale=Math.min(w/960f,h/540f);
        width=w/scale;
        height=h/scale;
        jx=prefs.getFloat("jx",.20f)*width;
        jy=prefs.getFloat("jy",.75f)*height;
        buttons.clear();
        buttons.add(new Button(width-104,height-112,43,13));
        buttons.add(new Button(width-221,height-67,36,14));
        buttons.add(new Button(width-62,54,26,16));
        buttons.add(new Button(64,54,26,41));
        settingsButton=new Button(width-154,54,26,0);
        buttons.add(settingsButton);
        for(Button b:buttons){
            b.x=prefs.getFloat("x"+b.scan,b.x/width)*width;
            b.y=prefs.getFloat("y"+b.scan,b.y/height)*height;
        }
        if(!buttonsVisible){settingsButton.x=width-48;settingsButton.y=48;}
        clampPositions();
    }

    private void drawButton(Canvas c,Button b){
        boolean pressed=held.contains(b.scan);
        boolean number=b.scan==13||b.scan==14;
        float r=b.r;
        c.save();
        c.translate(b.x,b.y);
        c.scale(controlSize,controlSize);
        paint.setColor(0x66000000);
        c.drawCircle(0,3,r+2,paint);
        if(pressed){c.translate(0,2);c.scale(.94f,.94f);}
        paint.setShader(new LinearGradient(0,-r,0,r,pressed?0xff594939:0xff30373c,0xff10161a,Shader.TileMode.CLAMP));
        c.drawCircle(0,0,r,paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(number?2f:1.25f);
        paint.setColor(pressed?0xffe8bd85:number?0xff947b5c:0xff95958b);
        c.drawCircle(0,0,r-1,paint);
        if(number){
            paint.setColor(0x665c5243);
            paint.setStrokeWidth(1);
            c.drawCircle(0,0,r-6,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        if(number)drawNumber(c,b.scan==13?"5":"8",r);
        else if(b.scan==16)drawMap(c);
        else if(b.scan==41){
            paint.setColor(0xffefe4cf);
            c.drawRoundRect(-10,-11,-3,11,1.8f,1.8f,paint);
            c.drawRoundRect(3,-11,10,11,1.8f,1.8f,paint);
        }else drawGear(c);
        c.restore();
    }
    private void drawNumber(Canvas c,String label,float radius){
        paint.setTypeface(numberFace);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius*1.22f);
        float y=-(paint.ascent()+paint.descent())/2;
        paint.setColor(0xff0d0c0b);
        c.drawText(label,0,y+2,paint);
        paint.setShader(new LinearGradient(0,-radius*.50f,0,radius*.50f,
                new int[]{0xfffff1d1,0xffe3ca9f,0xffa97f54,0xffe2c497},
                new float[]{0,.43f,.52f,1},Shader.TileMode.CLAMP));
        c.drawText(label,0,y,paint);
        paint.setShader(null);
    }
    private void drawGear(Canvas c){
        paint.setColor(0xffefe4cf);
        for(int i=0;i<8;i++){
            c.save();
            c.rotate(i*45f);
            c.drawRoundRect(-2.5f,-14,2.5f,-8,1,1,paint);
            c.restore();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5);
        c.drawCircle(0,0,8.3f,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    private void drawMap(Canvas c){
        drawFold(c,-15,-7,-5,-12,-5,7,-15,12,0xffd2ae76);
        drawFold(c,-5,-12,5,-8,5,11,-5,7,0xffefdbad);
        drawFold(c,5,-8,15,-13,15,6,5,11,0xffc49b61);
        iconPath.reset();
        iconPath.moveTo(-11,4);
        iconPath.lineTo(-7,0);
        iconPath.lineTo(-2,2);
        iconPath.lineTo(3,-2);
        iconPath.lineTo(10,-4);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(0xff73533a);
        c.drawPath(iconPath,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff90403a);
        c.drawCircle(10,-4,2.2f,paint);
    }
    private void drawFold(Canvas c,float x1,float y1,float x2,float y2,float x3,float y3,float x4,float y4,int color){
        iconPath.reset();
        iconPath.moveTo(x1,y1);
        iconPath.lineTo(x2,y2);
        iconPath.lineTo(x3,y3);
        iconPath.lineTo(x4,y4);
        iconPath.close();
        paint.setColor(color);
        c.drawPath(iconPath,paint);
    }
    private void drawStick(Canvas c){
        c.save();
        c.translate(jx,jy);
        c.scale(controlSize,controlSize);
        paint.setColor(0xb5192024);
        c.drawCircle(0,0,STICK_RADIUS,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0xffa89476);
        c.drawCircle(0,0,STICK_RADIUS-1,paint);
        paint.setColor(0x877e7461);
        c.drawCircle(0,0,STICK_TRAVEL,paint);
        paint.setStyle(Paint.Style.FILL);
        c.translate(dx,dy);
        paint.setColor(0x9b000000);
        c.drawCircle(0,2,23,paint);
        paint.setShader(new LinearGradient(0,-22,0,22,0xff737574,0xff2a3033,Shader.TileMode.CLAMP));
        c.drawCircle(0,0,22,paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(0xffd6bd95);
        c.drawCircle(0,0,21,paint);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
    }
    @Override protected void onDraw(Canvas c){
        c.save();
        c.scale(scale,scale);
        int layer=c.saveLayerAlpha(0,0,width,height,editing?255:(int)(opacity*255));
        if(buttonsVisible){
            if(editing){
                paint.setColor(0x497a6a50);
                c.drawRoundRect(Math.max(0,jx-width*.20f),Math.max(0,jy-height*.25f),
                        Math.min(width,jx+width*.20f),Math.min(height,jy+height*.25f),14,14,paint);
            }
            drawStick(c);
        }
        for(Button b:buttons)if(buttonsVisible||b.scan==0)drawButton(c,b);
        c.restoreToCount(layer);
        if(editing){
            paint.setColor(0xee202427);
            c.drawRoundRect(width/2-180,8,width/2+180,65,12,12,paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(15);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("拖动按键或固定摇杆 · 点击此处完成",width/2,42,paint);
        }
        c.restore();
    }

    private void moveStick(float x,float y,Set<Integer> next){
        float vx=x-jx,vy=y-jy;
        float length=(float)Math.hypot(vx,vy);
        float travel=STICK_TRAVEL*controlSize;
        if(length>travel){
            vx*=travel/length;
            vy*=travel/length;
            length=travel;
        }
        dx=vx/controlSize;
        dy=vy/controlSize;
        if(length>STICK_DEAD_ZONE*controlSize){
            if(Math.abs(vx)>length*.38f)next.add(vx<0?4:7);
            if(Math.abs(vy)>length*.38f)next.add(vy<0?26:22);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(editing)return editTouch(e);
        int action=e.getActionMasked(),index=e.getActionIndex();
        if(action==MotionEvent.ACTION_CANCEL){releaseAll();return true;}
        int id=e.getPointerId(index);
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            float x=e.getX(index)/scale,y=e.getY(index)/scale;
            Button hit=null;
            for(Button b:buttons){
                if((buttonsVisible||b.scan==0)&&hits(b,x,y)){hit=b;break;}
            }
            if(hit!=null){
                if(hit.scan==0){activity.showDisplaySettings();return true;}
                owners.put(id,hit);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }else if(buttonsVisible&&joystick<0&&inStickArea(x,y)){
                joystick=id;
                downX=x;
                downY=y;
                stickDownTime=e.getEventTime();
                stickDragged=false;
                dx=dy=0;
            }else{
                GameTouch gameTouch=new GameTouch(e.getDeviceId());
                forwarded.put(id,gameTouch);
                forwardGameTouch(e,index,MotionEvent.ACTION_DOWN,gameTouch);
            }
        }
        boolean lifting=action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP;
        if(lifting){
            GameTouch gameTouch=forwarded.remove(id);
            if(gameTouch!=null)forwardGameTouch(e,index,MotionEvent.ACTION_UP,gameTouch);
            owners.remove(id);
            if(id==joystick){
                joystick=-1;
                dx=dy=0;
            }
        }
        Set<Integer> next=new HashSet<>();
        for(int i=0;i<e.getPointerCount();i++){
            if(lifting&&i==index)continue;
            int pointer=e.getPointerId(i);
            float x=e.getX(i)/scale,y=e.getY(i)/scale;
            if(pointer==joystick)moveStick(x,y,next);
            Button button=owners.get(pointer);
            if(button!=null&&hits(button,x,y))next.add(button.scan);
            GameTouch gameTouch=forwarded.get(pointer);
            if(gameTouch!=null&&action==MotionEvent.ACTION_MOVE)forwardGameTouch(e,i,action,gameTouch);
        }
        for(int scan:held)if(!next.contains(scan))send(scan,false);
        for(int scan:next)if(!held.contains(scan))send(scan,true);
        held.clear();
        held.addAll(next);
        invalidate();
        return true;
    }
    private void send(int scan,boolean down){
        GameActivity.touchKey(scan,down);
        if(scan==41){
            if(down)SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
            else SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
        }
    }
    private void releasePointers(){
        for(int scan:held)send(scan,false);
        held.clear();
        owners.clear();
        for(Map.Entry<Integer,GameTouch> entry:forwarded.entrySet()){
            GameTouch touch=entry.getValue();
            SDLActivity.onNativeTouch(touch.device,entry.getKey(),MotionEvent.ACTION_UP,touch.x,touch.y,0);
        }
        forwarded.clear();
        joystick=-1;
        dx=dy=0;
    }
    void releaseAll(){
        releasePointers();
        GameActivity.clearTouch();
        invalidate();
    }
}
