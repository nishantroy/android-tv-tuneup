package local.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Current weather for one place, from Open-Meteo (open-meteo.com), which needs no account or key.
 *
 * It is off until a place is set, because asking for weather means sending that place's
 * coordinates to a third party. The place is set over adb and kept only on the TV:
 *
 *   adb shell am start -n local.tvhome/.HomeActivity --es weather "40.71,-74.01,New York"
 *   adb shell am start -n local.tvhome/.HomeActivity --es weather off
 *
 * An optional fourth part, C or F, picks the unit; otherwise it follows the TV's region.
 */
final class Weather {
    private Weather() {}

    static final String EXTRA = "weather";
    private static final String PREFS = "weather";
    private static final long FRESH_MS = 30 * 60 * 1000L, USABLE_MS = 3 * 60 * 60 * 1000L;

    static final class Now {
        int temp, high, low, code;
        boolean day;
        /** Today's sunrise and sunset there, in minutes after midnight, or -1 if not known. */
        int sunrise = -1, sunset = -1;
        String place;

        /** A word or two for the conditions, from the WMO weather code. */
        String words() {
            if (code == 0) return "Clear";
            if (code == 1) return day ? "Mostly sunny" : "Mostly clear";
            if (code == 2) return "Partly cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code >= 61 && code <= 67) return "Rain";
            if (code >= 71 && code <= 77) return "Snow";
            if (code >= 80 && code <= 82) return "Showers";
            if (code == 85 || code == 86) return "Snow showers";
            if (code >= 95) return "Thunderstorm";
            return "";
        }

        int icon() {
            if (code <= 1) return day ? Icon.SUN : Icon.MOON;
            if (code <= 3) return Icon.CLOUD;
            if (code == 45 || code == 48) return Icon.FOG;
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Icon.SNOW;
            if (code >= 95) return Icon.STORM;
            return Icon.RAIN;
        }
    }

    /** "lat,lon,Place[,C|F]" turns weather on for that place; anything else turns it off. */
    static void configure(Context c, String spec) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
        String[] parts = spec == null ? new String[0] : spec.split(",");
        if (parts.length >= 2) {
            try {
                e.putFloat("lat", Float.parseFloat(parts[0].trim()));
                e.putFloat("lon", Float.parseFloat(parts[1].trim()));
                e.putString("place", parts.length >= 3 ? parts[2].trim() : "");
                boolean fahrenheit = parts.length >= 4 ? parts[3].trim().equalsIgnoreCase("F")
                        : "US".equals(Locale.getDefault().getCountry());
                e.putBoolean("fahrenheit", fahrenheit);
                e.putBoolean("on", true);
            } catch (NumberFormatException ignored) {
                // Not coordinates: leave it off.
            }
        }
        e.apply();
    }

    /** The last reading if it is recent enough to show, else null. */
    static Now cached(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("on", false) || System.currentTimeMillis() - p.getLong("at", 0) > USABLE_MS) return null;
        Now n = new Now();
        n.temp = p.getInt("temp", 0);
        n.high = p.getInt("high", 0);
        n.low = p.getInt("low", 0);
        n.code = p.getInt("code", 0);
        n.day = p.getBoolean("day", true);
        n.sunrise = p.getInt("sunrise", -1);
        n.sunset = p.getInt("sunset", -1);
        n.place = p.getString("place", "");
        return n;
    }

    /** Hands over what there is to show, fetching first if the last reading has gone stale. */
    static void refresh(Context context, ExecutorService io, Handler ui, Consumer<Now> done) {
        Context c = context.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("on", false)) {
            done.accept(null);
            return;
        }
        // A reading saved before sunrise and sunset were kept is fetched again to get them.
        if (System.currentTimeMillis() - p.getLong("at", 0) < FRESH_MS && p.contains("sunrise")) {
            done.accept(cached(c));
            return;
        }
        io.execute(() -> {
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
                        + "&temperature_unit=%s&timezone=auto&forecast_days=1",
                        p.getFloat("lat", 0), p.getFloat("lon", 0),
                        p.getBoolean("fahrenheit", false) ? "fahrenheit" : "celsius");
                JSONObject json = new JSONObject(get(url));
                JSONObject current = json.getJSONObject("current"), daily = json.getJSONObject("daily");
                p.edit().putInt("temp", (int) Math.round(current.getDouble("temperature_2m")))
                        .putInt("code", current.getInt("weather_code"))
                        .putBoolean("day", current.getInt("is_day") == 1)
                        .putInt("high", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                        .putInt("low", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
                        .putInt("sunrise", minutes(daily.optJSONArray("sunrise")))
                        .putInt("sunset", minutes(daily.optJSONArray("sunset")))
                        .putLong("at", System.currentTimeMillis()).commit();
            } catch (Exception ignored) {
                // Offline or a bad answer: fall back to the last reading while it is usable.
            }
            ui.post(() -> done.accept(cached(c)));
        });
    }

    /** "2026-01-31T06:38", the first entry of a daily list, as minutes after midnight; -1 if it is not that. */
    private static int minutes(JSONArray days) {
        String s = days != null ? days.optString(0, "") : "";
        int t = s.indexOf('T');
        try {
            return t < 0 ? -1 : Integer.parseInt(s.substring(t + 1, t + 3)) * 60 + Integer.parseInt(s.substring(t + 4, t + 6));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
