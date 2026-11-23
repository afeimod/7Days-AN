package org.island.nativevalidation;
import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
final class HelpDialog extends Dialog {
    private int page=1;private final ImageView image;private final TextView number;
    HelpDialog(Activity activity){super(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setBackgroundColor(Color.BLACK);
        image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER);root.addView(bar);
        Button prev=OriginalUi.button(activity,"上一页",null,()->change(-1));bar.addView(prev,new LinearLayout.LayoutParams(OriginalUi.dp(activity,110),OriginalUi.dp(activity,46)));
        number=new TextView(activity);number.setTextColor(0xFFFFEBB9);number.setGravity(Gravity.CENTER);bar.addView(number,new LinearLayout.LayoutParams(OriginalUi.dp(activity,90),-2));
        Button next=OriginalUi.button(activity,"下一页",null,()->change(1));bar.addView(next,new LinearLayout.LayoutParams(OriginalUi.dp(activity,110),OriginalUi.dp(activity,46)));
        setContentView(root);change(0);
        setOnKeyListener((d,code,event)->{if(code==19||code==21||code==20||code==22){if(event.getAction()==KeyEvent.ACTION_DOWN)change(code==19||code==21?-1:1);return true;}if(code==23||code==66||code==96){if(event.getAction()==KeyEvent.ACTION_UP)change(1);return true;}return false;});
    }
    private void change(int delta){page=1+(page-1+delta+16)%16;image.setImageBitmap(PuzzleView.bitmap(getContext(),"help"+page));number.setText(page+" / 16");}
}
