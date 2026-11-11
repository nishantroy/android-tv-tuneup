package local.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the artwork that apps publish for their Watch Next entries. Each image is downloaded
 * once, kept on disk, and decoded at thumbnail size.
 */
final class Images {
    private Images() {}

    private static final int MAX_FILES = 80;
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    static void into(ImageView view, Uri uri, int targetWidthPx) {
        String key = uri.toString();
        view.setTag(key);
        Bitmap cached = memory.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        Context app = view.getContext().getApplicationContext();
        pool.execute(() -> {
            Bitmap b = fetch(app, uri, targetWidthPx);
            if (b == null) return;
            memory.put(key, b);
            view.post(() -> {
                if (!key.equals(view.getTag())) return; // the view was reused for something else
                view.setImageBitmap(b);
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(Ui.BASE).start();
            });
        });
    }

    private static Bitmap fetch(Context c, Uri uri, int targetWidthPx) {
        try {
            String scheme = uri.getScheme();
            File file;
            if ("http".equals(scheme) || "https".equals(scheme)) {
                File dir = new File(c.getCacheDir(), "art");
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                file = new File(dir, sha1(uri.toString()));
                if (!file.exists()) {
                    download(uri.toString(), file);
                    prune(dir);
                }
            } else {
                // content:// or android.resource:// artwork: copy it out so it can be decoded twice.
                file = File.createTempFile("art", null, c.getCacheDir());
                try (InputStream in = c.getContentResolver().openInputStream(uri);
                        OutputStream out = new FileOutputStream(file)) {
                    if (in == null) return null;
                    copy(in, out);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetWidthPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (!file.getParentFile().getName().equals("art")) file.delete();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static void download(String url, File into) throws Exception {
        File tmp = new File(into.getPath() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out);
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(into)) tmp.delete();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    /** Keeps the disk cache bounded by dropping the least recently written files. */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_FILES; i++) files[i].delete();
    }

    private static String sha1(String s) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
