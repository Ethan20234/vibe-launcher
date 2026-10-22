package com.maydings.vibelauncher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock. The order is the one the
 * user arranged with Move (saved in preferences); before that, the dock_apps favourites then alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long-press OK on a tile. */
        void onTileLongPress(int position, View card);

        /** A tile in move mode changed position; it keeps focus. */
        void onTileMoved(int position);

        /** OK on a tile. Goes through the listener so the app lock can stop it first. */
        void onTileOpen(ComponentName component, String label);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** The icon with the transparent margin most system apps ship cropped off, made on first use. */
        Drawable trimmed;
        /** The tile's plate, tinted with the icon's own dominant colour (Aerial's trick), made on first use. */
        Drawable plate;
        /** True when {@link #plate} is light enough that the dock's in-tile name needs dark text. */
        boolean plateIsLight;
        /** The plate's colour; 0 until it has been sampled from the icon. */
        int plateColor;
        /** The plate's corner radius the cached plate was built with. */
        float plateRadius;
        /** True when the artwork reaches every edge of its own bounds: drawn inset, like 酷9's logo. */
        boolean bleed;
        /** The fixed last tile that opens the system Apps screen. */
        final boolean allApps;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(label, component, banner, icon, false);
        }

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, boolean allApps) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.allApps = allApps;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    private static final String PREFS = "tiles";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final Object PAYLOAD_STATE = new Object();

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final SharedPreferences prefs;
    /** Position of the tile being moved, or -1. */
    private int moving = -1;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void reload() {
        // A tile part-way through Move (an app updating in the background reloads the list) keeps where it
        // got to: the order so far is saved, else the reload put every tile back.
        if (moving >= 0) stopMove();
        PackageManager pm = ctx.getPackageManager();
        // System apps on China-market TVs (Hisense VIDAA, Xiaomi, TCL) often ship only a plain LAUNCHER
        // entry, no LEANBACK_LAUNCHER one. Querying both is what makes them appear as tiles at all.
        Intent leanbackProbe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent plainProbe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(leanbackProbe, 0));
        found.addAll(pm.queryIntentActivities(plainProbe, 0));
        List<Entry> fresh = new ArrayList<>();
        Set<String> userHidden = userHidden();
        // Some system apps declare two Home entries under one name (Sony's Timers & Clock on some
        // BRAVIAs), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || userHidden.contains(pkg)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            // A small square "banner" (Rockchip's Media Center ships a 64 px one) is a blur cropped over a
            // wide tile: treat it as none, so the tile shows the icon and name instead.
            if (banner != null && !isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight())) banner = null;
            Drawable icon = ri.loadIcon(pm);
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        // The saved arrangement wins; apps installed since then go at the end, alphabetically.
        String saved = prefs.getString(KEY_ORDER, "");
        List<String> order = saved.isEmpty() ? favourites : Arrays.asList(saved.split(","));
        Collections.sort(fresh, (a, b) -> {
            int fa = order.indexOf(a.pkg), fb = order.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        fresh.add(new Entry(ctx.getString(R.string.all_apps), new ComponentName(ctx, MainActivity.class),
                null, ctx.getDrawable(R.drawable.ic_all_apps), true));
        // The grid icon is a white glyph on transparency: sampled, its plate would come out white on
        // white. A neutral plate keeps it reading as the launcher's own tile, not a blank card.
        if (!fresh.isEmpty()) {
            Entry allApps = fresh.get(fresh.size() - 1);
            allApps.plateColor = PLATE_FALLBACK;
            allApps.plateIsLight = false;
        }
        moving = -1;
        if (items.isEmpty()) {
            items.addAll(fresh);
            notifyDataSetChanged();
            return;
        }
        // Apply only what changed (hidden, unhidden, installed, uninstalled), so the rest of the
        // tiles glide into place instead of the grid redrawing.
        List<Entry> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return fresh.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o).component.equals(fresh.get(n).component);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                return old.get(o).label.equals(fresh.get(n).label);
            }
        });
        items.clear();
        items.addAll(fresh);
        diff.dispatchUpdatesTo(this);
        // Tiles that crossed the dock edge show or hide their name.
        notifyItemRangeChanged(0, items.size(), PAYLOAD_STATE);
    }

    /** Wide enough to be a banner. A drawable with no intrinsic size (a colour, a shape) still fills the tile. */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    // ---- hiding ----------------------------------------------------------------------------------

    /** Packages hidden from the long-press menu (on top of config.xml's hidden_apps). */
    Set<String> userHidden() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
    }

    /**
     * The apps that can be tiles right now: installed, enabled and not hidden (by config.xml or from the
     * menu). Backdrops keeps pools only for these. Safe off the main thread.
     */
    static Set<String> tilePackages(Context ctx) {
        Set<String> hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        hidden.addAll(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, Collections.emptySet()));
        Set<String> apps = new HashSet<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(probe, 0)) {   // enabled apps only
            if (ri.activityInfo != null && !hidden.contains(ri.activityInfo.packageName)) apps.add(ri.activityInfo.packageName);
        }
        return apps;
    }

    void hide(int position) {
        if (!isApp(position)) return;
        Set<String> h = userHidden();
        h.add(items.get(position).pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        reload();
    }

    void unhide(String pkg) {
        Set<String> h = userHidden();
        h.remove(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        reload();
    }

    /** Downloaded apps (or updates to built-in ones) can be uninstalled; built-in apps only hidden. */
    boolean canUninstall(int position) {
        if (!isApp(position)) return false;
        try {
            int flags = ctx.getPackageManager().getApplicationInfo(items.get(position).pkg, 0).flags;
            return (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens the system uninstall confirmation; the grid reloads when Home resumes. */
    void uninstall(int position) {
        if (!canUninstall(position)) return;
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** A real app tile (not the All apps tile). */
    boolean isApp(int position) {
        return position >= 0 && position < items.size() && !items.get(position).allApps;
    }

    // ---- move mode -------------------------------------------------------------------------------

    boolean isMoving() {
        return moving >= 0;
    }

    void startMove(int position) {
        if (!isApp(position)) return;
        moving = position;
        notifyItemChanged(position, PAYLOAD_STATE);
    }

    void stopMove() {
        if (moving < 0) return;
        int pos = moving;
        moving = -1;
        notifyItemChanged(pos, PAYLOAD_STATE);
        StringBuilder sb = new StringBuilder();
        for (Entry e : items) {
            if (e.allApps) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(e.pkg);
        }
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    /** Handles a D-pad key for the tile being moved. */
    private boolean moveKey(int keyCode) {
        int from = moving, to;
        int last = items.size() - 2;   // the All apps tile stays at the end
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: to = from - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: to = from + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: to = from - dockSize; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: to = Math.min(from + dockSize, last); break;
            default: return false;
        }
        if (to < 0 || to > last || to == from) return true;
        items.add(to, items.remove(from));
        moving = to;
        notifyItemMoved(from, to);
        // Tiles that crossed the dock edge show or hide their name.
        notifyItemRangeChanged(Math.min(from, to), Math.abs(to - from) + 1, PAYLOAD_STATE);
        listener.onTileMoved(to);
        return true;
    }

    void openAppInfo(int position) {
        if (!isApp(position)) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        // Sized to the column width: 4 per row keeps the design size, 5 and 6 shrink to fit.
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int w = tileWidthPx(width - parent.getPaddingLeft() - parent.getPaddingRight(), dockSize, density);
        int h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, this, listener);
    }

    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;
    /** Neutral plate for an icon whose own colour is too dark to read as one (or that has none). */
    static final int PLATE_FALLBACK = 0xFF2C2C2E;
    /**
     * Inset for a full-bleed icon, in dp. The tile is wide (1.78), so a square icon is capped by the
     * tile's height: 13dp leaves it at ~68% of that height, between 4dp's 80% (too heavy) and 18dp's
     * 56% (too small), and close to the margin 酷9's own artwork carries.
     */
    static final float BLEED_INSET_DP = 13f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass,
     * capped at the design size (so 4 columns on a 1080p TV are exactly as before).
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int fit = (int) ((innerWidthPx / columns - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name under it and the item's padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(44 * density);
    }

    /** Tile corner radius: 16dp at the design width, proportionally less on smaller tiles. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(h, position);
        } else {
            bindState(h, position);
        }
    }

    /** The parts that change while moving: dock label visibility and the move outline. */
    private void bindState(Holder h, int position) {
        // Names go under every tile, the dock's too: an in-tile name on a light plate (聚好看's is white)
        // reads as if the word were printed inside the icon. The dock row already reserves the space.
        h.name.setVisibility(View.VISIBLE);
        float density = h.card.getResources().getDisplayMetrics().density;
        // A full-bleed icon (its artwork touches all four edges) fills the tile edge to edge, which
        // reads heavier than 酷9's logo, whose own artwork already carries a margin. Inset those.
        Entry entry = position >= 0 && position < items.size() ? items.get(position) : null;
        // trimmedIcon() is what decides whether the artwork is full-bleed, so it has to run first.
        if (entry != null) trimmedIcon(entry);
        int side = Math.round((entry != null && entry.bleed ? BLEED_INSET_DP : 4) * density);
        // Equal on all four sides: the icon is centred in the tile, and the name sits below the tile,
        // so a larger bottom inset only pushed the artwork up and off-centre.
        if (h.icon.getPaddingLeft() != side || h.icon.getPaddingTop() != side
                || h.icon.getPaddingRight() != side || h.icon.getPaddingBottom() != side) {
            h.icon.setPadding(side, side, side, side);
        }
        h.card.setForeground(position == moving ? ctx.getDrawable(R.drawable.move_outline) : null);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        bindState(h, position);
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        // An opaque banner covers the whole card (centerCrop), so the card's dark fill under it would be drawn
        // for nothing on every frame; it stays under banners with see-through parts and under icon tiles.
        boolean bannerCovers = e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE;
        h.card.setBackground(bannerCovers ? null : h.cardBackground);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
            h.card.setForeground(null);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            // The plate takes the icon's own colour, so the icon's square background and the tile are one
            // surface and the artwork reads full-bleed. Aerial's logoOnPlate works exactly this way.
            Drawable plate = plate(e, AppAdapter.cornerRadius(h.card.getWidth() > 0 ? h.card.getWidth() : (int) (DESIGN_W_DP * h.card.getResources().getDisplayMetrics().density), h.card.getResources().getDisplayMetrics().density));
            h.card.setBackground(plate);
            h.icon.setImageDrawable(trimmedIcon(e));
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            // White names vanish on a light plate (聚好看's icon is white); the plate's luminance decides.
            h.label.setTextColor(e.plateIsLight ? 0xE6000000 : 0xFFFFFFFF);
            // Never inside the tile: a name printed on the plate reads as part of the icon artwork.
            h.label.setVisibility(View.GONE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && !isMoving()) listener.onTileLongPress(pos, v);
            return true;
        });
    }

    private void launch(Entry e) {
        if (e.allApps) {
            openAllApps();
            return;
        }
        listener.onTileOpen(e.component, e.label);
    }

    /** The system Apps screen (Settings > Apps). */
    private void openAllApps() {
        // The TV's own Settings > Apps screen is not usable here: Hisense VIDAA's AppsActivity throws
        // NoClassDefFoundError in onCreate (settingslib's ApplicationsState$AppFilter is missing) and
        // force-closes straight back to Home, so all three of those actions resolve and then land on a
        // dead screen. Vibe Launcher draws the list itself instead (AllAppsActivity).
        try {
            ctx.startActivity(new Intent(ctx, AllAppsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, ctx.getString(R.string.all_apps)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String packageAt(int position) {
        return isApp(position) ? items.get(position).pkg : null;
    }

    /** Where this app's tile is now, or -1 when it's gone. */
    int positionOf(String pkg) {
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).allApps && items.get(i).pkg.equals(pkg)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /**
     * The tile's plate, in the icon's own dominant colour. Aerial's logoOnPlate does the same thing:
     * app icons are a logo drawn on a coloured square, so a plate in that square's colour makes the
     * icon's background and the tile one and the same surface, and the artwork reads edge to edge
     * instead of as a small picture floating in a dark box. Sampling 24x24 is enough to find the
     * background colour and costs nothing per tile.
     */
    private static Drawable plate(Entry e, float radius) {
        if (e.plate != null && e.plateRadius == radius) return e.plate;
        int colour = e.plateColor;
        if (colour == 0) {
            colour = PLATE_FALLBACK;
            if (e.icon != null && e.icon.getIntrinsicWidth() > 0 && e.icon.getIntrinsicHeight() > 0) {
                try {
                    final int n = 24;
                    Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                    Drawable d = e.icon.getConstantState() != null
                            ? e.icon.getConstantState().newDrawable().mutate() : e.icon;
                    Rect saved = new Rect(d.getBounds());
                    d.setBounds(0, 0, n, n);
                    d.draw(new Canvas(bmp));
                    d.setBounds(saved);
                    // Quantised to 4 bits a channel so a gradient background still lands on one bucket.
                    int[] hist = new int[4096];
                    for (int y = 0; y < n; y++) {
                        for (int x = 0; x < n; x++) {
                            int p = bmp.getPixel(x, y);
                            if ((p >>> 24) < 200) continue;
                            hist[((p >> 12) & 0xF00) | ((p >> 8) & 0xF0) | ((p >> 4) & 0xF)]++;
                        }
                    }
                    bmp.recycle();
                    int best = -1, bestCount = 0;
                    for (int i = 0; i < hist.length; i++) {
                        if (hist[i] > bestCount) {
                            bestCount = hist[i];
                            best = i;
                        }
                    }
                    if (best >= 0) {
                        int sampled = 0xFF000000 | ((best & 0xF00) << 12) | ((best & 0xF0) << 8) | ((best & 0xF) << 4)
                                | 0x0F0F0F;   // widen the bucket back to a full byte
                        // Nearly black is no better than the fallback, and white text stays readable on it.
                        if (luminance(sampled) > 0.06f) colour = sampled;
                    }
                } catch (RuntimeException | OutOfMemoryError ex) {
                    // A tile is not worth a crash: the default plate.
                }
            }
            e.plateColor = colour;
            e.plateIsLight = luminance(colour) > 0.55f;
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(colour);
        g.setCornerRadius(radius);
        e.plate = g;
        e.plateRadius = radius;
        return e.plate;
    }

    private static float luminance(int colour) {
        return (0.2126f * ((colour >> 16) & 0xFF) + 0.7152f * ((colour >> 8) & 0xFF) + 0.0722f * (colour & 0xFF)) / 255f;
    }

    private static Drawable trimmedIcon(Entry e) {
        if (e.trimmed != null) return e.trimmed;
        e.trimmed = e.icon;
        if (e.icon == null || e.icon.getIntrinsicWidth() <= 0 || e.icon.getIntrinsicHeight() <= 0) return e.trimmed;
        final int n = 36;
        try {
            // Square sampling would squash a wide icon; keep the drawable's own aspect.
            int iw = Math.max(1, e.icon.getIntrinsicWidth()), ih = Math.max(1, e.icon.getIntrinsicHeight());
            int mw = iw >= ih ? n : Math.max(1, Math.round(n * (float) iw / ih));
            int mh = ih >= iw ? n : Math.max(1, Math.round(n * (float) ih / iw));
            Bitmap bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
            Drawable d = e.icon.getConstantState() != null
                    ? e.icon.getConstantState().newDrawable().mutate() : e.icon;
            Rect saved = new Rect(d.getBounds());
            d.setBounds(0, 0, mw, mh);
            d.draw(new Canvas(bmp));
            d.setBounds(saved);
            int minX = mw, minY = mh, maxX = -1, maxY = -1;
            for (int y = 0; y < mh; y++) {
                for (int x = 0; x < mw; x++) {
                    if ((bmp.getPixel(x, y) >>> 24) < 16) continue;   // a soft shadow's tail doesn't count
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            bmp.recycle();
            if (maxX < 0 || (minX == 0 && minY == 0 && maxX == mw - 1 && maxY == mh - 1)) {
                // Nothing opaque (keep it), or the artwork reaches every edge: a full-bleed square.
                // Those fill the whole tile and read heavier than an icon that carries its own margin
                // (ES's white block vs 酷9's logo), so they get inset when drawn.
                if (maxX >= 0) e.bleed = true;
                return e.trimmed;
            }
            // Scale the visible box back up to the icon's real size, then shift it so its top-left corner
            // sits at the origin: what the ImageView draws is now only the artwork.
            float sx = (maxX - minX + 1f) / mw, sy = (maxY - minY + 1f) / mh;
            Drawable cropped = e.icon.getConstantState() != null
                    ? e.icon.getConstantState().newDrawable().mutate() : e.icon;
            cropped.setBounds(-Math.round(minX / sx), -Math.round(minY / sy),
                    Math.round((mw - minX) / sx), Math.round((mh - minY) / sy));
            e.trimmed = new BoundsDrawable(cropped);
        } catch (RuntimeException | OutOfMemoryError ex) {
            // A tile is not worth a crash: the icon as it came.
        }
        return e.trimmed;
    }

    /** One drawable with its own bounds: lets the cropped icon be drawn at any size the tile asks for. */
    private static final class BoundsDrawable extends Drawable {
        private final Drawable inner;

        BoundsDrawable(Drawable inner) {
            this.inner = inner;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            inner.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            inner.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            inner.setBounds(bounds);
        }

        @Override
        public int getIntrinsicWidth() {
            return inner.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return inner.getIntrinsicHeight();
        }
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: behind an icon tile, or a banner with see-through parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        /** OK ends move mode on key down; swallow the matching key up so it doesn't launch the app. */
        private boolean swallowOkUp;

        Holder(View v, AppAdapter adapter, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
                if (event.getAction() == KeyEvent.ACTION_UP && ok && swallowOkUp) {
                    swallowOkUp = false;
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                // Held OK: remotes send repeats flagged as a long press. Open the menu from that too
                // (cancelling the touch-style timer so it can't fire a second time).
                if (ok && event.isLongPress() && !adapter.isMoving()) {
                    view.cancelLongPress();
                    view.performLongClick();
                    swallowOkUp = true;
                    return true;
                }
                if (adapter.isMoving()) {
                    if (ok || keyCode == KeyEvent.KEYCODE_BACK) {
                        adapter.stopMove();
                        swallowOkUp = ok;
                        return true;
                    }
                    return adapter.moveKey(keyCode);   // other keys (volume etc.) pass through
                }
                int pos = getBindingAdapterPosition();
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Stay put at a row's ends: Android's focus search would otherwise jump up to the top bar.
                    if (pos == RecyclerView.NO_POSITION) return false;
                    int col = pos % adapter.dockSize;
                    return keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? col == 0
                            : col == adapter.dockSize - 1 || pos == adapter.getItemCount() - 1;
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .withLayer()   // the rounded banner is drawn once and scaled, not redrawn every frame
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
