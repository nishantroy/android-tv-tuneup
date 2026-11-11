package local.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A card's surface finish: a one-dp inner edge that is brightest along the top and nearly gone at
 * the bottom, as if lit from above. It stands in for the depth that shadows would give, which
 * this TV's GPU cannot afford. Static, so it is baked into the card's cached texture.
 */
final class Edge extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;

    Edge(float radiusDp) {
        radius = Ui.dp(radiusDp);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(1));
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float half = paint.getStrokeWidth() / 2f;
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        paint.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0x2EFFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        c.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
