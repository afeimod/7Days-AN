package org.island.nativevalidation;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Supplied UI artwork, with semantic Android controls over the original sprites. */
public final class OriginalUi {
    private static final Map<String,Bitmap> cache=new HashMap<>();
    public static synchronized Bitmap bitmap(Context context,String name){
        Bitmap image=cache.get(name);if(image!=null)return image;
        try(InputStream in=context.getAssets().open("ui/"+name+".png")){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
            image=BitmapFactory.decodeStream(in,null,options);if(image==null)throw new IOException(name);
            cache.put(name,image);return image;
        }catch(IOException error){throw new IllegalStateException("Missing original UI: "+name,error);}
    }
    public static Drawable background(Context c,String name){BitmapDrawable d=new BitmapDrawable(c.getResources(),bitmap(c,name));d.setGravity(Gravity.FILL);d.setFilterBitmap(false);return d;}
    public static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static ImageView picture(Context c,String name){ImageView v=new ImageView(c);v.setImageBitmap(bitmap(c,name));v.setScaleType(ImageView.ScaleType.FIT_CENTER);return v;}
    public static Button button(Context c,String label,String sprite,Runnable task){
        Button b=new Button(c);b.setText(label);b.setContentDescription(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(0xFFFFE6AB);
        b.setPadding(dp(c,8),0,dp(c,8),0);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setElevation(0);b.setStateListAnimator(null);
        if(sprite!=null){
            b.setTextColor(Color.TRANSPARENT);
            StateListDrawable states=new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed},new Sprite(c,sprite+"-0"));
            states.addState(new int[]{android.R.attr.state_focused},new Sprite(c,sprite+"-0"));
            states.addState(new int[]{},new Sprite(c,sprite+"-1"));b.setBackground(states);
        }else{
            StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},background(c,"itemsel"));states.addState(new int[]{android.R.attr.state_focused},background(c,"itemsel"));states.addState(new int[]{},background(c,"gmenunosel"));b.setBackground(states);
            OriginalFont.apply(b);
        }
        b.setOnClickListener(v->{if(c instanceof MainActivity)((MainActivity)c).uiSound("ok.wav");task.run();});return b;
    }
    private static final class Sprite extends Drawable {
        private final Bitmap image;private final Paint paint=new Paint();
        Sprite(Context c,String name){image=bitmap(c,name);}
        @Override public void draw(Canvas c){Rect r=getBounds();float h=Math.min(r.height()*.80f,r.width()*.8f*image.getHeight()/image.getWidth());float w=h*image.getWidth()/image.getHeight();c.drawBitmap(image,null,new RectF(r.exactCenterX()-w/2,r.exactCenterY()-h/2,r.exactCenterX()+w/2,r.exactCenterY()+h/2),paint);}
        @Override public void setAlpha(int a){paint.setAlpha(a);}
        @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    /** Give every remaining modal its supplied paper/frame instead of a system panel. */
    public static void skin(AlertDialog dialog){
        Window w=dialog.getWindow();if(w==null)return;Context c=dialog.getContext();w.setBackgroundDrawable(background(c,"book"));
        decorate(w.getDecorView(),c);
        for(int id:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            Button b=dialog.getButton(id);if(b!=null){b.setBackground(background(c,"itemsel"));OriginalFont.apply(b);}
        }
    }
    private static void decorate(View v,Context c){
        if("virtual-menu-pad".equals(v.getTag()))return;
        if(v instanceof TextView&&!(v instanceof OriginalFont.Text))OriginalFont.apply((TextView)v);
        if(v instanceof Button)v.setBackground(background(c,"gmenunosel"));
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)decorate(((ViewGroup)v).getChildAt(i),c);
    }
    public static Dialog menu(Activity activity,String[] labels,String[] sprites,Runnable[] tasks){
        Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(activity,20),dp(activity,16),dp(activity,20),dp(activity,16));body.setBackground(background(activity,"gmenuback"));
        for(int i=0;i<labels.length;i++){final int n=i;Button b=button(activity,labels[i],sprites[i],()->{dialog.dismiss();tasks[n].run();});body.addView(b,new LinearLayout.LayoutParams(-1,dp(activity,49)));}
        dialog.setContentView(body);dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));return dialog;
    }
}
