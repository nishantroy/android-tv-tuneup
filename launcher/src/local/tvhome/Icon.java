package local.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A small family of line icons drawn on a 24-unit grid with one stroke weight. */
final class Icon extends Drawable {
    static final int PLUS = 0, INPUT = 1, SETTINGS = 2, DOWNLOAD = 3, OPEN = 4, MOVE = 5, REMOVE = 6,
            INFO = 7, OFFLINE = 8, DOWN = 9, ARROWS = 10;

    private final int type;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int directions = LEFT | UP | RIGHT | DOWN_DIR;

    static final int LEFT = 1, UP = 2, RIGHT = 4, DOWN_DIR = 8;

    /** For ARROWS: which of the four chevrons to draw. */
    Icon directions(int mask) {
        directions = mask;
        return this;
    }

    Icon(int type, int color) {
        this.type = type;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (type == ARROWS) {
            drawArrows(c, b);
            return;
        }
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        path.reset();
        switch (type) {
            case PLUS:
                line(12, 5.5f, 12, 18.5f);
                line(5.5f, 12, 18.5f, 12);
                break;
            case INPUT:
                // An open frame with an arrow entering it.
                path.moveTo(10, 4.5f);
                path.lineTo(17.5f, 4.5f);
                path.quadTo(20, 4.5f, 20, 7);
                path.lineTo(20, 17);
                path.quadTo(20, 19.5f, 17.5f, 19.5f);
                path.lineTo(10, 19.5f);
                line(3.5f, 12, 14, 12);
                chevron(10.5f, 8.5f, 14, 12, 10.5f, 15.5f);
                break;
            case SETTINGS:
                slider(6.5f, 15);
                slider(12, 8.5f);
                slider(17.5f, 13.5f);
                break;
            case DOWNLOAD:
                line(12, 4.5f, 12, 15);
                chevron(7.5f, 10.5f, 12, 15, 16.5f, 10.5f);
                line(5.5f, 19.5f, 18.5f, 19.5f);
                break;
            case OPEN:
                path.moveTo(8.5f, 6);
                path.lineTo(18, 12);
                path.lineTo(8.5f, 18);
                path.close();
                break;
            case MOVE:
                line(4, 12, 20, 12);
                line(12, 4, 12, 20);
                chevron(6.5f, 9.5f, 4, 12, 6.5f, 14.5f);
                chevron(17.5f, 9.5f, 20, 12, 17.5f, 14.5f);
                chevron(9.5f, 6.5f, 12, 4, 14.5f, 6.5f);
                chevron(9.5f, 17.5f, 12, 20, 14.5f, 17.5f);
                break;
            case REMOVE:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                line(8, 12, 16, 12);
                break;
            case INFO:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                line(12, 11.2f, 12, 16.3f);
                line(12, 7.8f, 12, 7.9f);
                break;
            case OFFLINE:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                line(6, 6, 18, 18);
                break;
            case DOWN:
                chevron(6.5f, 9.5f, 12, 15, 17.5f, 9.5f);
                break;
            default:
                break;
        }
        c.drawPath(path, paint);
        c.restore();
    }

    /**
     * Four chevrons at the middle of each edge of the bounds, pointing outward. Unlike the other
     * icons this is not drawn on the 24-unit grid: it wraps a tile of any proportions.
     */
    private void drawArrows(Canvas c, Rect b) {
        float u = Ui.dp(1), size = 8 * u, depth = 5 * u, inset = 2 * u;
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float saved = paint.getStrokeWidth();
        paint.setStrokeWidth(2 * u);
        path.reset();
        if ((directions & LEFT) != 0) {
            chevron(b.left + inset + depth, cy - size, b.left + inset, cy, b.left + inset + depth, cy + size);
        }
        if ((directions & RIGHT) != 0) {
            chevron(b.right - inset - depth, cy - size, b.right - inset, cy, b.right - inset - depth, cy + size);
        }
        if ((directions & UP) != 0) {
            chevron(cx - size, b.top + inset + depth, cx, b.top + inset, cx + size, b.top + inset + depth);
        }
        if ((directions & DOWN_DIR) != 0) {
            chevron(cx - size, b.bottom - inset - depth, cx, b.bottom - inset, cx + size, b.bottom - inset - depth);
        }
        c.drawPath(path, paint);
        paint.setStrokeWidth(saved);
    }

    private void line(float x1, float y1, float x2, float y2) {
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
    }

    private void chevron(float x1, float y1, float x2, float y2, float x3, float y3) {
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
    }

    /** One horizontal track with a round knob at knobX. */
    private void slider(float y, float knobX) {
        float r = 2.3f;
        if (knobX - r > 4.5f) line(4, y, knobX - r, y);
        if (knobX + r < 19.5f) line(knobX + r, y, 20, y);
        path.addCircle(knobX, y, r, Path.Direction.CW);
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
