package local.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Random;

/**
 * Weather drawn over whatever picture is showing on the ambient screen: rain, drizzle, snow,
 * drifting fog, and on clear nights the odd shooting star. It follows the
 * weather the launcher already fetches, so it works over any picture, the owner's own included.
 *
 * It is only on the ambient screen. Home already takes most of a frame to draw on this TV, and
 * anything moving behind the carousel would make it stutter. Everything here is cheap to draw:
 * rain is one batch of lines per depth, snow one batch of points, and fog two soft textures,
 * made once at a small size and stretched, sliding sideways. It redraws at about 30 frames a
 * second, and not at all when there is nothing to show.
 *
 * To see an effect without waiting for the weather (it is kept until changed):
 *
 *   adb shell am start -n local.tvhome/.HomeActivity --es effect rain     (drizzle, snow, fog, stars)
 *   adb shell am start -n local.tvhome/.HomeActivity --es effect off      (never draw any)
 *   adb shell am start -n local.tvhome/.HomeActivity --es effect auto     (follow the weather, the default)
 */
final class SkyView extends View {
    static final String EXTRA = "effect";
    private static final String PREFS = "sky", KEY = "effect";
    private static final long FRAME_MS = 33;

    private static final int NONE = 0, DRIZZLE = 1, RAIN = 2, SNOW = 3, FOG = 4;
    /** Rain and snow fall in three layers, far to near: slower, shorter and fainter far away. */
    private static final float[] DEPTH_SPEED = {0.62f, 0.8f, 1f}, DEPTH_ALPHA = {0.45f, 0.7f, 1f};

    private final Backdrop backdrop;
    private final Random rnd = new Random();
    private final Paint[] layer = new Paint[3];
    private final Paint fogPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Runnable wake = this::invalidate;

    private Weather.Now weather;
    private int effect = NONE, count;
    private boolean heavy, stars, forcedStars;
    /** Sideways drift as a share of the fall, from the wind; negative is leftward. */
    private float slant;

    // Rain and snow: position, and a phase for snow's sway. Particle i is in layer i % 3.
    private float[] x = new float[0], y = new float[0], phase = new float[0];
    private float[][] batch = new float[3][];
    private long last;

    private Bitmap fogNear, fogFar;
    private float fogShift;

    private long starAt, nextStar;
    private float starX, starY, starDx, starDy, starMs;

    SkyView(Context c, Backdrop backdrop) {
        super(c);
        this.backdrop = backdrop;
        for (int i = 0; i < 3; i++) {
            layer[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            layer[i].setColor(Color.WHITE);
            layer[i].setStrokeCap(Paint.Cap.ROUND);
        }
        starPaint.setColor(Color.WHITE);
        starPaint.setStrokeCap(Paint.Cap.ROUND);
        backdrop.onChange(this::update);
    }

    /** From the intent extra: an effect's name, "off", or "auto" to follow the weather. */
    static void configure(Context c, String value) {
        String v = value == null ? "auto" : value.trim().toLowerCase();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply();
    }

    private static String setting(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "auto");
    }

    void setWeather(Weather.Now now) {
        weather = now;
        update();
    }

    /** Works out what to draw from the setting, the weather, the hour and the picture. */
    void update() {
        String s = setting(getContext());
        int e;
        heavy = false;
        switch (s) {
            case "off": e = NONE; break;
            case "drizzle": e = DRIZZLE; break;
            case "rain": e = RAIN; break;
            case "snow": e = SNOW; break;
            case "fog": e = FOG; break;
            case "stars": e = NONE; break;
            default: e = fromWeather(weather);
        }
        // A picture painted with this weather already has it.
        if ((e == FOG && backdrop.showingTagged("fog")) || (e == SNOW && backdrop.showingTagged("snow"))
                || ((e == RAIN || e == DRIZZLE) && backdrop.showingTagged("rain"))) e = NONE;

        forcedStars = s.equals("stars");
        Calendar now = Calendar.getInstance();
        boolean night = "night".equals(Backdrop.timeOfDay(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), weather));
        stars = forcedStars || (s.equals("auto") && night && e == NONE && (weather == null || weather.code <= 1));

        // Wind leans the rain and pushes the fog. Which way is a guess, since the picture's
        // camera could face anywhere: wind from the west goes right, as on a map.
        float wind = weather != null ? weather.wind : 0;
        float lean = Math.min(wind / 70f, 0.4f) + 0.05f;
        slant = weather != null && Math.sin(Math.toRadians(weather.windFrom)) > 0 ? -lean : lean;

