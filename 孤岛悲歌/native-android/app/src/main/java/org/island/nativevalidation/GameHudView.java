package org.island.nativevalidation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;

/** HUD rebuilt from the original PLAYER.SBM graphics. */
public final class GameHudView extends View {
    private final IslandRenderer renderer;
    private final Paint paint = new Paint();
    private final Bitmap healthSprite = OriginalUi.bitmap(getContext(), "player-blood");
    private final Bitmap bulletSprite = OriginalUi.bitmap(getContext(), "player-bulleticon");
    private final float[] scale = new float[1];

    public GameHudView(Context context, IslandRenderer renderer) {
        super(context);
        this.renderer = renderer;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("生命值与弹丸数量");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameEngine game = renderer.game;
        if (game == null) {
            // The HUD is attached before StartupFlow creates the first game.
            // Keep polling so the original HP/ammo sprites appear as soon as
            // the opening scene finishes, even if no sibling view invalidates.
            postInvalidateDelayed(120);
            return;
        }
        float screenHeight = getResources().getDisplayMetrics().heightPixels;
        float unit = Math.min(getWidth() / 240f, screenHeight / 320f);
        if (unit <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - 240f * unit) / 2f, 0);
        canvas.scale(unit, unit);
        float left = 7f, top = 3f;
        float spriteScale = .25f;
        float spriteWidth = healthSprite.getWidth() * spriteScale;
        float spriteHeight = healthSprite.getHeight() * spriteScale;
        paint.setAlpha(255);
        paint.setFilterBitmap(false);
        canvas.drawBitmap(healthSprite, null,
            new RectF(left, top, left + spriteWidth, top + spriteHeight), paint);

        float fraction = Math.max(0, Math.min(1, game.health / (float) GameEngine.MAX_HEALTH));
        // PLAYER.BLOOD supplies the HP lettering and black track; the runtime fills its red bar.
        paint.setColor(0xFFFF1712);
        float barLeft = left + 27f;
        float barTop = top + 3.9f;
        canvas.drawRect(barLeft, barTop, barLeft + 99f * fraction, barTop + 1.9f, paint);

        float bulletX = left + 135f;
        canvas.drawBitmap(bulletSprite, null,
            new RectF(bulletX, top - 1f, bulletX + 10f, top + 9f), paint);
        String count = String.format(java.util.Locale.ROOT, "x%02d", Math.max(0, game.ammo));
        OriginalFont.draw(getContext(), canvas, count, bulletX + 11f, top - 1f,
            28f, 12f, 8f, Gravity.LEFT | Gravity.TOP);
        canvas.restore();

        setContentDescription("HP " + game.health + " / " + GameEngine.MAX_HEALTH
            + ", ammo " + game.ammo + (game.poisoned ? ", poisoned" : ""));
        postInvalidateDelayed(120);
    }
}
