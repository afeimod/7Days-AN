package org.island.nativevalidation;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Original-texture audio settings, drawn in the game's 240x320 coordinate space. */
public final class SettingsDialog extends Dialog {
    private final SettingsView view;

    public SettingsDialog(Activity activity, GameAudio audio) {
        super(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        view = new SettingsView(activity, audio, this::dismiss);
        setContentView(view);
        setCanceledOnTouchOutside(false);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = .62f;
        window.setAttributes(attributes);
    }

    public void open() {
        show();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        fitWindow();
    }

    void fitWindow() {
        MenuControls.install(this,true);GameWindow.apply(getWindow());getWindow().setLayout(-1,-1);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class SettingsView extends View {
        private int focusedTrack;
        @Override public boolean onKeyDown(int code,android.view.KeyEvent event){
            if(code==19||code==20){focusedTrack=(focusedTrack+(code==19?2:1))%3;invalidate();return true;}
            if(code==21||code==22){float delta=code==21?-.05f:.05f;if(focusedTrack==0){effect=clamp(effect+delta);audio.volume(0,effect);}else if(focusedTrack==1){music=clamp(music+delta);audio.volume(1,music);}invalidate();return true;}
            if(code==23||code==66||code==96){if(focusedTrack==2)close.run();return true;}return super.onKeyDown(code,event);
        }
        private static final float TRACK_LEFT = 91f;
        private static final float TRACK_WIDTH = 112f;
        private static final float EFFECT_Y = 108f;
        private static final float MUSIC_Y = 154f;
        private final GameAudio audio;
        private final Runnable close;
        private final Paint paint = new Paint();
        private final Bitmap panel, normalButton, selectedButton;
        private final Bitmap track, fill, cursor;
        private float effect, music, scale, offsetX, offsetY;
        private int activeTrack = -1;
        private boolean returnPressed;

        SettingsView(Activity activity, GameAudio audio, Runnable close) {
            super(activity);
            this.audio = audio;
            this.close = close;
            effect = audio.effectLevel();
            music = audio.musicLevel();
            panel = OriginalUi.bitmap(activity, "book");
            normalButton = OriginalUi.bitmap(activity, "gmenunosel");
            selectedButton = OriginalUi.bitmap(activity, "itemsel");
            track = OriginalUi.bitmap(activity, "soundbarback");
            fill = OriginalUi.bitmap(activity, "soundbarfront");
            cursor = OriginalUi.bitmap(activity, "soundmouse");
            setContentDescription("音效和音乐设置；拖动滑块调整音量");
            setFocusable(true);
        }

        private void geometry() {
            scale = Math.min(getWidth() / 240f, getHeight() / 320f);
            offsetX = (getWidth() - 240f * scale) * .5f;
            offsetY = (getHeight() - 320f * scale) * .5f;
        }

        private void asset(Canvas canvas, Bitmap bitmap, float x, float y, float w, float h) {
            paint.setAlpha(255);
            paint.setFilterBitmap(false);
            canvas.drawBitmap(bitmap, null, new RectF(x, y, x + w, y + h), paint);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            geometry();
            if (scale <= 0) return;
            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);
            asset(canvas, panel, 26, 52, 188, 194);
            OriginalFont.draw(getContext(), canvas, "声音设置", 48, 71, 144, 23, 15,
                Gravity.CENTER);
            drawTrack(canvas, "音效", EFFECT_Y, effect);
            drawTrack(canvas, "音乐", MUSIC_Y, music);
            asset(canvas, returnPressed||focusedTrack==2 ? selectedButton : normalButton, 69, 205, 102, 27);
            if(focusedTrack<2){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(0xFFF2D599);canvas.drawRoundRect(new RectF(39,(focusedTrack==0?EFFECT_Y:MUSIC_Y)-14,209,(focusedTrack==0?EFFECT_Y:MUSIC_Y)+14),3,3,paint);paint.setStyle(Paint.Style.FILL);}
            OriginalFont.draw(getContext(), canvas, "返回", 69, 205, 102, 27, 12,
                Gravity.CENTER);
            canvas.restore();
        }

        private void drawTrack(Canvas canvas, String label, float centerY, float value) {
            OriginalFont.draw(getContext(), canvas, label, 43, centerY - 8, 42, 16, 12,
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
            float top = centerY - 4;
            asset(canvas, track, TRACK_LEFT, top, TRACK_WIDTH, 8);
            canvas.save();
            canvas.clipRect(TRACK_LEFT, top, TRACK_LEFT + TRACK_WIDTH * clamp(value), top + 8);
            asset(canvas, fill, TRACK_LEFT, top, TRACK_WIDTH, 8);
            canvas.restore();
            float x = TRACK_LEFT + TRACK_WIDTH * clamp(value);
            asset(canvas, cursor, x - 4, centerY - 8, 8, 16);
        }

        private float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

        private float baseX(float x) { return (x - offsetX) / scale; }
        private float baseY(float y) { return (y - offsetY) / scale; }

        private void adjust(float x) {
            float value = clamp((baseX(x) - TRACK_LEFT) / TRACK_WIDTH);
            if (activeTrack == 0) { effect = value; audio.volume(0, value); }
            else if (activeTrack == 1) { music = value; audio.volume(1, value); }
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            geometry();
            float x = baseX(event.getX()), y = baseY(event.getY());
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (y >= EFFECT_Y - 12 && y <= EFFECT_Y + 12) activeTrack = 0;
                else if (y >= MUSIC_Y - 12 && y <= MUSIC_Y + 12) activeTrack = 1;
                else activeTrack = -1;
                if (activeTrack >= 0) { adjust(event.getX()); return true; }
                returnPressed = x >= 69 && x <= 171 && y >= 199 && y <= 238;
                invalidate();
                return returnPressed;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && activeTrack >= 0) {
                adjust(event.getX());
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (activeTrack >= 0) { adjust(event.getX()); activeTrack = -1; return true; }
                boolean pressed = returnPressed;
                returnPressed = false;
                invalidate();
                if (pressed && x >= 69 && x <= 171 && y >= 199 && y <= 238) close.run();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { activeTrack = -1; returnPressed = false; invalidate(); return true; }
            return true;
        }
    }
}
