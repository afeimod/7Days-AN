package org.island.nativevalidation;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.TextView;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Recovered HAN 12x12 glyphs and original FONT.SBM Latin rectangles. */
public final class OriginalFont {
    private static char[] map;private static byte[] widths;private static Bitmap atlas;
    private static synchronized void load(Context context){
        if(map!=null)return;
        try{byte[] raw=read(context,"font-map.bin");CharBuffer chars=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asCharBuffer();map=new char[65536];chars.get(map);widths=read(context,"font-widths.bin");atlas=OriginalUi.bitmap(context,"font");}
        catch(IOException error){throw new IllegalStateException("Missing original bitmap font",error);}
    }
    private static byte[] read(Context c,String name)throws IOException{try(InputStream in=c.getAssets().open("ui/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private static int glyph(int ch){int index=ch<map.length?map[ch]-1:-1;return index>=0&&(ch=='?'||index!=map['?']-1)?index:-1;}
    private static Paint fallback(float scale){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xFFFFEBB9);p.setTypeface(Typeface.DEFAULT);p.setTextSize(12*scale);return p;}
    private static float advance(int ch,float scale){int g=glyph(ch);return g>=0?((widths[g]&255)+1)*scale:fallback(scale).measureText(new String(Character.toChars(ch)));}
    public static ArrayList<String> lines(Context c,CharSequence value,float width,float size){
        load(c);ArrayList<String> result=new ArrayList<>();StringBuilder line=new StringBuilder();float x=0,scale=size/12f;
        for(int i=0;i<value.length();){int ch=Character.codePointAt(value,i);i+=Character.charCount(ch);if(ch=='\r')continue;if(ch=='\n'){result.add(line.toString());line.setLength(0);x=0;continue;}float a=advance(ch,scale);if(x+a>width&&line.length()>0){result.add(line.toString());line.setLength(0);x=0;}line.appendCodePoint(ch);x+=a;}
        result.add(line.toString());return result;
    }
    public static void draw(Context context,Canvas canvas,CharSequence value,float left,float top,float width,float height,float size,int gravity){
        load(context);float scale=size/12f,lineHeight=14*scale;ArrayList<String> lines=lines(context,value,width,size);int vertical=gravity&Gravity.VERTICAL_GRAVITY_MASK;
        if(vertical==Gravity.CENTER_VERTICAL)top+=Math.max(0,(height-lines.size()*lineHeight)/2);else if(vertical==Gravity.BOTTOM)top+=Math.max(0,height-lines.size()*lineHeight);
        Paint paint=new Paint(),system=fallback(scale);for(String line:lines){float x=left,total=0;for(int j=0;j<line.length();){int ch=line.codePointAt(j);j+=Character.charCount(ch);total+=advance(ch,scale);}int horizontal=gravity&Gravity.HORIZONTAL_GRAVITY_MASK;if(horizontal==Gravity.CENTER_HORIZONTAL)x+=Math.max(0,(width-total)/2);else if(horizontal==Gravity.RIGHT)x+=Math.max(0,width-total);
            for(int i=0;i<line.length();){int ch=line.codePointAt(i);i+=Character.charCount(ch);int g=glyph(ch);if(g>=0){int w=widths[g]&255;canvas.drawBitmap(atlas,new Rect(g%16*12,g/16*13,g%16*12+w,g/16*13+13),new RectF(x,top,x+w*scale,top+13*scale),paint);}else canvas.drawText(new String(Character.toChars(ch)),x,top+12*scale,system);x+=advance(ch,scale);}top+=lineHeight;
        }
    }
    public static void apply(TextView view){view.setTextColor(Color.TRANSPARENT);view.setIncludeFontPadding(false);view.setForeground(new Drawable(){
        public void draw(Canvas c){int left=view.getPaddingLeft(),top=view.getPaddingTop();c.save();c.clipRect(left,top,view.getWidth()-view.getPaddingRight(),view.getHeight()-view.getPaddingBottom());OriginalFont.draw(view.getContext(),c,view.getText(),left,top,view.getWidth()-left-view.getPaddingRight(),view.getHeight()-top-view.getPaddingBottom(),view.getTextSize(),view.getGravity());c.restore();}
        public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    });}
    public static final class Text extends TextView {
        public Text(Context c){super(c);setIncludeFontPadding(false);setGravity(Gravity.TOP|Gravity.LEFT);}
        @Override protected void onMeasure(int wSpec,int hSpec){int w=MeasureSpec.getSize(wSpec);float available=Math.max(1,w-getPaddingLeft()-getPaddingRight());int lines=OriginalFont.lines(getContext(),getText(),available,getTextSize()).size();int h=Math.max(getSuggestedMinimumHeight(),Math.round(lines*getTextSize()*14/12)+getPaddingTop()+getPaddingBottom());setMeasuredDimension(resolveSize(w,wSpec),resolveSize(h,hSpec));}
        @Override protected void onDraw(Canvas c){OriginalFont.draw(getContext(),c,getText(),getPaddingLeft(),getPaddingTop(),getWidth()-getPaddingLeft()-getPaddingRight(),getHeight()-getPaddingTop()-getPaddingBottom(),getTextSize(),getGravity());}
    }
}
