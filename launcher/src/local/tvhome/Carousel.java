package local.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * The home carousel: one app faces the viewer in the middle, the rest fan away to either side in
 * perspective and slide past as you press left and right. The whole strip is a single focus stop;
 * a fixed halo marks the middle slot and the apps move through it.
 */
final class Carousel extends FrameLayout {

    interface Host {
        /** The app in the middle changed, or the strip gained focus. */
        void onSelected(App app);

        void onOpen(App app, View card);

        void onOptions(App app);
    }

    static final float CARD_W = 264, CARD_H = 148, RADIUS = 18;
    /**
     * Side cards hinge on the edge nearest the middle and swing 40 degrees, outer edge toward the
     * viewer. GAP separates the middle card from the stacks; STEP is how much of each further
     * card shows; CAMERA sets how strongly the turn foreshortens.
     */
    private static final float GAP = 30, STEP = 58, ANGLE = 40, SIDE_SCALE = 0.8f, CAMERA = 1200, RING_GAP = 3;

    private final Host host;
    private final View ring;
    private final FrameLayout sheen;
    private final List<App> apps = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    // Cards are cached as textures and dimmed as they are composited (Ui.dim), so sliding
    // changes transforms only. The last level set per card is remembered to skip no-op updates.
    private int[] shadeLevel = new int[0];
    private int[] order = new int[0];
    private Integer[] sorted = new Integer[0];
    private final java.util.Comparator<Integer> farthestFirst =
            (a, b) -> Float.compare(distance(b), distance(a));

    private float pos, goal;
    private float rest = 1f; // 1 when the strip is not focused: the middle card dims like the others
    private boolean circular;
    private ValueAnimator slide, focusFade;

