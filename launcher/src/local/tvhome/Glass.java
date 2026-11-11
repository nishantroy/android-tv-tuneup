package local.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

/**
 * A pane of frosted glass, as a view's background: the slice of the {@link Backdrop}'s blurred
 * picture that lies behind the view, a wash of light across it, and a bright rim along the top.
 * With no wallpaper it falls back to the plain translucent fill the launcher used before.
 *
 * The slice is found from where the view is laid out, not where it is drawn, so a view that
 * slides or grows for a moment carries its glass with it instead of having it swim.
 *
 * The pane is painted once into a bitmap and that is what gets drawn each frame. Painting it
 * live means a bitmap shader and a gradient shader over the whole pane on every frame, which
 * on this TV's GPU took the carousel from 12 ms a frame to 31.
 */
final class Glass extends Drawable {
    private final View host;
    private final Backdrop backdrop;
    private final float radius;
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint washPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF box = new RectF();
    private final Paint panePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect paneBox = new Rect();
    private BitmapShader shader;
    private Bitmap shaderOf, pane, paneOf;
    private int paneX, paneY;

    Glass(View host, Backdrop backdrop, float radiusDp) {
        this.host = host;
        this.backdrop = backdrop;
        radius = Ui.dp(radiusDp);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1, Ui.dp(0.5f)));
        plainPaint.setColor(Ui.GLASS);
        backdrop.onChange(host::invalidate);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        washPaint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                0x3DFFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float half = linePaint.getStrokeWidth() / 2f;
        box.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        Bitmap frost = backdrop.frost();
        if (frost == null || backdrop.getWidth() == 0) {
            c.drawRoundRect(box, radius, radius, plainPaint);
            linePaint.setColor(Ui.HAIRLINE);
            c.drawRoundRect(box, radius, radius, linePaint);
            return;
        }
        // Where this view sits on the backdrop.
        int x = 0, y = 0;
        View v = host;
        while (v != null) {
            x += v.getLeft();
            y += v.getTop();
            ViewParent p = v.getParent();
            if (!(p instanceof View)) break;
            v = (View) p;
            x -= v.getScrollX();
            y -= v.getScrollY();
        }
        // Big panes are kept at half size: the frost is a blur, so nothing is lost.
        float keep = (long) b.width() * b.height() > 400_000 ? 0.5f : 1f;
        int pw = Math.max(1, Math.round(b.width() * keep)), ph = Math.max(1, Math.round(b.height() * keep));
        if (pane == null || paneOf != frost || paneX != x || paneY != y || pane.getWidth() != pw || pane.getHeight() != ph) {
            if (frost != shaderOf) {
                shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shaderOf = frost;
                frostPaint.setShader(shader);
            }
            if (pane == null || pane.getWidth() != pw || pane.getHeight() != ph) {
                pane = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            } else {
                pane.eraseColor(0);
            }
            paneOf = frost;
            paneX = x;
            paneY = y;
            Canvas pc = new Canvas(pane);
            pc.scale(keep, keep);
            pc.translate(-b.left, -b.top);
            // Stretch the frost over the whole backdrop, then slide it so this view sees its own part.
            matrix.setScale(backdrop.getWidth() / (float) frost.getWidth(), backdrop.getHeight() / (float) frost.getHeight());
            matrix.postTranslate(b.left - x, b.top - y);
            shader.setLocalMatrix(matrix);
            pc.drawRoundRect(box, radius, radius, frostPaint);
            pc.drawRoundRect(box, radius, radius, washPaint);
        }
        paneBox.set(b);
        c.drawBitmap(pane, null, paneBox, panePaint);
        linePaint.setColor(0x47FFFFFF);
        c.drawRoundRect(box, radius, radius, linePaint);
        // The rim: the same outline again, brighter, kept to the top edge.
        c.save();
        c.clipRect(b.left, b.top, b.right, b.top + Math.min(radius, b.height() / 2f));
        linePaint.setColor(0x80FFFFFF);
        c.drawRoundRect(box, radius, radius, linePaint);
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
