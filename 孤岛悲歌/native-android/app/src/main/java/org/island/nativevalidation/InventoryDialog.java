package org.island.nativevalidation;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** The original ITEMLUP / ITEMR / ITEMDOWN inventory screen. */
public final class InventoryDialog extends Dialog {
    public interface Actions {
        void use(int id);
        void combine(int first, int second);
        void empty();
        default void read(int id) {}
    }

    private final Activity activity;
    private final List<GameEngine.Item> items;
    private final int held;
    private final Actions actions;
    private final InventoryView inventoryView;

    public InventoryDialog(Activity activity, ArrayList<GameEngine.Item> items, int held, Actions actions) {
        super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.activity = activity;
        this.items = items;
        this.held = held;
        this.actions = actions;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(false);
        inventoryView = new InventoryView();
        setContentView(inventoryView);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        setOnKeyListener((d,code,event)->{if(code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_BUTTON_B||code==KeyEvent.KEYCODE_BUTTON_SELECT){if(event.getAction()==KeyEvent.ACTION_DOWN){if(inventoryView.showActions){inventoryView.showActions=false;inventoryView.invalidate();}else if(inventoryView.combiningFirst>=0){inventoryView.combiningFirst=-1;inventoryView.invalidate();}else dismiss();}return true;}if(event.getAction()==KeyEvent.ACTION_DOWN)return inventoryView.navigate(code);return code==19||code==20||code==21||code==22||code==23||code==66||code==96;});
    }

