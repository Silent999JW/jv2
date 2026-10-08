package xyz.juicevault.studio;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Native half of JuiceVault Studio (port of the Python download_job / scan / export code).
 * Songs are written through MediaStore into Music/<folder>/..., so they show up in every
 * Android music player and no broad storage permission is needed to write.
 */
@CapacitorPlugin(
    name = "JuiceVault",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_MEDIA_AUDIO }, alias = "audio33"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "audioLegacy"),
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notif")
    }
)
public class JuiceVaultPlugin extends Plugin {

    static final String API = "https://api.juicevault.xyz";
    static final String UA = "JuiceVault-Studio/1.0";
    private static final Pattern ID_RE = Pattern.compile("^[0-9a-fA-F-]{8,40}$");
    private static final Pattern BAD_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f]");

    // ------------------------------------------------------------ job state (survives activity recreation)
    private static final Object LOCK = new Object();
    private static volatile boolean running = false;
    private static volatile boolean cancel = false;
    private static volatile int total, done, okCount, seq;
    private static volatile long curBytes, curTotal, bytesDone, bytesTotal;
    private static volatile String current = "", msg = "", stopped = null;
    private static final List<String> failed = new ArrayList<>();
    private static final List<String[]> okEntries = new ArrayList<>(); // {id, path}

    private static class Item {
        String id, folder, sub, name, title, artist, album;
        int year;
        long bytes;
    }

    private static class RateLimited extends Exception {
        final int retryAfter;
        RateLimited(int ra) { super("rate limited"); retryAfter = ra; }
    }

    private static class Cancelled extends Exception {
        Cancelled() { super("cancelled"); }
    }

    private static class HttpError extends Exception {
        HttpError(int code) { super("HTTP " + code); }
    }

    // ------------------------------------------------------------ permissions
    private String audioAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio33" : "audioLegacy";
    }

    @PluginMethod
    public void ensurePermission(PluginCall call) {
        String alias = audioAlias();
        boolean needAudio = getPermissionState(alias) != PermissionState.GRANTED;
        boolean needNotif = Build.VERSION.SDK_INT >= 33 && getPermissionState("notif") != PermissionState.GRANTED;
        if (!needAudio && !needNotif) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        List<String> aliases = new ArrayList<>();
        if (needAudio) aliases.add(alias);
        if (needNotif) aliases.add("notif");
        requestPermissionForAliases(aliases.toArray(new String[0]), call, "permResult");
    }

    @PermissionCallback
    private void permResult(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState(audioAlias()) == PermissionState.GRANTED);
        call.resolve(r);
    }

    // ------------------------------------------------------------ scan existing audio
    @PluginMethod
    public void scan(PluginCall call) {
        ContentResolver cr = getContext().getContentResolver();
        Uri uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] proj = {
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH
        };
        JSArray files = new JSArray();
        try (Cursor c = cr.query(uri, proj, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    if (name == null || !name.toLowerCase().endsWith(".mp3")) continue;
                    String rel = c.getString(2);
                    JSObject o = new JSObject();
                    o.put("name", name);
                    o.put("size", c.getLong(1));
                    o.put("path", (rel == null ? "" : rel) + name);
                    files.put(o);
                }
            }
        } catch (Exception e) {
            call.reject("Scan fehlgeschlagen: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("files", files);
        call.resolve(r);
    }

    // ------------------------------------------------------------ download queue
    @PluginMethod
    public void startDownload(PluginCall call) {
        if (running) {
            call.reject("Es laeuft bereits ein Download.");
            return;
        }
        JSArray arr = call.getArray("items");
        if (arr == null || arr.length() == 0) {
            call.reject("Nichts zu laden.");
            return;
        }
        final List<Item> items = new ArrayList<>();
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id", "");
                if (!ID_RE.matcher(it.id).matches()) continue;
                it.folder = o.optString("folder", "JuiceVault");
                it.sub = o.optString("sub", "");
                it.name = o.optString("name", "");
                it.title = o.optString("title", "");
                it.artist = o.optString("artist", "");
                it.album = o.optString("album", "");
                it.year = o.optInt("year", 0);
                it.bytes = o.optLong("bytes", 0);
                items.add(it);
            }
        } catch (Exception e) {
            call.reject("Ungueltige Anfrage");
            return;
        }
        if (items.isEmpty()) {
            call.reject("Nichts zu laden.");
            return;
        }

        long bt = 0;
        for (Item it : items) bt += it.bytes;
        synchronized (LOCK) {
            failed.clear();
            okEntries.clear();
            running = true;
            cancel = false;
            total = items.size();
            done = 0;
            okCount = 0;
            curBytes = 0;
            curTotal = 0;
            bytesDone = 0;
            bytesTotal = bt;
            current = "";
            msg = "";
            stopped = null;
            seq++;
        }

        final Context ctx = getContext().getApplicationContext();
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, KeepAliveService.class));
        } catch (Exception ignored) {
            // download still works while the app is in the foreground
        }
        Thread t = new Thread(() -> runJob(ctx, items), "jv-download");
        t.setDaemon(true);
        t.start();

        JSObject r = new JSObject();
        r.put("ok", true);
        call.resolve(r);
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        cancel = true;
        JSObject r = new JSObject();
        r.put("ok", true);
        call.resolve(r);
    }

    @PluginMethod
    public void progress(PluginCall call) {
        JSObject r = new JSObject();
        synchronized (LOCK) {
            r.put("running", running);
            r.put("total", total);
            r.put("done", done);
            r.put("ok", okCount);
            r.put("current", current);
            r.put("cur_bytes", curBytes);
            r.put("cur_total", curTotal);
            r.put("bytes_done", bytesDone);
            r.put("bytes_total", bytesTotal);
            r.put("msg", msg);
            r.put("stopped", stopped);
            r.put("seq", seq);
            JSArray f = new JSArray();
            for (String s : failed) f.put(s);
            r.put("failed", f);
            JSArray ok = new JSArray();
            for (String[] e : okEntries) {
                JSObject o = new JSObject();
                o.put("id", e[0]);
                o.put("path", e[1]);
                ok.put(o);
            }
            r.put("ok_entries", ok);
        }
        call.resolve(r);
    }

    private static void runJob(Context ctx, List<Item> items) {
        try {
            for (Item it : items) {
                if (cancel) {
                    stopped = "Abgebrochen.";
                    break;
                }
                current = it.artist + " - " + it.title;
                curBytes = 0;
                curTotal = it.bytes;
                while (true) {
                    try {
                        String path = saveSong(ctx, it);
                        synchronized (LOCK) {
                            okCount++;
                            okEntries.add(new String[] { it.id, path });
                        }
                        break;
                    } catch (Cancelled e) {
                        stopped = "Abgebrochen.";
                        break;
                    } catch (RateLimited e) {
                        if (e.retryAfter > 0 && e.retryAfter <= 120) {
                            current = "Rate-Limit - warte " + e.retryAfter + "s";
                            try {
                                sleepMs((e.retryAfter + 1) * 1000L);
                            } catch (Cancelled c) {
                                stopped = "Abgebrochen.";
                                break;
                            }
                            continue;
                        }
                        stopped = "Download-Limit erreicht (anonym 500/Tag). Spaeter erneut starten.";
                        break;
                    } catch (Exception e) {
                        synchronized (LOCK) {
                            failed.add(it.title + ": " + e.getMessage());
                        }
                        break;
                    }
                }
                done++;
                KeepAliveService.update(ctx, done + "/" + total + " - " + it.title, total, done);
                if (stopped != null) break;
                try {
                    sleepMs(150);
                } catch (Cancelled c) {
                    stopped = "Abgebrochen.";
                    break;
                }
            }
        } finally {
            String m = okCount + " geladen, " + failed.size() + " Fehler.";
            msg = (stopped != null ? stopped + " " : "") + m;
            running = false;
            try {
                ctx.stopService(new Intent(ctx, KeepAliveService.class));
            } catch (Exception ignored) { }
        }
    }

    private static void sleepMs(long ms) throws Cancelled {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancel) throw new Cancelled();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new Cancelled();
            }
        }
        if (cancel) throw new Cancelled();
    }

    private static HttpURLConnection open(String url) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (cancel) throw new Cancelled();
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(30000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code == 429) {
                    String ra = c.getHeaderField("Retry-After");
                    c.disconnect();
                    int sec = 0;
                    try {
                        if (ra != null) sec = Integer.parseInt(ra.trim());
                    } catch (NumberFormatException ignored) { }
                    throw new RateLimited(sec);
                }
                if (code >= 500 && code <= 504 && attempt < 2) {
                    c.disconnect();
                    sleepMs(2000L * (attempt + 1));
                    continue;
                }
                if (code != 200) {
                    c.disconnect();
                    throw new HttpError(code);
                }
                return c;
            } catch (RateLimited | Cancelled | HttpError e) {
                throw e;
            } catch (IOException e) {
                if (c != null) c.disconnect();
                if (attempt == 2) throw e;
                sleepMs(2000L * (attempt + 1));
            }
        }
        throw new IOException("Verbindung fehlgeschlagen");
    }

    private static String cleanSeg(String s) {
        s = BAD_CHARS.matcher(s == null ? "" : s).replaceAll("_");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.startsWith(".") || s.endsWith(".")) s = s.replaceAll("^\\.+|\\.+$", "").trim();
        return s;
    }

    private static String truncBytes(String s, int maxBytes) {
        while (s.getBytes(StandardCharsets.UTF_8).length > maxBytes && s.length() > 1) {
            s = s.substring(0, s.offsetByCodePoints(s.length(), -1));
        }
        return s.trim();
    }

    private static String relPath(Item it) {
        StringBuilder sb = new StringBuilder("Music/");
        String[] parts = (it.folder == null ? "" : it.folder).split("[/\\\\]");
        for (String p : parts) {
            String c = cleanSeg(p);
            if (!c.isEmpty()) sb.append(truncBytes(c, 100)).append('/');
        }
        String sub = cleanSeg(it.sub);
        if (!sub.isEmpty()) sb.append(truncBytes(sub, 100)).append('/');
        return sb.toString();
    }

    private static boolean exists(ContentResolver cr, Uri col, String rel, String fname) {
        try (Cursor c = cr.query(col, new String[] { MediaStore.MediaColumns._ID },
            MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            new String[] { rel, fname }, null)) {
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Downloads one song into MediaStore. Returns "<relative path><file name>". */
    private static String saveSong(Context ctx, Item it) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Uri col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String rel = relPath(it);

        String base = cleanSeg(it.name);
        if (base.isEmpty()) base = it.id.substring(0, 8);
        base = truncBytes(base, 200);
        String fname = base + ".mp3";
        if (exists(cr, col, rel, fname)) {
            fname = base + " [" + it.id.substring(0, 8) + "].mp3";
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (!it.title.isEmpty()) v.put(MediaStore.Audio.Media.TITLE, it.title);
        if (!it.artist.isEmpty()) v.put(MediaStore.Audio.Media.ARTIST, it.artist);
        if (!it.album.isEmpty()) v.put(MediaStore.Audio.Media.ALBUM, it.album);
        if (it.year > 0) v.put(MediaStore.Audio.Media.YEAR, it.year);

        Uri uri = cr.insert(col, v);
        if (uri == null) throw new IOException("MediaStore-Eintrag nicht moeglich");
        boolean ok = false;
        HttpURLConnection conn = null;
        try {
            conn = open(API + "/music/download/" + it.id);
            try (InputStream in = conn.getInputStream(); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Datei nicht beschreibbar");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel) throw new Cancelled();
                    out.write(buf, 0, n);
                    curBytes += n;
                    bytesDone += n;
                }
            }
            ContentValues fin = new ContentValues();
            fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, fin, null, null);
            ok = true;
            return rel + fname;
        } finally {
            if (conn != null) conn.disconnect();
            if (!ok) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) { }
            }
        }
    }

    // ------------------------------------------------------------ export (JSON / CSV) -> Download/JuiceVault Export
    @PluginMethod
    public void saveText(PluginCall call) {
        String name = cleanSeg(call.getString("name", "export.txt"));
        String mime = call.getString("mime", "text/plain");
        String text = call.getString("text", "");
        if (name.isEmpty()) name = "export.txt";
        ContentResolver cr = getContext().getContentResolver();
        Uri col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String rel = "Download/JuiceVault Export/";
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        boolean ok = false;
        try {
            uri = cr.insert(col, v);
            if (uri == null) throw new IOException("Datei nicht moeglich");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Datei nicht beschreibbar");
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            ContentValues fin = new ContentValues();
            fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, fin, null, null);
            ok = true;
            JSObject r = new JSObject();
            r.put("path", rel + name);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Export fehlgeschlagen: " + e.getMessage());
        } finally {
            if (!ok && uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) { }
            }
        }
    }
}
