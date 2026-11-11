package local.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A quiet Android TV home screen. The stage shows the time, a carousel of your apps and what you
 * were last watching; one press down opens every app as a grid. A pool of light follows focus and
 * takes the focused app's color. Nothing is recommended and nothing is advertised.
 */
public class HomeActivity extends Activity implements Carousel.Host {

    private static final int COLS = 5, VISIBLE_ROWS = 3, MAX_CONTINUE = 12;
    // Stage rhythm (dp from the top): header, carousel band, app name, Continue Watching.
    private static final float HEADER_TOP = 38, FLOW_TOP = 109, FLOW_HEIGHT = 200, CONTINUE_TOP = 346;
    /** The Now Playing card: as tall as the date and clock opposite it. */
    private static final float NOW_HEIGHT = 64, NOW_TEXT_WIDTH = 200;
    private static final String K_FAVS = "favs", K_KNOWN = "known", K_COACH = "coach", K_LAST = "last";
    private static final String TAG_ADD = "+", TAG_STORE = "store", TAG_ORDER = "order";
    /** Carousel in most-recently-opened order (default), or the order of the grid. */
    private static final String K_RECENT_FIRST = "recentFirst", K_OPENED = "opened:";
    private static final String PLAY_STORE = "com.android.vending";
    /** `am start -n local.tvhome/.HomeActivity --ez demo true` turns demo mode on; false turns it off. */
    private static final String EXTRA_DEMO = "demo";

    /**
     * First-run order for apps that happen to be installed; everything else follows
     * alphabetically. It is only a starting point: the order is the user's to change.
     */
    private static final List<String> SEED = Arrays.asList(
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.hulu.livingroomplus", "com.wbd.stream",
            "com.peacocktv.peacockandroid", "com.apple.atve.androidtv.appletv",
            "com.google.android.youtube.tvunplugged", "com.spotify.tv.android");

    /**
     * Installed, but only shown on Home if the user adds them: the Play Store, and TCL's
     * live TV, media player and remote-shortcut apps on the TVs this was built for.
     */
    private static final List<String> NOT_ADDED_BY_DEFAULT = Arrays.asList(
            PLAY_STORE, "com.tcl.partnercustomizer", "com.tcl.tv", "com.tcl.ui_mediaCenter");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, App> cache = new HashMap<>(); // io thread only
    private volatile boolean dropCache;

    private Map<String, App> apps = new LinkedHashMap<>();
    private List<String> favs = new ArrayList<>(); // stored order; may name apps that are not installed
    private String renderedSig, continueSig;
    private String movingKey;
    private boolean firstRender = true, gridOpen, hintIsMove;
    /** Show sample Continue Watching entries instead of the real ones (see WatchNext.samples). */
    private boolean demo;

