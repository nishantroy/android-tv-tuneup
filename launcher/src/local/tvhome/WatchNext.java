package local.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads the system "Watch Next" list that TV apps publish (the stock launcher's Play Next row):
 * things the user was partway through, each with a deep link that resumes it.
 *
 * Needs android.permission.READ_TV_LISTINGS, which a sideloaded app only gets through
 * `adb shell pm grant local.tvhome android.permission.READ_TV_LISTINGS`.
 */
final class WatchNext {
    private WatchNext() {}

    private static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");

    static final class Item {
        String pkg, title, episode, intentUri;
        Uri art;
        /** Generated artwork, set only on sample entries. */
        Bitmap sampleArt;
        long positionMs, durationMs, engagedAt;

        /** 0..1 through the item, or 0 when the app didn't say. */
        float progress() {
            return durationMs > 0 ? Math.max(0f, Math.min(1f, positionMs / (float) durationMs)) : 0f;
        }
    }

    /** Most recently watched first. Empty if the system won't share the list with this app. */
    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(URI, null, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                Item it = new Item();
                it.pkg = str(cur, "package_name");
                it.title = str(cur, "title");
                String season = str(cur, "season_display_number"), ep = str(cur, "episode_display_number");
                it.episode = season != null && ep != null ? "S" + season + " E" + ep : "";
                // Some apps repeat the episode number in the title.
                if (it.title != null && !it.episode.isEmpty() && it.title.endsWith(" " + it.episode)) {
                    it.title = it.title.substring(0, it.title.length() - it.episode.length() - 1);
                }
                String art = str(cur, "poster_art_uri");
                if (art == null) art = str(cur, "thumbnail_uri");
                it.art = art != null ? Uri.parse(art) : null;
                it.intentUri = str(cur, "intent_uri");
                it.positionMs = num(cur, "last_playback_position_millis");
                it.durationMs = num(cur, "duration_millis");
                it.engagedAt = num(cur, "last_engagement_time_utc_millis");
                boolean finished = it.durationMs > 0 && it.positionMs >= it.durationMs * 0.96f;
                if (it.title != null && it.intentUri != null && it.pkg != null && !finished) out.add(it);
            }
        } catch (RuntimeException e) {
            Log.i("tvhome", "Watch Next unavailable: " + e);
        }
        out.sort((a, b) -> Long.compare(b.engagedAt, a.engagedAt));
        return out;
    }

    /**
     * Made-up entries with generated artwork, for screenshots and demos: they show what the row
     * looks like without revealing anyone's viewing history or using anyone's stills. Each is
     * attributed to an installed app, and opens that app.
     */
    static List<Item> samples(Collection<App> installed) {
        String[] titles = {"Northern Lights", "The Long Way Home", "Kitchen Stories", "Deep Ocean",
                "City at Night", "Trail Notes", "A Garden Year", "Small Hours"};
        String[] episodes = {"S2 E4", "", "S1 E7", "S3 E1", "", "S1 E2", "S4 E9", ""};
        float[] progress = {0.62f, 0.18f, 0.45f, 0f, 0.8f, 0.3f, 0.55f, 0.1f};
        float[] hues = {168f, 24f, 280f, 205f, 338f, 96f, 48f, 232f};
        List<App> owners = new ArrayList<>(installed);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < titles.length && !owners.isEmpty(); i++) {
            Item it = new Item();
            it.pkg = owners.get((i * 3 + 1) % owners.size()).pkg;
            it.title = titles[i];
            it.episode = episodes[i];
            it.durationMs = 1000;
            it.positionMs = Math.round(progress[i] * 1000);
            it.sampleArt = sampleArt(hues[i]);
            out.add(it);
        }
        return out;
    }

    /** An abstract 16:9 image: a dark diagonal wash in one hue with a soft glow off-centre. */
    private static Bitmap sampleArt(float hue) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h,
                Color.HSVToColor(new float[] {hue, 0.75f, 0.42f}),
                Color.HSVToColor(new float[] {(hue + 40f) % 360f, 0.8f, 0.14f}), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.7f, h * 0.35f, h * 0.9f,
                Color.HSVToColor(150, new float[] {(hue + 20f) % 360f, 0.5f, 1f}), Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
