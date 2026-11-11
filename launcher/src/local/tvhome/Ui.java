package local.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/** Design tokens and small view helpers shared by the launcher's screens. */
final class Ui {
    private Ui() {}

    // Surfaces
    static final int BG = 0xFF08090C;
    static final int SURFACE = 0xFF15171C;
    static final int GLASS = 0x14FFFFFF, GLASS_STRONG = 0x24FFFFFF;
    static final int HAIRLINE = 0x24FFFFFF;
    static final int SCRIM = 0xB3000000;
    static final int PANEL = 0xF517191E, PANEL_LINE = 0x1AFFFFFF;

    // Content
    static final int TEXT = 0xF5FFFFFF, TEXT_SECONDARY = 0x99FFFFFF, TEXT_TERTIARY = 0x8CFFFFFF;
    static final int ON_LIGHT = 0xFF08090B;
    static final int ACCENT = 0xFF8AB8FF, WARN = 0xFFFFB86B;
    static final int NEUTRAL_GLOW = 0xFF6F86B8;

    // Shape and rhythm (dp)
    static final float EDGE = 56, CARD_W = 156, CARD_H = 88, CARD_RADIUS = 12, RING = 2, RING_GAP = 2;
    /** Unfocused artwork is dimmed by this much so the focused tile is also the brightest one. */
    static final float REST_SHADE = 0.28f;

    // Motion: things decelerate into place; focus uses the standard ease.
    static final Interpolator OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    static final Interpolator STANDARD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final int FAST = 180, BASE = 260, SLOW = 420;

    private static float density = 2f;
    private static Context app;
    private static final SparseArray<Typeface> fonts = new SparseArray<>();

    static void init(Context c) {
        app = c.getApplicationContext();
        density = c.getResources().getDisplayMetrics().density;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    /** Inter at a given weight. Large text uses the display optical size. */
    static Typeface font(int weight, boolean display) {
        int key = weight * 2 + (display ? 1 : 0);
        Typeface t = fonts.get(key);
        if (t == null) {
            try {
                t = new Typeface.Builder(app.getAssets(), "Inter.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + (display ? 32 : 14))
                        .build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create(weight < 400 ? "sans-serif-light" : "sans-serif", Typeface.NORMAL);
            fonts.put(key, t);
        }
        return t;
    }

    static TextView text(Context c, CharSequence s, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(weight, sp >= 24));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static final int DIM_LEVELS = 32;
    private static final Paint[] dimPaints = new Paint[DIM_LEVELS + 1];

    /**
     * Darkens a view that is cached as a hardware layer, by tinting the layer as it is composited.
     * The view's content is never redrawn, so dimming costs nothing per frame. Levels are
     * quantized so the paints can be shared.
     */
    static void dim(View layerView, float shade) {
        int level = Math.max(0, Math.min(DIM_LEVELS, Math.round(shade * DIM_LEVELS)));
        if (dimPaints[level] == null) {
            int v = Math.round(255 * (1f - level / (float) DIM_LEVELS));
            Paint p = new Paint();
            p.setColorFilter(new LightingColorFilter(Color.rgb(v, v, v), 0));
            dimPaints[level] = p;
        }
        layerView.setLayerPaint(dimPaints[level]);
    }

    /** Animates the dimming of one layer-cached view. */
    static final class Dimmer {
        private final View view;
        private float value;
        private ValueAnimator anim;

        Dimmer(View layerView, float initial) {
            view = layerView;
            value = initial;
            dim(view, value);
        }

        void to(float target) {
            if (anim != null) anim.cancel();
            if (target == value) return;
            anim = ValueAnimator.ofFloat(value, target);
            anim.setDuration(FAST);
            anim.addUpdateListener(a -> {
                value = (float) a.getAnimatedValue();
                dim(view, value);
            });
            anim.start();
        }
    }

    /** A small tracked, uppercase label that sits above a title or a row. */
    static TextView eyebrow(Context c, CharSequence s) {
        TextView t = text(c, s, 12.5f, 600, TEXT_SECONDARY);
        t.setAllCaps(true);
        t.setLetterSpacing(0.16f);
        return t;
    }

    /** color at the given strength laid over the card surface. */
    static int tint(int color, float strength) {
        int base = 0xFF1A1C21;
        int r = Math.round(Color.red(base) + (Color.red(color) - Color.red(base)) * strength);
        int g = Math.round(Color.green(base) + (Color.green(color) - Color.green(base)) * strength);
        int b = Math.round(Color.blue(base) + (Color.blue(color) - Color.blue(base)) * strength);
        return Color.rgb(r, g, b);
    }

    static GradientDrawable shape(int fill, float radiusDp, float strokeDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    static void clipRound(View v, float radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
    }

    /**
     * The most characteristic color of an app's artwork, normalized so every app casts a glow of
     * similar strength. Falls back to a neutral blue-grey for monochrome artwork.
     */
    static int signatureColor(Drawable art) {
        return analyze(art)[0];
    }

    /** True when artwork is mostly white or pale. */
    static boolean isLight(Drawable art) {
        return analyze(art)[1] == 1;
    }

    /** Extra dimming for light artwork: a black overlay turns white into mid-grey unless it is deep. */
    static final float LIGHT_EXTRA = 0.14f;

    /** {signature color, 1 if the artwork's mean brightness is high else 0}. */
    private static int[] analyze(Drawable art) {
        int light = 0;
        try {
            int w = 32, h = 18;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Drawable d = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
            d.setBounds(0, 0, w, h);
            d.draw(c);
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();

            final int buckets = 12;
            float[] weight = new float[buckets], sr = new float[buckets], sg = new float[buckets], sb = new float[buckets];
            float[] hsv = new float[3];
            float luma = 0;
            for (int p : px) {
                luma += (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
                        * (Color.alpha(p) / 255f);
            }
            light = luma / px.length > 0.6f ? 1 : 0;
            for (int p : px) {
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue;
                int i = Math.min(buckets - 1, (int) (hsv[0] / 360f * buckets));
                float wgt = hsv[1] * hsv[2];
                weight[i] += wgt;
                sr[i] += Color.red(p) * wgt;
                sg[i] += Color.green(p) * wgt;
                sb[i] += Color.blue(p) * wgt;
            }
            int best = -1;
            for (int i = 0; i < buckets; i++) if (weight[i] > 4f && (best < 0 || weight[i] > weight[best])) best = i;
            if (best < 0) return new int[] {NEUTRAL_GLOW, light};
            Color.RGBToHSV((int) (sr[best] / weight[best]), (int) (sg[best] / weight[best]),
                    (int) (sb[best] / weight[best]), hsv);
            hsv[1] = Math.max(0.55f, Math.min(0.85f, hsv[1]));
            hsv[2] = 1f;
            return new int[] {Color.HSVToColor(hsv), light};
        } catch (RuntimeException e) {
            return new int[] {NEUTRAL_GLOW, light};
        }
    }
}
