package local.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallpaper behind everything, and the frosted copy of it that glass panels are cut from.
 *
 * Each picture is prepared once, off the main thread: cropped to the screen's shape with the
 * darkening that keeps text readable painted into it, so showing it costs one opaque blit a
 * frame and no second layer. This TV repaints the whole screen on every frame, and what that
 * blit costs depends on how much memory the picture takes: at full size in 32-bit color the
 * carousel ran at 25 ms a frame, at half size 17 ms, and at half size in 16-bit color (dithered)
 * 14 to 15 ms, which is what is used. Without a wallpaper it is 12 to 13. Real glass would blur what is behind it on every frame, which
 * this TV's GPU cannot afford; a wallpaper does not move, so it is blurred once here and
 * {@link Glass} draws the matching slice of that.
 *
 * Pictures come from the app's assets, unless the owner has put their own in this folder, in
 * which case only those are shown:
 * /sdcard/Android/data/local.tvhome/files/wallpapers
 *
 * A picture's name can say when it suits, as extra dot-separated words before the extension:
 * a time of day (dawn, day, dusk, night) and a kind of weather (fog, rain, snow), as in
 * "bridge.dawn.dusk.fog.jpg". A picture with times is only shown at those times, and when the
 * weather is known the pictures for that weather are preferred. A plain name is shown any time.
 */
final class Backdrop extends View {
    static final String DIR = "wallpapers";
    /** Half the screen each way. See the note above before raising it. */
    private static final int W = 960, H = 540, FROST_W = 320, FROST_H = 180;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private final List<Runnable> listeners = new ArrayList<>();
    private final List<String> sources = new ArrayList<>();

    private static final List<String> TIMES = Arrays.asList("dawn", "day", "dusk", "night");
    private Bitmap current, previous, frost;
    private String showing, wanted;
    private float fade = 1f;
    private boolean on, loading;
    private ValueAnimator anim;

    Backdrop(Context c) {
        super(c);
    }

    /** Runs whenever the picture, and so the frost, changes. */
    void onChange(Runnable r) {
        listeners.add(r);
    }

    /** The blurred picture, covering the same area as this view, or null when there is none. */
    Bitmap frost() {
        return frost;
    }

    boolean on() {
        return on;
    }