    Carousel(Context c, Host host) {
        super(c);
        this.host = host;
        setFocusable(true);
        setClickable(true);
        setClipChildren(false);
        setClipToPadding(false);
        setChildrenDrawingOrderEnabled(true);

        // A soft highlight over the top of the middle slot. It rests while cards slide under it.
        sheen = new FrameLayout(c);
        Ui.clipRound(sheen, RADIUS);
        View gloss = new View(c);
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x17FFFFFF, 0x00FFFFFF}));
        sheen.addView(gloss, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(Ui.dp(CARD_H) * 0.42f)));
        addView(sheen, new LayoutParams(Ui.dp(CARD_W), Ui.dp(CARD_H), Gravity.CENTER));

        ring = new View(c);
        ring.setBackground(Ui.shape(Color.TRANSPARENT, RADIUS + RING_GAP + Ui.RING, Ui.RING, Color.WHITE));
        ring.setAlpha(0f);
        int out = Ui.dp(RING_GAP + Ui.RING);
        addView(ring, new LayoutParams(Ui.dp(CARD_W) + 2 * out, Ui.dp(CARD_H) + 2 * out, Gravity.CENTER));

        setOnClickListener(v -> {
            App a = selected();
            if (a != null) host.onOpen(a, cards.get(index()));
        });
        setOnLongClickListener(v -> {
            App a = selected();
            if (a != null) host.onOptions(a);
            return true;
        });
        setOnFocusChangeListener((v, focused) -> {
            ring.animate().alpha(focused ? 1f : 0f).setDuration(Ui.FAST).start();
            if (focusFade != null) focusFade.cancel();
            focusFade = ValueAnimator.ofFloat(rest, focused ? 0f : 1f);
            focusFade.setDuration(Ui.FAST);
            focusFade.addUpdateListener(a -> {
                rest = (float) a.getAnimatedValue();
                place();
            });
            focusFade.start();
            App a = selected();
            if (focused && a != null) host.onSelected(a);
        });
    }

    /** Replaces the strip's contents, keeping the same app in the middle when it is still there. */
    void setApps(List<App> next, String keepKey) {
        if (slide != null) slide.cancel();
        for (View v : cards) removeView(v);
        cards.clear();
        apps.clear();
        apps.addAll(next);
        circular = apps.size() >= 8; // with fewer, wrapping would show the same app on both sides

        int start = 0;
        for (int i = 0; i < apps.size(); i++) {
            App a = apps.get(i);
            if (a.key.equals(keepKey)) start = i;
            FrameLayout card = new FrameLayout(getContext());
            Ui.clipRound(card, RADIUS);
            ImageView img = new ImageView(getContext());
            img.setImageDrawable(a.artCopy());
            if (a.isBanner) {
                card.setBackgroundColor(Ui.SURFACE);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(img, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            } else {
                card.setBackgroundColor(Ui.tint(a.color, 0.16f));
                card.addView(img, new LayoutParams(Ui.dp(84), Ui.dp(84), Gravity.CENTER));
            }
            View edge = new View(getContext());
            edge.setBackground(new Edge(RADIUS));
            card.addView(edge, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            card.setCameraDistance(Ui.dp(CAMERA));
            card.setPivotY(Ui.dp(CARD_H) / 2f);
            card.setLayerType(LAYER_TYPE_HARDWARE, null);
            addView(card, cards.size(), new LayoutParams(Ui.dp(CARD_W), Ui.dp(CARD_H), Gravity.CENTER));
            cards.add(card);
        }
        shadeLevel = new int[cards.size()];
        java.util.Arrays.fill(shadeLevel, -1);
        order = new int[cards.size()];
        pos = goal = start;
        place();
        if (!apps.isEmpty()) host.onSelected(apps.get(start));
    }

    App selected() {
        return apps.isEmpty() ? null : apps.get(index());
    }

    private int index() {
        int n = apps.size();
        return ((Math.round(goal) % n) + n) % n;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return step(-1, event.getRepeatCount() > 0);
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return step(1, event.getRepeatCount() > 0);
        return super.onKeyDown(keyCode, event);
    }

    private boolean step(int by, boolean held) {
        int n = apps.size();
        if (n < 2) return true;
        float next = goal + by;
        if (!circular) next = Math.max(0, Math.min(n - 1, next));
        if (next == goal) return true;
        goal = next;
        host.onSelected(apps.get(index()));
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(pos, goal);
        slide.setDuration(held ? 190 : 280);
        slide.setInterpolator(Ui.OUT);
        slide.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            place();
        });
        slide.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (pos == goal) sheen.animate().alpha(1f).setDuration(Ui.BASE).start();
            }
        });
        sheen.animate().cancel();
        sheen.setAlpha(0f);
        slide.start();
        return true;
    }

    /** Positions every card for the current scroll position. Only transform properties change. */
    private void place() {
        int n = cards.size();
        float width = Ui.dp(CARD_W), reach = width + Ui.dp(GAP), stepPx = Ui.dp(STEP);
        for (int i = 0; i < n; i++) {
            float o = i - pos;
            if (circular) {
                o = ((o % n) + n) % n;
                if (o > n / 2f) o -= n;
            }
            float dist = Math.abs(o);
            float near = Math.max(-1f, Math.min(1f, o)); // -1..1 across the two slots beside the middle
            float turn = Math.min(1f, dist);
            View card = cards.get(i);
            if (dist >= 4f) {
                card.setVisibility(INVISIBLE);
                continue;
            }
            card.setVisibility(VISIBLE);
            // The hinge is the inner edge, which scaling and turning leave where it is put.
            card.setPivotX(o >= 0 ? 0f : width);
            card.setTranslationX(near * reach + (o - near) * stepPx);
            card.setRotationY(near * ANGLE);
            float scale = 1f - (1f - SIDE_SCALE) * turn;
            card.setScaleX(scale);
            card.setScaleY(scale);
            // Depth: each step back is darker and fainter, gone before the screen edge.
            card.setAlpha(ramp(dist, 1f, 1f, 0.85f, 0.5f, 0f));
            float extra = apps.get(i).light ? Ui.LIGHT_EXTRA + 0.06f : 0f;
            float depth = ramp(dist, 0f, 0.3f, 0.5f, 0.65f, 0.65f) + extra * turn;
            float shade = Math.max(depth, rest * (Ui.REST_SHADE + extra));
            int level = Math.round(shade * 32);
            if (level != shadeLevel[i]) {
                shadeLevel[i] = level;
                Ui.dim(card, shade);
            }
        }
        reorder();
    }

    /** Piecewise-linear value at a distance of 0, 1, 2, 3 and 4 slots from the middle. */
    private static float ramp(float dist, float... at) {
        int i = Math.min(at.length - 2, (int) dist);
        float t = Math.min(1f, dist - i);
        return at[i] + (at[i + 1] - at[i]) * t;
    }

    private float distance(int i) {
        int n = cards.size();
        float o = i - pos;
        if (circular) {
            o = ((o % n) + n) % n;
            if (o > n / 2f) o -= n;
        }
        return Math.abs(o);
    }

    /** Farthest cards are drawn first so nearer ones overlap them. No elevation, so no shadows. */
    private void reorder() {
        int n = cards.size();
        if (sorted.length != n) {
            sorted = new Integer[n];
            for (int i = 0; i < n; i++) sorted[i] = i;
        }
        java.util.Arrays.sort(sorted, farthestFirst);
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (order[i] != sorted[i]) {
                order[i] = sorted[i];
                changed = true;
            }
        }
        if (changed) invalidate();
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        // Children are the cards in order, then the ring, which always draws last.
        return drawingPosition < order.length ? order[drawingPosition] : drawingPosition;
    }
}
