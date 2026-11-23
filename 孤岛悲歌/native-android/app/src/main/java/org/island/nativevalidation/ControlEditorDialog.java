package org.island.nativevalidation;
import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Drag the actual control preview, then save the complete layout together. */
final class ControlEditorDialog extends Dialog {
    private final GameControlsView preview;
    private final TextView title,sizeLabel,alphaLabel;
    private final SeekBar size,alpha;
    private ControlLayout.Spec selected;
    private boolean updating;
    ControlEditorDialog(Activity activity,Runnable saved){
        super(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root=new FrameLayout(activity);root.setBackgroundColor(0x3D07111C);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        Runnable none=()->{};preview=new GameControlsView(activity,new GameControlsView.Input(){public void move(float f,float r){}public void look(float x,float y){}},none,none,none,none,none,none,none,none,none);
        root.addView(preview,new FrameLayout.LayoutParams(-1,-1));preview.edit(this::select);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(14),dp(18),dp(14));
        GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xF5233443,0xF5101D29});background.setCornerRadius(dp(18));background.setStroke(dp(1),0x806D91A8);panel.setBackground(background);
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(dp(350),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);position.topMargin=dp(14);root.addView(panel,position);
        title=text(panel,"屏幕按键 · 轻触选择，拖动调整位置",17);
        sizeLabel=text(panel,"按键大小",13);size=new SeekBar(activity);panel.addView(size,new LinearLayout.LayoutParams(-1,dp(32)));
        alphaLabel=text(panel,"透明度",13);alpha=new SeekBar(activity);alpha.setMax(85);panel.addView(alpha,new LinearLayout.LayoutParams(-1,dp(32)));
        CheckBox floating=new CheckBox(activity);floating.setText("跟手摇杆");floating.setTextColor(0xFFEAF4FA);floating.setChecked(preview.layout.floating);panel.addView(floating);floating.setOnCheckedChangeListener((b,v)->{preview.layout.floating=v;preview.appearanceChanged();});
        LinearLayout row=new LinearLayout(activity);panel.addView(row);
        button(row,"恢复默认",()->{preview.layout.defaults();floating.setChecked(preview.layout.floating);preview.appearanceChanged();select(preview.layout.specs.get("stick"));});
        button(row,"取消",this::dismiss);button(row,"保存",()->{preview.layout.save();saved.run();dismiss();});
        size.setOnSeekBarChangeListener(listener(true));alpha.setOnSeekBarChangeListener(listener(false));
        setContentView(root);select(preview.layout.specs.get("stick"));setCanceledOnTouchOutside(false);
    }
    private int dp(float value){return Math.round(value*getContext().getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String value,int size){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(0xFFEAF4FA);parent.addView(t);return t;}
    private void button(LinearLayout row,String text,Runnable task){Button b=new Button(getContext());b.setText(text);b.setAllCaps(false);row.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));b.setOnClickListener(v->task.run());}
    private SeekBar.OnSeekBarChangeListener listener(boolean sizing){return new SeekBar.OnSeekBarChangeListener(){
        public void onProgressChanged(SeekBar bar,int value,boolean user){if(updating||selected==null||!user)return;if(sizing)selected.size=value+(selected.id.equals("stick")?80:28);else selected.alpha=(value+15)/100f;labels();preview.appearanceChanged();}
        public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
    };}
    private void select(ControlLayout.Spec spec){selected=spec;updating=true;size.setMax(spec.id.equals("stick")?140:84);size.setProgress(Math.round(spec.size)-(spec.id.equals("stick")?80:28));alpha.setProgress(Math.round(spec.alpha*100)-15);updating=false;labels();}
    private void labels(){title.setText(selected.label+" · 拖动调整位置");sizeLabel.setText("大小  "+Math.round(selected.size));alphaLabel.setText("不透明度  "+Math.round(selected.alpha*100)+"%");}
}