    private GlowView glow;
    private FrameLayout homeLayer, stage, pickerLayer, sheetLayer;
    private Carousel flow;
    private TextView nameLabel, hintPill, netText;
    private LinearLayout continueSection, continueRow, netBadge;
    private HorizontalScrollView continueScroll;
    private View gridCue, firstPill;
    private GridLayout favGrid, pickGrid;
    private ScrollView favScroll, pickScroll;
    private App sheetApp;
    private NowPlaying nowPlaying;
    private MediaController playing;
    private LinearLayout nowCard;
    private ImageView nowArt;
    private TextView nowTitle, nowDetail;
    private Icon nowGlyph;
    private String nowKey;
    private int nowColor;
    private boolean hintShowing;
    private String stripSig;
    private ConnectivityManager.NetworkCallback netCallback;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dropCache = true;
            stripSig = null; // artwork may have changed: rebuild the carousel too
            reload();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        // A flat fill: a full-screen gradient shader on every frame is too much for this GPU.
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));

        FrameLayout root = new FrameLayout(this);
        glow = new GlowView(this);
        root.addView(glow, match());
        homeLayer = buildHome();
        root.addView(homeLayer, match());
        pickerLayer = buildPicker();
        pickerLayer.setVisibility(View.GONE);
        root.addView(pickerLayer, match());
        sheetLayer = new FrameLayout(this);
        sheetLayer.setBackgroundColor(Ui.SCRIM);
        sheetLayer.setVisibility(View.GONE);
        root.addView(sheetLayer, match());
        setContentView(root);
        demo = getIntent().getBooleanExtra(EXTRA_DEMO, false);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
        watchNetwork();
        nowPlaying = new NowPlaying(this, ui, this::showNowPlaying);
        nowPlaying.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        nowPlaying.start(); // re-reads what is playing; also picks up a listener enabled since launch
        reload();
        if (!firstRender) {
            rise(gridOpen ? favScroll : stage, 10, Ui.BASE);
            coach();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_DEMO)) {
            demo = intent.getBooleanExtra(EXTRA_DEMO, false);
            continueSig = null;
            reload();
        }
        // Home pressed while already home: back to the stage.
        movingKey = null;
        closeSheet();
        if (pickerLayer.getVisibility() == View.VISIBLE) showPicker(false);
        render(true);
        if (gridOpen) closeGrid();
        else flow.requestFocus();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        nowPlaying.stop();
        if (netCallback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        }
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (sheetLayer.getVisibility() == View.VISIBLE) closeSheet();
        else if (pickerLayer.getVisibility() == View.VISIBLE) showPicker(false);
        else if (gridOpen) closeGrid();
        // Otherwise this is the home screen: there is nowhere to go back to.
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int key = e.getKeyCode();
        if (movingKey != null) {
            int step = 0;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT: step = -1; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: step = 1; break;
                case KeyEvent.KEYCODE_DPAD_UP: step = -COLS; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: step = COLS; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (!down) {
                        movingKey = null;
                        render(true);
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
            if (down) moveBy(step);
            return true;
        }
        boolean overlay = sheetLayer.getVisibility() == View.VISIBLE
                || pickerLayer.getVisibility() == View.VISIBLE;
        if (overlay) return super.dispatchKeyEvent(e);

        View focus = getCurrentFocus();
        if (key == KeyEvent.KEYCODE_MENU) {
            // The remote's Menu button opens the same options as holding OK.
            App a = focus == flow ? flow.selected() : focus instanceof Cell ? ((Cell) focus).app : null;
            if (a != null) {
                if (!down) showSheet(a);
                return true;
            }
        }
        // The home screen is three shelves: actions, the stage (carousel, then Continue Watching),
        // and the grid. Moving between shelves is decided here rather than left to focus search.
        if (down && key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (focus == flow) {
                if (continueRow.getChildCount() > 0) continueRow.getChildAt(0).requestFocus();
                else openGrid();
                return true;
            }
            if (focus instanceof ContinueCard) {
                openGrid();
                return true;
            }
            if (focus != null && focus.getParent() == firstPill.getParent()) {
                if (gridOpen && favGrid.getChildCount() > 0) favGrid.getChildAt(0).requestFocus();
                else flow.requestFocus();
                return true;
            }
        }
        if (down && key == KeyEvent.KEYCODE_DPAD_UP) {
            if (focus instanceof ContinueCard) {
                flow.requestFocus();
                return true;
            }
            if (focus == flow) {
                firstPill.requestFocus();
                return true;
            }
            if (focus instanceof Cell && focus.getParent() == favGrid && favGrid.indexOfChild(focus) < COLS) {
                closeGrid();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- home layout

    private FrameLayout buildHome() {
        FrameLayout layer = new FrameLayout(this);

        // Header: the date as an eyebrow over the time.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        TextClock date = clock("EEEE, MMMM d", "EEEE, MMMM d", 12.5f, 600, Ui.TEXT_SECONDARY);
        date.setAllCaps(true);
        date.setLetterSpacing(0.16f);
        date.setMaxLines(1); // not setSingleLine: that would replace the all-caps transformation
        // Letter-spaced text is under-measured by wrap_content, so the line gets a fixed width.
        header.addView(date, new LinearLayout.LayoutParams(Ui.dp(420), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextClock time = clock("h:mm", "H:mm", 46, 200, Ui.TEXT);
        time.setLetterSpacing(-0.02f);
        timeRow.addView(time);
        TextClock ampm = clock("a", "", 14, 500, Ui.TEXT_TERTIARY);
        ampm.setLetterSpacing(0.06f);
        ampm.setPadding(Ui.dp(6), 0, 0, 0);
        timeRow.addView(ampm);
        LinearLayout.LayoutParams rowLp = wrapLinear();
        rowLp.topMargin = Ui.dp(6);
        rowLp.leftMargin = -Ui.dp(2); // optical alignment: large numerals carry side bearing
        header.addView(timeRow, rowLp);
        FrameLayout.LayoutParams headerLp = wrap(Gravity.TOP | Gravity.START);
        headerLp.setMargins(Ui.dp(Ui.EDGE), Ui.dp(HEADER_TOP), 0, 0);
        layer.addView(header, headerLp);

        // Actions, top right, sharing the header's top line.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.TOP); // the Now Playing card is taller than the pills beside it
        actions.setClipChildren(false);
        // Room on the left for the first control to grow into when focused.
        actions.setPadding(Ui.dp(10), 0, 0, 0);
        actions.setClipToPadding(false);
        netBadge = new LinearLayout(this);
        netBadge.setGravity(Gravity.CENTER_VERTICAL);
        ImageView netIcon = new ImageView(this);
        netIcon.setImageDrawable(new Icon(Icon.OFFLINE, Ui.WARN));
        netBadge.addView(netIcon, new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
        netText = Ui.text(this, "", 14, 500, Ui.WARN);
        netText.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        netBadge.addView(netText);
        netBadge.setVisibility(View.GONE);
        netBadge.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(36)));
        actions.addView(netBadge);
        actions.addView(buildNowPlayingCard());
        firstPill = pill(Icon.INPUT, "Inputs", v -> openInputs());
        actions.addView(firstPill);
        actions.addView(pill(Icon.SETTINGS, "Settings", v -> start(new Intent(Settings.ACTION_SETTINGS), null)));
        FrameLayout.LayoutParams actionsLp = wrap(Gravity.TOP | Gravity.END);
        actionsLp.setMargins(0, Ui.dp(HEADER_TOP), Ui.dp(Ui.EDGE), 0);
        layer.addView(actions, actionsLp);

        hintPill = Ui.text(this, "", 14, 500, Ui.TEXT);
        hintPill.setBackground(Ui.shape(Ui.PANEL, 18, 0.5f, Ui.PANEL_LINE));
        hintPill.setGravity(Gravity.CENTER_VERTICAL);
        hintPill.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        hintPill.setAlpha(0f);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.topMargin = Ui.dp(HEADER_TOP);
        layer.addView(hintPill, hintLp);

        stage = buildStage();
        layer.addView(stage, match());

        favGrid = grid();
        favScroll = scroll(favGrid);
        favScroll.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams scrollLp = match();
        scrollLp.topMargin = Ui.dp(GRID_VIEWPORT_TOP);
        layer.addView(favScroll, scrollLp);
        return layer;
    }

    /** The carousel, the focused app's name, and the Continue Watching row. */
    private FrameLayout buildStage() {
        FrameLayout s = new FrameLayout(this);
        s.setClipChildren(false);

        flow = new Carousel(this, this);
        FrameLayout.LayoutParams flowLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(FLOW_HEIGHT));
        flowLp.topMargin = Ui.dp(FLOW_TOP);
        s.addView(flow, flowLp);

        nameLabel = Ui.text(this, "", 22, 600, Ui.TEXT);
        nameLabel.setLetterSpacing(-0.01f);
        FrameLayout.LayoutParams nameLp = wrap(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        nameLp.topMargin = Ui.dp(FLOW_TOP + FLOW_HEIGHT / 2 + Carousel.CARD_H / 2 + 16);
        s.addView(nameLabel, nameLp);

        continueSection = new LinearLayout(this);
        continueSection.setOrientation(LinearLayout.VERTICAL);
        continueSection.setClipChildren(false);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(Ui.EDGE), 0, Ui.dp(Ui.EDGE), 0);
        head.addView(Ui.eyebrow(this, "Continue watching"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(cue());
        continueSection.addView(head);
        continueRow = new LinearLayout(this);
        continueRow.setOrientation(LinearLayout.HORIZONTAL);
        continueRow.setClipChildren(false);
        continueRow.setClipToPadding(false); // a focused card grows into the padding
        int inset = Ui.dp(Ui.EDGE - Ui.RING - Ui.RING_GAP);
        continueRow.setPadding(inset, Ui.dp(10), inset, Ui.dp(8));
        HorizontalScrollView hs = new HorizontalScrollView(this) {
            /**
             * Scroll by whole cards, and only when the focused card would otherwise be off
             * screen: five cards always sit margin to margin, with the next one peeking in.
             */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                if (getChildCount() == 0) return 0;
                int pitch = Ui.dp(ContinueCard.W + ContinueCard.GAP);
                int index = Math.max(0, Math.round((rect.left - continueRow.getPaddingLeft()) / (float) pitch));
                int first = Math.round(getScrollX() / (float) pitch);
                if (index < first) first = index;
                if (index > first + CONTINUE_VISIBLE - 1) first = index - (CONTINUE_VISIBLE - 1);
                int max = Math.max(0, getChildAt(0).getWidth() - getWidth());
                return Math.min(max, first * pitch) - getScrollX();
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                labelWholeCards();
            }
        };
        continueScroll = hs;
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setFocusable(false);
        hs.addView(continueRow);
        continueSection.addView(hs);
        continueSection.setVisibility(View.GONE);
        FrameLayout.LayoutParams contLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        contLp.topMargin = Ui.dp(CONTINUE_TOP);
        s.addView(continueSection, contLp);

        // When there is nothing to continue, the way down to the grid is signposted on its own.
        gridCue = cue();
        FrameLayout.LayoutParams cueLp = wrap(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cueLp.bottomMargin = Ui.dp(40);
        s.addView(gridCue, cueLp);
        return s;
    }

    /** "All apps" with a down chevron: there is a grid below. */
    private View cue() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(Ui.eyebrow(this, "All apps"));
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new Icon(Icon.DOWN, Ui.TEXT_SECONDARY));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16));
        lp.leftMargin = Ui.dp(4);
        c.addView(chevron, lp);
        return c;
    }

    private FrameLayout buildPicker() {
        FrameLayout layer = new FrameLayout(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(Ui.eyebrow(this, "Library"));
        TextView title = Ui.text(this, "Add to Home", 28, 600, Ui.TEXT);
        title.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams titleLp = wrapLinear();
        titleLp.topMargin = Ui.dp(8);
        head.addView(title, titleLp);
        FrameLayout.LayoutParams headLp = wrap(Gravity.TOP | Gravity.START);
        headLp.setMargins(Ui.dp(Ui.EDGE), Ui.dp(HEADER_TOP), Ui.dp(Ui.EDGE), 0);
        layer.addView(head, headLp);

        pickGrid = grid();
        pickScroll = scroll(pickGrid);
        FrameLayout.LayoutParams scrollLp = match();
        scrollLp.topMargin = Ui.dp(GRID_VIEWPORT_TOP);
        layer.addView(pickScroll, scrollLp);
        return layer;
    }

    private GridLayout grid() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(COLS);
        int side = Ui.dp(Ui.EDGE - Ui.RING - Ui.RING_GAP - CELL_GAP);
        // Bottom padding is sized so the last row can scroll up into the third visible slot;
        // with less, the scroll range ends mid-row and the top row is sliced.
        int viewport = getResources().getDisplayMetrics().heightPixels - Ui.dp(GRID_VIEWPORT_TOP);
        int bottom = Math.max(Ui.dp(24), viewport - Ui.dp(GRID_TOP) - VISIBLE_ROWS * cellHeight());
        g.setPadding(side, Ui.dp(GRID_TOP), side, bottom);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        return g;
    }

    // Grid geometry (dp). A cell is the card, the halo around it, and the label line below.
    // The viewport starts above the first row by enough to hold a move arrow.
    // Rows are spaced so three fit whole and a fourth only hints at itself at the bottom edge.
    private static final float GRID_VIEWPORT_TOP = 124, GRID_TOP = 18, CELL_GAP = 4.5f, LABEL_ZONE = 26;

    private static int cellWidth() {
        return Ui.dp(Ui.CARD_W) + 2 * Ui.dp(Ui.RING + Ui.RING_GAP);
    }

    private static int cellHeight() {
        return Ui.dp(Ui.CARD_H) + 2 * Ui.dp(Ui.RING + Ui.RING_GAP) + Ui.dp(LABEL_ZONE);
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this) {
            /**
             * Scroll by whole rows, and only when the focused row would otherwise be off screen,
             * so a row is never sliced by the top edge.
             */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                if (getChildCount() == 0) return 0;
                View content = getChildAt(0);
                int pitch = cellHeight();
                int row = Math.max(0, Math.round((rect.top - content.getPaddingTop()) / (float) pitch));
                int first = Math.round(getScrollY() / (float) pitch);
                if (row < first) first = row;
                if (row > first + VISIBLE_ROWS - 1) first = row - (VISIBLE_ROWS - 1);
                return first * pitch - getScrollY();
            }
        };
        s.setVerticalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setFocusable(false);
        s.setOnScrollChangeListener((v, x, y, oldX, oldY) -> glow.shiftBy(oldY - y));
        s.addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private TextClock clock(String f12, String f24, float sp, int weight, int color) {
        TextClock t = new TextClock(this);
        t.setFormat12Hour(f12);
        t.setFormat24Hour(f24);
        t.setTextSize(sp);
        t.setTypeface(Ui.font(weight, sp >= 24));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A capsule with an icon and label. Inverts to solid white when focused. */
    private View pill(int iconType, String label, View.OnClickListener onClick) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(Ui.dp(14), 0, Ui.dp(18), 0);
        p.setFocusable(true);
        p.setClickable(true);
        p.setOnClickListener(onClick);
        Icon icon = new Icon(iconType, Ui.TEXT);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        p.addView(iv, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        TextView t = Ui.text(this, label, 14, 500, Ui.TEXT);
        t.setPadding(Ui.dp(8), 0, 0, 0);
        p.addView(t);
        View.OnFocusChangeListener style = (v, focused) -> {
            p.setBackground(focused ? Ui.shape(Color.WHITE, 18, 0, 0) : Ui.shape(Ui.GLASS, 18, 0.5f, Ui.HAIRLINE));
            t.setTextColor(focused ? Ui.ON_LIGHT : Ui.TEXT);
            icon.setColor(focused ? Ui.ON_LIGHT : Ui.TEXT);
            float s = focused ? 1.04f : 1f;
            p.animate().scaleX(s).scaleY(s).setDuration(Ui.FAST).setInterpolator(Ui.STANDARD).start();
            if (focused) ambient(p, 0.45f);
        };
        style.onFocusChange(p, false);
        p.setOnFocusChangeListener(style);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(36));
        lp.leftMargin = Ui.dp(10);
        p.setLayoutParams(lp);
        return p;
    }

    // ---------------------------------------------------------------- now playing

    /**
     * A card in the header for whatever is playing in the background: artwork, title, artist,
     * and a play or pause button. OK toggles playback; holding OK opens the app that is playing.
     * It is as tall as the clock block opposite, and lights the room in its artwork's color.
     */
    private View buildNowPlayingCard() {
        nowCard = new LinearLayout(this);
        nowCard.setOrientation(LinearLayout.HORIZONTAL);
        nowCard.setGravity(Gravity.CENTER_VERTICAL);
        nowCard.setPadding(Ui.dp(8), 0, Ui.dp(12), 0);
        nowCard.setFocusable(true);
        nowCard.setClickable(true);

        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowArt.setBackgroundColor(Ui.SURFACE);
        Ui.clipRound(nowArt, 9);
        nowCard.addView(nowArt, new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        nowTitle = Ui.text(this, "", 15, 600, Ui.TEXT);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowTitle.setMarqueeRepeatLimit(-1);
        words.addView(nowTitle);
        nowDetail = Ui.text(this, "", 13, 400, Ui.TEXT_SECONDARY);
        nowDetail.setSingleLine(true);
        nowDetail.setEllipsize(TextUtils.TruncateAt.END);
        nowDetail.setPadding(0, Ui.dp(4), 0, 0);
        words.addView(nowDetail);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(Ui.dp(NOW_TEXT_WIDTH),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wordsLp.setMargins(Ui.dp(12), 0, Ui.dp(12), 0);
        nowCard.addView(words, wordsLp);

        nowGlyph = new Icon(Icon.PAUSE, Ui.TEXT);
        ImageView button = new ImageView(this);
        button.setImageDrawable(nowGlyph);
        button.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9));
        nowCard.addView(button, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));

        View.OnFocusChangeListener style = (v, focused) -> {
            nowCard.setBackground(focused ? Ui.shape(Color.WHITE, 16, 0, 0) : Ui.shape(Ui.GLASS, 16, 0.5f, Ui.HAIRLINE));
            nowTitle.setTextColor(focused ? Ui.ON_LIGHT : Ui.TEXT);
            nowDetail.setTextColor(focused ? Ui.ON_LIGHT_SECONDARY : Ui.TEXT_SECONDARY);
            nowGlyph.setColor(focused ? Ui.ON_LIGHT : Ui.TEXT);
            button.setBackground(Ui.shape(focused ? 0x14000000 : 0x1FFFFFFF, 17, 0, 0));
            nowTitle.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            nowTitle.setSelected(focused);
            float s = focused ? 1.03f : 1f;
            nowCard.animate().scaleX(s).scaleY(s).setDuration(Ui.FAST).setInterpolator(Ui.STANDARD).start();
            if (focused) {
                if (nowColor != 0) spotlight(nowCard, nowColor, 0.5f);
                else ambient(nowCard, 0.45f);
            }
        };
        style.onFocusChange(nowCard, false);
        nowCard.setOnFocusChangeListener(style);
        nowCard.setOnClickListener(v -> {
            if (playing == null) return;
            if (NowPlaying.isPlaying(playing)) playing.getTransportControls().pause();
            else playing.getTransportControls().play();
        });
        nowCard.setOnLongClickListener(v -> {
            App owner = playing != null ? appForPackage(playing.getPackageName()) : null;
            if (owner != null) launch(owner, null);
            return true;
        });
        nowCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(NOW_HEIGHT));
        lp.rightMargin = Ui.dp(6); // it grows when focused; keep it clear of the pill beside it
        nowCard.setLayoutParams(lp);
        return nowCard;
    }

    private void showNowPlaying(MediaController controller) {
        playing = controller;
        MediaMetadata m = controller != null ? controller.getMetadata() : null;
        String title = m != null ? NowPlaying.title(m) : null;
        if (title == null) {
            nowKey = null;
            syncNowCard();
            return;
        }
        App owner = appForPackage(controller.getPackageName());
        String artist = NowPlaying.artist(m);
        String album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String detail = artist != null && album != null && !album.isEmpty() && !album.equals(title)
                ? artist + "  ·  " + album
                : artist != null ? artist : owner != null ? owner.label : "";
        nowTitle.setText(title);
        nowDetail.setText(detail);
        nowCard.setContentDescription("Now playing: " + title + (artist != null ? ", " + artist : ""));
        nowGlyph.setType(NowPlaying.isPlaying(controller) ? Icon.PAUSE : Icon.OPEN);

        // Playback state changes arrive far more often than the track does: only touch the
        // artwork, and work out its color, when the track is a different one.
        String key = controller.getPackageName() + '|' + title + '|' + artist;
        if (!key.equals(nowKey)) {
            nowKey = key;
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            String artUri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (artUri == null) artUri = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (artUri == null) artUri = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            nowArt.setTag(null);
            if (art != null) {
                nowArt.setImageBitmap(art);
                nowColor = Ui.signatureColor(new BitmapDrawable(getResources(), art));
            } else if (artUri != null) {
                Images.into(nowArt, Uri.parse(artUri), Ui.dp(48));
                nowColor = owner != null ? owner.color : 0;
            } else {
                nowArt.setImageDrawable(owner != null ? owner.artCopy() : null);
                nowColor = owner != null ? owner.color : 0;
            }
            if (nowCard.isFocused() && nowColor != 0) spotlight(nowCard, nowColor, 0.5f);
        }
        syncNowCard();
    }

    /** The card shows while there is something to show, and steps aside for the hint above it. */
    private void syncNowCard() {
        boolean show = nowKey != null && !hintShowing;
        if (!show && nowCard.isFocused()) firstPill.requestFocus();
        nowCard.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- focusable pieces

    /** The white halo that marks focus, sized to sit just outside a card of the given size. */
    private View halo(int cardW, int cardH, float cardRadius) {
        View ring = new View(this);
        ring.setBackground(Ui.shape(Color.TRANSPARENT, cardRadius + Ui.RING + Ui.RING_GAP, Ui.RING, Color.WHITE));
        ring.setAlpha(0f);
        int out = Ui.dp(Ui.RING + Ui.RING_GAP);
        ring.setLayoutParams(new FrameLayout.LayoutParams(cardW + 2 * out, cardH + 2 * out));
        return ring;
    }

    /**
     * One grid position: artwork on a rounded card, a halo that appears around it on focus, and
     * its name underneath. Action cells (Add, Get more apps) use an outlined card with an icon.
     */
    private final class Cell extends FrameLayout {
        final App app;
        final FrameLayout card;

        Cell(App app, String tag, int iconType, String label, boolean alwaysLabel) {
            super(HomeActivity.this);
            this.app = app;
            setTag(tag);
            setFocusable(true);
            setClickable(true);
            setClipChildren(false);
            int out = Ui.dp(Ui.RING + Ui.RING_GAP), w = Ui.dp(Ui.CARD_W), h = Ui.dp(Ui.CARD_H);
            boolean moving = tag.equals(movingKey);
            boolean art = app != null && app.isBanner;
            boolean quietLabel = art && !alwaysLabel; // artwork names itself until it is focused
            int glowColor = app != null ? app.color : Ui.NEUTRAL_GLOW;
            float restShade = movingKey != null && !moving ? 0.6f
                    : Ui.REST_SHADE + (app != null && app.light ? Ui.LIGHT_EXTRA : 0f);

            View ring = halo(w, h, Ui.CARD_RADIUS);
            addView(ring);

            card = new FrameLayout(getContext());
            Ui.clipRound(card, Ui.CARD_RADIUS);
            Ui.Dimmer dimmer = null;
            if (art) {
                card.setBackgroundColor(Ui.SURFACE);
                ImageView img = new ImageView(getContext());
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageDrawable(app.art);
                card.addView(img, match());
                View edge = new View(getContext()); // hairline so dark artwork keeps its shape
                edge.setBackground(new Edge(Ui.CARD_RADIUS));
                card.addView(edge, match());
            } else {
                GradientDrawable fill = Ui.shape(app != null ? Ui.tint(app.color, 0.16f) : Ui.GLASS, Ui.CARD_RADIUS, 0, 0);
                fill.setStroke(1, Ui.HAIRLINE);
                card.setBackground(fill);
                ImageView icon = new ImageView(getContext());
                icon.setImageDrawable(app != null ? app.art : new Icon(iconType, Ui.TEXT));
                int size = Ui.dp(app != null ? 56 : 26);
                card.addView(icon, new LayoutParams(size, size, Gravity.CENTER));
                card.setAlpha(movingKey != null ? 0.4f : 0.8f);
            }
            // Cached as a texture: only a focus change redraws it, and scaling it is free.
            card.setLayerType(LAYER_TYPE_HARDWARE, null);
            if (art) dimmer = new Ui.Dimmer(card, restShade);
            LayoutParams cardLp = new LayoutParams(w, h);
            cardLp.setMargins(out, out, out, 0);
            addView(card, cardLp);

            if (moving) {
                // Arrows on all four sides say "this one moves".
                View arrows = new View(getContext());
                List<String> order = shown();
                int at = order.indexOf(tag), last = order.size() - 1;
                int ways = (canMove(at, -1, last) ? Icon.LEFT : 0) | (canMove(at, 1, last) ? Icon.RIGHT : 0)
                        | (canMove(at, -COLS, last) ? Icon.UP : 0) | (canMove(at, COLS, last) ? Icon.DOWN_DIR : 0);
                arrows.setBackground(new Icon(Icon.ARROWS, 0xD9FFFFFF).directions(ways));
                int reach = Ui.dp(9); // puts the chevrons in the gutters between tiles
                LayoutParams arrowsLp = new LayoutParams(w + 2 * out + 2 * reach, h + 2 * out + 2 * reach);
                arrowsLp.setMargins(-reach, -reach, -reach, 0);
                addView(arrows, arrowsLp);
            }

            TextView caption = Ui.text(getContext(), app != null ? app.label : label, 13, 500, Ui.TEXT);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            float restLabel = quietLabel ? 0f : 0.6f;
            if (moving) caption.setVisibility(INVISIBLE); // the arrows and the hint say enough
            caption.setAlpha(restLabel);
            LayoutParams capLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            capLp.topMargin = h + 2 * out + Ui.dp(4);
            addView(caption, capLp);

            setContentDescription(app != null ? app.label : label);
            setPivotX(out + w / 2f);
            setPivotY(out + h / 2f);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellWidth();
            lp.height = cellHeight();
            lp.setMargins(Ui.dp(CELL_GAP), 0, Ui.dp(CELL_GAP), 0);
            setLayoutParams(lp);

            final Ui.Dimmer dim = dimmer;
            final float lifted = moving ? 1.04f : 1.08f; // a moving tile stays clear of its arrows
            setOnFocusChangeListener((v, focused) -> {
                float s = focused ? lifted : 1f;
                animate().scaleX(s).scaleY(s).setDuration(Ui.FAST).setInterpolator(Ui.STANDARD).start();
                if (dim != null) {
                    dim.to(focused ? 0f : restShade);
                } else {
                    card.animate().alpha(focused ? 1f : 0.8f).setDuration(Ui.FAST).start();
                }
                ring.animate().alpha(focused ? 1f : 0f).setDuration(Ui.FAST).start();
                caption.animate().alpha(focused ? 1f : restLabel).setDuration(Ui.FAST).start();
                if (focused) spotlight(card, glowColor, app != null ? 1f : 0.5f);
            });
        }
    }

    /** Something to resume: its artwork with a progress bar, then the title and where it's from. */
    private final class ContinueCard extends LinearLayout {
        static final float W = 144, H = 81;
        static final float GAP = 32;
        final FrameLayout thumb;
        final Ui.Dimmer dimmer;
        private final TextView title, subtitle;
        private boolean whole = true; // fully on screen, so its text is shown

        ContinueCard(WatchNext.Item item) {
            super(HomeActivity.this);
            setOrientation(VERTICAL);
            setFocusable(true);
            setClickable(true);
            setClipChildren(false);
            int out = Ui.dp(Ui.RING + Ui.RING_GAP), w = Ui.dp(W), h = Ui.dp(H);
            App owner = appForPackage(item.pkg);
            int color = owner != null ? owner.color : Ui.NEUTRAL_GLOW;

            FrameLayout frame = new FrameLayout(getContext());
            frame.setClipChildren(false);
            View ring = halo(w, h, Ui.CARD_RADIUS);
            frame.addView(ring);
            thumb = new FrameLayout(getContext());
            Ui.clipRound(thumb, Ui.CARD_RADIUS);
            thumb.setBackgroundColor(Ui.tint(color, 0.16f));
            ImageView img = new ImageView(getContext());
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.addView(img, match());
            if (item.sampleArt != null) img.setImageBitmap(item.sampleArt);
            else if (item.art != null) Images.into(img, item.art, w);
            float progress = item.progress();
            if (progress > 0f) {
                // A scrim under the bar keeps it readable on light artwork.
                View scrim = new View(getContext());
                scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                        new int[] {0xB3000000, 0x00000000}));
                thumb.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.round(h * 0.45f), Gravity.BOTTOM));
                int inset = Ui.dp(8), barH = Ui.dp(3);
                View track = new View(getContext());
                track.setBackground(Ui.shape(0x40FFFFFF, 1, 0, 0));
                FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(w - 2 * inset, barH, Gravity.BOTTOM);
                trackLp.setMargins(inset, 0, inset, inset);
                thumb.addView(track, trackLp);
                View fill = new View(getContext());
                fill.setBackground(Ui.shape(0xF2FFFFFF, 1, 0, 0));
                FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(
                        Math.max(barH, Math.round((w - 2 * inset) * progress)), barH, Gravity.BOTTOM);
                fillLp.setMargins(inset, 0, inset, inset);
                thumb.addView(fill, fillLp);
            }
            thumb.addView(edgeView(), match());
            thumb.setLayerType(LAYER_TYPE_HARDWARE, null);
            dimmer = new Ui.Dimmer(thumb, CONTINUE_REST);
            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(w, h);
            thumbLp.setMargins(out, out, out, out);
            frame.addView(thumb, thumbLp);
            addView(frame);

            title = Ui.text(getContext(), item.title, 15, 500, Ui.TEXT);
            title.setMarqueeRepeatLimit(-1);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setAlpha(0.85f);
            LayoutParams titleLp = new LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLp.setMargins(out, Ui.dp(5), out, 0);
            addView(title, titleLp);
            String from = owner != null ? owner.label : "";
            String sub = item.episode.isEmpty() ? from : from.isEmpty() ? item.episode : from + "  ·  " + item.episode;
            subtitle = Ui.text(getContext(), sub, 13, 500, 0xA6FFFFFF);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams subLp = new LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.setMargins(out, Ui.dp(3), out, 0);
            addView(subtitle, subLp);

            setContentDescription(item.title);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(GAP) - 2 * out;
            setLayoutParams(lp);

            setOnClickListener(v -> resume(item, thumb));
            setOnFocusChangeListener((v, focused) -> {
                // Only the artwork grows; the text underneath stays put.
                float s = focused ? 1.06f : 1f;
                frame.animate().scaleX(s).scaleY(s).setDuration(Ui.FAST).setInterpolator(Ui.STANDARD).start();
                ring.animate().alpha(focused ? 1f : 0f).setDuration(Ui.FAST).start();
                if (whole) title.animate().alpha(focused ? 1f : 0.85f).setDuration(Ui.FAST).start();
                // A long title scrolls while its card is focused, so two shows that start with
                // the same words can be told apart.
                title.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                title.setSelected(focused);
                ui.removeCallbacks(dimContinue);
                ui.post(dimContinue);
                if (focused) ambient(thumb, 0.5f);
            });
        }
    }

    /** A card cut off by the screen edge shows its artwork only: half a title is just noise. */
    private void labelWholeCards() {
        if (continueScroll == null) return;
        int out = Ui.dp(Ui.RING + Ui.RING_GAP), x = continueScroll.getScrollX(), width = continueScroll.getWidth();
        for (int i = 0; i < continueRow.getChildCount(); i++) {
            ContinueCard c = (ContinueCard) continueRow.getChildAt(i);
            boolean whole = c.getLeft() + out - x >= 0 && c.getRight() - out - x <= width;
            if (whole == c.whole) continue;
            c.whole = whole;
            c.title.animate().alpha(whole ? (c.isFocused() ? 1f : 0.85f) : 0f).setDuration(Ui.FAST).start();
            c.subtitle.animate().alpha(whole ? 1f : 0f).setDuration(Ui.FAST).start();
        }
    }

    private static final int CONTINUE_VISIBLE = 5;

    /** Resting dim of Continue Watching while focus is elsewhere, and of its unfocused cards. */
    private static final float CONTINUE_REST = 0.42f, CONTINUE_SIBLING = 0.2f;

    /** The row is subdued until it has focus, so the carousel stays the main thing on screen. */
    private final Runnable dimContinue = () -> {
        boolean rowFocused = getCurrentFocus() instanceof ContinueCard;
        for (int i = 0; i < continueRow.getChildCount(); i++) {
            ContinueCard c = (ContinueCard) continueRow.getChildAt(i);
            c.dimmer.to(c.isFocused() ? 0f : rowFocused ? CONTINUE_SIBLING : CONTINUE_REST);
        }
    };

    private App appForPackage(String pkg) {
        for (App a : apps.values()) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    // ---------------------------------------------------------------- backdrop light

    private View lit;
    private int litColor;
    private float litStrength;

    /** Moves the backdrop light behind a view. */
    private void spotlight(View target, int color, float strength) {
        lit = target;
        litColor = color;
        litStrength = strength;
        if (!target.isLaidOut()) {
            target.post(this::respotlight);
            return;
        }
        int[] at = new int[2];
        target.getLocationInWindow(at);
        // getLocationInWindow reports the scaled bounds' corner, so use the scaled size.
        glow.moveTo(at[0] + target.getWidth() * scaleOf(target, true) / 2f,
                at[1] + target.getHeight() * scaleOf(target, false) / 2f, color, strength);
    }

    /**
     * Focus is on something that has no color of its own (a header pill, a Continue Watching
     * card): on the stage the carousel's app keeps lighting the room, more softly.
     */
    private void ambient(View fallback, float strength) {
        App a = flow.selected();
        if (!gridOpen && a != null) spotlight(flow, a.color, strength);
        else spotlight(fallback, Ui.NEUTRAL_GLOW, strength);
    }

    private void respotlight() {
        if (lit != null && lit.isAttachedToWindow()) spotlight(lit, litColor, litStrength);
    }

    private static float scaleOf(View v, boolean x) {
        float s = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            s *= x ? ((View) p).getScaleX() : ((View) p).getScaleY();
        }
        return s;
    }

    /** A view settles upward into place as it appears. */
    private void rise(View v, float fromDp, int duration) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(fromDp));
        v.animate().alpha(1f).translationY(0).setDuration(duration).setInterpolator(Ui.OUT).start();
    }

    // ---------------------------------------------------------------- carousel callbacks

    @Override
    public void onSelected(App a) {
        if (!a.label.contentEquals(nameLabel.getText())) {
            nameLabel.animate().cancel();
            nameLabel.setText(a.label);
            nameLabel.setAlpha(0.3f);
            nameLabel.animate().alpha(1f).setDuration(Ui.BASE).start();
        }
        if (flow.isFocused()) {
            spotlight(flow, a.color, 1f);
            getPreferences(MODE_PRIVATE).edit().putString(K_LAST, a.key).apply();
        }
    }

    @Override
    public void onOpen(App a, View card) {
        launch(a, card);
    }

    @Override
    public void onOptions(App a) {
        showSheet(a);
    }

    // ---------------------------------------------------------------- stage and grid

    private void openGrid() {
        App a = flow.selected();
        openGrid(a != null ? a.key : null);
    }

    private void openGrid(String focusKey) {
        gridOpen = true;
        stage.animate().cancel();
        stage.animate().alpha(0f).translationY(-Ui.dp(28)).setDuration(Ui.FAST).setInterpolator(Ui.STANDARD)
                .withEndAction(() -> {
                    if (gridOpen) stage.setVisibility(View.INVISIBLE);
                }).start();
        favScroll.setVisibility(View.VISIBLE);
        rise(favScroll, 40, Ui.BASE);
        if (focusKey == null || !focusTag(favGrid, focusKey)) {
            if (favGrid.getChildCount() > 0) favGrid.getChildAt(0).requestFocus();
        }
    }

    private void closeGrid() {
        gridOpen = false;
        favScroll.animate().cancel();
        favScroll.animate().alpha(0f).translationY(Ui.dp(40)).setDuration(Ui.FAST).setInterpolator(Ui.STANDARD)
                .withEndAction(() -> {
                    if (!gridOpen) {
                        favScroll.setVisibility(View.INVISIBLE);
                        favScroll.scrollTo(0, 0);
                    }
                }).start();
        stage.setVisibility(View.VISIBLE);
        rise(stage, -28, Ui.BASE);
        flow.requestFocus();
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        io.execute(() -> {
            if (dropCache) {
                dropCache = false;
                cache.clear();
            }
            PackageManager pm = getPackageManager();
            LinkedHashMap<String, App> found = new LinkedHashMap<>();
            Set<String> tvPackages = new HashSet<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, found, tvPackages);
            collect(pm, Intent.CATEGORY_LAUNCHER, false, found, tvPackages);
            List<WatchNext.Item> watching;
            if (demo) {
                List<App> owners = new ArrayList<>();
                for (App a : found.values()) {
                    if (a.leanback && a.isBanner && !NOT_ADDED_BY_DEFAULT.contains(a.pkg)) owners.add(a);
                }
                watching = WatchNext.samples(owners);
            } else {
                watching = WatchNext.load(this);
            }
            ui.post(() -> {
                apply(found);
                showContinue(watching);
            });
        });
    }

    private void collect(PackageManager pm, String category, boolean leanback,
            Map<String, App> out, Set<String> tvPackages) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (leanback) {
                tvPackages.add(pkg);
            } else {
                if (tvPackages.contains(pkg)) continue; // already listed through its TV entry point
                // Phone-style entry points of system packages are setup and service tools, not apps.
                if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            }
            ComponentName cn = new ComponentName(pkg, ai.name);
            String key = cn.flattenToString();
            App a = cache.get(key);
            if (a == null) {
                a = new App();
                a.key = key;
                a.pkg = pkg;
                a.cn = cn;
                a.leanback = leanback;
                a.label = String.valueOf(ri.loadLabel(pm));
                Drawable banner = sharpBanner(pm, ai);
                a.isBanner = banner != null;
                a.art = banner != null ? banner : ri.loadIcon(pm);
                a.color = Ui.signatureColor(a.art);
                a.light = Ui.isLight(a.art);
                cache.put(key, a);
            }
            out.put(key, a);
        }
    }

    /** The app's TV banner at the highest density it ships, since the carousel shows it large. */
    private static Drawable sharpBanner(PackageManager pm, ActivityInfo ai) {
        int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        if (id != 0) {
            try {
                Drawable d = pm.getResourcesForApplication(ai.applicationInfo)
                        .getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // fall through to the default density
            }
        }
        return ai.loadBanner(pm);
    }

    private void apply(Map<String, App> found) {
        apps = found;
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        Set<String> known = new HashSet<>(split(p.getString(K_KNOWN, "")));
        List<String> f = new ArrayList<>(split(p.getString(K_FAVS, "")));
        boolean changed = false;

        if (!p.contains(K_FAVS)) {
            List<App> seed = new ArrayList<>();
            for (App a : found.values()) {
                if (a.leanback && !NOT_ADDED_BY_DEFAULT.contains(a.pkg)) seed.add(a);
            }
            Collections.sort(seed, (x, y) -> {
                int ix = SEED.indexOf(x.pkg), iy = SEED.indexOf(y.pkg);
                if (ix != iy) return Integer.compare(ix < 0 ? 999 : ix, iy < 0 ? 999 : iy);
                return x.label.compareToIgnoreCase(y.label);
            });
            for (App a : seed) f.add(a.key);
            known.addAll(found.keySet());
            changed = true;
        } else {
            // Anything never seen before was just installed: put it on Home.
            for (App a : found.values()) {
                if (known.add(a.key)) {
                    changed = true;
                    if (a.leanback && !NOT_ADDED_BY_DEFAULT.contains(a.pkg) && !f.contains(a.key)) f.add(a.key);
                }
            }
        }
        favs = f;
        if (changed) {
            p.edit().putString(K_FAVS, TextUtils.join("\n", favs))
                    .putString(K_KNOWN, TextUtils.join("\n", known)).apply();
        }
        render(false);
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("\n")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    /** Favorites that are currently installed, in order. */
    private List<String> shown() {
        List<String> out = new ArrayList<>();
        for (String k : favs) if (apps.containsKey(k)) out.add(k);
        return out;
    }

    /** Persists a new order for the visible favorites, keeping entries for apps that are not installed. */
    private void saveFavs(List<String> visibleOrder) {
        List<String> next = new ArrayList<>(visibleOrder);
        for (String k : favs) if (!apps.containsKey(k)) next.add(k);
        favs = next;
        getPreferences(MODE_PRIVATE).edit().putString(K_FAVS, TextUtils.join("\n", favs)).apply();
    }

    // ---------------------------------------------------------------- rendering

    private void render(boolean force) {
        List<String> shown = shown();
        List<App> addable = new ArrayList<>();
        for (App a : apps.values()) if (!shown.contains(a.key)) addable.add(a);
        Collections.sort(addable, (x, y) -> x.label.compareToIgnoreCase(y.label));

        // The carousel shows the same apps as the grid, optionally most recently opened first.
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        boolean recentFirst = prefs.getBoolean(K_RECENT_FIRST, true);
        List<App> strip = new ArrayList<>();
        for (String k : shown) strip.add(apps.get(k));
        if (recentFirst) {
            // A stable sort: apps never opened keep their grid order, after the ones that were.
            Collections.sort(strip, (x, y) -> Long.compare(
                    prefs.getLong(K_OPENED + y.key, 0), prefs.getLong(K_OPENED + x.key, 0)));
        }

        StringBuilder sig = new StringBuilder(String.valueOf(movingKey)).append(recentFirst);
        for (String k : shown) sig.append('|').append(k);
        sig.append("||");
        for (App a : strip) sig.append('|').append(a.key);
        sig.append("||");
        for (App a : addable) sig.append('|').append(a.key);
        if (!force && sig.toString().equals(renderedSig)) return;
        renderedSig = sig.toString();

        View focus = getCurrentFocus();
        Object focusTag = movingKey != null ? movingKey : focus != null ? focus.getTag() : null;
        boolean focusWasInGrid = focus instanceof Cell;

        StringBuilder order = new StringBuilder();
        for (App a : strip) order.append(a.key).append('|');
        if (!order.toString().equals(stripSig)) {
            stripSig = order.toString();
            App centered = flow.selected();
            // Recent-first puts the app just used in the middle; otherwise stay where we were.
            String keep = recentFirst && !strip.isEmpty() ? strip.get(0).key
                    : centered != null ? centered.key : prefs.getString(K_LAST, null);
            flow.setApps(strip, keep);
        }
        if (strip.isEmpty()) nameLabel.setText("");

        favGrid.removeAllViews();
        for (String k : shown) {
            App a = apps.get(k);
            Cell c = new Cell(a, k, 0, null, false);
            c.setOnClickListener(v -> launch(a, c.card));
            c.setOnLongClickListener(v -> {
                showSheet(a);
                return true;
            });
            favGrid.addView(c);
        }
        Cell add = new Cell(null, TAG_ADD, Icon.PLUS, "Add app", true);
        add.setOnClickListener(v -> showPicker(true));
        favGrid.addView(add);
        Cell sort = new Cell(null, TAG_ORDER, Icon.SORT,
                recentFirst ? "Carousel: recent first" : "Carousel: this order", true);
        sort.setOnClickListener(v -> {
            prefs.edit().putBoolean(K_RECENT_FIRST, !recentFirst).apply();
            render(true);
            focusTag(favGrid, TAG_ORDER);
        });
        favGrid.addView(sort);

        pickGrid.removeAllViews();
        Cell store = new Cell(null, TAG_STORE, Icon.DOWNLOAD, "Get more apps", true);
        store.setOnClickListener(v -> {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(PLAY_STORE);
            if (i != null) start(i, store.card);
        });
        pickGrid.addView(store);
        for (App a : addable) {
            Cell c = new Cell(a, a.key, 0, null, true);
            c.setOnClickListener(v -> {
                List<String> next = shown();
                next.add(a.key);
                saveFavs(next);
                showPicker(false);
                render(true);
                focusTag(favGrid, a.key);
            });
            pickGrid.addView(c);
        }

        if (movingKey != null) {
            hintIsMove = true;
            showHint("Move with the arrows   ·   OK when done", true);
        } else if (hintIsMove) {
            hintIsMove = false;
            hideHint();
        }

        boolean pickerOpen = pickerLayer.getVisibility() == View.VISIBLE;
        if (pickerOpen || gridOpen) {
            GridLayout active = pickerOpen ? pickGrid : favGrid;
            boolean restored = focusTag != null && (focusWasInGrid || movingKey != null) && focusTag(active, focusTag);
            if (!restored && focusWasInGrid && active.getChildCount() > 0) active.getChildAt(0).requestFocus();
        } else if (firstRender || getCurrentFocus() == null) {
            flow.requestFocus();
        }
        if (firstRender) {
            rise(stage, 18, 460);
            coach();
        }
        firstRender = false;
    }

    private void showContinue(List<WatchNext.Item> items) {
        if (items.size() > MAX_CONTINUE) items = items.subList(0, MAX_CONTINUE);
        StringBuilder sig = new StringBuilder();
        for (WatchNext.Item it : items) sig.append(it.title).append(it.intentUri).append(it.positionMs).append('|');
        if (sig.toString().equals(continueSig)) return;
        continueSig = sig.toString();

        boolean hadFocus = getCurrentFocus() instanceof ContinueCard;
        continueRow.removeAllViews();
        for (WatchNext.Item it : items) continueRow.addView(new ContinueCard(it));
        continueSection.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        gridCue.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        continueRow.post(this::labelWholeCards);
        if (hadFocus) {
            if (continueRow.getChildCount() > 0) continueRow.getChildAt(0).requestFocus();
            else flow.requestFocus();
        }
    }

    private boolean focusTag(GridLayout g, Object tag) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (tag.equals(c.getTag())) return c.requestFocus();
        }
        return false;
    }

    /**
     * Whether the tile at index can move by step (-1/+1 along its row, -COLS/+COLS between rows).
     * A row's ends do not wrap, so a tile in the first or last column never needs an arrow in
     * the screen's edge margin.
     */
    private static boolean canMove(int index, int step, int last) {
        int to = index + step;
        if (to < 0 || to > last) return false;
        return Math.abs(step) != 1 || to / COLS == index / COLS;
    }

    private void moveBy(int step) {
        List<String> s = shown();
        int i = s.indexOf(movingKey);
        if (i < 0) {
            movingKey = null;
            render(true);
            return;
        }
        if (!canMove(i, step, s.size() - 1)) return; // the arrows only offer directions that exist
        int j = i + step;
        if (j == i) return;
        s.remove(i);
        s.add(j, movingKey);
        saveFavs(s);
        render(true);
    }

    // ---------------------------------------------------------------- hint

    private void showHint(String text, boolean stay) {
        ui.removeCallbacks(hideHintLater);
        hintPill.setText(text);
        hintShowing = true;
        syncNowCard();
        hintPill.animate().alpha(1f).setDuration(Ui.BASE).start();
        if (!stay) ui.postDelayed(hideHintLater, 6000);
    }

    private void hideHint() {
        ui.removeCallbacks(hideHintLater);
        hintPill.animate().alpha(0f).setDuration(Ui.FAST).start();
        hintShowing = false;
        syncNowCard();
    }

    private final Runnable hideHintLater = this::hideHint;

    /** Teaches the one hidden gesture for the first few visits, then stays out of the way. */
    private void coach() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        int seen = p.getInt(K_COACH, 0);
        if (seen >= 6 || movingKey != null) return;
        p.edit().putInt(K_COACH, seen + 1).apply();
        showHint("Hold OK on an app for options", false);
    }

    // ---------------------------------------------------------------- picker

    private void showPicker(boolean show) {
        if (show) {
            pickerLayer.setVisibility(View.VISIBLE);
            rise(pickerLayer, 24, Ui.BASE);
            homeLayer.setVisibility(View.INVISIBLE);
            pickScroll.scrollTo(0, 0);
            if (pickGrid.getChildCount() > 0) pickGrid.getChildAt(0).requestFocus();
        } else {
            pickerLayer.setVisibility(View.GONE);
            homeLayer.setVisibility(View.VISIBLE);
            rise(homeLayer, 0, Ui.BASE);
            if (!focusTag(favGrid, TAG_ADD)) flow.requestFocus();
        }
    }

    // ---------------------------------------------------------------- options sheet

    private void showSheet(App a) {
        sheetApp = a;
        sheetLayer.removeAllViews();
        float pad = 20, radius = Ui.CARD_RADIUS + pad; // concentric with the artwork inside it

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(Ui.shape(Ui.PANEL, radius, 0.5f, Ui.PANEL_LINE));
        panel.setPadding(Ui.dp(pad), Ui.dp(pad), Ui.dp(pad), Ui.dp(pad));

        FrameLayout art = new FrameLayout(this);
        Ui.clipRound(art, Ui.CARD_RADIUS);
        art.setBackgroundColor(a.isBanner ? Ui.SURFACE : Ui.tint(a.color, 0.16f));
        ImageView img = new ImageView(this);
        img.setImageDrawable(a.artCopy());
        if (a.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(img, match());
        } else {
            art.addView(img, new FrameLayout.LayoutParams(Ui.dp(72), Ui.dp(72), Gravity.CENTER));
        }
        art.addView(edgeView(), match());
        if (a.light) {
            View calm = new View(this); // a white banner would be the brightest thing on screen
            calm.setBackgroundColor(0x1F000000);
            art.addView(calm, match());
        }
        panel.addView(art, new LinearLayout.LayoutParams(Ui.dp(256), Ui.dp(144)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, a.label, 20, 600, Ui.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(6));
        col.addView(title);
        boolean pinned = shown().contains(a.key);
        View first = row(col, Icon.OPEN, "Open", () -> launch(a, null));
        if (pinned) {
            row(col, Icon.MOVE, "Move", () -> {
                if (!gridOpen) openGrid(a.key);
                movingKey = a.key;
                render(true);
            });
            row(col, Icon.REMOVE, "Remove from Home", () -> {
                List<String> s = shown();
                int i = s.indexOf(a.key);
                s.remove(a.key);
                saveFavs(s);
                render(true);
                if (gridOpen) {
                    favGrid.getChildAt(Math.min(Math.max(i, 0), favGrid.getChildCount() - 1)).requestFocus();
                } else {
                    flow.requestFocus();
                }
            });
        }
        row(col, Icon.INFO, "App info", () -> start(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)), null));
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(Ui.dp(252),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = Ui.dp(pad - 8);
        panel.addView(col, colLp);

        sheetLayer.addView(panel, wrap(Gravity.CENTER));
        homeLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sheetLayer.setVisibility(View.VISIBLE);
        sheetLayer.setAlpha(0f);
        sheetLayer.animate().alpha(1f).setDuration(Ui.FAST).start();
        panel.setScaleX(0.97f);
        panel.setScaleY(0.97f);
        panel.setTranslationY(Ui.dp(12));
        panel.animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(Ui.BASE).setInterpolator(Ui.OUT).start();
        first.requestFocus();
    }

    private View edgeView() {
        View edge = new View(this);
        edge.setBackground(new Edge(Ui.CARD_RADIUS));
        return edge;
    }

    /** One action in the options sheet. Runs after the sheet has closed. */
    private View row(LinearLayout into, int iconType, String label, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        r.setFocusable(true);
        r.setClickable(true);
        Icon icon = new Icon(iconType, Ui.TEXT_SECONDARY);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        TextView t = Ui.text(this, label, 14, 500, Ui.TEXT);
        t.setPadding(Ui.dp(12), 0, 0, 0);
        r.addView(t);
        r.setOnFocusChangeListener((v, focused) -> {
            r.setBackground(focused ? Ui.shape(Color.WHITE, 10, 0, 0) : null);
            t.setTextColor(focused ? Ui.ON_LIGHT : Ui.TEXT);
            icon.setColor(focused ? Ui.ON_LIGHT : Ui.TEXT_SECONDARY);
        });
        r.setOnClickListener(v -> {
            closeSheet();
            action.run();
        });
        into.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(32)));
        return r;
    }

    private void closeSheet() {
        if (sheetLayer.getVisibility() != View.VISIBLE) return;
        sheetLayer.setVisibility(View.GONE);
        sheetLayer.removeAllViews();
        homeLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (gridOpen && sheetApp != null) focusTag(favGrid, sheetApp.key);
        else flow.requestFocus();
        sheetApp = null;
    }

    // ---------------------------------------------------------------- actions

    private void launch(App a, View from) {
        getPreferences(MODE_PRIVATE).edit().putLong(K_OPENED + a.key, System.currentTimeMillis()).apply();
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(a.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i, from);
    }

    /** Opens a Watch Next entry through the deep link its app published. */
    private void resume(WatchNext.Item item, View from) {
        App resumed = appForPackage(item.pkg);
        if (resumed != null) {
            getPreferences(MODE_PRIVATE).edit().putLong(K_OPENED + resumed.key, System.currentTimeMillis()).apply();
        }
        try {
            if (item.intentUri == null) throw new java.net.URISyntaxException("", "sample entry");
            start(Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME), from);
        } catch (java.net.URISyntaxException e) {
            App owner = appForPackage(item.pkg);
            if (owner != null) launch(owner, from);
        }
    }

    private void openInputs() {
        Intent i = new Intent("com.android.tv.action.VIEW_INPUTS").setPackage("com.tcl.tv");
        if (i.resolveActivity(getPackageManager()) == null) i.setPackage(null);
        start(i, null);
    }

    /** Starts an activity, growing it out of the given view when there is one. */
    private void start(Intent i, View from) {
        try {
            Bundle opts = from == null ? null : ActivityOptions
                    .makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- network badge

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        setNetBadge(cm.getActiveNetwork() == null ? "No network" : null);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                boolean ok = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                ui.post(() -> setNetBadge(ok ? null : "No internet"));
            }

            @Override
            public void onLost(Network n) {
                ui.post(() -> setNetBadge("No network"));
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    private void setNetBadge(String text) {
        netText.setText(text);
        netBadge.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- layout params

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
    }

    private static LinearLayout.LayoutParams wrapLinear() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
