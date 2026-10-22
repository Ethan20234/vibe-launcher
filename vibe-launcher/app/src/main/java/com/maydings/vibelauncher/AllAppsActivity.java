package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every installed app, in a grid of its own. Home's All apps tile used to open the TV's Settings >
 * Apps screen, which on a Hisense VIDAA throws NoClassDefFoundError in AppsActivity.onCreate and
 * force-closes back to Home: a tile that does nothing. This draws the list itself, so it depends on
 * nothing but the PackageManager.
 *
 * Deliberately not the Home grid: no dock, no reordering, no banners (the point is to see every app
 * at once), and it starts focused on the first tile so the D-pad works the moment it opens.
 */
public class AllAppsActivity extends Activity {

    private static final int COLUMNS = 6;

    private final List<App> apps = new ArrayList<>();
    private Grid grid;
    private RecyclerView allAppsGrid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_apps);
        RecyclerView list = findViewById(R.id.allAppsGrid);
        allAppsGrid = list;
        list.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        list.setHasFixedSize(true);
        grid = new Grid();
        list.setAdapter(grid);
        // One listener for the whole grid: RecyclerView never reports which item holds focus, and the
        // per-child listeners only fired for the two tiles that happened to be focused at bind time.
        list.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> grid.syncRings());
        load();
        // Nothing else claims focus now that the root is not focusable, so land on the first tile:
        // without this the D-pad has no starting point and the ring never appears.
        focusFirst();
        // Back leaves; Home returns to the launcher.
        findViewById(R.id.allAppsRoot).setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP
                    && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
            return false;
        });
    }

    /**
     * The drawer lists the apps Home is hiding — nothing else.
     *
     * Home's grid is the set of apps in use; the drawer is where the rest went. Listing every app the
     * system knows about instead pulled in Hisense's own plumbing (工厂菜单, 儿童锁, 通知服务, 屏保,
     * AISDK) that nobody with a remote ever wants, so the drawer is driven by the hidden set.
     */
    private void load() {
        PackageManager pm = getPackageManager();
        // load() also runs after a lock changes, so the list has to start empty or every app is added
        // a second time and the grid shows two of each.
        apps.clear();
        Set<String> hidden = new HashSet<>(Arrays.asList(
                getResources().getStringArray(R.array.hidden_apps)));
        hidden.addAll(getSharedPreferences("tiles", MODE_PRIVATE)
                .getStringSet("hidden", Collections.emptySet()));
        hidden.remove(getPackageName());
        AppLock lock = new AppLock(this);
        Set<String> locked = lock.locked();
        // One entry per package: Settings alone declares a dozen MAIN activities, and the first one
        // found is often not the UI (CalendarDebug, 酷9's LoginActivity).
        Map<String, ResolveInfo> pick = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN), 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (!hidden.contains(pkg)) continue;
            // A package with no activity that draws a window (AISDK, DiskService) has nothing to show.
            if (isJunkEntry(ri.activityInfo.name)) continue;
            ResolveInfo prev = pick.get(pkg);
            if (prev == null || entryRank(ri.activityInfo.name) < entryRank(prev.activityInfo.name)) {
                pick.put(pkg, ri);
            }
        }
        for (ResolveInfo ri : pick.values()) {
            String pkg = ri.activityInfo.packageName;
            CharSequence label = ri.loadLabel(pm);
            if (label == null || label.length() == 0) label = pkg;
            // Prefer the app's own launch intent: it is the entry the system launcher would use.
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            android.content.ComponentName cn = launch != null && launch.getComponent() != null
                    ? launch.getComponent()
                    : new android.content.ComponentName(pkg, ri.activityInfo.name);
            apps.add(new App(String.valueOf(label), cn, ri.loadIcon(pm), locked.contains(pkg)));
        }
        Collator collator = Collator.getInstance(java.util.Locale.getDefault());
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        grid.notifyDataSetChanged();
    }

    /**
     * Activities that exist for developers or for the vendor's own test harness, not for a person
     * holding a remote: systemui's Easter egg ("甜品盒"), CalendarDebug, factory entry points, and
     * background services that register a MAIN activity only so the system can reach them.
     */
    private static boolean isJunkEntry(String name) {
        if (name.contains("Debug") || name.contains("Egg") || name.contains("Dessert")
                || name.contains("MLand") || name.contains("ConfirmDialog")) return true;
        // Service-ish names: no window to show, or a diagnostic screen. 日历信息 came out as
        // "Calendar Content Provider", DiskService and AISDK as themselves.
        String tail = name.substring(name.lastIndexOf('.') + 1);
        return tail.endsWith("Service") || tail.endsWith("Provider") || tail.endsWith("SDK");
    }

    /** Lower is a better entry point. 0 = named like a home screen, 3 = anything else. */
    private static int entryRank(String name) {
        if (name.endsWith("MainActivity") || name.endsWith("HomeActivity")) return 0;
        if (name.endsWith("LauncherActivity") || name.endsWith("EntranceActivity")) return 1;
        if (name.endsWith("Activity")) return 2;
        return 3;
    }

    /**
     * Puts focus on the first tile once the grid has laid out. A RecyclerView has no children until
     * after layout, so requesting focus in onCreate does nothing; one post after the next frame does.
     */
    private void focusFirst() {
        RecyclerView list = allAppsGrid;
        if (list == null) return;
        list.post(() -> {
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.requestFocus();
        });
    }

    static final class App {
        final String label;
        final android.content.ComponentName component;
        final android.graphics.drawable.Drawable icon;
        /** Needs the PIN before it opens. */
        final boolean locked;

        App(String label, android.content.ComponentName component, android.graphics.drawable.Drawable icon,
            boolean locked) {
            this.label = label;
            this.component = component;
            this.icon = icon;
            this.locked = locked;
        }
    }

    private final class Grid extends RecyclerView.Adapter<Grid.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final View ring;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.allAppsIcon);
                label = v.findViewById(R.id.allAppsLabel);
                ring = v.findViewById(R.id.allAppsRing);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_all_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            App a = apps.get(position);
            h.icon.setImageDrawable(a.icon);
            h.label.setText(a.label);
            View tile = h.itemView.findViewById(R.id.allAppsTile);
            // Focus lands on the item root, not the tile, so both carry the same listeners: whichever
            // one the framework gave focus to, OK and a held OK work.
            View[] targets = tile != null ? new View[]{tile, h.itemView} : new View[]{h.itemView};
            for (View target : targets) {
                target.setOnClickListener(v -> open(a));
                // The lock lives here, on the drawer's tiles: Home's grid is what a child is supposed
                // to reach, so the lock is set where the owner manages what is out of reach.
                target.setOnLongClickListener(v -> {
                    showLockMenu(a, h.itemView);
                    return true;
                });
                // A held OK arrives as key repeats flagged long-press, which is what a remote sends;
                // the touch long-press above never fires for it.
                target.setOnKeyListener((v, keyCode, event) -> {
                    boolean ok = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                    if (ok && event.isLongPress()) {
                        v.cancelLongPress();
                        showLockMenu(a, h.itemView);
                        return true;
                    }
                    return false;
                });
            }
            bindFocus(h.itemView);
        }

        /**
         * RecyclerView never reports which item holds focus, and focus can land on the item root or on
         * the tile inside it. A global focus listener rescans the visible items, so the ring follows the
         * D-pad whatever the framework focused and whichever item was recycled into view.
         */
        private void bindFocus(View item) {
            View tile = item.findViewById(R.id.allAppsTile);
            View ring = item.findViewById(R.id.allAppsRing);
            if (tile == null || ring == null) return;
            View label = item.findViewById(R.id.allAppsLabel);
            boolean focused = tile.hasFocus() || item.hasFocus();
            ring.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            tile.setScaleX(focused ? 1.08f : 1f);
            tile.setScaleY(focused ? 1.08f : 1f);
            if (label != null) label.setAlpha(focused ? 1f : 0.72f);
        }

        /** Re-checks every laid-out item, so exactly the focused one wears the ring. */
        void syncRings() {
            RecyclerView list = allAppsGrid;
            if (list == null) return;
            for (int i = 0; i < list.getChildCount(); i++) bindFocus(list.getChildAt(i));
        }
        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    /**
     * System apps on China-market TVs declare only a plain LAUNCHER category, so a LEANBACK-only
     * Intent lands on "can't open". Same three-step fallback as the Home tiles.
     */
    private void open(App a) {
        // A locked app in this drawer asks for the PIN too, or the drawer becomes the way around it.
        if (a.locked) {
            PinDialog.show(this, getString(R.string.pin_title, a.label), pin -> {
                if (!new AppLock(this).matches(pin)) return false;
                start(a);
                return true;
            });
            return;
        }
        start(a);
    }

    private void start(App a) {
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER, null}) {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .setComponent(a.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (category != null) i.addCategory(category);
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        android.widget.Toast.makeText(this, getString(R.string.cant_open, a.label),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---- app lock: set from the drawer's tiles ---------------------------------------------------

    /** Lock or unlock this app, or set the PIN the first time. */
    private void showLockMenu(App a, View anchor) {
        final android.widget.PopupWindow[] holder = new android.widget.PopupWindow[1];
        android.widget.LinearLayout rows = new android.widget.LinearLayout(this);
        rows.setOrientation(android.widget.LinearLayout.VERTICAL);
        rows.setBackgroundResource(R.drawable.glass_panel);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        rows.setPadding(pad, pad, pad, pad);

        AppLock lock = new AppLock(this);
        if (lock.isLocked(a.component.getPackageName())) {
            addRow(rows, holder, R.drawable.ic_lock, getString(R.string.unlock_app),
                    () -> withPin(getString(R.string.pin_title_unlock), () -> {
                        new AppLock(this).unlock(a.component.getPackageName());
                        Toast.makeText(this, getString(R.string.unlocked_app, a.label), Toast.LENGTH_SHORT).show();
                        load();
                    }));
        } else {
            addRow(rows, holder, R.drawable.ic_lock, getString(R.string.lock_app), () -> {
                // A lock with no PIN could never be opened, so the first one asks for the PIN first.
                if (!new AppLock(this).hasPin()) {
                    choosePin(() -> {
                        new AppLock(this).lock(a.component.getPackageName());
                        Toast.makeText(this, getString(R.string.locked_app, a.label), Toast.LENGTH_SHORT).show();
                        load();
                    });
                    return;
                }
                withPin(getString(R.string.pin_title_unlock), () -> {
                    new AppLock(this).lock(a.component.getPackageName());
                    Toast.makeText(this, getString(R.string.locked_app, a.label), Toast.LENGTH_SHORT).show();
                    load();
                });
            });
        }

        android.widget.PopupWindow popup = new android.widget.PopupWindow(rows,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        holder[0] = popup;
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.showAsDropDown(anchor, 0, -anchor.getHeight());
    }

    private void addRow(android.widget.LinearLayout rows, android.widget.PopupWindow[] holder,
                        int iconRes, String title, Runnable action) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.pin_key);
        int p = Math.round(10 * getResources().getDisplayMetrics().density);
        row.setPadding(p, p, p, p);
        row.setMinimumWidth(Math.round(200 * getResources().getDisplayMetrics().density));

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        int s = Math.round(22 * getResources().getDisplayMetrics().density);
        row.addView(icon, new android.widget.LinearLayout.LayoutParams(s, s));

        android.widget.TextView text = new android.widget.TextView(this);
        text.setText(title);
        text.setTextSize(17);
        text.setTextColor(0xFFFFFFFF);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(12 * getResources().getDisplayMetrics().density));
        row.addView(text, lp);

        row.setOnClickListener(v -> {
            holder[0].dismiss();
            action.run();
        });
        rows.addView(row);
        if (rows.getChildCount() == 1) row.requestFocus();
    }

    /** Runs the action after the PIN, or straight away when none is set. */
    private void withPin(String title, Runnable action) {
        if (!new AppLock(this).hasPin()) {
            action.run();
            return;
        }
        PinDialog.show(this, title, pin -> {
            if (!new AppLock(this).matches(pin)) return false;
            action.run();
            return true;
        });
    }

    /** Sets the PIN, asked twice: one mistyped digit would leave no way back but clearing app data. */
    private void choosePin(Runnable then) {
        PinDialog.show(this, getString(R.string.pin_choose), first -> {
            // Close this dialog first, or the second one stacks on top of it.
            PinDialog.show(this, getString(R.string.pin_confirm), second -> {
                if (!first.equals(second)) return false;
                new AppLock(this).setPin(first);
                then.run();
                return true;
            });
            return true;
        });
    }
}
