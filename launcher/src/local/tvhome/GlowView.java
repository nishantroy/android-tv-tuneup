package local.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The backdrop: a soft pool of light that sits behind whatever is focused and takes its color,
 * as if the focused app were lighting the room.
 *
 * The falloff is rendered once into a small alpha-only bitmap and stretched, so moving or
 * recoloring the light costs one tinted textured quad per frame rather than a full-screen
 * gradient shader, which this TV's GPU cannot afford.
 */
final class GlowView extends View {
    private static final int TEX = 128;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Bitmap falloff = Bitmap.createBitmap(TEX, TEX, Bitmap.Config.ALPHA_8);
    private final RectF dst = new RectF();
    private final ArgbEvaluator argb = new ArgbEvaluator();

    // Current state, and the two ends of the glide in progress.
    private float x, y, strength;
    private int color = Ui.NEUTRAL_GLOW;
    private float fromX, fromY, fromStrength, toX, toY, toStrength;
    private int fromColor, toColor;
    private boolean placed;
    private ValueAnimator anim;

    GlowView(Context c) {
        super(c);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(TEX / 2f, TEX / 2f, TEX / 2f,
                new int[] {0x70000000, 0x42000000, 0x1A000000, 0x07000000, 0x00000000},
                new float[] {0f, 0.28f, 0.58f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(falloff).drawRect(0, 0, TEX, TEX, p);
    }

    /** Glide to a new position and color. strength 0..1 scales the light's intensity. */
    void moveTo(float tx, float ty, int tColor, float tStrength) {
        if (anim != null) anim.cancel();
        if (!placed) {
            placed = true;
            x = tx;
            y = ty;
            color = tColor;
        }
        if (x == tx && y == ty && color == tColor && strength == tStrength) return;
        fromX = x;
        fromY = y;
        fromStrength = strength;
        fromColor = color;
        toX = tx;
        toY = ty;
        toStrength = tStrength;
        toColor = tColor;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Ui.SLOW);
        anim.setInterpolator(Ui.OUT);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            x = fromX + (toX - fromX) * t;
            y = fromY + (toY - fromY) * t;
            strength = fromStrength + (toStrength - fromStrength) * t;
            color = (int) argb.evaluate(t, fromColor, toColor);
            invalidate();
        });
        anim.start();
    }

    /** The content under the light scrolled: carry the light, and any glide in progress, with it. */
    void shiftBy(float dy) {
        y += dy;
        fromY += dy;
        toY += dy;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (strength <= 0.01f) return;
        float ry = Ui.dp(330), rx = ry * 1.25f;
        dst.set(x - rx, y - ry, x + rx, y + ry);
        // An alpha-only bitmap is drawn in the paint's color.
        paint.setColor(color);
        paint.setAlpha(Math.round(255 * strength));
        c.drawBitmap(falloff, null, dst, paint);
    }
}
