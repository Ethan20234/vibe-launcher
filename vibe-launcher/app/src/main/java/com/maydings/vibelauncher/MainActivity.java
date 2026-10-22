package com.maydings.vibelauncher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.net.Inet4Address;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * tvOS style home: a backdrop for the focused dock app, a pinned dock row, an app grid, and one glass
 * highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots: Vibe Launcher settings (4, 5 or 6), else config.xml. Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /** 2025-01-01: a clock before this hasn't been set yet. */
    private static final long TIME_SET_AFTER = 1_735_689_600_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private View backdrop;
    private ImageView backdropImage;
    /** The still being faded out under backdropImage while the next one fades in. */
    private ImageView backdropPrevious;
    private View backdropTitleBox;
    private ImageView backdropLogo;
    private TextView backdropTitle;
    private Backdrops backdrops;
    /** Which apps need a PIN, and that PIN. */
    private AppLock lock;
    /** The dock app whose backdrop is showing or loading, or null. */
    private String backdropPkg;
    private TextView btnWifi;
    /** The network pill's icon as last set (updateWifiLabel). */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    /** Scroll the glass target already accounts for, so it heads (mostly) to where the row will settle. */
    private int plannedScroll;
    /** The focused tile, so the glass can re-measure it once a scroll settles. */
    private View focusedCard;
    /** Width of the tile the glass was last fitted to. */
    private int glassCardW;
    /** Share of a vertical scroll the glass runs ahead into before settling back: a gentle bounce. */
    private static final float GLASS_LEAD = 0.12f;
    private boolean glassShown;
    /** The dropdown currently on screen (Inputs or a tile menu), if any. */
    private PopupWindow openMenu;
    private int lastFocused = 0;
    private float density;
    /**
     * Rebuild Home on resume: an app covered it (or it is starting). Not after a side panel such as the
     * TV's Settings, which only pauses Home: nothing changed underneath, so Home stays as it was.
     */
    private boolean rebuildOnResume = true;
    /** Home was pressed while Home was in front: go back to the first dock tile. */
    private boolean homeToTop;
    /** The time box's width for each length of time text ("8:08" is 4, "10:08" is 5); see sizeTimeBox. */
    private final Map<Integer, Integer> timeBoxWidths = new HashMap<>();
    /** The 12/24-hour setting and language timeBoxWidths were measured for. */
    private String timeBoxMeasuredFor;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: after a reboot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /**
     * Apps installed, updated or removed while Home is on screen show up (or go) straight away, instead
     * of at the next return to Home. An update sends "removed" then "added": keep its tile meanwhile.
     */
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            adapter.reload();
            // Tiles may have moved: once they settle, bring the glass to wherever focus went.
            grid.postOnAnimation(() -> {
                RecyclerView.ItemAnimator animator = grid.getItemAnimator();
                if (animator != null) animator.isRunning(MainActivity.this::refreshFocusedTile);
                else refreshFocusedTile();
            });
        }
    };
    /**
     * Rebuilds the dock apps' backdrop pools that are due (six hours old), then comes back when the next
     * one will be. Only while Home is on screen; the next visit catches up on any that fell due meanwhile.
     */
    private final Runnable refreshPools = new Runnable() {
        @Override
        public void run() {
            List<String> dock = new ArrayList<>();
            for (int i = 0; i < COLUMNS; i++) {
                String pkg = adapter.packageAt(i);
                if (pkg != null) dock.add(pkg);
            }
            for (String pkg : dock) backdrops.refresh(pkg);
            handler.removeCallbacks(this);
            // Builds just started count as fresh, so this lands on the next pool to fall due.
            handler.postDelayed(this, Math.max(MINUTE_MS, backdrops.untilDue(dock)));
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Settings, or the TV's own
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // Tiles per row: chosen in Vibe Launcher settings (4, 5 or 6), else config.xml.
        COLUMNS = new VibeSettings(this).columns(Math.max(1, getResources().getInteger(R.integer.columns)));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        backdrop = findViewById(R.id.backdrop);
        lock = new AppLock(this);
        backdropImage = findViewById(R.id.backdropImage);
        backdropPrevious = findViewById(R.id.backdropPrevious);
        backdropTitleBox = findViewById(R.id.backdropTitleBox);
        backdropLogo = findViewById(R.id.backdropLogo);
        backdropTitle = findViewById(R.id.backdropTitle);
        backdrops = new Backdrops(this);
        if (!askPermissionsOnce()) askHomeOnce();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        // One drawable, nothing overlapping: its fades can apply alpha while drawing, instead of rendering
        // the full-screen glow into an offscreen buffer on every frame of the fade.
        ambient.forceHasOverlappingRendering(false);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        btnWifi = findViewById(R.id.btnWifi);
        TextView clock = findViewById(R.id.clock), date = findViewById(R.id.date);
        localDate((TextClock) date, "EEEE d MMMM yyyy");
        showClockOnceTimeIsSet(findViewById(R.id.clockRow));
        sizeTimeBox();
        clock.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fitTimeBox(s);   // the hour gained or lost a digit
            }
        });
        date.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> centreTwoLineDate(clock, date));

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(new TileAnimator());
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);
        FrostView frost = findViewById(R.id.frost);
        frost.setSource(grid);
        frost.setBackdrop(backdrop);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int tileH = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(
                    (r - l) - grid.getPaddingLeft() - grid.getPaddingRight(), COLUMNS, density));
            int dockRow = AppAdapter.itemHeightPx(tileH, density) + (int) ((6 + 12) * density);   // plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            boolean moved = grid.getPaddingTop() != want;
            if (moved) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            // Cold start, or a new column count: the focused tile can move or change size after the glass was
            // fitted to it. Fit it again once this layout settles.
            View card = focusedCard;
            if (card != null && card.isFocused() && (moved || card.getWidth() != glassCardW)) {
                grid.post(() -> {
                    RecyclerView.ViewHolder holder = grid.findContainingViewHolder(card);
                    int pos = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
                    if (card.isFocused() && pos != RecyclerView.NO_POSITION) onTileFocused(pos, card);
                });
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                if (plannedScroll != 0 && (dy > 0) == (plannedScroll > 0)) {
                    int take = Math.abs(dy) >= Math.abs(plannedScroll) ? plannedScroll : dy;
                    plannedScroll -= take;
                    dy -= take;
                }
                if (dy == 0) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // Settle exactly on the tile: the plan can be off by the few pixels the grid scrolled on
                // its own before onTileFocused ran, or the scroll stopped short at the end of the list.
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !glassShown || focusedCard == null
                        || !focusedCard.isAttachedToWindow()) return;
                plannedScroll = 0;
                Rect r = new Rect(0, 0, focusedCard.getWidth(), focusedCard.getHeight());
                root.offsetDescendantRectToMyCoords(focusedCard, r);
                float y = r.exactCenterY() - glass.getLayoutParams().height / 2f;
                if (Math.abs(y - glassTargetY) < 0.5f) return;
                glassTargetY = y;
                springY.animateToFinalPosition(y);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(TileAnimator.STIFFNESS).setDampingRatio(TileAnimator.DAMPING));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> showNetwork());
        btnSettings.setOnClickListener(v -> showSettingsMenu(btnSettings));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not whichever tile the focus finder picks.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && (v.getId() == R.id.btnInputs
                    || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE))) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Registered while Home is visible, side panels included, so no change is missed under one.
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);   // enabled or disabled
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageReceiver, packages);
    }

    /**
     * Home pressed: singleTask delivers it here, then onResume runs. From an app, Home was stopped and
     * returns to the tile you left from as usual. Pressed on Home itself (Home never stopped), it goes
     * back to the top and the first dock tile, like the stock launcher.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME) && !rebuildOnResume) homeToTop = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        showUpdateReady();
        Updater.checkIfDue(this, (newer, failed) -> showUpdateReady());   // at most once a day
        sizeTimeBox();   // the 12/24-hour setting may have changed meanwhile
        // The pills keep their state while Home is away, and the network callback updates the network one, so
        // this only catches anything missed. Its binder calls (the Wi-Fi name, the TV's inputs) would hold up
        // the first frame back on Home, so after the first resume they run once that frame is drawn.
        boolean rebuild = rebuildOnResume;
        if (pillsShown) {
            refreshPillsAfterNextDraw(rebuild);
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        handler.postDelayed(minuteTick, MINUTE_MS);
        refreshPools.run();
        if (homeToTop) {
            homeToTop = false;
            lastFocused = 0;
            grid.scrollToPosition(0);
            grid.post(() -> focusTile(0));
        }
        if (!rebuildOnResume) return;   // back from a side panel: Home is as it was
        rebuildOnResume = false;
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        adapter.reload();
        ChannelSetup.run(this);
        backdropPkg = null;
        backdrop.setAlpha(0f);
        backdrops.reshuffle();
        grid.scheduleLayoutAnimation();
        grid.requestLayout();   // reload() only touches changed tiles, so ask for the pass that plays the entrance

        // Return to the tile we left from, or the first one. When a tile kept focus while we were away
        // (the usual case), no focus change follows, so bring back its glass, name and backdrop here:
        // the reset above cleared them.
        grid.post(() -> {
            if (!refreshFocusedTile()) focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(refreshPools);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        rebuildOnResume = true;
    }

    @Override
    protected void onDestroy() {
        backdrops.close();
        handler.removeCallbacksAndMessages(null);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // never registered
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Puts the glass, name and backdrop on the tile that has focus; false when no tile has it. */
    private boolean refreshFocusedTile() {
        View focused = getCurrentFocus();
        RecyclerView.ViewHolder holder = focused != null ? grid.findContainingViewHolder(focused) : null;
        int position = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
        if (position == RecyclerView.NO_POSITION || focused.getId() != R.id.card) return false;
        onTileFocused(position, focused);
        return true;
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        focusedCard = card;
        // Focus can arrive before the first layout (cold start): the tile has no size yet, and the glass would
        // shrink to its padding. Fit it once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        glassCardW = card.getWidth();
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE);   // same corners as the tile
        }
        // Work out the scroll first so the glass goes straight to the row's final spot. Aiming at the
        // row's current spot and then following the scroll sent it too far up/down and back.
        View item = (View) card.getParent();
        boolean dock = position < COLUMNS;
        int dy;
        if (dock) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            dy = decoratedTop - grid.getPaddingTop();
        } else {
            // Scroll just enough so the whole glass (not only the tile) is visible.
            int top = item.getTop() - (int) pad * 2, bottom = item.getBottom() + (int) pad;
            int parentTop = grid.getPaddingTop(), parentBottom = grid.getHeight() - grid.getPaddingBottom();
            // When the glass is taller than the space (the 940E: 328 px of glass, 318 of room), favour the
            // top: fitting the bottom pushed the top out, so each sideways press scrolled 10 px back and forth.
            dy = top < parentTop ? top - parentTop
                    : (bottom > parentBottom ? Math.min(bottom - parentBottom, top - parentTop) : 0);
        }
        // The grid can't scroll past its ends (the bottom row), so only plan what is actually left.
        // Measured from the first or last tile when it's laid out: RecyclerView's scroll range is only an
        // estimate for a grid, and with a short last row it came out too small, stopping the scroll with
        // the bottom row cut off.
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (dy > 0 && lm != null) {
            View last = lm.findViewByPosition(adapter.getItemCount() - 1);
            if (last != null) {
                dy = Math.min(dy, Math.max(0, lm.getDecoratedBottom(last) + grid.getPaddingBottom() - grid.getHeight()));
            }
        } else if (dy < 0 && lm != null) {
            View first = lm.findViewByPosition(0);
            if (first != null) dy = Math.max(dy, Math.min(0, lm.getDecoratedTop(first) - grid.getPaddingTop()));
        }
        if (Math.abs(dy) <= 1) dy = 0;
        plannedScroll = Math.round(dy * (1f - GLASS_LEAD));
        y -= plannedScroll;

        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        if (dy != 0) grid.smoothScrollBy(0, dy);
        if (dock) showHero(position, r.top - dy);
        else hideHero();
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        } else {
            heroName.setY(y);
        }
        showBackdrop(adapter.packageAt(position), color);
    }

    /**
     * The app's still behind the dock when it has one, otherwise its colour glow. Nothing changes until
     * the still has loaded, so the screen goes straight from one app's look to the next: the glow is
     * drawn above the stills and would otherwise flash over a picture while the next one loads.
     */
    private void showBackdrop(String pkg, int color) {
        if (pkg == null) {   // e.g. the All apps tile in the dock
            backdropPkg = null;
            fadeBackdrop(null, null, null, color);
            return;
        }
        if (pkg.equals(backdropPkg)) return;
        backdropPkg = pkg;
        backdrops.load(pkg, (loaded, bitmap, logo, title) -> {
            if (!heroShown || !loaded.equals(backdropPkg)) return;   // moved on while it loaded
            // Nothing came back (no pool, offline, out of memory): let go of the app so coming back to it
            // tries again instead of returning early above, which left the tile on the colour glow until
            // Home was recreated.
            if (bitmap == null) backdropPkg = null;
            fadeBackdrop(bitmap, logo, title, color);
        });
    }

    /** Shows a still (cross-fading from the one before, if any), or the glow in this colour. */
    private void fadeBackdrop(Bitmap bitmap, Bitmap logo, String title, int color) {
        if (bitmap == null) {
            backdrop.animate().alpha(0f).setDuration(300).withLayer().start();
            if (ambient.getAlpha() > 0f && color != ambientColor) {
                // Glow to glow: dip, change colour, come back.
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            } else {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(500).start();
            }
            return;
        }
        ambient.animate().alpha(0f).setDuration(300).start();
        if (backdrop.getAlpha() > 0f && backdropImage.getDrawable() != null) {
            // Picture to picture: the old one stays underneath while the new one fades in over it.
            backdropPrevious.setImageDrawable(backdropImage.getDrawable());
            backdropPrevious.setVisibility(View.VISIBLE);
            backdropImage.animate().cancel();
            backdropImage.setAlpha(0f);
            backdropImage.setImageBitmap(bitmap);
            backdropImage.animate().alpha(1f).setDuration(450).withEndAction(() -> {
                backdropPrevious.setImageDrawable(null);   // let the old picture go
                backdropPrevious.setVisibility(View.GONE);
            }).start();
            backdropTitleBox.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                showBackdropTitle(logo, title);
                backdropTitleBox.animate().alpha(1f).setDuration(300).start();
            }).start();
            // In case it was fading out. No layer: the picture inside is cross-fading, so a layer would be
            // redrawn on every frame anyway.
            backdrop.animate().alpha(1f).setDuration(200).start();
            return;
        }
        backdropImage.animate().cancel();
        backdropImage.setAlpha(1f);
        backdropImage.setImageBitmap(bitmap);
        backdropTitleBox.animate().cancel();
        backdropTitleBox.setAlpha(1f);
        showBackdropTitle(logo, title);
        backdrop.animate().alpha(1f).setDuration(450).withLayer().start();
    }

    /** The film or show's logo, else its name, at the right, level with the app name; nothing otherwise. */
    private void showBackdropTitle(Bitmap logo, String title) {
        backdropLogo.setImageBitmap(logo);
        backdropLogo.setVisibility(logo != null ? View.VISIBLE : View.GONE);
        backdropTitle.setText(title);
        backdropTitle.setVisibility(logo == null && title != null ? View.VISIBLE : View.GONE);
        backdropTitleBox.post(() -> backdropTitleBox.setY(
                heroName.getY() + heroName.getHeight() - backdropTitleBox.getHeight()));
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
        backdrop.animate().alpha(0f).setDuration(300).withLayer().start();
        backdropPkg = null;
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // Handled here rather than by Android's focus search, which is what plays the click for left/right.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /**
     * TVs without a clock battery start in 1970 until network time arrives, and Home showed that date. The
     * clock and date stay hidden (keeping their space) until the time is plausible, checked once a second.
     */
    private void showClockOnceTimeIsSet(View clockRow) {
        if (System.currentTimeMillis() > TIME_SET_AFTER) {
            clockRow.setVisibility(View.VISIBLE);
            return;
        }
        clockRow.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> showClockOnceTimeIsSet(clockRow), 1000);
    }

    /**
     * Other languages put the weekday, day, month and year in their own order ("2026年10月20日 星期二");
     * English keeps the launcher's own "TUESDAY  20 OCTOBER 2026".
     */
    static void localDate(TextClock date, String skeleton) {
        Locale locale = Locale.getDefault();
        if (locale.getLanguage().equals("en")) return;
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
    }

    /**
     * The time box is as wide as the widest time with as many characters as the one showing ("8:08" from 1:00
     * to 9:59, "10:08" from 10:00), with the wider of AM and PM: the digits differ in width, and the date
     * beside it would shift from 1:40 to 1:41. Sized for "10:00" all day, it left a wide gap after 1:11; this
     * way the date moves only when the hour gains or loses a digit (in 24-hour time, never).
     */
    private void sizeTimeBox() {
        TextClock clock = findViewById(R.id.clock), ampm = findViewById(R.id.ampm);
        // Forced to 24-hour time whatever the system says (see the layout: both formats are "HH:mm"),
        // so the AM/PM clock never contributes width and the date no longer shifts at 10:00.
        boolean h24 = true;
        // Measuring every minute of the day takes a moment: only again when the format or language changed.
        String measuredFor = h24 + "|" + Locale.getDefault();
        if (measuredFor.equals(timeBoxMeasuredFor)) return;
        timeBoxMeasuredFor = measuredFor;
        CharSequence format = h24 ? clock.getFormat24Hour() : clock.getFormat12Hour();
        float extra = 0;
        if (!h24) {
            float half = 0;
            for (String s : new DateFormatSymbols().getAmPmStrings()) {
                half = Math.max(half, ampm.getPaint().measureText(s.toUpperCase(Locale.getDefault())));
            }
            extra = ((ViewGroup.MarginLayoutParams) ampm.getLayoutParams()).getMarginStart() + half;
        }
        ampm.setVisibility(h24 ? View.GONE : View.VISIBLE);
        Calendar time = Calendar.getInstance();
        timeBoxWidths.clear();
        for (int minute = 0; minute < 24 * 60; minute++) {
            time.set(Calendar.HOUR_OF_DAY, minute / 60);
            time.set(Calendar.MINUTE, minute % 60);
            String s = DateFormat.format(format, time).toString();
            int w = (int) Math.ceil(clock.getPaint().measureText(s) + extra);
            Integer known = timeBoxWidths.get(s.length());
            if (known == null || w > known) timeBoxWidths.put(s.length(), w);
        }
        fitTimeBox(clock.getText());
    }

    /** Applies the width sizeTimeBox worked out for times as long as this one. */
    private void fitTimeBox(CharSequence shown) {
        Integer w = timeBoxWidths.get(shown.length());
        View box = findViewById(R.id.timeBox);
        if (w != null && box.getMinimumWidth() != w) box.setMinimumWidth(w);
    }

    /**
     * One line of date sits level with AM. When it wraps onto two (a long network name narrows the space,
     * or a long language), the pair is centred on the clock's digits instead: kept level with AM, the
     * second line pulled the block below the digits' middle.
     */
    private void centreTwoLineDate(TextView clock, TextView date) {
        Layout lines = date.getLayout();
        if (lines == null || lines.getLineCount() < 2) {
            date.setTranslationY(0f);
            return;
        }
        Rect cap = new Rect();
        clock.getPaint().getTextBounds("0", 0, 1, cap);   // top is negative: the digit's height above the baseline
        // The clock sits in the time box, beside the date: offset by the box's own top.
        float digits = ((View) clock.getParent()).getTop() + clock.getTop() + clock.getBaseline() + cap.exactCenterY();
        date.getPaint().getTextBounds("D", 0, 1, cap);
        float firstTop = date.getTop() + date.getTotalPaddingTop() + lines.getLineBaseline(0) + cap.top;
        float lastBaseline = date.getTop() + date.getTotalPaddingTop() + lines.getLineBaseline(lines.getLineCount() - 1);
        date.setTranslationY(digits - (firstTop + lastBaseline) / 2f);
    }

    /**
     * Granted at install over adb (-g). Installed from a file manager instead, ask once for both: TV
     * listings (the apps' home screen cards, for backdrops) and location (Android only reveals the
     * Wi-Fi network's name with it). Refused, the launcher keeps the colour glow and shows "Wi-Fi".
     */
    private boolean askPermissionsOnce() {
        if (getPreferences(MODE_PRIVATE).getBoolean("askedPermissions", false)) return false;
        List<String> missing = new ArrayList<>();
        // Coarse with fine: Android 12+ refuses a request for fine location alone.
        for (String p : new String[]{Backdrops.PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) return false;
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedPermissions", true).apply();
        requestPermissions(missing.toArray(new String[0]), 1);
        return true;
    }

    /**
     * Opened as an app rather than as Home (installed from a file, no computer): offer once to become the
     * default Home where the TV has a way to (see DefaultHome). The gear menu offers it again later.
     */
    private void askHomeOnce() {
        if (getPreferences(MODE_PRIVATE).getBoolean("askedHome", false) || DefaultHome.isHome(this)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedHome", true).apply();
        if (DefaultHome.request(this) != null) makeHome();
    }

    /** The system's own screen for choosing the default Home, or a note that this TV has none. */
    private void makeHome() {
        Intent request = DefaultHome.request(this);
        if (request == null) {
            Toast.makeText(this, R.string.home_not_changeable, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // The Android 10+ dialog answers with a result; Android 9's chooser is plain Home.
            if (Build.VERSION.SDK_INT >= 29) startActivityForResult(request, 2);
            else startActivity(request);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.home_not_changeable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // Show what the new permissions unlock straight away: the network name and this visit's backdrops.
        updateWifiLabel();
        backdrops.reshuffle();
        backdropPkg = null;   // onResume, which follows the permission dialog, loads it again
        rebuildOnResume = true;   // the dialog only paused Home
        askHomeOnce();
    }

    /** Runs the pill refresh just after the next frame is drawn; the Inputs pill only when Home rebuilds. */
    private void refreshPillsAfterNextDraw(boolean inputs) {
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame. A post straight from
                // onResume would run first: the frame is only scheduled once onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    if (inputs) updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            // The pill names the physical connection; a VPN on top of it shows in the dropdown.
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) caps = underlying(cm);
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = wifiName();
                if (ssid != null) text = ssid;
            }
        } catch (Exception ignored) {
        }
        // Only what changed: setting the same text or icon again lays the top bar out again.
        if (!TextUtils.equals(btnWifi.getText(), text)) btnWifi.setText(text);
        if (icon != wifiIcon) {
            wifiIcon = icon;
            // The style's drawableTint also applies to an icon swapped in here.
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(topIcon(icon), 0, 0, 0);
        }
    }

    /**
     * The top pills are 34dp, so their icons are the 18dp copies, not the 22dp ones the popup rows use.
     * Swapping an icon at runtime bypasses the layout, so the mapping has to happen here too.
     */
    private static int topIcon(int icon) {
        if (icon == R.drawable.ic_wifi) return R.drawable.ic_wifi_top;
        if (icon == R.drawable.ic_ethernet) return R.drawable.ic_ethernet_top;
        return icon;
    }

    /** The Ethernet (preferred) or Wi-Fi connection under a VPN, or null. */
    private static NetworkCapabilities underlying(ConnectivityManager cm) {
        NetworkCapabilities wifi = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return c;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = c;
        }
        return wifi;
    }

    /** The connected Wi-Fi network's name, or null when it can't be read. */
    private String wifiName() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        String ssid = info != null ? info.getSSID() : null;
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        return ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x") ? null : ssid;
    }

    /** The connected Wi-Fi's link speed in Mbps, or -1 when unknown. */
    private int wifiLinkSpeed() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        return info != null ? info.getLinkSpeed() : -1;
    }

    /**
     * Dropdown under the network pill: each connected interface with its IPv4 address (and Wi-Fi's link
     * speed), then settings.
     */
    private void showNetwork() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);

        // Which device this is (Settings > About), to tell the TVs and the Shield apart.
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        name = name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
        addInfoRow(rows, R.drawable.ic_livetv, name, name.equalsIgnoreCase(Build.MODEL) ? null : Build.MODEL);
        addSeparator(rows);
        int firstNetworkRow = rows.getChildCount();

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                LinkProperties lp = cm.getLinkProperties(net);
                if (caps == null || lp == null) continue;
                boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                String ip = null;
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) {
                        ip = a.getAddress().getHostAddress();
                        break;
                    }
                }
                // A VPN is worth listing even with only an IPv6 address (or none we can show).
                if (ip == null && vpn) {
                    ip = lp.getLinkAddresses().isEmpty() ? getString(R.string.connected)
                            : lp.getLinkAddresses().get(0).getAddress().getHostAddress();
                }
                if (ip == null) continue;
                String type;
                int icon = R.drawable.ic_wifi;
                // VPN first: Android often also marks a VPN with the Wi-Fi or Ethernet it runs over.
                if (vpn) {
                    type = getString(R.string.vpn_connected);
                    icon = R.drawable.ic_lock;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    type = getString(R.string.ethernet);
                    icon = R.drawable.ic_ethernet;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    String ssid = wifiName();
                    type = ssid != null ? getString(R.string.wifi) + " \u00B7 " + ssid : getString(R.string.wifi);
                    // The rate the Wi-Fi link runs at now. Ethernet's isn't readable by apps, so it shows none.
                    int mbps = wifiLinkSpeed();
                    if (mbps > 0) ip += " \u00B7 " + mbps + " Mbps";
                } else {
                    type = getString(R.string.network);
                }
                String iface = lp.getInterfaceName();
                addInfoRow(rows, icon, iface != null ? type + " (" + iface + ")" : type, ip);
            }
        }
        if (rows.getChildCount() == firstNetworkRow) {
            addInfoRow(rows, R.drawable.ic_wifi, getString(R.string.offline), null);
        }
        addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.network_settings), -1,
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        showMenu(popup, panel, rows, btnWifi, Gravity.END);
    }

    /** Dropdown under the gear: the launcher's own settings, or the system's. */
    private void showSettingsMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        // Usage on top, like the device and addresses in the network menu.
        // CPU only where the device lets apps read the counters (not on Android 8+ TVs like ours).
        CpuInfo cpuInfo = new CpuInfo();
        boolean cpuLive = cpuInfo.canMeasure();
        View cpu = cpuLive ? addMeterRow(rows, getString(R.string.cpu)) : null;
        View ram = addMeterRow(rows, getString(R.string.ram));
        addSeparator(rows);
        // The last check's answer at once, then ask GitHub again: a release out since then replaces it in
        // this row, or shows the row. Settings → General, opened from here, shows the same answer. The row is
        // always there (hidden without an update) so showMenu gives it the menu bar's Left and Right.
        Updater.Release[] update = {Updater.available(this)};
        View updateRow = addInputRow(rows, popup, R.drawable.ic_update, "", -1,
                () -> SettingsDialog.startUpdate(this, update[0]));
        Runnable showUpdate = () -> {
            updateRow.setVisibility(update[0] != null ? View.VISIBLE : View.GONE);
            if (update[0] != null) {
                ((TextView) updateRow.findViewById(R.id.title)).setText(getString(R.string.update_to, update[0].version));
            }
        };
        showUpdate.run();
        addInputRow(rows, popup, R.drawable.ic_all_apps, getString(R.string.vibe_settings), -1,
                () -> SettingsDialog.show(this, root));
        if (!adapter.userHidden().isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.show_hidden), -1,
                    () -> showHidden(anchor));
        }
        // At most every few minutes: GitHub allows 60 unsigned API calls an hour per address, shared by every
        // TV in the house, and this menu is opened often (it shows the memory meter).
        Updater.checkIfOlderThan(this, 5 * 60 * 1000L, (newer, failed) -> {
            showUpdateReady();
            if (newer == null || !popup.isShowing()) return;
            update[0] = newer;
            showUpdate.run();
        });
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.android_settings), -1,
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
        if (!DefaultHome.isHome(this)) {
            addInputRow(rows, popup, R.drawable.ic_home, getString(R.string.make_home), -1, this::makeHome);
        }
        // Live while the menu is open.
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (cpuLive) updateCpu(cpu, cpuInfo.usage());
                updateRam(ram);
                handler.postDelayed(this, 1000);
            }
        };
        tick.run();
        popup.setOnDismissListener(() -> handler.removeCallbacks(tick));
        showMenu(popup, panel, rows, anchor, Gravity.END);
    }

    /** The green dot in the Settings gear: shown while a newer release is waiting to be installed. */
    void showUpdateReady() {
        findViewById(R.id.updateDot).setVisibility(Updater.available(this) != null ? View.VISIBLE : View.GONE);
    }

    /** A read-only row with a usage bar; fill it with the returned view. */
    private View addMeterRow(LinearLayout rows, String label) {
        View row = getLayoutInflater().inflate(R.layout.item_meter, rows, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        row.setFocusable(false);
        rows.addView(row);
        return row;
    }

    private void updateCpu(View row, float usage) {
        if (usage < 0) return;
        ((ProgressBar) row.findViewById(R.id.bar)).setProgress((int) (1000 * usage));
        ((TextView) row.findViewById(R.id.value)).setText(getString(R.string.percent, Math.round(100 * usage)));
    }

    /** Memory in use: total minus what the system says it can hand out without killing apps. */
    private void updateRam(View row) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        ProgressBar bar = row.findViewById(R.id.bar);
        bar.setProgress((int) (1000 * used / Math.max(1, mi.totalMem)));
        // Green while there's room, yellow under 100 MB free, red under 10 MB.
        long mb = 1024 * 1024;
        int color = mi.availMem > 100 * mb ? 0xFF34C759 : mi.availMem > 10 * mb ? 0xFFFFCC00 : 0xFFFF3B30;
        bar.setProgressTintList(ColorStateList.valueOf(color));
        float gb = 1024f * 1024 * 1024;
        ((TextView) row.findViewById(R.id.value)).setText(getString(R.string.ram_value, used / gb, mi.totalMem / gb));
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Launches a locked app only after the PIN. The tile's click goes through here rather than straight
     * into the adapter, so there is one place the lock can't be forgotten.
     */
    void openTile(ComponentName component, String label) {
        String pkg = component != null ? component.getPackageName() : null;
        if (!lock.needsPin(pkg)) {
            launchComponent(component, label);
            return;
        }
        PinDialog.show(this, getString(R.string.pin_title, label), pin -> {
            if (!lock.matches(pin)) return false;
            launchComponent(component, label);
            return true;
        });
    }

    /** Starts the tile's component, trying the categories its app actually declares. */
    private void launchComponent(ComponentName component, String label) {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER, null}) {
            Intent attempt = new Intent(i);
            if (category != null) attempt.addCategory(category);
            try {
                startActivity(attempt);
                return;
            } catch (Exception ignored) {
                // try the next category
            }
        }
        Toast.makeText(this, getString(R.string.cant_open, label), Toast.LENGTH_SHORT).show();
    }

    /** Hides the Inputs pill on devices with nothing to switch to (e.g. a Shield: no HDMI-in, no tuner). */
    private void updateInputsButton() {
        boolean any = getLiveTvIntent() != null;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && !info.isHidden(this)) {   // what the menu would list
                    any = true;
                    break;
                }
            }
        }
        findViewById(R.id.btnInputs).setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private Intent getLiveTvIntent() {
        Intent launch = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (launch == null) return null;
        // Launched plainly, the TV app resumes the last source, often an HDMI input (seen on the
        // XBR-940E). Ask it for the built-in tuner instead, when the TV has one.
        String tuner = builtInTuner();
        return tuner == null ? launch : new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tuner), "vnd.android.cursor.item/channel")
                .setPackage(liveTvPkg);
    }

    /** The TV's own tuner input (a system app, not e.g. an HDHomeRun app), or null. */
    private String builtInTuner() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        String tuner = null;
        if (tim == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_TUNER
                    && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (tuner == null || info.getId().compareTo(tuner) < 0)) {
                tuner = info.getId();
            }
        }
        return tuner;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = newMenu(panel);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        // Settings → General: leave out inputs that say nothing is connected. Ones whose state the TV doesn't
        // know stay, so nothing usable disappears.
        boolean hideInactive = new VibeSettings(this).hideInactiveInputs();
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // A device found over HDMI-CEC is a child input of its port: an Apple TV and a PS5 behind a switch
            // on HDMI 2 are two children of HDMI 2. Picking one has the TV ask over CEC for that device's
            // picture (and wakes it), which also turns a CEC-aware switch or receiver to it. One device shows
            // as "HDMI 2 · Apple TV"; several as an HDMI 2 heading with a row for each. Ordered by id, which
            // follows the device's address, so a switch's inputs come in order.
            Map<String, List<TvInputInfo>> devices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                if (info.getParentId() == null || info.isHidden(this)) continue;
                List<TvInputInfo> known = devices.get(info.getParentId());
                if (known == null) devices.put(info.getParentId(), known = new ArrayList<>());
                known.add(info);
            }
            for (List<TvInputInfo> list : devices.values()) {
                Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
            }
            for (TvInputInfo info : inputs) {
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this) || info.getParentId() != null) continue;
                List<TvInputInfo> children = devices.get(info.getId());
                if (hideInactive) {
                    if (children != null) {
                        List<TvInputInfo> active = new ArrayList<>();
                        for (TvInputInfo child : children) if (!inactive(tim, child.getId())) active.add(child);
                        children = active.isEmpty() ? null : active;
                    }
                    // A port stays while any device behind it is connected.
                    if (children == null && inactive(tim, info.getId())) continue;
                }
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                // The port, then the name given in Settings > Inputs.
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) label = label + " · " + custom;
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                if (children == null) {
                    addPassthroughRow(rows, popup, hdmi, label, info.getId(), tim);
                } else if (children.size() == 1) {
                    addPassthroughRow(rows, popup, hdmi, label + " · " + name(children.get(0), label), children.get(0).getId(), tim);
                } else {
                    // Several devices (behind a switch or a receiver): the port as a heading, each device under it.
                    View heading = addPassthroughRow(rows, popup, hdmi, label, info.getId(), tim);
                    heading.setFocusable(false);
                    heading.setClickable(false);
                    // Just the port's name: each device under it says whether it's connected.
                    heading.findViewById(R.id.status).setVisibility(View.GONE);
                    heading.findViewById(R.id.dot).setVisibility(View.GONE);
                    for (TvInputInfo child : children) {
                        View row = addPassthroughRow(rows, popup, hdmi, name(child, label), child.getId(), tim);
                        // Indented: no icon, the name lines up under the port's.
                        row.findViewById(R.id.icon).setVisibility(View.INVISIBLE);
                    }
                }
            }
        }
        Intent liveTv = getLiveTvIntent();
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Right-aligned to the pill so a wider menu grows leftwards instead of off the screen edge.
        showMenu(popup, panel, rows, anchor, Gravity.END);
    }

    /** A glass dropdown (the Inputs look). Rows go in R.id.rows; show it with {@link #showMenu}. */
    private PopupWindow newMenu(View panel) {
        PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    private void showMenu(PopupWindow popup, View panel, LinearLayout rows, View anchor, int gravity) {
        // Fit the widest row (TVs label inputs with long names like "HDMI 4/ARC" or the connected
        // device), at least as wide as the anchor and capped so the menu stays compact.
        int minWidth = Math.max(anchor.getWidth(), (int) (150 * density));
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.4f);
        panel.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.setWidth(Math.min(maxWidth, Math.max(minWidth, panel.getMeasuredWidth())));

        // Below the anchor, or above it when that side has more room (dock tiles). The top bar's pills
        // sit at the very top, so a menu that does not fit below still hangs below: there is nothing
        // above them but the screen edge.
        int gap = (int) (10 * density);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int spaceBelow = screenH - loc[1] - anchor.getHeight();
        int spaceAbove = loc[1];
        boolean above = panel.getMeasuredHeight() > spaceBelow && spaceAbove > spaceBelow;

        // Cap the menu to the room on that side and scroll inside it: the hidden apps list is longer
        // than the screen, and uncapped its last rows were cut off by the bottom edge.
        int room = (above ? spaceAbove : spaceBelow) - 2 * gap;
        View scroll = panel.findViewById(R.id.rowsScroll);
        if (panel.getMeasuredHeight() > room && scroll != null) {
            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
            lp.height = room - (panel.getMeasuredHeight() - scroll.getMeasuredHeight());
            scroll.setLayoutParams(lp);
            panel.measure(View.MeasureSpec.makeMeasureSpec(popup.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(room, View.MeasureSpec.AT_MOST));
        }
        int menuH = panel.getMeasuredHeight();
        int yOff = above ? -(anchor.getHeight() + menuH + gap) : gap;

        // Drop in: fade and unfold from the edge nearest the anchor.
        panel.setAlpha(0f);
        panel.setPivotY(above ? menuH : 0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY((above ? 8 : -8) * density);
        popup.showAsDropDown(anchor, 0, yOff, gravity);
        openMenu = popup;
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (row.isFocusable() && row.getVisibility() == View.VISIBLE) {
                row.requestFocus();
                break;
            }
        }
        // The top bar's menus work as one menu bar: Left and Right go to the neighbouring pill's menu,
        // and stop at the ends (nothing left of Inputs, nothing right of Settings).
        View[] bar = topBarPills();
        int at = Arrays.asList(bar).indexOf(anchor);
        if (at < 0) return;   // a tile's menu: not part of the bar
        View.OnKeyListener sideways = (v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int to = at + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (to < 0 || to >= bar.length) return true;   // an end of the bar
            popup.dismiss();
            bar[to].requestFocus();
            bar[to].performClick();
            return true;
        };
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) rows.getChildAt(i).setOnKeyListener(sideways);
        }
    }

    /** The top bar's pills that are showing, left to right (no Inputs on devices without inputs). */
    private View[] topBarPills() {
        List<View> pills = new ArrayList<>();
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
            View v = findViewById(id);
            if (v.getVisibility() == View.VISIBLE) pills.add(v);
        }
        return pills.toArray(new View[0]);
    }

    // ---- tile menu: move, hide, remove, app info, hidden apps -------------------------------------

    /** Runs the action, asking for the PIN first when one is set. */
    private void requirePinThen(String title, Runnable action) {
        if (!lock.hasPin()) {
            action.run();
            return;
        }
        PinDialog.show(this, title, pin -> {
            if (!lock.matches(pin)) return false;
            action.run();
            return true;
        });
    }

    /**
     * Sets the PIN, asking twice. One mistyped digit would otherwise mean the only way back is clearing
     * the app's data, which takes the whole launcher layout with it.
     */
    private void choosePin(Runnable then) {
        PinDialog.show(this, getString(R.string.pin_choose), first -> {
            PinDialog.show(this, getString(R.string.pin_confirm), second -> {
                if (!first.equals(second)) return false;
                lock.setPin(first);
                then.run();
                return true;
            });
            return true;
        });
    }

    @Override
    public void onTileOpen(ComponentName component, String label) {
        openTile(component, label);
    }

    @Override
    public void onTileLongPress(int position, View card) {
        // Holding OK on a remote triggers both the long-press timer and the long-press key repeat;
        // only the first may open the menu, or a second copy stays behind holding focus.
        if (openMenu != null && openMenu.isShowing()) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        if (adapter.isApp(position)) {
            // Acted on by app, not by place: an app installed or updated while the menu is open moves the
            // tiles, and the place would then point at a different app.
            String pkg = adapter.packageAt(position);
            addInputRow(rows, popup, R.drawable.ic_move, getString(R.string.move), -1, () -> {
                int at = adapter.positionOf(pkg);
                if (at < 0) return;
                adapter.startMove(at);
                Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
            });
            addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide), -1, () -> {
                int at = adapter.positionOf(pkg);
                if (at < 0) return;
                adapter.hide(at);
                grid.postOnAnimation(() -> focusTile(at));   // the tile sliding into its place
            });
            // Remove uninstalls: only downloaded apps (or updates to built-in ones) can be.
            if (adapter.canUninstall(position)) {
                addInputRow(rows, popup, R.drawable.ic_delete, getString(R.string.remove), -1,
                        () -> adapter.uninstall(adapter.positionOf(pkg)));
            }
            addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.app_info), -1,
                    () -> adapter.openAppInfo(adapter.positionOf(pkg)));
        }
        if (!adapter.userHidden().isEmpty()) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_all_apps, getString(R.string.show_hidden), -1,
                    () -> showHidden(card));
        }
        if (rows.getChildCount() == 0) return;
        showMenu(popup, panel, rows, card, Gravity.START);
    }

    /** Lists hidden apps; picking one puts it back at the end of the grid. */
    private void showHidden(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        PackageManager pm = getPackageManager();
        List<String> pkgs = new ArrayList<>(adapter.userHidden());
        Collections.sort(pkgs, (a, b) -> appLabel(pm, a).compareToIgnoreCase(appLabel(pm, b)));
        for (String pkg : pkgs) {
            addInputRow(rows, popup, R.drawable.ic_hide, appLabel(pm, pkg), -1, () -> adapter.unhide(pkg));
        }
        if (rows.getChildCount() == 0) return;
        showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    @Override
    public void onTileMoved(int position) {
        // The grid has fixed size, so it applies the move on the next animation frame without a layout
        // pass. Queue behind it on that frame, then bring the highlight (and dock title) to the new spot.
        grid.postOnAnimation(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
            if (vh == null) return;
            View card = vh.itemView.findViewById(R.id.card);
            if (!card.hasFocus()) card.requestFocus();
            onTileFocused(position, card);
        });
    }

    /** An input that reports nothing connected (not one whose state the TV doesn't know). */
    private static boolean inactive(TvInputManager tim, String id) {
        return tim.getInputState(id) == TvInputManager.INPUT_STATE_DISCONNECTED;
    }

    /** A device's name as the TV reports it over CEC, or the port's when it has none. */
    private String name(TvInputInfo device, String port) {
        CharSequence name = device.loadLabel(this);
        return name == null || name.length() == 0 ? port : name.toString();
    }

    /** A row that shows an HDMI or AV input (or a device on one), with whether something is plugged in. */
    private View addPassthroughRow(LinearLayout rows, PopupWindow popup, boolean hdmi, String label, String id,
                                   TvInputManager tim) {
        return addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, tim.getInputState(id),
                () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** A read-only row (focus skips it): icon, title and, when given, a green dot with a status. */
    private void addInfoRow(LinearLayout rows, int iconRes, String title, String status) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView statusView = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (status != null) {
            statusView.setText(status);
        } else {
            statusView.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setFocusable(false);
        row.setClickable(false);
        rows.addView(row);
    }

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // Only definite states are shown: some TVs report "standby" for ports with nothing plugged in.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }
}