        if (e != effect) {
            effect = e;
            count = 0; // particles are laid out again on the next frame
        }
        if (stars && nextStar == 0) nextStar = SystemClock.uptimeMillis() + (forcedStars ? 3000 : 20_000 + rnd.nextInt(60_000));
        if (!stars) nextStar = 0;
        invalidate();
    }

    private int fromWeather(Weather.Now w) {
        if (w == null) return NONE;
        int c = w.code;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return SNOW;
        // A thunderstorm is shown as heavy rain: flashes of light on a screen left on are more
        // startling than atmospheric.
        if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82) || c >= 95) {
            heavy = c == 65 || c == 67 || c == 82 || c >= 95;
            return RAIN;
        }
        if (c >= 51 && c <= 57) return DRIZZLE;
        return w.foggy() ? FOG : NONE;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(now - last, 100) / 1000f;
        last = now;
        boolean moving = false;

        if (effect == FOG) {
            drawFog(c, w, h, dt);
            moving = true;
        } else if (effect != NONE) {
            if (effect == RAIN) c.drawColor(heavy ? 0x26000000 : 0x18000000);
            if (effect == SNOW) drawSnow(c, w, h, dt, now);
            else drawRain(c, w, h, dt);
            moving = true;
        }
        if (stars) moving |= drawStar(c, w, h, now);

        removeCallbacks(wake);
        if (moving) postInvalidateDelayed(FRAME_MS);
        else if (stars && nextStar > now) postDelayed(wake, nextStar - now);
    }

    // ---------------------------------------------------------------- rain

    private void drawRain(Canvas c, int w, int h, float dt) {
        float speed = effect == DRIZZLE ? 1100 : 2000, length = effect == DRIZZLE ? 22 : 64;
        float stroke = effect == DRIZZLE ? 1.5f : 2f;
        int n = effect == DRIZZLE ? 120 : heavy ? 260 : 180;
        if (count != n) {
            lay(n, w, h);
            for (int i = 0; i < 3; i++) {
                layer[i].setStrokeWidth(stroke * (0.75f + 0.25f * i));
                layer[i].setAlpha(Math.round((effect == DRIZZLE ? 70 : 95) * DEPTH_ALPHA[i]));
            }
        }
        // Rain falling at a slant enters from the side it leans away from, so the band it
        // starts in is wider than the screen.
        float spread = Math.abs(slant) * h;
        int[] used = new int[3];
        for (int i = 0; i < n; i++) {
            int d = i % 3;
            float v = speed * DEPTH_SPEED[d], len = length * DEPTH_SPEED[d];
            y[i] += v * dt;
            x[i] += v * slant * dt;
            if (y[i] - len > h) {
                y[i] = -rnd.nextFloat() * h * 0.15f;
                x[i] = rnd.nextFloat() * (w + spread) - (slant > 0 ? spread : 0);
            }
            float[] b = batch[d];
            int k = used[d];
            b[k] = x[i];
            b[k + 1] = y[i];
            b[k + 2] = x[i] - slant * len;
            b[k + 3] = y[i] - len;
            used[d] = k + 4;
        }
        for (int d = 0; d < 3; d++) c.drawLines(batch[d], 0, used[d], layer[d]);
    }


    // ---------------------------------------------------------------- snow

    private void drawSnow(Canvas c, int w, int h, float dt, long now) {
        int n = 150;
        if (count != n) {
            lay(n, w, h);
            for (int i = 0; i < 3; i++) {
                layer[i].setStrokeWidth(Ui.dp(1.5f + 1.25f * i));
                layer[i].setAlpha(Math.round(215 * DEPTH_ALPHA[i]));
            }
        }
        float t = now / 1000f, drift = slant * 140;
        int[] used = new int[3];
        for (int i = 0; i < n; i++) {
            int d = i % 3;
            float v = 55 + 75 * d;
            y[i] += v * dt;
            x[i] += drift * DEPTH_SPEED[d] * dt;
            if (y[i] > h + 10) {
                y[i] = -10;
                x[i] = rnd.nextFloat() * w;
            }
            if (x[i] < -30) x[i] += w + 60;
            if (x[i] > w + 30) x[i] -= w + 60;
            float[] b = batch[d];
            int k = used[d];
            b[k] = x[i] + (float) Math.sin(t * (0.6f + 0.25f * d) + phase[i]) * (10 + 8 * d);
            b[k + 1] = y[i];
            used[d] = k + 2;
        }
        for (int d = 0; d < 3; d++) c.drawPoints(batch[d], 0, used[d], layer[d]);
    }

    private void lay(int n, int w, int h) {
        count = n;
        x = new float[n];
        y = new float[n];
        phase = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextFloat() * w;
            y[i] = rnd.nextFloat() * h;
            phase[i] = rnd.nextFloat() * 6.283f;
        }
        for (int d = 0; d < 3; d++) batch[d] = new float[(n / 3 + 1) * 4];
    }

    // ---------------------------------------------------------------- fog

    private void drawFog(Canvas c, int w, int h, float dt) {
        if (fogNear == null) {
            fogFar = cloud(256, 72, 11, 0.55f);
            fogNear = cloud(256, 72, 23, 0.8f);
        }
        boolean night = weather != null && !weather.day;
        fogPaint.setColor(night ? 0xFF6E7887 : 0xFFE4E9EE);
        // Fog moves slowly whatever the wind, and the near layer faster than the far one.
        float wind = weather != null ? weather.wind : 8;
        fogShift += dt * (10 + Math.min(wind, 40)) * Math.signum(slant);
        layerOfFog(c, fogFar, w * 1.7f, h * 0.95f, h, fogShift * 0.6f, night ? 130 : 170);
        layerOfFog(c, fogNear, w * 1.35f, h * 0.7f, h, fogShift, night ? 150 : 200);
    }

    /** One fog texture, stretched to tileW by tall and sitting on the bottom edge, slid by shift and repeated. */
    private void layerOfFog(Canvas c, Bitmap tex, float tileW, float tall, int h, float shift, int alpha) {
        fogPaint.setAlpha(alpha);
        float off = shift % tileW;
        if (off > 0) off -= tileW;
        for (float left = off; left < getWidth(); left += tileW) {
            dst.set(left, h - tall, left + tileW, h);
            c.drawBitmap(tex, null, dst, fogPaint);
        }
    }

    /**
     * A strip of soft cloud as an alpha mask: smooth noise that repeats seamlessly side to side,
     * thinning to nothing toward the top. density 0..1 is how much of it is fog.
     */
    private static Bitmap cloud(int w, int h, long seed, float density) {
        Random r = new Random(seed);
        int[] cells = {4, 8, 16};
        float[] weights = {0.55f, 0.3f, 0.15f};
        float[][] grids = new float[cells.length][];
        for (int o = 0; o < cells.length; o++) {
            int gw = cells[o], gh = Math.max(2, cells[o] * h / w + 2);
            grids[o] = new float[gw * gh];
            for (int i = 0; i < grids[o].length; i++) grids[o][i] = r.nextFloat();
        }
        byte[] px = new byte[w * h];
        for (int j = 0; j < h; j++) {
            float fy = j / (float) (h - 1);
            // Thin at the top, full from about two thirds of the way down.
            float fall = smooth(Math.min(1f, fy / 0.7f));
            for (int i = 0; i < w; i++) {
                float n = 0;
                for (int o = 0; o < cells.length; o++) {
                    int gw = cells[o], gh = grids[o].length / gw;
                    float gx = i * gw / (float) w, gy = fy * (gh - 1.001f);
                    int x0 = (int) gx, y0 = (int) gy;
                    float tx = smooth(gx - x0), ty = smooth(gy - y0);
                    int x1 = (x0 + 1) % gw;
                    float[] g = grids[o];
                    float top = g[y0 * gw + x0] + (g[y0 * gw + x1] - g[y0 * gw + x0]) * tx;
                    float bot = g[(y0 + 1) * gw + x0] + (g[(y0 + 1) * gw + x1] - g[(y0 + 1) * gw + x0]) * tx;
                    n += (top + (bot - top) * ty) * weights[o];
                }
                float a = (n - (1f - density) * 0.75f) / 0.45f;
                a = Math.max(0f, Math.min(1f, a)) * fall;
                px[j * w + i] = (byte) Math.round(a * 255);
            }
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        return b;
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    // ---------------------------------------------------------------- shooting star

    /** Draws a shooting star if one is due or under way; true while one is crossing. */
    private boolean drawStar(Canvas c, int w, int h, long now) {
        if (starAt == 0 && nextStar > 0 && now >= nextStar) {
            starAt = now;
            starMs = 650 + rnd.nextInt(400);
            float angle = (float) Math.toRadians(18 + rnd.nextInt(20));
            float speed = 1100 + rnd.nextInt(600);
            boolean right = rnd.nextBoolean();
            starDx = (float) Math.cos(angle) * speed * (right ? 1 : -1);
            starDy = (float) Math.sin(angle) * speed;
            starX = w * (right ? 0.1f + rnd.nextFloat() * 0.45f : 0.45f + rnd.nextFloat() * 0.45f);
            starY = h * (0.05f + rnd.nextFloat() * 0.22f);
            nextStar = now + (forcedStars ? 4000 : 45_000 + rnd.nextInt(120_000));
        }
        if (starAt == 0) return false;
        float t = (now - starAt) / 1000f, life = (now - starAt) / starMs;
        if (life >= 1f) {
            starAt = 0;
            return false;
        }
        // In quickly, out slowly.
        float bright = life < 0.15f ? life / 0.15f : life > 0.6f ? (1f - life) / 0.4f : 1f;
        float hx = starX + starDx * t, hy = starY + starDy * t;
        float tail = 0.16f; // seconds of travel the tail covers
        int pieces = 10;
        for (int i = 0; i < pieces; i++) {
            float a = i / (float) pieces, b = (i + 1) / (float) pieces;
            starPaint.setStrokeWidth(Ui.dp(1.6f) * (1f - a * 0.7f));
            starPaint.setAlpha(Math.round(230 * bright * (1f - a) * (1f - a)));
            c.drawLine(hx - starDx * tail * a, hy - starDy * tail * a, hx - starDx * tail * b, hy - starDy * tail * b, starPaint);
        }
        starPaint.setAlpha(Math.round(70 * bright));
        c.drawCircle(hx, hy, Ui.dp(4), starPaint);
        starPaint.setAlpha(Math.round(255 * bright));
        c.drawCircle(hx, hy, Ui.dp(1.4f), starPaint);
        return true;
    }
}
