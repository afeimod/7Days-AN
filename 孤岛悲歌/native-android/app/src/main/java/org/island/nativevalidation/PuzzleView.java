package org.island.nativevalidation;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.io.*;
import java.util.*;

/** Original bitmap clues with touch, virtual direction keys and gamepad navigation. */
public final class PuzzleView extends View implements MenuControls.Handler {
    public final PuzzleState state;
    private final GameEngine game;
    private final Paint paint=new Paint(3);
    private final Bitmap art,painting,board,dust;
    private final Set<Integer> cleared=new HashSet<>();
    private final RectF square=new RectF();
    private float previousAngle;
    private boolean turning;
    private int color=1;
    private int cursor;
    private boolean keyboard;
    void blow(float strength){if(state.type!=108||strength<=0)return;int amount=Math.max(1,Math.round(strength*3));for(int i=0;i<100&&amount>0;i++)if(cleared.add(i))amount--;state.progress=cleared.size();invalidate();}
    public PuzzleView(Context context,PuzzleState state,GameEngine game){
        super(context);this.state=state;this.game=game;
        String name=state.type==13?"pintu":state.type==101?"longmen":state.type==104?"basebmp":state.type==108?symbol(state.id):"pintu";
        art=bitmap(context,state.type==108?"textures/0332.png":name);dust=state.type==108?bitmap(context,"textures/0235.png"):null;painting=state.type==104?bitmap(context,"youhua"):null;
        board=OriginalUi.bitmap(context,"book");
        for(int i=0;i<state.progress&&i<100;i++)cleared.add(i);
        setContentDescription("机关操作区，方向键选择、OK操作，向下移到确认按钮");setFocusable(true);setFocusableInTouchMode(true);setMinimumHeight(240);
    }
    private static String symbol(int id){return id==95111?"keheng3":id==95121?"keheng2":id==95131?"keheng1":id==95141?"keheng4":"jiaocontent3";}
    static Bitmap bitmap(Context c,String name){String path=name.contains("/")?name:"puzzles/"+name+".png";try(InputStream in=c.getAssets().open(path)){Bitmap b=BitmapFactory.decodeStream(in);if(b==null)return null;int[] pixels=new int[b.getWidth()*b.getHeight()];b.getPixels(pixels,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());for(int i=0;i<pixels.length;i++)if(Color.red(pixels[i])>240&&Color.blue(pixels[i])>240&&Color.green(pixels[i])<20)pixels[i]=Color.TRANSPARENT;return Bitmap.createBitmap(pixels,b.getWidth(),b.getHeight(),Bitmap.Config.ARGB_8888);}catch(Exception e){return null;}}
    private void text(Canvas c,String s,float x,float y,float size,int color){OriginalFont.draw(getContext(),c,s,x-size*5,y-size*.65f,size*10,size*1.4f,size,Gravity.CENTER);}
    @Override protected void onDraw(Canvas c){
        float w=getWidth(),top=16,extra=(state.type==101||state.type==102||state.type==104)?130:16;
        float side=Math.max(60,Math.min(w-32,getHeight()-top-extra)),left=(w-side)/2;
        square.set(left,top,left+side,top+side);paint.setAlpha(255);paint.setFilterBitmap(false);c.drawBitmap(board,null,square,paint);
        if(state.type==13){
            float cell=side/3;
            for(int i=0;i<9;i++){
                int tile=state.board[i];RectF r=new RectF(left+i%3*cell+2,top+i/3*cell+2,left+(i%3+1)*cell-2,top+(i/3+1)*cell-2);
                if(tile==0){paint.setColor(0xFF11171B);c.drawRect(r,paint);continue;}
                if(art!=null){int a=art.getWidth()/3,b=art.getHeight()/3;c.drawBitmap(art,new Rect(tile%3*a,tile/3*b,(tile%3+1)*a,(tile/3+1)*b),r,paint);}
            }
        }else if(state.type==101||state.type==102){
            if(art!=null){paint.setAlpha(55);c.drawBitmap(art,null,square,paint);paint.setAlpha(255);}
            for(int i=0;i<state.slots.length;i++){
                float[] p=slot(i);paint.setColor(state.slots[i]==0?0xFF45555A:state.slots[i]==21210?0xFF101214:state.slots[i]==21550?0xFFE6E3CF:itemColor(state.slots[i]));c.drawCircle(p[0],p[1],side*.065f,paint);
                text(c,""+(i+1),p[0],p[1]+8,23,state.slots[i]==21550?Color.BLACK:Color.WHITE);
            }
            for(int i=0;i<state.items.length;i++){
                float itemLeft=left+i*side/state.items.length,right=left+(i+1)*side/state.items.length;
                paint.setColor(state.chosen==state.items[i]?0xFFE7CF8A:0xFF55371F);c.drawRect(itemLeft,top+side+52,right-3,top+side+120,paint);
                String s=game.itemName(state.items[i]);if(s.length()>6)s=s.substring(0,6);
                if(game.inventory.contains(state.items[i]))text(c,s,(itemLeft+right)/2,top+side+94,11,Color.WHITE);
            }
        }else if(state.type==103||state.type==201){
            float cx=square.centerX(),cy=square.centerY(),r=side*.32f;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(10);paint.setColor(0xFF687778);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.FILL);
            double angle=Math.toRadians(state.progress%360);paint.setColor(0xFFD3B475);c.drawCircle(cx+(float)Math.cos(angle)*r,cy+(float)Math.sin(angle)*r,22,paint);
        }else if(state.type==108){
            if(art!=null)c.drawBitmap(art,null,square,paint);
            if(dust!=null){
                float cell=side/10;paint.setColor(Color.WHITE);paint.setFilterBitmap(true);
                for(int i=0;i<100;i++)if(!cleared.contains(i)){
                    int save=c.save();c.clipRect(left+i%10*cell,top+i/10*cell,left+(i%10+1)*cell,top+(i/10+1)*cell);
                    c.drawBitmap(dust,null,square,paint);c.restoreToCount(save);
                }
                paint.setFilterBitmap(false);
            }
        }else if(state.type==104){
            if(art!=null){paint.setColorFilter(new ColorMatrixColorFilter(new float[]{.3f,.59f,.11f,0,0,.3f,.59f,.11f,0,0,.3f,.59f,.11f,0,0,0,0,0,1,0}));c.drawBitmap(art,null,square,paint);paint.setColorFilter(null);}
            for(int i=0;i<4;i++)if(state.slots[i]!=0){RectF r=paintRegion(i);int save=c.save();c.clipRect(r);if(art!=null){if(state.slots[i]!=i+1)paint.setColorFilter(new PorterDuffColorFilter(palette(state.slots[i]),PorterDuff.Mode.SRC_ATOP));c.drawBitmap(art,null,square,paint);paint.setColorFilter(null);}c.restoreToCount(save);}
            for(int i=0;i<4;i++){paint.setColor(palette(i+1));c.drawCircle(left+(i+.5f)*side/4,square.bottom+65,color==i+1?28:21,paint);}
        }
        if(keyboard&&hasFocus()){
            paint.setColor(0xFFFFE0A0);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);
            if(state.type==13||state.type==108){int n=state.type==13?3:10;float cell=side/n;c.drawRect(left+cursor%n*cell+2,top+cursor/n*cell+2,left+(cursor%n+1)*cell-2,top+(cursor/n+1)*cell-2,paint);}
            else if(state.type==101||state.type==102){if(cursor<state.slots.length){float[] p=slot(cursor);c.drawCircle(p[0],p[1],side*.09f,paint);}else if(state.items.length>0){int i=cursor-state.slots.length;c.drawRect(left+i*side/state.items.length,square.bottom+50,left+(i+1)*side/state.items.length,square.bottom+122,paint);}}
            else if(state.type==104){if(cursor<4)c.drawRect(paintRegion(cursor),paint);else c.drawCircle(left+(cursor-4+.5f)*side/4,square.bottom+65,32,paint);}
            else c.drawRect(square,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    private int itemColor(int id){return id==22220?0xFFBE4A41:id==22680?0xFF548BC0:id==22250?0xFF65A16D:0xFFD7D9D3;}
    private int palette(int id){return id==1?0xFFD84F35:id==2?0xFF6CAB43:id==3?0xFFE0E0D4:0xFFE1B44E;}
    private RectF paintRegion(int i){float l=square.left,t=square.top,s=square.width();if(i==0)return new RectF(l+s*.5f,t+s*.03f,l+s*.94f,t+s*.41f);if(i==1)return new RectF(l,t+s*.72f,l+s,t+s);if(i==2)return new RectF(l+s*.05f,t+s*.30f,l+s*.51f,t+s*.7f);return new RectF(l+s*.49f,t+s*.41f,l+s*.94f,t+s*.8f);}
    private float[] slot(int i){
        float side=square.width();GameEngine.Entity e=game.find((state.type==101?101:1000)+i);
        if(e!=null){float cy=state.type==101?2384:-281,cz=state.type==101?89:65;return new float[]{square.centerX()+(state.type==101?-1:1)*(e.y-cy)/64*side*.75f,square.centerY()-(e.z-cz)/64*side*.75f};}
        double a=Math.toRadians(-90-i*90);return new float[]{square.centerX()+(float)Math.cos(a)*side*.33f,square.centerY()+(float)Math.sin(a)*side*.33f};
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        float x=event.getX(),y=event.getY(),side=square.width();int action=event.getActionMasked();
        if(state.type==13&&action==MotionEvent.ACTION_UP&&square.contains(x,y)){state.slide(Math.min(8,(int)((y-square.top)/(side/3))*3+(int)((x-square.left)/(side/3))));}
        if((state.type==101||state.type==102)&&action==MotionEvent.ACTION_UP){
            if(y>square.bottom+45&&y<square.bottom+130){int index=(int)((x-square.left)/side*state.items.length);if(index>=0&&index<state.items.length)state.chosen=state.items[index];}
            else for(int i=0;i<state.slots.length;i++){float[] p=slot(i);if(Math.hypot(x-p[0],y-p[1])<side*.10)state.place(i,game.inventory);}
        }
        if(state.type==103||state.type==201){
            float angle=(float)Math.toDegrees(Math.atan2(y-square.centerY(),x-square.centerX()));
            if(action==MotionEvent.ACTION_DOWN){turning=square.contains(x,y);previousAngle=angle;}
            else if(action==MotionEvent.ACTION_MOVE&&turning){float delta=(angle-previousAngle+540)%360-180;if(delta>0&&delta<80)state.progress+=Math.round(delta);previousAngle=angle;}
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)turning=false;
        }
        if(state.type==108&&square.contains(x,y)&&(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE)){
            int cx=(int)((x-square.left)/side*10),cy=(int)((y-square.top)/side*10);
            for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)if(cx+a>=0&&cx+a<10&&cy+b>=0&&cy+b<10)cleared.add((cy+b)*10+cx+a);
            state.progress=cleared.size();
        }
        if(state.type==104&&action==MotionEvent.ACTION_UP){if(y>square.bottom+30&&y<square.bottom+100)color=Math.max(1,Math.min(4,(int)((x-square.left)/side*4)+1));else for(int i=0;i<4;i++)if(paintRegion(i).contains(x,y)){state.slots[i]=color;break;}}
        keyboard=false;requestFocus();
        invalidate();return true;
    }
    public boolean key(int code){
        if(code!=19&&code!=20&&code!=21&&code!=22&&code!=23&&code!=66&&code!=96)return false;
        keyboard=true;boolean ok=code==23||code==66||code==96;
        if(state.type==13||state.type==108){int n=state.type==13?3:10;
            if(ok){if(state.type==13)state.slide(cursor);else{int x=cursor%n,y=cursor/n;for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)if(x+a>=0&&x+a<n&&y+b>=0&&y+b<n)cleared.add((y+b)*n+x+a);state.progress=cleared.size();}}
            else if(code==19){if(cursor<n)return false;cursor-=n;}else if(code==20){if(cursor>=n*(n-1))return false;cursor+=n;}else if(code==21)cursor=Math.max(cursor/n*n,cursor-1);else cursor=Math.min(cursor/n*n+n-1,cursor+1);
        }else if(state.type==101||state.type==102||state.type==104){
            int slots=state.type==104?4:state.slots.length,items=state.type==104?4:state.items.length;
            if(ok){if(state.type==104){if(cursor<slots)state.slots[cursor]=color;else color=cursor-slots+1;}else if(cursor<slots)state.place(cursor,game.inventory);else if(items>0)state.chosen=state.items[cursor-slots];}
            else if(code==19){if(cursor<slots)return false;cursor=Math.min(slots-1,cursor-slots);}else if(code==20){if(cursor>=slots||items==0)return false;cursor=slots+Math.min(items-1,cursor);}else{int first=cursor<slots?0:slots,last=cursor<slots?slots-1:slots+items-1;cursor=Math.max(first,Math.min(last,cursor+(code==21?-1:1)));}
        }else if(state.type==103||state.type==201){if(code==19||code==20)return false;state.progress=Math.max(0,state.progress+(ok?90:code==22?45:-45));}else return false;
        invalidate();return true;
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){return key(code)||super.onKeyDown(code,event);}
}
