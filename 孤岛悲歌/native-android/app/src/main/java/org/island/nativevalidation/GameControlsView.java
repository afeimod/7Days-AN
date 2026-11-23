package org.island.nativevalidation;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Vector touch controls, with independent movement and camera pointers. */
public final class GameControlsView extends FrameLayout {
    public interface Input {void move(float forward,float right);void look(float dx,float dy);}
    interface Selection {void select(ControlLayout.Spec spec);}
    public final Button action;
    private final Input input;private final Stick stick;final ControlLayout layout;
    private final Map<String,PadButton> buttons=new LinkedHashMap<>();
    private boolean editing;private Selection selection;private ControlLayout.Spec selected;
    private float dragX,dragY,startX,startY;
    public GameControlsView(Context context,Input input,Runnable investigate,Runnable sling,Runnable jump,Runnable crouch,Runnable inventory,Runnable menu,Runnable settings,Runnable pick,Runnable map){
        super(context);this.input=input;layout=new ControlLayout(context);setMotionEventSplittingEnabled(true);setWillNotDraw(false);
        stick=new Stick(context);addView(stick,new LayoutParams(-1,-1));
        add("1",jump);add("7",crouch);action=add("5",investigate);add("inventory",inventory);add("menu",menu);add("settings",settings);add("*",sling);add("0",map);add("#",pick);
    }
    private PadButton add(String id,Runnable task){
        PadButton b=new PadButton(getContext(),id);b.setContentDescription(layout.specs.get(id).label);b.setOnClickListener(v->task.run());buttons.put(id,b);addView(b,new LayoutParams(1,1));return b;
    }
    private float unit(){return Math.min(getResources().getDisplayMetrics().density,getHeight()/540f);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width,height);
        stick.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
        float scale=Math.min(getResources().getDisplayMetrics().density,height/540f);
        for(Map.Entry<String,PadButton> e:buttons.entrySet()){
            int size=Math.round(layout.specs.get(e.getKey()).size*scale);
            e.getValue().measure(MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY));
        }
    }
    private RectF bounds(ControlLayout.Spec s){float size=s.size*unit(),r=size/2,x=ControlLayout.clamp(s.x*getWidth(),r+8,getWidth()-r-8),y=ControlLayout.clamp(s.y*getHeight(),r+8,getHeight()-r-8);return new RectF(x-r,y-r,x+r,y+r);}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        stick.layout(0,0,getWidth(),getHeight());for(Map.Entry<String,PadButton> e:buttons.entrySet()){RectF box=bounds(layout.specs.get(e.getKey()));e.getValue().layout(Math.round(box.left),Math.round(box.top),Math.round(box.right),Math.round(box.bottom));e.getValue().setAlpha(layout.specs.get(e.getKey()).alpha);}
    }
    public void reloadLayout(){layout.load();release();requestLayout();invalidate();}
    public void actionHint(String hint){PadButton button=buttons.get("5");String label=hint.contains("确认")||hint.contains("继续")?"确认":hint.contains("射击")?"射击":hint.contains("敲击")||hint.contains("攻击")?"攻击":hint.contains("抓住")?"抓取":hint.contains("放开")?"放开":hint.contains("等待")?"等待":hint.equals("使用")?"使用":"调查";if(!label.equals(button.contextAction)){button.contextAction=label;button.invalidate();}button.setContentDescription("5 / OK · "+hint);}
    void edit(Selection listener){editing=true;selection=listener;release();}
    void appearanceChanged(){release();requestLayout();invalidate();stick.invalidate();}
    @Override public boolean onInterceptTouchEvent(MotionEvent e){return editing||super.onInterceptTouchEvent(e);}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!editing)return super.onTouchEvent(e);
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){selected=null;for(ControlLayout.Spec s:layout.specs.values())if(bounds(s).contains(e.getX(),e.getY()))selected=s;if(selected!=null){startX=selected.x;startY=selected.y;dragX=e.getX();dragY=e.getY();selection.select(selected);invalidate();}return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&selected!=null){selected.x=ControlLayout.clamp(startX+(e.getX()-dragX)/getWidth(),0,1);selected.y=ControlLayout.clamp(startY+(e.getY()-dragY)/getHeight(),0,1);appearanceChanged();}return true;
    }
    @Override protected void dispatchDraw(Canvas c){super.dispatchDraw(c);if(editing&&selected!=null){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);p.setColor(0xFF68DCF3);p.setStrokeWidth(2*unit());RectF box=bounds(selected);box.inset(-5*unit(),-5*unit());c.drawRoundRect(box,16*unit(),16*unit(),p);}}
    public void release(){stick.release();}
    @Override protected void onDetachedFromWindow(){release();super.onDetachedFromWindow();}
    private static final class PadButton extends Button {
        private final String id;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private String contextAction="调查";
        PadButton(Context c,String id){super(c);this.id=id;setText("");setPadding(0,0,0,0);setMinimumWidth(0);setMinimumHeight(0);setBackgroundColor(Color.TRANSPARENT);setElevation(0);setStateListAnimator(null);setAllCaps(false);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),x=w/2,y=h/2,r=Math.min(w,h)*.44f;
            int accent=id.equals("#")?0xFFB5C89B:id.equals("7")?0xFFD5A199:id.equals("1")?0xFFA6B9C6:id.equals("5")?0xFFE2C691:id.equals("*")?0xFFADC7BD:0xFFCFC6B4;
            boolean down=isPressed();p.setStyle(Paint.Style.FILL);p.setShader(null);
            p.setColor(0x85000000);c.drawCircle(x,y+w*.055f,r*1.04f,p);
            p.setShader(new LinearGradient(0,y-r,0,y+r,new int[]{0xFFDAC49B,0xFF77634A,0xFF302B25},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,r,p);
            p.setShader(null);p.setColor(0xFF302B25);c.drawCircle(x,y,r*.88f,p);
            y+=down?w*.035f:-w*.018f;
            p.setShader(new LinearGradient(0,y-r,0,y+r,new int[]{down?0xFF49433B:0xFF968B78,down?0xFF746754:0xFF52483A,0xFF302D27},new float[]{0,.55f,1},Shader.TileMode.CLAMP));c.drawCircle(x,y,r*.82f,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.018f);p.setColor(isFocused()?0xFFFFE1A0:0xFFB8A583);c.drawCircle(x,y,r*.82f,p);
            p.setColor(down?0x554C4133:0xB8DED0B5);c.drawArc(new RectF(x-r*.76f,y-r*.76f,x+r*.76f,y+r*.76f),205,128,false,p);
            p.setColor(accent);p.setStrokeWidth(w*.045f);p.setStrokeCap(Paint.Cap.ROUND);p.setStyle(Paint.Style.FILL);
            if(id.equals("5")){
                actionIcon(c,x,y-w*.09f,w*.16f);
                p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(accent);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.145f);c.drawText(contextAction,x,y+w*.255f,p);
                p.setTextSize(w*.13f);c.drawText("5",x+w*.25f,y-w*.22f,p);
            }else if(id.length()==1){p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*(id.equals("*")?.55f:.39f));Paint.FontMetrics fm=p.getFontMetrics();c.drawText(id,x,y-(fm.ascent+fm.descent)/2,p);}
            else{p.setStyle(Paint.Style.STROKE);float k=r*.44f;
                if(id.equals("menu")){c.drawLine(x-k*.65f,y-k,x-k*.65f,y+k,p);c.drawLine(x+k*.65f,y-k,x+k*.65f,y+k,p);}
                else if(id.equals("settings")){c.drawCircle(x,y,k*.68f,p);c.drawCircle(x,y,k*.22f,p);for(int i=0;i<8;i++){float a=(float)(i*Math.PI/4);c.drawLine(x+(float)Math.cos(a)*k*.78f,y+(float)Math.sin(a)*k*.78f,x+(float)Math.cos(a)*k*1.12f,y+(float)Math.sin(a)*k*1.12f,p);}}
                else{RectF bag=new RectF(x-k*.8f,y-k*.7f,x+k*.8f,y+k);c.drawRoundRect(bag,k*.3f,k*.3f,p);c.drawArc(new RectF(x-k*.5f,y-k*1.1f,x+k*.5f,y),180,180,false,p);c.drawLine(x-k*.46f,y+k*.1f,x+k*.46f,y+k*.1f,p);}
            }
        }
        private void actionIcon(Canvas c,float x,float y,float k){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(k*.17f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(contextAction.equals("攻击")){c.drawLine(x-k*.7f,y+k*.8f,x+k*.55f,y-k*.7f,p);Path head=new Path();head.moveTo(x-k*.65f,y-k*.3f);head.quadTo(x,y-k*1.15f,x+k*.95f,y-k*.05f);c.drawPath(head,p);}
            else if(contextAction.equals("射击")){c.drawCircle(x,y,k*.62f,p);c.drawLine(x-k,y,x-k*.32f,y,p);c.drawLine(x+k*.32f,y,x+k,y,p);c.drawLine(x,y-k,x,y-k*.32f,p);c.drawLine(x,y+k*.32f,x,y+k,p);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,k*.1f,p);}
            else if(contextAction.equals("确认")){Path tick=new Path();tick.moveTo(x-k*.8f,y);tick.lineTo(x-k*.2f,y+k*.55f);tick.lineTo(x+k*.85f,y-k*.7f);c.drawPath(tick,p);}
            else if(contextAction.equals("抓取")||contextAction.equals("放开")){c.drawRect(x-k*.65f,y-k*.65f,x+k*.65f,y+k*.65f,p);c.drawLine(x,y-k*.65f,x,y+k*.65f,p);c.drawLine(x-k*.65f,y,x+k*.65f,y,p);if(contextAction.equals("放开")){c.drawLine(x+k*.45f,y-k*.95f,x+k*.95f,y-k*.45f,p);}}
            else if(contextAction.equals("等待")){c.drawCircle(x,y,k*.75f,p);c.drawLine(x,y,x,y-k*.4f,p);c.drawLine(x,y,x+k*.3f,y,p);}
            else if(contextAction.equals("使用")){c.drawCircle(x,y,k*.6f,p);c.drawLine(x-k*.9f,y,x+k*.9f,y,p);c.drawLine(x,y-k*.9f,x,y+k*.9f,p);}
            else{c.drawCircle(x-k*.15f,y-k*.15f,k*.62f,p);c.drawLine(x+k*.32f,y+k*.32f,x+k*.9f,y+k*.9f,p);}
        }
    }
    private final class Stick extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private int movePointer=-1,lookPointer=-1;private float baseX,baseY,knobX,knobY,lookX,lookY;
        Stick(Context c){super(c);setContentDescription("摇杆移动；拖动空白区域转向");}
        private float radius(){return layout.specs.get("stick").size*unit()/2;}
        private void idle(){RectF b=bounds(layout.specs.get("stick"));baseX=knobX=b.centerX();baseY=knobY=b.centerY();}
        void release(){movePointer=lookPointer=-1;input.move(0,0);idle();invalidate();}
        private void move(float x,float y){float dx=x-baseX,dy=y-baseY,d=(float)Math.hypot(dx,dy),r=radius();if(layout.floating&&d>r){baseX+=dx*(d-r)/d;baseY+=dy*(d-r)/d;dx=x-baseX;dy=y-baseY;d=r;}float limit=d>r?r/d:1;knobX=baseX+dx*limit;knobY=baseY+dy*limit;float scaled=Math.max(0,(Math.min(1,d/r)-.12f)/.88f);input.move(d<.001f?0:-dy/d*scaled,d<.001f?0:dx/d*scaled);invalidate();}
        @Override public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked(),index=e.getActionIndex(),id=e.getPointerId(index);
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){float x=e.getX(index),y=e.getY(index);RectF center=bounds(layout.specs.get("stick"));boolean near=Math.hypot(x-center.centerX(),y-center.centerY())<radius()*1.55;if(movePointer<0&&(near||(layout.floating&&x<getWidth()*.42f&&y>getHeight()*.5f))){movePointer=id;if(layout.floating){baseX=knobX=x;baseY=knobY=y;}input.move(0,0);invalidate();}else if(lookPointer<0){lookPointer=id;lookX=x;lookY=y;}}
            else if(action==MotionEvent.ACTION_MOVE){int m=e.findPointerIndex(movePointer);if(m>=0)move(e.getX(m),e.getY(m));int l=e.findPointerIndex(lookPointer);if(l>=0){float x=e.getX(l),y=e.getY(l);input.look((x-lookX)*.16f,(y-lookY)*.12f);lookX=x;lookY=y;}}
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){if(id==movePointer){movePointer=-1;input.move(0,0);idle();invalidate();}if(id==lookPointer)lookPointer=-1;if(action==MotionEvent.ACTION_UP)release();}else if(action==MotionEvent.ACTION_CANCEL)release();return true;
        }
        @Override protected void onDraw(Canvas c){if(movePointer<0)idle();float r=radius();if(r<=0)return;boolean active=movePointer>=0;c.saveLayerAlpha(null,Math.round(layout.specs.get("stick").alpha*255));float alpha=1;
            p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x88000000);c.drawCircle(baseX,baseY+r*.07f,r,p);
            p.setShader(new LinearGradient(0,baseY-r,0,baseY+r,new int[]{0xFFD2B98D,0xFF72624B,0xFF302B24},null,Shader.TileMode.CLAMP));c.drawCircle(baseX,baseY,r*.96f,p);
            p.setShader(new RadialGradient(baseX,baseY,r*.88f,new int[]{0xFF211F1B,0xFF443D31,0xFF82735A},null,Shader.TileMode.CLAMP));c.drawCircle(baseX,baseY,r*.88f,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(unit()*1.4f);p.setColor(0xFFBBA37C);c.drawCircle(baseX,baseY,r*.95f,p);p.setColor(0x66564936);c.drawCircle(baseX,baseY,r*.67f,p);
            for(int i=0;i<4;i++){float a=(float)(i*Math.PI/2),x=baseX+(float)Math.cos(a)*r*.9f,y=baseY+(float)Math.sin(a)*r*.9f;p.setStrokeWidth(unit()*2);c.drawLine(x,y,baseX+(float)Math.cos(a)*r*.81f,baseY+(float)Math.sin(a)*r*.81f,p);}
            p.setStyle(Paint.Style.FILL);p.setColor(0xAA000000);c.drawCircle(knobX+r*.035f,knobY+r*.075f,r*.39f,p);
            p.setShader(new RadialGradient(knobX-r*.12f,knobY-r*.16f,r*.58f,new int[]{active?0xFFE8D8B5:0xFFD0C2A5,0xFF8C7A5F,0xFF3E372B},new float[]{0,.58f,1},Shader.TileMode.CLAMP));c.drawCircle(knobX,knobY,r*.37f,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setColor(0xFFE3CFAB);p.setStrokeWidth(unit());c.drawCircle(knobX,knobY,r*.37f,p);p.setColor(0x66514432);c.drawCircle(knobX,knobY,r*.23f,p);
            c.restore();
        }
    }
}
