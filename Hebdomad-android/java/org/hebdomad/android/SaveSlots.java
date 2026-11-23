package org.hebdomad.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** Slot metadata and a game-frame thumbnail, independent of the Android overlays. */
final class SaveSlots {
    interface Selection { void select(int slot); }
    static File save(Context c,int slot){return new File(c.getFilesDir(),"slot"+(99+slot)+".json");}
    static File snapshot(Context c,int slot){return new File(c.getFilesDir(),"slot"+(99+slot)+".jpg");}
    private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static void show(final Context context,boolean loading,final Selection selection){
        final Bitmap[] images=new Bitmap[6];
        final String[] details=new String[6];
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault());
        for(int i=0;i<6;i++){
            File file=save(context,i);
            if(!file.isFile()){details[i]="空槽位";continue;}
            details[i]=format.format(new Date(file.lastModified()));
            try {
                byte[] bytes;
                try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] block=new byte[8192];int count;
                    while((count=input.read(block))!=-1){if(output.size()+count>8*1024*1024)throw new java.io.IOException("存档过大");output.write(block,0,count);}
                    bytes=output.toByteArray();
                }
                JSONObject state=new JSONObject(new String(bytes,"UTF-8"));
                details[i]+="\n第 "+state.optInt("day",1)+" 天 · 地点 "+state.optInt("map",0);
            }catch(Exception ignored){}
            File image=snapshot(context,i);if(image.isFile())images[i]=BitmapFactory.decodeFile(image.getAbsolutePath());
        }
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return 6;}
            public Object getItem(int position){return position;}
            public long getItemId(int position){return position;}
            public View getView(int position,View recycled,ViewGroup parent){
                LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(context,16),dp(context,8),dp(context,16),dp(context,8));
                ImageView preview=new ImageView(context);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                preview.setBackgroundColor(0xff171b20);
                preview.setContentDescription(images[position]==null?"尚无快照":"槽位 "+(position+1)+" 的存档画面");
                if(images[position]!=null)preview.setImageBitmap(images[position]);
                row.addView(preview,new LinearLayout.LayoutParams(dp(context,144),dp(context,65)));
                LinearLayout text=new LinearLayout(context);text.setOrientation(LinearLayout.VERTICAL);text.setPadding(dp(context,16),0,0,0);
                TextView title=new TextView(context);title.setText("槽位 "+(position+1));title.setTextSize(18);title.setTextColor(0xfff5f5f5);text.addView(title);
                TextView detail=new TextView(context);detail.setText(details[position]);detail.setTextSize(13);detail.setTextColor(0xffc5cbd2);text.addView(detail);
                row.addView(text,new LinearLayout.LayoutParams(0,-2,1));return row;
            }
        };
        new AlertDialog.Builder(context).setTitle(loading?"即时读档 · 选择槽位":"即时存档 · 选择槽位")
            .setAdapter(adapter,new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int slot){selection.select(slot);}}).setNegativeButton("取消",null)
            .show();
    }
    // Called on the render thread only after the corresponding save succeeds.
    static boolean writeSnapshot(String path,int[] pixels,int width,int height){
        File destination=new File(path),temporary=new File(path+".tmp");
        Bitmap bitmap=null;
        try {
            bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
            try(FileOutputStream output=new FileOutputStream(temporary)){
                if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,output))return false;
                output.flush();output.getFD().sync();
            }
            return temporary.renameTo(destination);
        }catch(Exception|OutOfMemoryError error){return false;}
        finally {if(bitmap!=null)bitmap.recycle();if(temporary.exists())temporary.delete();}
    }
}
