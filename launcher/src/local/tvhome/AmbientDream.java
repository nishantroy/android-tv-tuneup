package local.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ambient screen as the system screensaver, so the TV looks the same when it is left idle
 * inside an app as when it is left on the home screen. It is chosen once over adb:
 *
 *   adb shell settings put secure screensaver_components local.tvhome/local.tvhome.AmbientDream
 *
 * Any button on the remote ends it, as with any screensaver.
 */
public final class AmbientDream extends DreamService {
    private static final long ROTATE_MS = 3 * 60 * 1000L, WEATHER_MS = 30 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Backdrop backdrop;
    private AmbientView ambient;
    private NowPlaying nowPlaying;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            backdrop.next();
            ui.postDelayed(this, ROTATE_MS);
        }
    };

    private final Runnable weather = new Runnable() {
        @Override
        public void run() {
            Weather.refresh(AmbientDream.this, io, ui, ambient::setWeather);
            ui.postDelayed(this, WEATHER_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ambient = new AmbientView(this, backdrop, false);
        root.addView(ambient, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // The launcher's own choice of wallpaper or plain applies here too.
        backdrop.setOn(getSharedPreferences(HomeActivity.class.getSimpleName(), Context.MODE_PRIVATE)
                .getBoolean(HomeActivity.K_WALLPAPER, true));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        nowPlaying = new NowPlaying(this, ui, c -> ambient.setNowPlaying(c, null));
        nowPlaying.start();
        ui.postDelayed(rotate, ROTATE_MS);
        weather.run();
    }

    @Override
    public void onDreamingStopped() {
        ui.removeCallbacksAndMessages(null);
        if (nowPlaying != null) nowPlaying.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        io.shutdown();
        super.onDetachedFromWindow();
    }
}
