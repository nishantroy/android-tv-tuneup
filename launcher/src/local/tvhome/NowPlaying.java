package local.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows whatever is playing on the TV (music carrying on in the background, say) through the
 * system's media sessions, and reports the one worth showing.
 */
final class NowPlaying implements MediaSessionManager.OnActiveSessionsChangedListener {

    interface Listener {
        /** controller is null when nothing is playing or paused. */
        void onNowPlaying(MediaController controller);
    }

    private final MediaSessionManager manager;
    private final ComponentName component;
    private final Handler handler;
    private final Listener listener;
    /** Every active session, not only the one shown: one that is idle now may start playing. */
    private final List<MediaController> watched = new ArrayList<>();
    private boolean started;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            choose();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            choose();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    NowPlaying(Context c, Handler handler, Listener listener) {
        manager = c.getSystemService(MediaSessionManager.class);
        component = new ComponentName(c, MediaListener.class);
        this.handler = handler;
        this.listener = listener;
    }

    /**
     * False if the listener has not been enabled for this app, in which case nothing is shown.
     * Safe to call again: the listener can be enabled while the launcher is already running,
     * and a repeat call re-reads the sessions.
     */
    boolean start() {
        try {
            if (!started) manager.addOnActiveSessionsChangedListener(this, component, handler);
            started = true;
            watch(manager.getActiveSessions(component));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    void stop() {
        if (!started) return;
        manager.removeOnActiveSessionsChangedListener(this);
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        started = false;
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        watch(controllers);
    }

    private void refresh() {
        try {
            watch(manager.getActiveSessions(component));
        } catch (SecurityException e) {
            watch(null);
        }
    }

    private void watch(List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                c.registerCallback(callback, handler);
                watched.add(c);
            }
        }
        choose();
    }

    private void choose() {
        MediaController best = null;
        for (MediaController c : watched) {
            if (!worthShowing(c)) continue;
            if (best == null || (isPlaying(c) && !isPlaying(best))) best = c;
        }
        listener.onNowPlaying(best);
    }

    static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static boolean worthShowing(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (s == null || m == null || title(m) == null) return false;
        int state = s.getState();
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING;
    }

    static String title(MediaMetadata m) {
        String t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (t == null || t.isEmpty()) t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        return t == null || t.isEmpty() ? null : t;
    }

    static String artist(MediaMetadata m) {
        String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null || a.isEmpty()) a = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        return a == null || a.isEmpty() ? null : a;
    }
}
