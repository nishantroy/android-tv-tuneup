package local.tvhome;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** One launchable app, with its artwork and the color the backdrop takes when it is focused. */
final class App {
    String key, pkg, label;
    ComponentName cn;
    Drawable art;
    boolean isBanner, leanback;
    int color;
    /** Mostly white or pale artwork, which needs deeper dimming to recede instead of going grey. */
    boolean light;

    /** A fresh drawable for a second view, so two views never share one drawable's bounds. */
    Drawable artCopy() {
        return art.getConstantState() != null ? art.getConstantState().newDrawable() : art;
    }
}
