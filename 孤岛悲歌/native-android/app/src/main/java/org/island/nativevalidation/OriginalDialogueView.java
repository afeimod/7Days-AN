package org.island.nativevalidation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/** Original parchment dialogue panel, positioned in the original 240x320 UI space. */
final class OriginalDialogueView extends View {
    private final Bitmap frame;
    private final Bitmap cursor;
    private final Paint paint = new Paint();
    private String text = "";
    private final Runnable advance;
    private final ScaleGestureDetector scaleDetector;
    private float textScale;
    private boolean scaled;
    private float scroll,touchY;private boolean scrolled;

    OriginalDialogueView(Context context, Runnable advance) {
        super(context);
        this.advance = advance;
        textScale=context.getSharedPreferences("touch-controls",0).getFloat("dialogue-scale",1.15f);
        scaleDetector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){scaled=true;textScale=ControlLayout.clamp(textScale*detector.getScaleFactor(),.8f,1.8f);invalidate();return true;}
            @Override public void onScaleEnd(ScaleGestureDetector detector){getContext().getSharedPreferences("touch-controls",0).edit().putFloat("dialogue-scale",textScale).apply();}
        });
        frame = OriginalUi.bitmap(context, "info");
        cursor = OriginalUi.bitmap(context, "dlgcursor");
        setContentDescription("剧情对话，轻触继续");
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void text(String value) {
        if (value == null) value = "";
        if (!text.equals(value)) { text = value; scroll=0;invalidate(); }
        setVisibility(value.isEmpty() ? GONE : VISIBLE);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (text.isEmpty()) return;
        RectF panel=panelBounds();float padding=dp(18),size=dp(16)*textScale;
        paint.setAlpha(255);
        paint.setFilterBitmap(false);
        canvas.drawBitmap(frame, null, panel, paint);
        canvas.save();canvas.clipRect(panel.left+padding,panel.top+padding,panel.right-padding,panel.bottom-padding);
        OriginalFont.draw(getContext(), canvas, text, panel.left+padding, panel.top+padding-scroll, panel.width()-padding*2, panel.height()-padding*2, size,
            Gravity.LEFT | Gravity.TOP);
        canvas.restore();
        canvas.drawBitmap(cursor,null,new RectF(panel.right-dp(22),panel.bottom-dp(22),panel.right-dp(10),panel.bottom-dp(10)),paint);
    }

    private RectF panelBounds() {
        float width=Math.min(getWidth()*.88f,Math.min(getWidth()*.66f,dp(650))*textScale/1.15f),size=dp(16)*textScale;
        int count=OriginalFont.lines(getContext(),text,Math.max(1,width-dp(36)),size).size();
        float height=Math.min(getHeight()*.52f,Math.max(dp(98),count*size*14/12+dp(36))),left=(getWidth()-width)/2,bottom=getHeight()-dp(38);
        return new RectF(left,bottom-height,left+width,bottom);
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    void reloadScale(){textScale=getContext().getSharedPreferences("touch-controls",0).getFloat("dialogue-scale",1.15f);invalidate();}

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        if (text.isEmpty()) return false;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){scaled=scrolled=false;touchY=event.getY();}
        scaleDetector.onTouchEvent(event);
        if(scaleDetector.isInProgress()||event.getPointerCount()>1)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
            RectF panel=panelBounds();float size=dp(16)*textScale,total=OriginalFont.lines(getContext(),text,panel.width()-dp(36),size).size()*size*14/12;
            float dy=touchY-event.getY();if(Math.abs(dy)>dp(6)||scrolled){scroll=ControlLayout.clamp(scroll+dy,0,Math.max(0,total-panel.height()+dp(36)));touchY=event.getY();scrolled=true;invalidate();return true;}
        }
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
            return panelBounds().contains(event.getX(), event.getY());
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            if (panelBounds().contains(event.getX(), event.getY())) {
                if(!scaled&&!scrolled)performClick();
                return true;
            }
            return false;
        }
        return event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE
            && panelBounds().contains(event.getX(), event.getY());
    }

    @Override public boolean performClick() {
        super.performClick();
        advance.run();
        return true;
    }
}
