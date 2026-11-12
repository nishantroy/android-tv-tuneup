package local.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * What the TV shows when nobody is using it: the time, the weather and whatever is playing,
 * over the wallpaper. The launcher fades to this after a while idle, and {@link AmbientDream}
 * shows the same thing as the system screensaver.
 */
final class AmbientView extends FrameLayout {
    private final LinearLayout weatherRow, card;
    private final Icon weatherIcon = new Icon(Icon.SUN, Ui.TEXT);
    private final TextView weatherText, title, detail;
    private final ImageView art;
    private final SkyView sky;
    private String shownKey;

    AmbientView(Context c, Backdrop backdrop, boolean hint) {
        super(c);

        // Weather over the picture, behind everything else.
        sky = new SkyView(c, backdrop);
        addView(sky, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        // The big numerals are pulled left so their ink, not their box, lines up with the date
        // above: at this size a digit starts about 2dp inside its box. The column carries the
        // pull as padding and does not clip to it, so the digits are never drawn outside
        // anything that would cut their edge off.
        int pull = Ui.dp(2);
        left.setPadding(pull, 0, 0, 0);
        left.setClipToPadding(false);
        TextClock date = clock(c, "EEEE, MMMM d", "EEEE, MMMM d", 13, 600, Ui.TEXT);
        date.setAllCaps(true);
        date.setLetterSpacing(0.18f);
        date.setMaxLines(1);
        // Letter-spaced text is under-measured by wrap_content, so the line gets a fixed width.
        left.addView(date, new LinearLayout.LayoutParams(Ui.dp(460), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout timeRow = new LinearLayout(c);
        timeRow.setGravity(Gravity.BOTTOM);
        TextClock time = clock(c, "h:mm", "H:mm", 132, 200, Ui.TEXT);
        time.setLetterSpacing(-0.04f);
        timeRow.addView(time);
        TextClock ampm = clock(c, "a", "", 22, 400, Ui.TEXT);
        ampm.setPadding(Ui.dp(10), 0, 0, Ui.dp(18));
        timeRow.addView(ampm);
        LinearLayout.LayoutParams timeLp = wrap();
        timeLp.topMargin = Ui.dp(4);
        timeLp.leftMargin = -pull;
        left.addView(timeRow, timeLp);

        weatherRow = new LinearLayout(c);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(weatherIcon);
        weatherRow.addView(icon, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        weatherText = Ui.text(c, "", 17, 400, Ui.TEXT);
        weatherText.setPadding(Ui.dp(10), 0, 0, 0);
        weatherRow.addView(weatherText);
        weatherRow.setVisibility(GONE);
        LinearLayout.LayoutParams weatherLp = wrap();
        weatherLp.topMargin = Ui.dp(6);
        left.addView(weatherRow, weatherLp);

        LayoutParams leftLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        leftLp.setMargins(Ui.dp(Ui.EDGE) - pull, 0, 0, Ui.dp(46));
        addView(left, leftLp);

        // What is playing, on glass, bottom right.
        card = new LinearLayout(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(18), Ui.dp(12));
        card.setBackground(new Glass(card, backdrop, 22));
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Ui.SURFACE);
        Ui.clipRound(art, 13);
        card.addView(art, new LinearLayout.LayoutParams(Ui.dp(78), Ui.dp(78)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = Ui.eyebrow(c, "Now playing");
        eyebrow.setTextSize(10.5f);
        eyebrow.setTextColor(Ui.TEXT);
        eyebrow.setAlpha(0.8f);
        words.addView(eyebrow);
        title = Ui.text(c, "", 17, 600, Ui.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, Ui.dp(6), 0, 0);
        words.addView(title);
        detail = Ui.text(c, "", 13.5f, 400, Ui.TEXT);
        detail.setAlpha(0.84f);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        detail.setPadding(0, Ui.dp(4), 0, 0);
        words.addView(detail);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(Ui.dp(206), ViewGroup.LayoutParams.WRAP_CONTENT);
        wordsLp.leftMargin = Ui.dp(15);
        card.addView(words, wordsLp);
        card.setVisibility(GONE);
        LayoutParams cardLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        cardLp.setMargins(0, 0, Ui.dp(Ui.EDGE), Ui.dp(48));
        addView(card, cardLp);

        if (hint) {
            TextView back = Ui.text(c, "Press any button to return", 12.5f, 500, Ui.TEXT);
            back.setAlpha(0.7f);
            LayoutParams backLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            backLp.topMargin = Ui.dp(34);
            addView(back, backLp);
        }
    }

    /** The effect setting changed. */
    void skyChanged() {
        sky.update();
    }

    void setWeather(Weather.Now now) {
        sky.setWeather(now);
        if (now == null) {
            weatherRow.setVisibility(GONE);
            return;
        }
        weatherIcon.setType(now.icon());
        StringBuilder s = new StringBuilder().append(now.temp).append("°   ").append(now.words());
        s.append("  ·  H ").append(now.high).append("°  L ").append(now.low).append('°');
        if (now.place != null && !now.place.isEmpty()) s.append("  ·  ").append(now.place);
        weatherText.setText(s);
        weatherRow.setVisibility(VISIBLE);
    }

    /** controller is null, or has nothing with a title, when there is nothing to show. */
    void setNowPlaying(MediaController controller, App owner) {
        MediaMetadata m = controller != null ? controller.getMetadata() : null;
        String name = m != null ? NowPlaying.title(m) : null;
        // Only while it is actually playing: a track left paused for hours is not "now playing".
        if (name == null || !NowPlaying.isPlaying(controller)) {
            card.setVisibility(GONE);
            return;
        }
        String artist = NowPlaying.artist(m);
        title.setText(name);
        detail.setText(artist != null ? artist : owner != null ? owner.label : "");
        detail.setVisibility(detail.getText().length() > 0 ? VISIBLE : GONE);
        String key = controller.getPackageName() + '|' + name + '|' + artist;
        if (!key.equals(shownKey)) {
            shownKey = key;
            Bitmap picture = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (picture == null) picture = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (picture == null) picture = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            String uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            art.setTag(null);
            if (picture != null) art.setImageBitmap(picture);
            else if (uri != null) Images.into(art, Uri.parse(uri), Ui.dp(78));
            else art.setImageDrawable(owner != null ? owner.artCopy() : null);
        }
        card.setVisibility(VISIBLE);
    }

    private static TextClock clock(Context c, String f12, String f24, float sp, int weight, int color) {
        TextClock t = new TextClock(c);
        t.setFormat12Hour(f12);
        t.setFormat24Hour(f24);
        t.setTextSize(sp);
        t.setTypeface(Ui.font(weight, sp >= 24));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
