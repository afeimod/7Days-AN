package org.island.nativevalidation;

import android.app.*;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    public GLSurfaceView surface;
    public IslandRenderer renderer;
    private OriginalDialogueView dialogue;
    private Button action;
    private ImageView cross;
    private GameControlsView controls;
    private GameAudio audio;
    private SaveSlots saves;
    private StartupFlow startup;
    private GamepadInput gamepad;
    private MicrophoneInput microphone;
    private CameraDialog activeCamera;
    private Runnable permissionAction;
    private android.hardware.input.InputManager inputManager;
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener=new android.hardware.input.InputManager.InputDeviceListener(){
        public void onInputDeviceAdded(int id){}public void onInputDeviceChanged(int id){if(gamepad!=null)gamepad.release();}public void onInputDeviceRemoved(int id){if(gamepad!=null)gamepad.release();}
    };
    private Dialog movieDialog;
    private ClipPlayback moviePlayback;
    private boolean deathShown,endingShown,rescueShown;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean resumed;
    private int shownPuzzle=-1,shownMovie=-1,shownPicture=-1;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(!resumed)return;
            String text=renderer.dialogue;
            dialogue.text(text);
            controls.actionHint(!text.isEmpty()?"确认 / 继续":renderer.actionHint);
            cross.setVisibility(renderer.aiming?View.VISIBLE:View.GONE);
            if(renderer.puzzleId>=0&&shownPuzzle!=renderer.puzzleId){shownPuzzle=renderer.puzzleId;showPuzzle(shownPuzzle);}
            if(renderer.puzzleId<0)shownPuzzle=-1;
            if(renderer.movieId>=0&&shownMovie!=renderer.movieId){shownMovie=renderer.movieId;showMovie(shownMovie);}
            if(renderer.movieId<0)shownMovie=-1;
            if(renderer.pictureId>=0&&shownPicture!=renderer.pictureId){shownPicture=renderer.pictureId;showPicture(shownPicture);}
            if(renderer.pictureId<0)shownPicture=-1;
            if(renderer.game!=null&&!renderer.game.rescueChoice)rescueShown=false;
            if(renderer.game!=null&&renderer.game.rescueChoice&&!rescueShown){rescueShown=true;show(new AlertDialog.Builder(MainActivity.this).setTitle("救阿大？").setMessage("解毒草只有一株。把它给阿大，还是留给自己？").setPositiveButton("给阿大解毒",(d,w)->surface.queueEvent(()->renderer.game.chooseRescue(true))).setNegativeButton("先留给自己",(d,w)->surface.queueEvent(()->renderer.game.chooseRescue(false))).setCancelable(false).create());}
            if(renderer.game!=null&&!renderer.game.ended)endingShown=false;
            if(renderer.game!=null&&renderer.game.ended&&!endingShown){endingShown=true;show(new AlertDialog.Builder(MainActivity.this).setTitle("故事结束").setMessage("这段冒险已经结束。你可以读取之前保存的进度，探索其他道路，或重新开始。").setPositiveButton("读取存档",(d,w)->loadGame()).setNegativeButton("重新开始",(d,w)->surface.queueEvent(()->{try{renderer.game.start();}catch(Exception e){toast(e.toString());}})).setCancelable(false).create());}
            if(renderer.game!=null&&!renderer.game.loading&&renderer.game.health>0)deathShown=false;
            if(renderer.game!=null&&renderer.game.health<=0&&!deathShown){deathShown=true;show(new AlertDialog.Builder(MainActivity.this).setTitle("生命耗尽").setMessage("可以读取保存的进度，或重新开始。").setPositiveButton("读取存档",(d,w)->loadGame()).setNegativeButton("重新开始",(d,w)->surface.queueEvent(()->{try{renderer.game.start();}catch(Exception e){toast(e.toString());}})).setCancelable(false).create());}
            handler.postDelayed(this,120);
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        saves=new SaveSlots(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(0xFF12171B);getWindow().setNavigationBarColor(0xFF12171B);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xFF12171B);
        audio=new GameAudio(this);renderer=new IslandRenderer(getAssets());renderer.audio=audio;renderer.setSoftLight(getSharedPreferences("visual-settings",0).getFloat("global-soft-light",.28f));surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(2);surface.setPreserveEGLContextOnPause(true);surface.setRenderer(renderer);
        surface.setContentDescription("孤岛悲歌游戏场景，拖动画面转动视角");
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        cross=OriginalUi.picture(this,"player-allow0");cross.setVisibility(View.GONE);cross.setAlpha(.85f);root.addView(cross,new FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER));
        controls=new GameControlsView(this,new GameControlsView.Input(){public void move(float forward,float right){renderer.move(forward,right);}public void look(float dx,float dy){renderer.look(dx,dy);}},
            ()->surface.queueEvent(()->renderer.action()),()->surface.queueEvent(()->renderer.toggleSlingshot()),this::jumpOrConfirm,()->surface.queueEvent(()->renderer.crouch()),this::showInventory,this::menu,this::settings,()->surface.queueEvent(()->renderer.equipPickaxe()),this::showMap);
        root.addView(controls,new FrameLayout.LayoutParams(-1,-1));action=controls.action;
        FrameLayout.LayoutParams hud=new FrameLayout.LayoutParams(-1,dp(95),Gravity.TOP);root.addView(new GameHudView(this,renderer),hud);
        dialogue=new OriginalDialogueView(this,()->surface.queueEvent(()->renderer.action()));
        root.addView(dialogue,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        gamepad=new GamepadInput(renderer,new GamepadInput.Actions(){public void jump(){jumpOrConfirm();}public void crouch(){surface.queueEvent(()->renderer.crouch());}public void attack(){surface.queueEvent(()->renderer.attack());}public void investigate(){surface.queueEvent(()->renderer.action());}public void inventory(){showInventory();}public void sling(){surface.queueEvent(()->renderer.toggleSlingshot());}public void menu(){MainActivity.this.menu();}});
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);inputManager.registerInputDeviceListener(deviceListener,handler);
        renderer.uiDepth++;
        startup=new StartupFlow(this,()->surface.queueEvent(()->{try{renderer.game.start();}catch(Exception e){toast(e.toString());}}),this::loadGame,saves.any(),audio,()->renderer.uiDepth=Math.max(0,renderer.uiDepth-1),this::settings);startup.show();
    }
    private void jumpOrConfirm(){surface.queueEvent(()->{if(renderer.game!=null&&!renderer.game.dialog.isEmpty())renderer.action();else renderer.jump();});}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(renderer!=null&&renderer.uiDepth==0){int code=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
            if(code==KeyEvent.KEYCODE_F5||code==KeyEvent.KEYCODE_F9){if(down&&event.getRepeatCount()==0){if(code==KeyEvent.KEYCODE_F5)writeSlot(SaveSlots.QUICK,true);else readSlot(SaveSlots.QUICK);}return true;}
            if(code==KeyEvent.KEYCODE_2||code==KeyEvent.KEYCODE_8||code==KeyEvent.KEYCODE_4||code==KeyEvent.KEYCODE_6){renderer.key(code==KeyEvent.KEYCODE_2?1:code==KeyEvent.KEYCODE_8?4:code==KeyEvent.KEYCODE_4?0:2,down);return true;}
            if(code==KeyEvent.KEYCODE_1||code==KeyEvent.KEYCODE_3||code==KeyEvent.KEYCODE_7||code==KeyEvent.KEYCODE_9||code==KeyEvent.KEYCODE_5||code==KeyEvent.KEYCODE_STAR||code==KeyEvent.KEYCODE_POUND||code==KeyEvent.KEYCODE_0){
                if(down&&event.getRepeatCount()==0){if(code==KeyEvent.KEYCODE_1||code==KeyEvent.KEYCODE_3)jumpOrConfirm();else if(code==KeyEvent.KEYCODE_7||code==KeyEvent.KEYCODE_9)surface.queueEvent(()->renderer.crouch());else if(code==KeyEvent.KEYCODE_5)surface.queueEvent(()->renderer.action());else if(code==KeyEvent.KEYCODE_STAR)surface.queueEvent(()->renderer.toggleSlingshot());else if(code==KeyEvent.KEYCODE_POUND)surface.queueEvent(()->renderer.equipPickaxe());else showMap();}return true;
            }
            if(gamepad!=null&&gamepad.key(event))return true;
        }return super.dispatchKeyEvent(event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event){if(renderer!=null&&renderer.uiDepth==0&&gamepad!=null&&gamepad.motion(event))return true;return super.onGenericMotionEvent(event);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void uiSound(String name){if(audio!=null)audio.effect(name);}
    private void hideSystemBars(){GameWindow.apply(getWindow());
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    private Button click(LinearLayout row,String title,Runnable task){Button b=OriginalUi.button(this,title,null,task);row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));return b;}
    private void show(AlertDialog dialog){showModal(dialog);OriginalUi.skin(dialog);MenuControls.install(dialog,false);}
    private void showModal(Dialog dialog){controls.release();if(gamepad!=null)gamepad.release();renderer.releaseKeys();renderer.uiDepth++;dialog.setOnDismissListener(d->renderer.uiDepth=Math.max(0,renderer.uiDepth-1));dialog.show();GameWindow.apply(dialog.getWindow());}
    private int immersiveFlags(){return View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;}
    private View notice;
    private void toast(String text){runOnUiThread(()->{
        FrameLayout root=findViewById(android.R.id.content);if(notice!=null)root.removeView(notice);
        OriginalFont.Text message=new OriginalFont.Text(this);message.setText(text==null?"":text);message.setTextSize(11);message.setGravity(Gravity.CENTER);message.setPadding(dp(14),dp(8),dp(14),dp(8));message.setBackground(OriginalUi.background(this,"info"));message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(340)),dp(64),Gravity.TOP|Gravity.CENTER_HORIZONTAL);position.leftMargin=position.rightMargin=dp(16);position.topMargin=dp(110);root.addView(message,position);notice=message;
        handler.postDelayed(()->{root.removeView(message);if(notice==message)notice=null;},2200);
    });}
    private void menu(){
        Dialog dialog=OriginalUi.menu(this,new String[]{"继续游戏","保存游戏","读取游戏","即时存档","即时读档","游戏设置","游戏帮助","退出游戏"},new String[]{"resume","save","loadgame",null,null,"gamesettings","help","quitgame"},new Runnable[]{()->{},this::saveGame,this::loadGame,()->writeSlot(SaveSlots.QUICK,true),()->readSlot(SaveSlots.QUICK),this::settings,this::showHelp,this::finish});
        showModal(dialog);MenuControls.install(dialog,false);
    }
    private void settings(){
        Dialog dialog=OriginalUi.menu(this,new String[]{"屏幕按键布局","声音设置","全局柔光","对话大小","手柄操作","摄像头与麦克风"},new String[]{null,null,null,null,null,null},new Runnable[]{this::editControls,()->{SettingsDialog d=new SettingsDialog(this,audio);showModal(d);d.fitWindow();},this::softLightSettings,this::dialogueSettings,()->show(new AlertDialog.Builder(this).setTitle("手柄操作").setMessage("左摇杆 / 方向键：移动\n右摇杆：转向\nA：跳跃 / 对话确认\nB：下蹲\nX / RT：使用当前武器\nY：调查 / 继续\nLB / Select：物品栏\nRB：弹弓模式\nStart：暂停菜单\n菜单中方向键选择，A 确认，B 返回。").setPositiveButton("返回",null).create()),this::sensors});
        showModal(dialog);MenuControls.install(dialog,false);
    }
    private void softLightSettings(){
        android.content.SharedPreferences prefs=getSharedPreferences("visual-settings",0);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView label=new TextView(this);panel.addView(label);SeekBar slider=new SeekBar(this);slider.setMax(100);int initial=Math.round(renderer.softLight*100);slider.setProgress(initial);panel.addView(slider);
        label.setText("全局柔光  "+initial+"%");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){label.setText("全局柔光  "+value+"%");if(user){renderer.setSoftLight(value/100f);prefs.edit().putFloat("global-soft-light",value/100f).apply();}}
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
        });
        show(new AlertDialog.Builder(this).setTitle("全局柔光").setView(panel).setPositiveButton("完成",null).create());
    }
    private void editControls(){ControlEditorDialog d=new ControlEditorDialog(this,()->controls.reloadLayout());showModal(d);d.getWindow().setLayout(-1,-1);}
    private void dialogueSettings(){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView label=new TextView(this);panel.addView(label);SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(Math.round(getSharedPreferences("touch-controls",0).getFloat("dialogue-scale",1.15f)*100)-80);panel.addView(slider);
        TextView help=new TextView(this);help.setText("对话中也可双指缩放；长对话可上下滑动阅读。");panel.addView(help);
        label.setText("对话大小  "+(slider.getProgress()+80)+"%");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){label.setText("对话大小  "+(n+80)+"%");if(user){getSharedPreferences("touch-controls",0).edit().putFloat("dialogue-scale",(n+80)/100f).apply();dialogue.reloadScale();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        show(new AlertDialog.Builder(this).setTitle("对话大小").setView(panel).setPositiveButton("完成",null).create());
    }
    private void showHelp(){
        HelpDialog d=new HelpDialog(this);showModal(d);MenuControls.install(d,true);
    }
    private void showMap(){
        if(renderer.game==null||renderer.game.busy())return;
        if(!renderer.game.inventory.contains(20080)){toast("需要先找到地图。");return;}
        MapDialog d=new MapDialog(this,renderer.level);showModal(d);MenuControls.install(d,true);
    }
    private void saveGame(){slotDialog(true);}
    private void loadGame(){slotDialog(false);}
    private void slotDialog(boolean saving){
        String[] labels=new String[SaveSlots.COUNT+(saving?0:1)];for(int i=0;i<labels.length;i++)labels[i]=saves.label(saving?i+1:i);
        AlertDialog d=new AlertDialog.Builder(this).setTitle(saving?"选择存档槽位":"选择读取槽位").setItems(labels,(dialog,index)->{int slot=saving?index+1:index;
            if(!saving){readSlot(slot);return;}
            if(saves.read(slot)==null)writeSlot(slot,false);else show(new AlertDialog.Builder(this).setTitle("覆盖这个槽位？").setMessage(saves.label(slot)).setPositiveButton("保存",(v,w)->writeSlot(slot,false)).setNegativeButton("返回",null).create());
        }).setNegativeButton("返回",null).create();show(d);
        ListView list=d.getListView();list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){@Override public View getView(int position,View convert,ViewGroup parent){OriginalFont.Text row=convert instanceof OriginalFont.Text?(OriginalFont.Text)convert:new OriginalFont.Text(MainActivity.this);row.setText(getItem(position));row.setTextSize(13);row.setPadding(dp(18),0,dp(18),0);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinHeight(dp(48));return row;}});list.setFocusableInTouchMode(true);list.requestFocus();list.setSelection(0);
    }
    private void writeSlot(int slot,boolean instant){surface.queueEvent(()->{try{
        if(renderer.game==null||renderer.game.loading)throw new IllegalStateException("请先进入游戏");
        String save=instant?renderer.saveSnapshot():renderer.saveGame();if(!saves.write(slot,save,renderer.level))throw new java.io.IOException("存档写入失败，请重试");
        toast(slot==0?"即时存档已保存":"已保存到存档 "+slot);
    }catch(Exception e){toast(e.getMessage());}});}
    private void readSlot(int slot){String save=saves.read(slot);if(save==null){toast(slot==0?"尚无即时存档":"这个槽位没有存档");return;}
        controls.release();gamepad.release();renderer.releaseKeys();surface.queueEvent(()->{try{renderer.loadGame(save);toast(slot==0?"即时存档已读取":"已读取存档 "+slot);}catch(Exception e){toast(e.getMessage());}});
    }
    private void showInventory(){renderer.releaseKeys();surface.queueEvent(()->{
        if(renderer.game==null||renderer.game.busy()){toast("请先结束当前对话或动作");return;}
        ArrayList<GameEngine.Item> items=new ArrayList<>();for(int id:renderer.game.inventory){GameEngine.Item item=renderer.game.items.get(id);if(item!=null)items.add(item);}int selected=renderer.game.selected;
        runOnUiThread(()->{
            InventoryDialog inventoryDialog=new InventoryDialog(this,items,selected,new InventoryDialog.Actions(){
                public void empty(){surface.queueEvent(()->renderer.game.selected=-1);}
                public void use(int id){surface.queueEvent(()->{GameEngine.Item item=renderer.game.items.get(id);if(item!=null&&item.tab==2)renderer.game.useMedicine(id);else renderer.selectItem(id);});}
                public void combine(int first,int second){surface.queueEvent(()->renderer.game.combine(first,second));}
            });
            controls.release();renderer.releaseKeys();renderer.uiDepth++;inventoryDialog.setOnDismissListener(d->renderer.uiDepth=Math.max(0,renderer.uiDepth-1));inventoryDialog.open();
        });
    });}
    private void item(int id){surface.queueEvent(()->{
        GameEngine.Item item=renderer.game.items.get(id);if(item==null)return;int selected=renderer.game.selected;
        runOnUiThread(()->{
            boolean readable=item.required<0||renderer.game.inventory.contains(item.required);
            AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(item.info.isEmpty()?item.name:item.info).setMessage(item.description+(item.content.isEmpty()?"":readable?"\n\n"+item.content.replace("||","\n\n"):"\n\n需要先准备能播放磁带的录音机。"));
            b.setPositiveButton(item.tab==2?"使用草药":"选中 / 使用",(d,w)->surface.queueEvent(()->{if(item.tab==2)renderer.game.useMedicine(id);else renderer.selectItem(id);}));
            if(selected>=0&&selected!=id)b.setNeutralButton("与手持道具组合",(d,w)->surface.queueEvent(()->renderer.game.combine(selected,id)));
            b.setNegativeButton("返回",null);show(b.create());
        });
    });}
    private void showPuzzle(int id){surface.queueEvent(()->{
        PuzzleState state=renderer.game.puzzleState();if(state==null)return;
        runOnUiThread(()->{
            if(state.type==205||state.type==305||state.type==405){showMorse(state);return;}
            if(state.type==107){showPhoto(state);return;}
            String title=state.type==13?"九宫格石板":state.type==101?"龙壁石珠":state.type==102?"四剑石槽":state.type==103?"转动水泵":state.type==201?"手摇发电机":state.type==104?"为儿童简画上色":"拂去灰尘";
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(8),dp(5),dp(8),dp(5));
            root.setBackground(OriginalUi.background(this,"book"));
            PuzzleView view=new PuzzleView(this,state,renderer.game);root.addView(view,new LinearLayout.LayoutParams(-1,Math.min((int)(getResources().getDisplayMetrics().heightPixels*.68f),getResources().getDisplayMetrics().widthPixels+dp(90))));
            Button mic=state.type==108?OriginalUi.button(this,"麦克风 · 吹去灰尘",null,()->{}):null;
            if(mic!=null)root.addView(mic,new LinearLayout.LayoutParams(-1,dp(44)));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(root).setPositiveButton("确认",(d,w)->surface.queueEvent(()->renderer.game.completePuzzle(state.solved()))).setNegativeButton("稍后再试",(d,w)->surface.queueEvent(()->renderer.game.puzzle=-1)).create();
            dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->surface.queueEvent(()->renderer.game.puzzle=-1));show(dialog);
            if(mic!=null)connectMicrophone(dialog,mic,new MicrophoneInput.Listener(){public void level(float strength){view.blow(strength);}public void pulse(char symbol){}public void error(String message){toast(message);}});
        });
    });}
    private void showPhoto(PuzzleState state){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(8),dp(12),dp(8));
        ImageView photo=new ImageView(this);photo.setImageBitmap(PuzzleView.bitmap(this,"zhaopian"));photo.setScaleType(ImageView.ScaleType.FIT_CENTER);photo.setBackground(OriginalUi.background(this,"gmenuback"));
        root.addView(photo,new LinearLayout.LayoutParams(-1,Math.min((int)(getResources().getDisplayMetrics().heightPixels*.54f),getResources().getDisplayMetrics().widthPixels)));
        SeekBar light=new SeekBar(this);light.setMax(100);root.addView(light);
        light.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){state.progress=value;float gain=1+value*.035f;photo.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{gain,0,0,0,0,0,gain,0,0,0,0,0,gain,0,0,0,0,0,1,0}));}});
        light.setProgress(state.progress);
        Button camera=OriginalUi.button(this,"摄像头",null,()->camera(false,value->light.setProgress(value)));
        root.addView(camera,new LinearLayout.LayoutParams(-1,dp(44)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("透光查看底片").setView(root).setPositiveButton("继续",(d,w)->surface.queueEvent(()->renderer.game.puzzle=-1)).create();
        dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->surface.queueEvent(()->renderer.game.puzzle=-1));show(dialog);
    }
    private void showMorse(PuzzleState state){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView instructions=new TextView(this);instructions.setText(state.type==205?"先练习发送字母 A。点、划组成一个字母，按「字母间隔」结束。":state.type==305?"发送国际求救信号。":"发送岛屿坐标：E、经度、N、纬度。");root.addView(instructions);
        TextView output=new TextView(this);output.setTextSize(23);output.setPadding(0,dp(20),0,dp(20));root.addView(output);
        Runnable refresh=()->output.setText("已编码："+state.decoded+"\n当前："+state.morse);refresh.run();
        LinearLayout row=new LinearLayout(this);root.addView(row);click(row,"· 点",()->{state.signal('.');refresh.run();});click(row,"— 划",()->{state.signal('-');refresh.run();});
        row=new LinearLayout(this);root.addView(row);click(row,"字母间隔",()->{if(!state.letter())toast("这个点划组合不是有效字母");refresh.run();});click(row,"清空重发",()->{state.morse="";state.decoded="";refresh.run();});
        Button mic=OriginalUi.button(this,"麦克风",null,()->{});root.addView(mic,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView table=new TextView(this);table.setText("A ·—   E ·   N —·   O ———   S ···\n1 ·————  2 ··———  4 ····—  8 ———··");table.setPadding(0,dp(12),0,dp(8));root.addView(table);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("无线电发报").setView(root).setPositiveButton("发送",(d,w)->surface.queueEvent(()->renderer.game.completePuzzle(state.solved()))).setNegativeButton("稍后再试",(d,w)->surface.queueEvent(()->renderer.game.puzzle=-1)).create();dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->surface.queueEvent(()->renderer.game.puzzle=-1));show(dialog);
        connectMicrophone(dialog,mic,new MicrophoneInput.Listener(){public void level(float strength){}public void pulse(char symbol){state.signal(symbol);refresh.run();}public void error(String message){toast(message);}});
    }
    private void permission(String name,Runnable task){
        if(checkSelfPermission(name)==android.content.pm.PackageManager.PERMISSION_GRANTED){task.run();return;}
        permissionAction=task;requestPermissions(new String[]{name},41);
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==41){Runnable task=permissionAction;permissionAction=null;if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){if(task!=null)task.run();}else toast("可以在系统应用权限中允许摄像头或麦克风。");}}
    private void camera(boolean selfie,java.util.function.IntConsumer brightness){permission(android.Manifest.permission.CAMERA,()->{CameraDialog d=new CameraDialog(this,selfie,new CameraDialog.Listener(){public void brightness(int value){brightness.accept(value);}public void captured(java.io.File file){toast("照片已保存");}});activeCamera=d;showModal(d);MenuControls.install(d,true);d.setOnDismissListener(v->{activeCamera=null;renderer.uiDepth=Math.max(0,renderer.uiDepth-1);});});}
    private void stopMicrophone(){if(microphone!=null){microphone.stop();microphone=null;}}
    private void connectMicrophone(Dialog dialog,Button button,MicrophoneInput.Listener listener){
        button.setOnClickListener(v->{if(microphone!=null){stopMicrophone();button.setText("麦克风");return;}permission(android.Manifest.permission.RECORD_AUDIO,()->{if(!dialog.isShowing())return;microphone=new MicrophoneInput();microphone.start(listener);button.setText("停止麦克风");});});
        dialog.setOnDismissListener(d->{stopMicrophone();renderer.uiDepth=Math.max(0,renderer.uiDepth-1);});
    }
    private void sensors(){
        Dialog d=OriginalUi.menu(this,new String[]{"自拍照","麦克风"},new String[]{null,null},new Runnable[]{()->camera(true,value->{}),this::microphoneSettings});showModal(d);MenuControls.install(d,false);
    }
    private void microphoneSettings(){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(12),dp(20),dp(12));
        TextView level=new TextView(this);level.setText("对着麦克风吹气，或发出短音、长音。");body.addView(level);
        Button button=OriginalUi.button(this,"麦克风",null,()->{});body.addView(button,new LinearLayout.LayoutParams(-1,dp(48)));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("麦克风").setView(body).setPositiveButton("返回",null).create();show(d);
        connectMicrophone(d,button,new MicrophoneInput.Listener(){public void level(float strength){level.setText("吹气强度  "+Math.round(strength*100)+"%");}public void pulse(char symbol){toast(symbol=='.'?"短音 ·":"长音 —");}public void error(String message){level.setText(message);}});
    }
    private void showMovie(int id){
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        dialog.setContentView(frame);
        dialog.setCanceledOnTouchOutside(false);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ClipPlayback playback;
        try {
            playback = new ClipPlayback(frame, Integer.toString(id), !resumed, dialog::dismiss);
        } catch (java.io.IOException error) {
            android.util.Log.e("IslandVideo", "cannot open " + id, error);
            surface.queueEvent(() -> renderer.game.movie = -1);
            return;
        }
        movieDialog = dialog;
        moviePlayback = playback;
        audio.clipPlaying(true);
        showModal(dialog);
        dialog.getWindow().getDecorView().setSystemUiVisibility(immersiveFlags());
        dialog.getWindow().setLayout(-1, -1);
        dialog.setOnDismissListener(d -> {
            playback.stop();
            moviePlayback = null;
            movieDialog = null;
            audio.clipPlaying(false);
            renderer.uiDepth = Math.max(0, renderer.uiDepth - 1);
            surface.queueEvent(() -> renderer.game.movie = -1);
        });
    }
    private void showPicture(int id){
        String name = id >= 1000 ? "credits" + (id - 1000) : "help" + id;
        try {
            ImageView picture = new ImageView(this);
            picture.setImageBitmap(PuzzleView.bitmap(this, name));
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            FrameLayout page = new FrameLayout(this);
            page.setBackgroundColor(Color.BLACK);
            if (id >= 1000) {
                ScrollView scroll = new ScrollView(this);
                scroll.setFillViewport(true);
                picture.setAdjustViewBounds(true);
                picture.setScaleType(ImageView.ScaleType.FIT_START);
                scroll.addView(picture, new ScrollView.LayoutParams(-1, -2));
                page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            } else {
                page.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            }
            dialog.setContentView(page);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(false);
            page.setOnClickListener(v -> dialog.dismiss());
            showModal(dialog);
            dialog.getWindow().setLayout(-1, -1);
            dialog.setOnDismissListener(d -> {
                renderer.uiDepth = Math.max(0, renderer.uiDepth - 1);
                surface.queueEvent(() -> renderer.game.picture = -1);
            });
        } catch (Exception error) {
            android.util.Log.e("IslandNative", "cannot open picture " + id, error);
            surface.queueEvent(() -> renderer.game.picture = -1);
        }
    }
    @Override public void onBackPressed(){menu();}
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus)hideSystemBars();
        if (!hasFocus && controls != null) {
            if(gamepad!=null)gamepad.release();
            controls.release();
            renderer.releaseKeys();
        }
    }
    @Override protected void onResume(){super.onResume();surface.onResume();if(activeCamera!=null)activeCamera.resumeCamera();if(audio!=null)audio.resume();if(startup!=null)startup.resume();if(moviePlayback!=null)moviePlayback.resume();resumed=true;handler.post(refresh);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(refresh);stopMicrophone();if(activeCamera!=null)activeCamera.pauseCamera();controls.release();if(gamepad!=null)gamepad.release();renderer.releaseKeys();surface.onPause();if(audio!=null)audio.pause();if(startup!=null)startup.pause();if(moviePlayback!=null)moviePlayback.pause();super.onPause();}
    @Override protected void onDestroy() {
        if (movieDialog != null) {
            movieDialog.setOnDismissListener(null);
            moviePlayback.stop();
            movieDialog.dismiss();
        }
        if (startup != null) startup.destroy();
        if (audio != null) audio.release();
        if(inputManager!=null)inputManager.unregisterInputDeviceListener(deviceListener);
        super.onDestroy();
    }
}