    public void open() {
        show();
        getWindow().setLayout(-1, -1);
        MenuControls.install(this,true);GameWindow.apply(getWindow());
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private final class InventoryView extends View {
        private static final int ACTION_USE = 0;
        private static final int ACTION_COMBINE = 1;
        private static final int ACTION_CANCEL = 2;
        private static final float ART_SCALE = .25f;
        private final Paint paint = new Paint();
        private final Bitmap grid = OriginalUi.bitmap(activity, "itemlup");
        private final Bitmap previewFrame = OriginalUi.bitmap(activity, "itemr");
        private final Bitmap descriptionFrame = OriginalUi.bitmap(activity, "itemdown");
        private final Bitmap tabs = OriginalUi.bitmap(activity, "itemtab");
        private final Bitmap selection = OriginalUi.bitmap(activity, "itemcursor");
        private final Bitmap actionFrame = OriginalUi.bitmap(activity, "item3");
        private final Bitmap actionSelection = OriginalUi.bitmap(activity, "itemsel");
        private final Rect source = new Rect();
        private final ArrayList<GameEngine.Item> categoryItems = new ArrayList<>();
        private GameEngine.Item selected;
        private int category = 1;
        private int page;
        private int popupAction = ACTION_USE;
        private boolean showActions;
        private boolean tabsFocused;
        private int combiningFirst=-1,previewId=-1;private MeshPreview preview;private Bitmap previewImage;private long previewTime;

        InventoryView() {
            super(activity);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setFocusable(true);
            setContentDescription("物品栏");
            selectCategory(1);
        }

        private float unit() { return Math.min(getWidth() / 240f, getHeight() / 320f); }
        private float offsetX(float u) { return (getWidth() - 240f * u) / 2f; }
        private float offsetY(float u) { return (getHeight() - 320f * u) / 2f; }
        private void drawAsset(Canvas c, Bitmap image, float x, float y, float w, float h) {
            paint.setAlpha(255);
            paint.setFilterBitmap(false);
            c.drawBitmap(image, null, new RectF(x, y, x + w, y + h), paint);
        }
        private void drawText(Canvas c, String text, float x, float y, float w, float h,
                              float size, int gravity) {
            OriginalFont.draw(activity, c, text, x, y, w, h, size, gravity);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float u = unit();
            if (u <= 0) return;
            canvas.drawColor(0x3D000000);
            canvas.save();
            canvas.translate(offsetX(u), offsetY(u));
            canvas.scale(u, u);
            float art = ART_SCALE;
            float left = 33f;
            float top = 56.5f;
            float gridW = grid.getWidth() * art;
            float gridH = grid.getHeight() * art;
            float rightW = previewFrame.getWidth() * art;
            float descY = top + gridH;
            drawAsset(canvas, grid, left, top, gridW, gridH);
            drawAsset(canvas, previewFrame, left + gridW, top, rightW, gridH);
            drawAsset(canvas, descriptionFrame, left, descY,
                descriptionFrame.getWidth() * art, descriptionFrame.getHeight() * art);
            drawTabs(canvas, left, top, art);
            drawGridItems(canvas, left, top, art);
            drawItemPreview(canvas, left + gridW, top, rightW, gridH);
            drawDescription(canvas, left, descY, art);
            if(combiningFirst>=0)drawText(canvas,"选择第二件物品",left,descY+78,174,17,10,Gravity.CENTER);
            if (showActions && selected != null) drawActionPopup(canvas, left, top, art);
            canvas.restore();
        }

        private void drawTabs(Canvas canvas, float left, float top, float art) {
            int selectedTab = Math.max(0, Math.min(2, category - 1));
            source.set(selectedTab * 96, 0, (selectedTab + 1) * 96, 96);
            canvas.drawBitmap(tabs, source,
                new RectF(left + selectedTab * 24f, top, left + (selectedTab + 1) * 24f, top + 24f), paint);
            if(tabsFocused){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(0xFFFFEBB9);canvas.drawRect(left+selectedTab*24f,top,left+(selectedTab+1)*24f,top+24,paint);paint.setStyle(Paint.Style.FILL);}
        }

        private void drawGridItems(Canvas canvas, float left, float top, float art) {
            int start = page * 9;
            int count = Math.min(9, Math.max(0, categoryItems.size() - start));
            for (int index = 0; index < count; index++) {
                GameEngine.Item item = categoryItems.get(start + index);
                int row = index / 3, column = index % 3;
                float x = left + column * 26f;
                float y = top + 36.75f + row * 26f;
                if (item == selected) drawAsset(canvas, selection, x + 1f, y + 1f, 24f, 24f);
                Bitmap icon;
                try { icon = OriginalUi.bitmap(activity, "icon-" + item.picpos); }
                catch (RuntimeException missing) { continue; }
                drawAsset(canvas, icon, x + 1f, y + 1f, 24f, 24f);
            }
        }

        private void drawItemPreview(Canvas canvas, float x, float y, float width, float height) {
            if (selected == null) return;
            String title = selected.info == null || selected.info.isEmpty() ? selected.name : selected.info;
            drawText(canvas, title, x + 5, y + 6, width - 10, 20, 11, Gravity.CENTER);
            try {
                long now=android.os.SystemClock.uptimeMillis();
                if(previewId!=selected.picpos){previewId=selected.picpos;Bitmap skin=OriginalUi.bitmap(activity,"preview-"+previewId);int[] texels=new int[skin.getWidth()*skin.getHeight()];skin.getPixels(texels,0,skin.getWidth(),0,0,skin.getWidth(),skin.getHeight());
                    java.io.InputStream stream=activity.getAssets().open("ui/preview-"+previewId+".mesh");java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1)data.write(buffer,0,n);stream.close();java.nio.ByteBuffer mesh=java.nio.ByteBuffer.wrap(data.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN);int count=mesh.getInt();float[] values=new float[count*5];mesh.asFloatBuffer().get(values);preview=new MeshPreview(values,texels,skin.getWidth(),skin.getHeight());previewTime=0;previewImage=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
                }
                if(now-previewTime>=80){previewImage.setPixels(preview.render(192,192,now*.00055f),0,192,0,0,192,192);previewTime=now;}
                drawAsset(canvas,previewImage,x+8,y+29,width-16,88);postInvalidateDelayed(80);
            } catch (Exception error) {android.util.Log.e("IslandInventory","preview "+selected.picpos,error);}
        }

        private void drawDescription(Canvas canvas, float x, float y, float art) {
            String text = "";
            if (selected != null && selected.description != null) text = selected.description;
            float size = 11f;
            canvas.save();
            canvas.clipRect(x + 3, y + 3, x + 171, y + 69);
            drawText(canvas, text, x + 4, y + 3, 164, 65, size, Gravity.TOP | Gravity.LEFT);
            canvas.restore();
            drawText(canvas, "返回", x + 137, y + 62, 31, 13, 11, Gravity.CENTER);
        }

        private void drawActionPopup(Canvas canvas, float left, float top, float art) {
            int gridIndex = Math.max(0, categoryItems.indexOf(selected) - page * 9);
            int row = gridIndex / 3;
            int column = gridIndex % 3;
            float x = left + column * 26f + 25f;
            float y = top + 36.75f + row * 26f;
            float w = actionFrame.getWidth() * art;
            float h = actionFrame.getHeight() * art;
            drawAsset(canvas, actionFrame, x, y, w, h);
            drawAsset(canvas, actionSelection, x + 1, y + popupAction * 15f, w - 2, 15f);
            String[] labels = {"使用", "组合", "取消"};
            for (int i = 0; i < labels.length; i++) {
                drawText(canvas, labels[i], x + 4, y + i * 15f + 1, w - 8, 13, 11,
                    Gravity.CENTER);
            }
        }

        private void selectCategory(int value) {
            category = value;
            page = 0;
            categoryItems.clear();
            for (GameEngine.Item item : items) if (item.tab == category) categoryItems.add(item);
            selected = categoryItems.isEmpty() ? null : categoryItems.get(0);
            showActions = false;
            invalidate();
        }

        private void useSelected() {
            if (selected == null) return;
            int id = selected.id;
            dismiss();
            actions.use(id);
        }
        private void chooseForCombination(){
            if(selected==null)return;
            if(combiningFirst<0){combiningFirst=selected.id;showActions=false;invalidate();return;}
            if(selected.id==combiningFirst){showActions=false;invalidate();return;}
            int first=combiningFirst,second=selected.id;dismiss();actions.combine(first,second);
        }
        private boolean navigate(int code){
            if(code==KeyEvent.KEYCODE_STAR||code==KeyEvent.KEYCODE_0||code==KeyEvent.KEYCODE_POUND){selectCategory(code==KeyEvent.KEYCODE_STAR?1:code==KeyEvent.KEYCODE_0?2:3);return true;}
            if(code==KeyEvent.KEYCODE_BUTTON_L1||code==KeyEvent.KEYCODE_BUTTON_R1){selectCategory((category+(code==KeyEvent.KEYCODE_BUTTON_R1?0:1))%3+1);return true;}
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_BUTTON_A||code==KeyEvent.KEYCODE_5){
                if(tabsFocused){tabsFocused=false;invalidate();return true;}
                if(selected==null)return true;
                if(combiningFirst>=0&&!showActions)chooseForCombination();else if(!showActions){showActions=true;popupAction=ACTION_USE;}else if(popupAction==ACTION_USE)useSelected();else if(popupAction==ACTION_CANCEL)showActions=false;else chooseForCombination();invalidate();return true;
            }
            if(code<19||code>22)return false;
            if(showActions){popupAction=(popupAction+(code==19||code==21?2:1))%3;invalidate();return true;}
            if(tabsFocused){if(code==21||code==22)selectCategory((category+(code==22?0:1))%3+1);else tabsFocused=false;invalidate();return true;}
            int index=selected==null?0:categoryItems.indexOf(selected);
            if(code==19&&index<3){tabsFocused=true;invalidate();return true;}
            int next=ControlIndex.move(index,categoryItems.size(),code);if(next>=0){selected=categoryItems.get(next);page=next/9;}invalidate();return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float u = unit();
            if (u <= 0) return true;
            float x = (event.getX() - offsetX(u)) / u;
            float y = (event.getY() - offsetY(u)) / u;
            float left = 33f, top = 56.5f, art = ART_SCALE;
            float gridW = grid.getWidth() * art, gridH = grid.getHeight() * art;
            float rightW = previewFrame.getWidth() * art;
            float rightX = left + gridW;
            float descY = top + gridH;

            if (showActions && selected != null) {
                int index = Math.max(0, categoryItems.indexOf(selected) - page * 9);
                float popupX = left + (index % 3) * 26f + 25f;
                float popupY = top + 36.75f + (index / 3) * 26f;
                float popupW = actionFrame.getWidth() * art;
                float popupH = actionFrame.getHeight() * art;
                if (x >= popupX && x <= popupX + popupW && y >= popupY && y <= popupY + popupH) {
                    int choice = Math.max(0, Math.min(2, (int)((y - popupY) / 15f)));
                    if (choice == ACTION_USE) { useSelected(); return true; }
                    if (choice == ACTION_COMBINE) {
                        chooseForCombination();
                        return true;
                    }
                    showActions = false;
                    popupAction = ACTION_USE;
                    invalidate();
                    return true;
                }
                showActions = false;
            }

            if (y >= top && y < top + 24f && x >= left && x < left + 72f) {
                selectCategory(Math.min(3, (int)((x - left) / 24f) + 1));
                return true;
            }
            float gridTop = top + 36.75f;
            if (x >= left && x < left + gridW && y >= gridTop && y < gridTop + 78f) {
                int column = Math.min(2, (int)((x - left) / 26f));
                int row = Math.min(2, (int)((y - gridTop) / 26f));
                int index = page * 9 + row * 3 + column;
                if (index < categoryItems.size()) {
                    GameEngine.Item next = categoryItems.get(index);
                    if(combiningFirst>=0){selected=next;chooseForCombination();return true;}
                    if (selected == next && showActions) {
                        popupAction = ACTION_USE;
                    } else {
                        selected = next;
                        popupAction = ACTION_USE;
                    }
                    showActions = true;
                    invalidate();
                }
                return true;
            }
            if (x >= rightX && x < rightX + rightW && y >= top && y < top + gridH) {
                if (selected != null && y < top + 38f) actions.read(selected.id);
                return true;
            }
            if (x >= left && x <= left + 174f && y >= descY && y <= descY + 80f) {
                if (x >= left + 136f && y >= descY + 58f) { dismiss(); return true; }
                if (selected != null && selected.content != null && !selected.content.isEmpty()) actions.read(selected.id);
                return true;
            }
            // The arrows built into ITEMLUP page through long inventories.
            if (x >= left && x <= left + gridW && y >= top + 24f && y <= top + 36.75f) {
                page = Math.max(0, page - 1); showActions = false; invalidate(); return true;
            }
            if (x >= left && x <= left + gridW && y >= top + 114f && y <= top + 127f) {
                int pages = Math.max(1, (categoryItems.size() + 8) / 9);
                page = Math.min(pages - 1, page + 1); showActions = false; invalidate(); return true;
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
