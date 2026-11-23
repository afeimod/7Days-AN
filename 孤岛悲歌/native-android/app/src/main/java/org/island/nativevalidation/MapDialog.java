package org.island.nativevalidation;
import android.app.*;
import android.graphics.*;
import android.view.*;

/** Original keyed floor plan laid over the supplied map parchment. */
final class MapDialog extends Dialog {
    MapDialog(Activity activity,int level){super(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen);setContentView(new MapView(activity,level));setOnKeyListener((d,code,e)->{if(code==KeyEvent.KEYCODE_0){if(e.getAction()==KeyEvent.ACTION_UP)dismiss();return true;}return false;});}
    private static final class MapView extends View {
        private final Bitmap parchment,plan;private final Paint paint=new Paint();private float panX,panY;private float downX,downY;
        MapView(Activity activity,int level){super(activity);parchment=OriginalUi.bitmap(activity,"mapback");plan=OriginalUi.bitmap(activity,"map-"+level);setFocusable(true);setContentDescription("地图；方向键或拖动查看，返回关闭");}
        @Override protected void onDraw(Canvas c){float h=getHeight()*.82f,w=h*parchment.getWidth()/parchment.getHeight(),x=(getWidth()-w)/2,y=(getHeight()-h)/2;
            paint.setFilterBitmap(false);c.drawBitmap(parchment,null,new RectF(x,y,x+w,y+h),paint);
            c.save();c.clipRect(x+w*.06f,y+h*.07f,x+w*.94f,y+h*.93f);float scale=Math.min(w*.8f/plan.getWidth(),h*.8f/plan.getHeight());float pw=plan.getWidth()*scale,ph=plan.getHeight()*scale;
            c.drawBitmap(plan,null,new RectF((getWidth()-pw)/2+panX,(getHeight()-ph)/2+panY,(getWidth()+pw)/2+panX,(getHeight()+ph)/2+panY),paint);c.restore();
        }
        @Override public boolean onKeyDown(int code,KeyEvent e){if(code>=19&&code<=22){panX+=(code==21?25:code==22?-25:0);panY+=(code==19?25:code==20?-25:0);invalidate();return true;}return super.onKeyDown(code,e);}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();}else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){panX+=e.getX()-downX;panY+=e.getY()-downY;downX=e.getX();downY=e.getY();invalidate();}return true;}
    }
}