    void setOn(boolean wanted) {
        if (on == wanted) return;
        on = wanted;
        if (on) {
            sources.clear();
            try {
                String[] bundled = getContext().getAssets().list(DIR);
                if (bundled != null) {
                    Arrays.sort(bundled);
                    for (String name : bundled) if (isPicture(name)) sources.add("asset:" + DIR + "/" + name);
                }
            } catch (Exception ignored) {
            }
            File own = new File(getContext().getExternalFilesDir(null), DIR);
            File[] files = own.listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) if (isPicture(f.getName())) sources.add(f.getPath());
            }
            next();
        } else {
            if (anim != null) anim.cancel();
            current = previous = frost = null;
            showing = null;
            changed();
            invalidate();
        }
    }

    /** The pictures whose names say they suit now, or all of them if none does. */
    private List<String> suitable() {
        Weather.Now weather = Weather.cached(getContext());
        Calendar clock = Calendar.getInstance();
        String time = timeOfDay(clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE), weather);
        String sky = weather == null ? null : weather.icon() == Icon.RAIN ? "rain" : weather.icon() == Icon.SNOW ? "snow"
                : weather.foggy() ? "fog" : null;
        // Pictures the owner added replace the built-in ones; those are only the fallback.
        List<String> pool = new ArrayList<>();
        for (String source : sources) if (!source.startsWith("asset:")) pool.add(source);
        if (pool.isEmpty()) pool = sources;
        List<String> timely = new ArrayList<>(), forSky = new ArrayList<>();
        for (String source : pool) {
            String name = source.substring(source.lastIndexOf('/') + 1).toLowerCase();
            List<String> words = Arrays.asList(name.split("\\."));
            words = words.subList(Math.min(1, words.size()), Math.max(1, words.size() - 1)); // drop the name and the extension
            if (sky != null && words.contains(sky)) forSky.add(source);
            boolean timed = false;
            for (String t : TIMES) timed |= words.contains(t);
            if (!timed || words.contains(time)) timely.add(source);
        }
        return !forSky.isEmpty() ? forSky : !timely.isEmpty() ? timely : pool;
    }

    /**
     * dawn, day, dusk or night, for a time in minutes after midnight.
     *
     * With the weather on, dawn and dusk follow the sun where the weather is for: each runs
     * from 75 minutes on the daylight side of sunrise or sunset to 45 minutes on the dark side,
     * the stretch when the sky has colour. That assumes the TV's clock is in that place's time
     * zone. Without the sun's times it goes by the clock alone.
     */
    static String timeOfDay(int minute, Weather.Now weather) {
        if (weather != null && weather.sunrise >= 0 && weather.sunset > weather.sunrise) {
            if (minute >= weather.sunrise - 45 && minute < weather.sunrise + 75) return "dawn";
            if (minute >= weather.sunset - 75 && minute < weather.sunset + 45) return "dusk";
            return minute > weather.sunrise && minute < weather.sunset ? "day" : "night";
        }
        if (weather != null && !weather.day) return "night";
        int hour = minute / 60;
        return hour >= 5 && hour < 8 ? "dawn" : hour >= 8 && hour < 16 ? "day" : hour >= 16 && hour < 20 ? "dusk" : "night";
    }

    /** Whether the picture showing, or on its way, is tagged with this word, as "fog" in "sutro.day.fog.jpg". */
    boolean showingTagged(String word) {
        if (showing == null) return false;
        String[] words = showing.substring(showing.lastIndexOf('/') + 1).toLowerCase().split("\\.");
        for (int i = 1; i < words.length - 1; i++) if (words[i].equals(word)) return true;
        return false;
    }

    private static boolean isPicture(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    /** Fades to the next picture that suits the hour and the weather. Does nothing if there is no other. */
    void next() {
        if (!on || loading || sources.isEmpty()) return;
        List<String> fits = suitable();
        int at = fits.indexOf(showing);
        // The first picture of a session varies by the day; after that they take turns.
        String source = fits.get(showing == null ? Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % fits.size()
                : (at + 1) % fits.size());
        load(source);
    }

    /** Shows the picture whose file name contains the given text, whatever the hour. */
    void pick(String text) {
        if (!on) return;
        if (loading) {
            wanted = text; // asked for while another picture was still being prepared
            return;
        }
        for (String source : sources) {
            if (source.substring(source.lastIndexOf('/') + 1).toLowerCase().contains(text.toLowerCase())) {
                load(source);
                return;
            }
        }
    }

    private void load(String source) {
        if (source.equals(showing)) return;
        loading = true;
        showing = source;
        worker.execute(() -> {
            Bitmap[] made = null;
            try {
                made = prepare(source);
            } catch (Throwable ignored) {
                // An unreadable picture is skipped; the one showing stays.
            }
            Bitmap[] ready = made;
            main.post(() -> {
                loading = false;
                if (ready != null && on) show(ready[0], ready[1]);
                if (wanted != null) {
                    String text = wanted;
                    wanted = null;
                    pick(text);
                }
            });
        });
    }

    private void show(Bitmap picture, Bitmap frosted) {
        if (anim != null) anim.cancel();
        previous = current;
        current = picture;
        if (previous == null) {
            frost = frosted;
            fade = 1f;
            changed();
            invalidate();
            return;
        }
        fade = 0f;
        boolean[] swapped = {false};
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            // The glass changes over at the midpoint, when both pictures are half there.
            if (!swapped[0] && fade >= 0.5f) {
                swapped[0] = true;
                frost = frosted;
                changed();
            }
            if (fade >= 1f) previous = null;
            invalidate();
        });
        anim.start();
    }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (current == null) return;
        dst.set(0, 0, getWidth(), getHeight());
        if (previous != null && fade < 1f) {
            paint.setAlpha(255);
            c.drawBitmap(previous, null, dst, paint);
            paint.setAlpha(Math.round(255 * fade));
            c.drawBitmap(current, null, dst, paint);
        } else {
            paint.setAlpha(255);
            c.drawBitmap(current, null, dst, paint);
        }
    }

    /** {the picture as shown, its frosted copy}. Runs on the worker thread. */
    private Bitmap[] prepare(String source) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= W && bounds.outHeight / (o.inSampleSize * 2) >= H) {
            o.inSampleSize *= 2;
        }
        Bitmap raw;
        try (InputStream in = open(source)) {
            raw = BitmapFactory.decodeStream(in, null, o);
        }
        if (raw == null) throw new IllegalArgumentException(source);

        // Center-crop to 16:9.
        float scale = Math.max(W / (float) raw.getWidth(), H / (float) raw.getHeight());
        int sw = Math.round(W / scale), sh = Math.round(H / scale);
        Rect src = new Rect((raw.getWidth() - sw) / 2, (raw.getHeight() - sh) / 2,
                (raw.getWidth() + sw) / 2, (raw.getHeight() + sh) / 2);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        // The frost is cut from the picture before it is darkened, a little richer in color.
        Bitmap small = Bitmap.createBitmap(FROST_W, FROST_H, Bitmap.Config.ARGB_8888);
        ColorMatrix rich = new ColorMatrix();
        rich.setSaturation(1.5f);
        p.setColorFilter(new ColorMatrixColorFilter(rich));
        new Canvas(small).drawBitmap(raw, src, new Rect(0, 0, FROST_W, FROST_H), p);
        p.setColorFilter(null);
        blur(small, 10);
        small.setHasAlpha(false);

        Bitmap picture = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(picture);
        c.drawBitmap(raw, src, new Rect(0, 0, W, H), p);
        raw.recycle();
        // Darker at the top, for the header, and at the bottom, for Continue Watching.
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, 0, H,
                new int[] {0x7A08090C, 0x4008090C, 0x8C08090C}, new float[] {0f, 0.42f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, scrim);
        return new Bitmap[] {picture, small};
    }

    private InputStream open(String source) throws Exception {
        if (source.startsWith("asset:")) return getContext().getAssets().open(source.substring(6));
        return new FileInputStream(source);
    }

    /** Three passes of a box blur, which is close to a Gaussian. In place. */
    private static void blur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void box(int[] in, int[] out, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h, step = horizontal ? 1 : w;
        int window = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * w : line;
            int r = 0, g = 0, bl = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = in[start + Math.max(0, Math.min(length - 1, i)) * step];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                bl += c & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                out[start + i * step] = 0xFF000000 | ((r / window) << 16) | ((g / window) << 8) | (bl / window);
                int drop = in[start + Math.max(0, i - radius) * step];
                int add = in[start + Math.min(length - 1, i + radius + 1) * step];
                r += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                bl += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }
}
