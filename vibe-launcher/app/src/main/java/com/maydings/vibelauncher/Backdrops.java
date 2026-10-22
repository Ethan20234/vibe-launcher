package com.maydings.vibelauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Backdrop stills from the cards apps publish for the TV home screen (their channels and Watch Next
 * rows in the system TV provider), read with READ_TV_LISTINGS. A card's film or show is shown with a
 * clean full-size backdrop and the title's logo instead of the app's own art: from TMDB with a key,
 * from Stremio's free metadata without one. Otherwise only wide artwork is used, and low-res art
 * (Netflix's 853x480) becomes a 2x2 wall of four cards. YouTube publishes no cards, so its tile borrows
 * SmartTube's (the user's own feed), or without SmartTube shows popular videos via the YouTube Data API
 * key. Plex, with a Plex token, shows art from the user's own server, and HDHomeRun the shows on the
 * user's tuner now (HdHomeRun). Apps with none keep the colour glow.
 *
 * Each app has a pool of up to {@link #POOL_SIZE} stills, rebuilt in the background when it is six
 * hours old. They are stored already cut to the screen and darkened, so showing one is a quick decode
 * of a small file with no network: each visit to Home picks one at random.
 */
final class Backdrops {

    interface Callback {
        /**
         * The still for the app (null for none). For a film or show found by its title, also that title
         * and its logo (null when it has none, and then the title is shown as text).
         */
        void onBackdrop(String pkg, Bitmap bitmap, Bitmap logo, String title);
    }

    /** A still to make: one image or a wall of four, and for a known title its logo. */
    private static final class Choice {
        final List<File> files;
        final File logo;
        final String title;

        Choice(List<File> files, File logo, String title) {
            this.files = files;
            this.logo = logo;
            this.title = title;
        }
    }

    /** A finished still in an app's pool: screen-sized and darkened, with the title's logo if any. */
    static final class Entry {
        final File still, logo;
        final String title;

        Entry(File still, File logo, String title) {
            this.still = still;
            this.logo = logo;
            this.title = title;
        }
    }

    /** An app's pool, when it was built and for which keys and permission. */
    private static final class Pool {
        final long built;
        final String fingerprint;
        final List<Entry> entries;

        Pool(long built, String fingerprint, List<Entry> entries) {
            this.built = built;
            this.fingerprint = fingerprint;
            this.entries = entries;
        }
    }

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String[] COLUMNS = {"package_name", "intent_uri", "title", "poster_art_uri", "thumbnail_uri"};
    private static final int FIRST_IMAGE = 3;   // COLUMNS from here on are image URIs
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String PLEX = "com.plexapp.android";
    private static final String HDHOMERUN = "com.silicondust.view";
    private static final int GUIDE_WIDTH = 480;   // the HDHomeRun guide's images: only ever in a wall
    /** SmartTube builds (stable, beta, the old names): they publish the signed-in user's YouTube feed. */
    private static final String[] SMARTTUBE = SmartTube.PACKAGES;
    private static final int MIN_WIDTH = 800;   // Netflix publishes 853x480
    private static final float MIN_ASPECT = 1.45f, MAX_ASPECT = 2.4f;
    private static final int SHARP_WIDTH = 1280;  // narrower art goes into a 2x2 wall
    private static final int WALL = 4;
    private static final int WALL_GAP = 6;        // px between the wall's cards
    private static final int MAX_TRIES = 12;      // lookups and downloads for one still before giving up
    /**
     * Where a picture taller than the screen is cut: 0 keeps its top, 0.5 the middle. Faces sit in the
     * upper part of a poster, so cropping in the middle cut their heads off at the screen edge.
     */
    private static final float TOP_BIAS = 0.2f;
    private static final int POOL_SIZE = 8;
    private static final int POOL_TRIES = 40;     // lookups and downloads for a whole pool
    private static final int GUIDE_IMAGES = POOL_SIZE * WALL;  // most fetched to fill the pool with walls
    static final long POOL_MS = 6L * 60 * 60 * 1000;
    /**
     * How stills are stored: lossless, since a second lossy save (JPEG) showed in the darkened gradients.
     * Part of the pool's fingerprint, so changing it rebuilds every pool.
     */
    private static final String STILL_FORMAT = "png";
    private static final long EMPTY_RETRY_MS = 2L * 60 * 1000;   // an empty pool (offline?) is retried sooner
    private static final long RETRY_MS = 10L * 60 * 1000;        // after a build that wasn't kept
    private static final long SHRINK_MS = 24L * 60 * 60 * 1000;  // a smaller pool is accepted after a day
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final long CACHE_MS = 2L * 24 * 60 * 60 * 1000;   // downloads, reused by the next rebuilds

    private final Context context;
    private final VibeSettings settings;
    private final ContentResolver resolver;
    private final Tmdb tmdb;
    private final Stremio stremio;
    private final Popular popular;
    private final YouTubePopular youtubePopular;
    private final Plex plex;
    private final HdHomeRun hdHomeRun;
    private final File root, poolRoot;
    /** Showing a still: the one on screen goes to the front, ahead of anything else waiting. */
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue);
    /** Building pools, at low priority so it never slows down what's on screen. */
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BackdropPools");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pool> pools = new HashMap<>();
    private final Map<String, Entry> shown = new HashMap<>();   // this visit's still per app
    private final Map<String, Entry> before = new HashMap<>();  // the last visit's, not repeated next
    private final Set<String> building = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> attempted = Collections.synchronizedMap(new HashMap<>());   // builds not kept
    private final Set<String> unsuitable = Collections.synchronizedSet(new HashSet<>());
    private final Random random = new Random();
    private final int targetWidth, targetHeight;

    Backdrops(Context context) {
        this.context = context.getApplicationContext();
        settings = new VibeSettings(context);
        resolver = context.getContentResolver();
        tmdb = new Tmdb(context);
        stremio = new Stremio(context);
        popular = new Popular(context);
        youtubePopular = new YouTubePopular(context);
        root = new File(context.getCacheDir(), "cards");
        poolRoot = new File(context.getCacheDir(), "pools");
        targetWidth = context.getResources().getDisplayMetrics().widthPixels;
        targetHeight = context.getResources().getDisplayMetrics().heightPixels;
        plex = new Plex(context, targetWidth, targetHeight);
        hdHomeRun = new HdHomeRun(context);
        worker.prestartCoreThread();   // waiting on the queue, so jobs can go straight to its front
        builder.execute(this::prune);
    }

    /**
     * Home is gone (recreated for a new tiles-per-row choice or language): stop this instance's threads,
     * or each recreation leaves two behind and the old and new one rebuild the same pools at once. A pool
     * being built finishes first; the new instance picks it up from disk.
     */
    void close() {
        worker.shutdownNow();
        builder.shutdown();
    }

    /** Pick a fresh still per app next time each is shown (called when Home comes back). */
    synchronized void reshuffle() {
        before.putAll(shown);
        shown.clear();
        // Pools replaced since the last visit can go now: nothing from them is picked any more. And the
        // old downloads: the launcher can run for weeks, so starting up isn't often enough.
        try {
            builder.execute(() -> {
                dropReplaced();
                prune();
            });
        } catch (RejectedExecutionException closed) {
            // Home was rebuilt: the new instance does this.
        }
    }

    /** Rebuilds the app's pool in the background if it is due (six hours old, or the keys changed). */
    void refresh(String pkg) {
        if (!due(pkg) || !building.add(pkg)) return;
        try {
            builder.execute(() -> {
                try {
                    build(pkg);
                } catch (RuntimeException | OutOfMemoryError e) {
                    // Keep the old pool; the next refresh tries again.
                } finally {
                    building.remove(pkg);
                }
            });
        } catch (RejectedExecutionException closed) {
            // Closed (Home was rebuilt), maybe from a load still finishing: the new instance builds it.
            building.remove(pkg);
        }
    }

    /** How long until the first of these apps' pools is due; 0 when one already is. */
    long untilDue(List<String> pkgs) {
        long next = POOL_MS;
        long now = System.currentTimeMillis();
        for (String pkg : pkgs) {
            if (building.contains(pkg)) continue;   // rebuilt from now on
            next = Math.min(next, dueAt(pkg) - now);
        }
        return Math.max(0, next);
    }

    /** Delivers this visit's still for the app on the main thread, or null when it has none. */
    void load(String pkg, Callback callback) {
        queue.offerFirst(() -> {
            Bitmap bitmap = null, logo = null;
            String title = null;
            try {
                Entry e = choose(pkg);
                if (e != null) {
                    bitmap = BitmapFactory.decodeFile(e.still.getPath());
                    logo = e.logo != null ? decodeLogo(e.logo) : null;
                    title = e.title;
                } else {
                    // No pool yet (first run, or offline until now): make one still the old way.
                    List<Choice> one = collect(pkg, 1, MAX_TRIES);
                    if (!one.isEmpty()) {
                        Choice c = one.get(0);
                        bitmap = render(c);
                        logo = c.logo != null ? decodeLogo(c.logo) : null;
                        title = c.title;
                    }
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                // A bad image or cache entry, or no memory left on a small TV: the colour glow instead
                // of taking the launcher down (an error here would crash the whole app).
                bitmap = null;
                logo = null;
                title = null;
            }
            Bitmap b = bitmap, l = logo;
            String t = title;
            main.post(() -> callback.onBackdrop(pkg, b, l, t));
            refresh(pkg);
        });   // ahead of anything else: this is the one on screen
    }

    /** This visit's still from the app's pool: kept for the visit, and not the one shown last time. */
    private synchronized Entry choose(String pkg) {
        Entry e = shown.get(pkg);
        if (e != null && e.still.exists()) return e;
        Pool p = pool(pkg);
        if (p == null || p.entries.isEmpty()) return null;
        List<Entry> options = new ArrayList<>(p.entries);
        Entry last = before.get(pkg);
        if (options.size() > 1 && last != null) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).still.equals(last.still)) {
                    options.remove(i);
                    break;
                }
            }
        }
        e = options.get(random.nextInt(options.size()));
        shown.put(pkg, e);
        return e;
    }

    // ---- pools ----------------------------------------------------------------------------------------

    /** pools/<app>/ holds one folder per build and current.txt, naming the build in use. */
    private File poolDir(String pkg) {
        return new File(poolRoot, sha1(pkg));
    }

    /** The keys and the permission a pool was built with: changing any of them rebuilds it. */
    private String fingerprint() {
        boolean cards = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
        return sha1(settings.tmdbKey() + "|" + settings.youtubeKey() + "|" + settings.plexToken() + "|" + cards
                + "|" + STILL_FORMAT + "|" + TOP_BIAS + "|2");   // the scrim is baked in too: bump to rebuild
    }

    /** When the app's pool should next be rebuilt (in the past when it's due now). */
    private long dueAt(String pkg) {
        Pool p = pool(pkg);
        long at = p == null || !p.fingerprint.equals(fingerprint()) ? 0
                : p.built + (p.entries.isEmpty() ? EMPTY_RETRY_MS : POOL_MS);
        Long tried = attempted.get(pkg);
        return tried != null ? Math.max(at, tried + RETRY_MS) : at;
    }

    private boolean due(String pkg) {
        return System.currentTimeMillis() >= dueAt(pkg);
    }

    /** The app's pool in use, read from disk the first time; null before one was built. */
    private synchronized Pool pool(String pkg) {
        if (pools.containsKey(pkg)) return pools.get(pkg);
        Pool p = null;
        try {
            String current = readText(new File(poolDir(pkg), "current.txt")).trim();
            p = readPool(new File(poolDir(pkg), current));
        } catch (IOException | RuntimeException e) {
            // None yet, or unreadable: built afresh.
        }
        pools.put(pkg, p);
        return p;
    }

    private static Pool readPool(File gen) throws IOException {
        String[] lines = readText(new File(gen, "index.txt")).split("\n");
        String[] head = lines[0].split("\t", -1);
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length < 3 || f[0].isEmpty()) continue;
            entries.add(new Entry(new File(gen, f[0]), f[1].isEmpty() ? null : new File(gen, f[1]),
                    f[2].isEmpty() ? null : f[2]));
        }
        return new Pool(Long.parseLong(head[0]), head[1], entries);
    }

    private static String readText(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return new String(Http.readAll(in, 64 * 1024), StandardCharsets.UTF_8);
        }
    }

    /**
     * Makes up to POOL_SIZE stills for the app in a folder of their own, and switches to them only once
     * the new pool is as full as the one in use (or that one is over a day old): until then the old
     * pool keeps serving, so there is always a picture rather than the colour glow. The replaced pool is
     * deleted on the next visit to Home, see {@link #dropReplaced}.
     */
    private void build(String pkg) {
        long built = System.currentTimeMillis();
        String fingerprint = fingerprint();
        List<Choice> choices = collect(pkg, POOL_SIZE, POOL_TRIES);
        File gen = new File(poolDir(pkg), String.valueOf(built));
        gen.mkdirs();
        StringBuilder index = new StringBuilder().append(built).append('\t').append(fingerprint).append('\n');
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            Choice c = choices.get(i);
            try {
                Bitmap b = render(c);
                if (b == null) continue;
                File still = new File(gen, i + ".png");
                b.setHasAlpha(false);   // opaque: stored as RGB, a quarter smaller than RGBA
                try (FileOutputStream out = new FileOutputStream(still)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                } finally {
                    b.recycle();
                }
                File logo = null;
                if (c.logo != null) {
                    logo = new File(gen, i + ".logo");
                    copy(c.logo, logo);
                }
                String title = c.title == null ? "" : c.title.replace('\t', ' ').replace('\n', ' ');
                index.append(still.getName()).append('\t').append(logo == null ? "" : logo.getName())
                        .append('\t').append(title).append('\n');
                entries.add(new Entry(still, logo, c.title));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // Leave this one out.
            }
        }
        Pool old = pool(pkg);
        boolean keys = old == null || !old.fingerprint.equals(fingerprint);   // new keys: the old pool is wrong
        int want = old == null || keys ? 1 : Math.max(1, Math.min(POOL_SIZE, old.entries.size()));
        boolean stale = old != null && built - old.built > SHRINK_MS;
        if (entries.size() < want && !(stale && !entries.isEmpty()) && !keys) {
            deleteTree(gen);   // not as good as what's in use: keep that, try again later
            attempted.put(pkg, built);
            return;
        }
        try {
            try (FileOutputStream out = new FileOutputStream(new File(gen, "index.txt"))) {
                out.write(index.toString().getBytes(StandardCharsets.UTF_8));
            }
            File tmp = new File(poolDir(pkg), "current.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(String.valueOf(built).getBytes(StandardCharsets.UTF_8));
            }
            synchronized (this) {
                if (!tmp.renameTo(new File(poolDir(pkg), "current.txt"))) throw new IOException("rename");
                pools.put(pkg, new Pool(built, fingerprint, entries));
            }
            attempted.remove(pkg);
        } catch (IOException e) {
            deleteTree(gen);
            attempted.put(pkg, built);
        }
    }

    /**
     * Every still in the pools in use, for the screensaver: films and shows, so not the video sites'
     * thumbnails. Reads files only; call off the main thread.
     */
    static List<Entry> screensaverStills(Context context) {
        Set<String> videoSites = new HashSet<>();
        videoSites.add(sha1(YOUTUBE));
        videoSites.add(sha1(YOUTUBE + "kids"));   // com.google.android.youtube.tvkids
        for (String s : SMARTTUBE) videoSites.add(sha1(s));
        List<Entry> stills = new ArrayList<>();
        File[] apps = new File(context.getCacheDir(), "pools").listFiles();
        if (apps == null) return stills;
        for (File app : apps) {
            if (videoSites.contains(app.getName())) continue;
            try {
                String current = readText(new File(app, "current.txt")).trim();
                for (Entry e : readPool(new File(app, current)).entries) if (e.still.exists()) stills.add(e);
            } catch (IOException | RuntimeException e) {
                // No pool for this app yet.
            }
        }
        return stills;
    }

    /** Deletes every app's builds other than the one in use. Runs on the builder thread. */
    private void dropReplaced() {
        File[] apps = poolRoot.listFiles();
        if (apps == null) return;
        for (File app : apps) {
            String current;
            try {
                current = readText(new File(app, "current.txt")).trim();
            } catch (IOException e) {
                continue;
            }
            File[] gens = app.listFiles();
            if (gens == null) continue;
            for (File g : gens) if (g.isDirectory() && !g.getName().equals(current)) deleteTree(g);
        }
    }

    private static void deleteTree(File f) {
        File[] inside = f.listFiles();
        if (inside != null) for (File c : inside) deleteTree(c);
        f.delete();
    }

    private static void copy(File from, File to) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            out.write(Http.readAll(in, MAX_IMAGE_BYTES));
        }
    }

    /** The finished still for a choice: cut to the screen and darkened. Null if it can't be decoded. */
    private Bitmap render(Choice c) {
        Bitmap src = c.files.size() == 1 ? decode(c.files.get(0)) : wall(c.files);
        if (src == null) return null;
        Bitmap out = src;
        if (src.getWidth() != targetWidth || src.getHeight() != targetHeight) {
            out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src,
                    centreCrop(src.getWidth(), src.getHeight(), targetWidth, targetHeight, TOP_BIAS),
                    new Rect(0, 0, targetWidth, targetHeight), new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
        }
        darken(out);
        return out;
    }

    /**
     * One card from the provider: its title, whether its art carries a sticker, and its images. Images
     * narrower than minWidth aren't used.
     */
    private static final class Card {
        final String title;
        final boolean badged;
        final List<String> images = new ArrayList<>();
        int minWidth = MIN_WIDTH;
        String logo;   // a title's own logo, when the source already knows it (see Popular)

        Card(String title, boolean badged) {
            this.title = title;
            this.badged = badged;
        }
    }

    /**
     * Up to max stills to make, in the order found: clean backdrops for the cards' titles and the
     * cards' own sharp, unbadged art. Only when there are none, walls of four low-res cards (which may
     * be badged: the sticker doesn't change the card's size), or else one low-res card on its own.
     * HDHomeRun is the exception: what's on often has few titles to look up (news, talk shows), so its
     * sharp stills are topped up to max with walls of the guide's small images, which aren't counted
     * as tries.
     */
    private List<Choice> collect(String pkg, int max, int maxTries) {
        List<Card> cards = cards(pkg);
        // Films and shows by title: TMDB with a key, Stremio without. Not for video sites' video names.
        TitleArt titleArt = isVideoSite(pkg) ? null : tmdb.enabled() ? tmdb : stremio;
        List<Choice> sharp = new ArrayList<>(), walls = new ArrayList<>();
        List<File> wall = new ArrayList<>();
        File plain = null;   // first unbadged low-res card, the fallback when there aren't enough for a wall
        Set<String> used = new HashSet<>();
        int tries = 0, next = 0;
        for (Card card : cards) {
            if (sharp.size() >= max || tries >= maxTries) break;
            next++;
            // A card that already names its own logo (Popular's list) needs no title lookup: the source
            // shipped the artwork and the logo together.
            if (card.logo != null) {
                for (String url : card.images) {
                    if (unsuitable.contains(url) || !used.add(url)) continue;
                    if (tries++ >= maxTries) break;
                    File file = fetch(url, card.minWidth);
                    if (file == null) continue;
                    File logo = unsuitable.contains(card.logo) ? null : fetch(card.logo, false);
                    sharp.add(new Choice(Collections.singletonList(file), logo, null));
                    break;
                }
                continue;
            }
            if (titleArt != null && card.title != null) {
                tries++;
                TitleArt.Art art = titleArt.lookup(card.title);
                if (art != null && used.add(art.backdrop) && !unsuitable.contains(art.backdrop)) {
                    File file = fetch(art.backdrop, true);
                    if (file != null) {
                        File logo = art.logo != null && !unsuitable.contains(art.logo) ? fetch(art.logo, false) : null;
                        sharp.add(new Choice(Collections.singletonList(file), logo, card.title));
                        continue;
                    }
                }
            }
            for (String url : card.images) {
                if (unsuitable.contains(url) || !used.add(url)) continue;
                if (tries++ >= maxTries) break;
                File file = fetch(url, card.minWidth);
                if (file == null) continue;
                if (width(file) >= SHARP_WIDTH) {
                    if (!card.badged) sharp.add(new Choice(Collections.singletonList(file), null, null));
                } else {
                    if (plain == null && !card.badged) plain = file;
                    wall.add(file);
                    if (wall.size() == WALL) {
                        walls.add(new Choice(new ArrayList<>(wall), null, null));
                        wall.clear();
                    }
                }
                break;   // one image per card
            }
        }
        if (pkg.equals(HDHOMERUN)) {
            // The rest of the shows, by their guide images alone, until the walls fill the pool.
            int images = 0;
            for (int i = next; i < cards.size() && sharp.size() + walls.size() < max && images < GUIDE_IMAGES; i++) {
                String url = cards.get(i).images.get(0);
                if (unsuitable.contains(url) || !used.add(url)) continue;
                images++;
                File file = fetch(url, GUIDE_WIDTH);
                if (file == null) continue;
                wall.add(file);
                if (wall.size() == WALL) {
                    walls.add(new Choice(new ArrayList<>(wall), null, null));
                    wall.clear();
                }
            }
            // Never one small guide image stretched over the screen: too few for a wall leaves the
            // colour glow.
            List<Choice> all = new ArrayList<>(sharp);
            all.addAll(walls.subList(0, Math.min(walls.size(), max - Math.min(max, sharp.size()))));
            return all;
        }
        if (!sharp.isEmpty()) return sharp;
        if (!walls.isEmpty()) return walls.subList(0, Math.min(max, walls.size()));
        return plain != null ? Collections.singletonList(new Choice(Collections.singletonList(plain), null, null))
                : Collections.emptyList();
    }

    /**
     * The app's home screen cards, unbadged ones first, each group shuffled. Empty without the
     * permission or any cards.
     */
    private List<Card> cards(String pkg) {
        if (pkg.equals(HDHOMERUN)) {
            // What's on the user's tuner now: each title looked up for a sharp backdrop, else the guide's image.
            List<Card> cards = new ArrayList<>();
            for (HdHomeRun.Show show : hdHomeRun.onNow()) {
                Card card = new Card(show.title, false);
                card.images.add(show.image);
                card.minWidth = GUIDE_WIDTH;
                cards.add(card);
            }
            Collections.shuffle(cards, random);
            return cards;
        }
        if (pkg.equals(PLEX)) {
            // The server's own art; Plex's old cards point at a provider the new app no longer has.
            List<Card> cards = new ArrayList<>();
            for (String url : plex.backdrops()) {
                Card card = new Card(null, false);
                card.images.add(url);
                cards.add(card);
            }
            if (!cards.isEmpty()) {
                Collections.shuffle(cards, random);
                return cards;
            }
        }
        Set<String> sources = new HashSet<>(Collections.singletonList(pkg));
        if (pkg.equals(YOUTUBE)) Collections.addAll(sources, SMARTTUBE);
        List<Card> clean = new ArrayList<>(), badged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Uri table : new Uri[]{WATCH_NEXT, PREVIEW}) {
            // Filtering by package in the query isn't allowed without system permissions; read all.
            try (Cursor c = resolver.query(table, COLUMNS, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (!sources.contains(c.getString(0))) continue;
                    Card card = new Card(c.getString(2), badged(c.getString(1)));
                    for (int i = FIRST_IMAGE; i < COLUMNS.length; i++) {
                        String url = c.getString(i);
                        if (url == null || url.isEmpty() || !seen.add(url)) continue;
                        String sharper = youtubeMaxRes(url);
                        if (sharper != null && seen.add(sharper)) card.images.add(sharper);
                        card.images.add(url);
                    }
                    if (!card.images.isEmpty()) (card.badged ? badged : clean).add(card);
                }
            } catch (RuntimeException e) {
                // No permission, or the provider is missing (not a TV): no stills.
            }
        }
        if (pkg.equals(YOUTUBE) && clean.isEmpty()) {
            // No SmartTube cards: popular videos instead (with the YouTube key), else films' trailers.
            for (String id : youtubePopular.videoIds()) {
                Card card = new Card(null, false);
                card.images.add("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg");
                card.images.add("https://i.ytimg.com/vi/" + id + "/hq720.jpg");
                clean.add(card);
            }
        }
        Collections.shuffle(clean, random);
        Collections.shuffle(badged, random);
        clean.addAll(badged);
        if (clean.isEmpty()) {
            // This app publishes no TV provider cards (Emby, Jellyfin, Kodi and most other media apps
            // on a Hisense or Xiaomi TV), so the provider has nothing to turn into a still. The top
            // films and shows stand in: their own backdrops and logos, which the caller cuts and
            // darkens exactly as it would a card's art. Without them the tile keeps the colour glow.
            List<Popular.Entry> list = new ArrayList<>(popular.entries());
            if (!list.isEmpty()) {
                // Shuffled with a seed of the pool period, so each rebuild brings a different list
                // rather than the same pictures for another six hours.
                long period = System.currentTimeMillis() / POOL_MS;
                Collections.shuffle(list, new Random(period));
                // Each app takes its own block of the list, not a random sample of it: a random sample
                // of eight out of ninety-nine still shared a title with the next app about every time,
                // and two tiles showing the same film read as a bug. Blocks are disjoint until the
                // apps outnumber them, and then they start over.
                int blocks = Math.max(1, list.size() / POOL_SIZE);
                int block = Math.floorMod(appIndex(pkg), blocks);
                int from = block * POOL_SIZE;
                for (int i = 0; i < Math.min(POOL_SIZE, list.size()); i++) {
                    Popular.Entry e = list.get((from + i) % list.size());
                    Card card = new Card(null, false);
                    card.images.add(e.backdrop);
                    card.logo = e.logo;
                    clean.add(card);
                }
            }
        }
        return clean;
    }

    /** The app's place in the sorted list of tiles, so each app can claim a block of the popular list. */
    private int appIndex(String pkg) {
        List<String> apps = new ArrayList<>(AppAdapter.tilePackages(context));
        Collections.sort(apps);
        int at = apps.indexOf(pkg);
        return at < 0 ? Math.abs(pkg.hashCode()) : at;
    }

    /** Video sites: their card titles are video names, not films or shows to look up on TMDB. */
    private static boolean isVideoSite(String pkg) {
        if (pkg.startsWith(YOUTUBE)) return true;   // also YouTube Kids (com.google.android.youtube.tvkids)
        for (String s : SMARTTUBE) if (s.equals(pkg)) return true;
        return false;
    }

    /**
     * YouTube thumbnails come in several sizes under one video ID; the largest, maxresdefault, is
     * 1280x720 when the uploader provided one (a 404 otherwise, and the card's own size is used).
     */
    private static String youtubeMaxRes(String url) {
        if (!url.startsWith("https://i.ytimg.com/vi/")) return null;
        int end = url.indexOf('/', "https://i.ytimg.com/vi/".length());
        return end < 0 ? null : url.substring(0, end) + "/maxresdefault.jpg";
    }

    /**
     * Netflix bakes stickers such as "Recently Added" and "Top 10" into the artwork of some cards, and
     * names them in the card's link (badges=B:recently.added). Those don't work as a full-screen still.
     */
    private static boolean badged(String intentUri) {
        return intentUri != null && (intentUri.contains("badges%3D") || intentUri.contains("badges="));
    }

    /**
     * The cached file for this image, downloading it first. Null if it can't be had, or for a backdrop
     * if it isn't wide and big enough.
     */
    private File fetch(String url, boolean backdrop) {
        return fetch(url, backdrop ? MIN_WIDTH : 0);
    }

    /** As above, for a backdrop at least minWidth wide; 0 for any decodable image (a logo). */
    private File fetch(String url, int minWidth) {
        boolean backdrop = minWidth > 0;
        File file = new File(root, sha1(url) + ".img");
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        byte[] data = read(url);
        if (data == null || !(backdrop ? suitable(data, minWidth) : decodable(data))) {
            unsuitable.add(url);
            return null;
        }
        root.mkdirs();
        File tmp = null;
        try {
            tmp = File.createTempFile("download", ".tmp", root);   // unique: both threads may fetch one image
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
        } catch (IOException e) {
            if (tmp != null) tmp.delete();
            return null;
        }
        return tmp.renameTo(file) || file.exists() ? file : null;
    }

    /** A file name for a URL: a 32-bit hash could give two images the same name and show the wrong one. */
    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** The image's bytes, or null when it can't be had (offline, gone, too big). */
    private byte[] read(String url) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) return Http.get(url, MAX_IMAGE_BYTES);
            // content:// and android.resource:// art served by the app itself.
            try (InputStream in = resolver.openInputStream(Uri.parse(url))) {
                return in == null ? null : Http.readAll(in, MAX_IMAGE_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean decodable(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    private static boolean suitable(byte[] data, int minWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth < minWidth || o.outHeight <= 0) return false;
        float aspect = o.outWidth / (float) o.outHeight;
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT;
    }

    /**
     * Decoded no larger than the screen, in full colour: 16-bit colour (half the memory) turned dark,
     * smooth areas into visible bands.
     */
    private Bitmap decode(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= targetWidth) o.inSampleSize *= 2;
        o.inMutable = true;   // darkened in place
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    /**
     * Darkens a still behind the clock and the dock for legibility, with a gradient that leaves the
     * middle of the picture nearly untouched. Baked into the image once, here on the worker thread,
     * instead of a full-screen layer the GPU would paint on every frame.
     */
    private static void darken(Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        Paint scrim = new Paint();
        // The top stop is light: the top bar's FrostView already darkens the strip above the clock, and
        // a heavy scrim here on top of that turned the faces at the top of a poster into silhouettes.
        scrim.setShader(new LinearGradient(0, 0, 0, bitmap.getHeight(),
                new int[]{0x33000000, 0x40000000, 0xF2000000}, new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), scrim);
    }

    /** A title logo, kept transparent and no wider than 800 px (it shows at about a third of the screen). */
    static Bitmap decodeLogo(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 800) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private static int width(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        return o.outWidth;
    }

    /** Four cards in a 2x2 grid filling the screen, each centre-cropped into its cell. */
    private Bitmap wall(List<File> files) {
        Bitmap out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int cellW = (targetWidth - WALL_GAP) / 2, cellH = (targetHeight - WALL_GAP) / 2;
        for (int i = 0; i < files.size(); i++) {
            Bitmap card = BitmapFactory.decodeFile(files.get(i).getPath());
            if (card == null) continue;
            int left = (i % 2) * (cellW + WALL_GAP), top = (i / 2) * (cellH + WALL_GAP);
            canvas.drawBitmap(card, centreCrop(card.getWidth(), card.getHeight(), cellW, cellH, 0.5f),
                    new Rect(left, top, left + cellW, top + cellH), paint);
            card.recycle();
        }
        return out;
    }

    /**
     * The part of a w x h image with the cell's aspect ratio. bias 0 keeps the top of the picture, 0.5
     * the middle, 1 the bottom. Posters are cropped at {@link #TOP_BIAS} rather than in the middle:
     * centred, a 2:3 poster loses the top of the frame, which is where faces are, so the people on it
     * came out with their heads cut off by the screen edge.
     */
    private static Rect centreCrop(int w, int h, int cellW, int cellH, float bias) {
        if (w * cellH > h * cellW) {
            int cw = h * cellW / cellH;
            return new Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = w * cellH / cellW;
        int top = Math.round((h - ch) * bias);
        return new Rect(0, top, w, top + ch);
    }

    /**
     * Drops downloads not used for two days: the pools keep their own finished copies, so no picture is
     * lost. A pool is never dropped for its age, only once a newer one replaced it (dropReplaced), so
     * there is always a picture to show. The exception: the pool of an app that can't be a tile any more,
     * because it was uninstalled, disabled or hidden. Brought back, it has no pool and gets a fresh one.
     */
    private void prune() {
        long now = System.currentTimeMillis();
        File[] files = root.listFiles();
        if (files != null) for (File f : files) if (now - f.lastModified() > CACHE_MS) f.delete();
        File[] dirs = poolRoot.listFiles();
        if (dirs == null) return;
        Set<String> apps = AppAdapter.tilePackages(context);
        if (apps.isEmpty()) return;   // the app list unreadable (early in boot?): never take that as "none"
        Set<String> keep = new HashSet<>();
        for (String pkg : apps) keep.add(sha1(pkg));   // pools are named by hash
        boolean dropped = false;
        for (File d : dirs) {
            if (keep.contains(d.getName())) continue;
            deleteTree(d);
            dropped = true;
        }
        // Forget the dropped pools too, or a cached one would still be served: read from disk again,
        // an app that comes back starts with none.
        if (dropped) {
            synchronized (this) {
                pools.clear();
            }
        }
    }
}
