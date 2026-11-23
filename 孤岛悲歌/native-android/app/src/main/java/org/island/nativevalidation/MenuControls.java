package org.island.nativevalidation;
import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;

/** Original direction/OK/soft keys, routed through the same dialog key handlers. */
final class MenuControls extends View {
    interface Handler {boolean key(int code);}
    private final Dialog dialog;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] labels={"▲","◀","▼","▶","OK","↶"};
    private final int[] codes={19,21,20,22,23,4};private int pressed=-1;
    private int listCursor;
    private MenuControls(Context c,Dialog d){super(c);dialog=d;setTag("virtual-menu-pad");setContentDescription("虚拟方向键、OK、返回");}
    static void install(Dialog d,boolean fullContent){
        FrameLayout content=d.findViewById(android.R.id.content);if(content==null||content.getChildCount()==0||content.findViewWithTag("virtual-menu-pad")!=null)return;
        View body=content.getChildAt(0);content.removeView(body);
        if(d instanceof android.app.AlertDialog&&body.getBackground()==null)body.setBackground(OriginalUi.background(d.getContext(),"book"));
        FrameLayout root=new FrameLayout(d.getContext());root.setBackgroundColor(0x55000000);content.addView(root,new FrameLayout.LayoutParams(-1,-1));
        int screen=d.getContext().getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(fullContent?-1:Math.min((int)(screen*.62f),OriginalUi.dp(d.getContext(),650)),fullContent?-1:-2,Gravity.CENTER);
        root.addView(body,lp);root.addView(new MenuControls(d.getContext(),d),new FrameLayout.LayoutParams(-1,-1));
        d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(-1,-1);GameWindow.apply(d.getWindow());
        body.post(()->focusFirst(body));
    }
    private static boolean focusFirst(View v){
        if(v instanceof ListView){v.setFocusableInTouchMode(true);((ListView)v).setSelection(0);return v.requestFocus();}
        if(v instanceof ViewGroup){for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(focusFirst(((ViewGroup)v).getChildAt(i)))return true;}
        else if(v.isEnabled()&&v.isFocusable()&&v.getVisibility()==VISIBLE){v.setFocusableInTouchMode(true);return v.requestFocus();}return false;
    }
    private RectF box(int i){float u=Math.min(getResources().getDisplayMetrics().density,getHeight()/540f),s=52*u,x,y;
        if(i<4){float bx=95*u,by=getHeight()-100*u;x=bx+(i==1?-s:i==3?s:0);y=by+(i==0?-s:i==2?s:0);}else{x=getWidth()-(i==4?100:40)*u;y=getHeight()-(i==4?105:38)*u;s=(i==4?68:44)*u;}
        return new RectF(x-s/2,y-s/2,x+s/2,y+s/2);
    }
    private int hit(float x,float y){for(int i=0;i<6;i++)if(box(i).contains(x,y))return i;return -1;}
    @Override public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){pressed=hit(e.getX(),e.getY());invalidate();return pressed>=0;}if(action==MotionEvent.ACTION_UP){int i=pressed;pressed=-1;invalidate();if(i>=0&&hit(e.getX(),e.getY())==i)route(codes[i]);return i>=0;}if(action==MotionEvent.ACTION_CANCEL){pressed=-1;invalidate();}return pressed>=0;}
    private void route(int code){
        Context context=getContext();while(context instanceof android.content.ContextWrapper&&!(context instanceof MainActivity))context=((android.content.ContextWrapper)context).getBaseContext();if(context instanceof MainActivity)((MainActivity)context).uiSound(code==23?"ok.wav":code==19||code==21?"left.wav":"right.wav");
        View focused=dialog.getWindow().getDecorView().findFocus();
        if(focused instanceof Handler&&((Handler)focused).key(code))return;
        if(focused instanceof ListView){ListView list=(ListView)focused;
            if(code>=19&&code<=22){listCursor=Math.max(0,Math.min(list.getCount()-1,listCursor+(code==19||code==21?-1:1)));list.setSelection(listCursor);list.post(()->{for(int i=0;i<list.getChildCount();i++)list.getChildAt(i).setBackgroundColor(list.getFirstVisiblePosition()+i==listCursor?0x996F5430:Color.TRANSPARENT);});return;}
            if(code==23){if(listCursor<list.getCount())list.performItemClick(list.getChildAt(listCursor-list.getFirstVisiblePosition()),listCursor,list.getItemIdAtPosition(listCursor));return;}
        }
        if(code>=19&&code<=22&&focused!=null&&(focused instanceof Button||focused instanceof SeekBar)){
            if(focused instanceof SeekBar&&(code==21||code==22)){long n=SystemClock.uptimeMillis();focused.dispatchKeyEvent(new KeyEvent(n,n,KeyEvent.ACTION_DOWN,code,0));return;}
            int direction=code==19?FOCUS_UP:code==20?FOCUS_DOWN:code==21?FOCUS_LEFT:FOCUS_RIGHT;
            View next=focused.focusSearch(direction);if(next!=null){next.setFocusableInTouchMode(true);next.requestFocus();}return;
        }
        long now=SystemClock.uptimeMillis();dialog.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0));dialog.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,code,0));
    }
    @Override protected void onDraw(Canvas c){for(int i=0;i<6;i++){RectF b=box(i);paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(0x99000000);c.drawOval(new RectF(b.left,b.top+b.height()*.06f,b.right,b.bottom+b.height()*.06f),paint);
        paint.setShader(new LinearGradient(0,b.top,0,b.bottom,new int[]{0xFFE0C99E,0xFF726049,0xFF302B24},null,Shader.TileMode.CLAMP));c.drawOval(b,paint);
        RectF face=new RectF(b);face.inset(b.width()*.08f,b.height()*.08f);if(pressed==i)face.offset(0,b.height()*.025f);
        paint.setShader(new LinearGradient(0,face.top,0,face.bottom,new int[]{pressed==i?0xFF494138:0xFF8E806A,0xFF352F27},null,Shader.TileMode.CLAMP));c.drawOval(face,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(0xD0D4BE98);c.drawOval(face,paint);paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(b.width()*(i==4?.32f:.38f));paint.setTextAlign(Paint.Align.CENTER);paint.setColor(0xFFFFE4B6);Paint.FontMetrics f=paint.getFontMetrics();c.drawText(labels[i],face.centerX(),face.centerY()-(f.ascent+f.descent)/2,paint);}}
}
