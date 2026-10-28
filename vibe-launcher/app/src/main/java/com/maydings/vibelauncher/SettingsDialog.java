package com.maydings.vibelauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Vibe Launcher settings on two tabs. General: tiles per row and updates (Updater). API keys: the TMDB,
 * YouTube and Plex keys, each with an (i) explaining it, SmartTube to install or sign in to (the key-free
 * way to YouTube backdrops), and a QR code for pasting the keys from a phone (PhoneLink). Save and Cancel
 * serve both tabs.
 */
final class SettingsDialog {

    private SettingsDialog() {
    }

    static void show(Activity activity, ViewGroup root) {
        float density = activity.getResources().getDisplayMetrics().density;
        VibeSettings settings = new VibeSettings(activity);
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_settings, root, false);
        EditText tmdb = panel.findViewById(R.id.tmdbKey);
        EditText youtube = panel.findViewById(R.id.youtubeKey);
        EditText plex = panel.findViewById(R.id.plexToken);
        // Saved keys show only their last four characters. Focusing a field selects it, so typing replaces
        // the key and clearing it removes it; left alone, the saved key stays.
        EditText[] fields = {tmdb, youtube, plex};
        String[] saved = {settings.tmdbKey(), settings.youtubeKey(), settings.plexToken()};
        for (int i = 0; i < fields.length; i++) {
            fields[i].setText(mask(saved[i]));
            fields[i].setSelectAllOnFocus(true);
        }
        Runnable saveKeys = () -> {
            for (int i = 0; i < fields.length; i++) saved[i] = value(fields[i], saved[i]);
            settings.save(saved[0], saved[1], saved[2]);
        };
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Near the top, so the on-screen keyboard (bottom half on TVs) doesn't cover the fields,
            // and the keyboard only comes up once a field is picked.
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.y = (int) (24 * density);
            window.setAttributes(lp);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            // The dialog theme caps the width; the fields and the QR code sit side by side.
            window.setLayout((int) (620 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        View cancel = panel.findViewById(R.id.cancel), save = panel.findViewById(R.id.save);
        View tmdbInfo = panel.findViewById(R.id.tmdbInfo), youtubeInfo = panel.findViewById(R.id.youtubeInfo),
                plexInfo = panel.findViewById(R.id.plexInfo);
        tmdbInfo.setOnClickListener(v -> showInfo(activity, v, R.string.tmdb_info));
        youtubeInfo.setOnClickListener(v -> showInfo(activity, v, R.string.youtube_info));
        plexInfo.setOnClickListener(v -> showInfo(activity, v, R.string.plex_info));
        // Tiles per row: one of three pills, the current choice marked; applied on Save.
        int current = MainActivity.COLUMNS;
        int[] chosen = {current};
        View[] columnPills = {panel.findViewById(R.id.columns4), panel.findViewById(R.id.columns5),
                panel.findViewById(R.id.columns6)};
        for (int i = 0; i < columnPills.length; i++) {
            int value = 4 + i;
            columnPills[i].setSelected(value == current);
            columnPills[i].setOnClickListener(v -> {
                chosen[0] = value;
                for (View p : columnPills) p.setSelected(p == v);
            });
        }
        View columnsEntry = columnPills[Math.max(0, Math.min(2, current - 4))];
        TextView updateButton = panel.findViewById(R.id.updateButton);
        // Language: the TV's own ("") or one from Lang; like tiles per row, applied on Save.
        TextView languageButton = panel.findViewById(R.id.languageButton);
        String savedLanguage = settings.language();
        String[] chosenLanguage = {savedLanguage};
        Runnable showLanguage = () -> languageButton.setText(chosenLanguage[0].isEmpty()
                ? activity.getString(R.string.language_system) : Lang.name(chosenLanguage[0]));
        showLanguage.run();
        languageButton.setOnClickListener(v -> pickLanguage(activity, v, chosenLanguage[0], tag -> {
            chosenLanguage[0] = tag;
            showLanguage.run();
            languageButton.requestFocus();
        }));
        // App lock: the PIN that guards locked tiles. Change asks for the current one first; Set appears
        // while there is none. Applied at once rather than on Save — it is its own dialog, and Cancel
        // closing it should not silently undo a PIN change.
        AppLock lock = new AppLock(activity);
        TextView pinButton = panel.findViewById(R.id.pinButton);
        pinButton.setText(lock.hasPin() ? R.string.pin_change : R.string.pin_set);
        pinButton.setOnClickListener(v -> {
            Runnable change = () -> PinDialog.chooseNew(activity, lock, () -> {
                pinButton.setText(R.string.pin_change);
                Toast.makeText(activity, R.string.pin_changed, Toast.LENGTH_SHORT).show();
            });
            if (!lock.hasPin()) {
                change.run();
                return;
            }
            PinDialog.show(activity, activity.getString(R.string.pin_current), pin -> {
                if (!lock.matches(pin)) return false;
                change.run();
                return true;
            });
        });
        // Hide inactive inputs: Off or On, the choice marked; applied on Save.
        boolean savedHideInputs = settings.hideInactiveInputs();
        boolean[] hideInputs = {savedHideInputs};
        View[] hidePills = {panel.findViewById(R.id.hideInputsOff), panel.findViewById(R.id.hideInputsOn)};
        for (int i = 0; i < hidePills.length; i++) {
            boolean value = i == 1;
            hidePills[i].setSelected(value == savedHideInputs);
            hidePills[i].setOnClickListener(v -> {
                hideInputs[0] = value;
                for (View p : hidePills) p.setSelected(p == v);
            });
        }
        View hideEntry = hidePills[savedHideInputs ? 1 : 0];
        TextView updateStatus = panel.findViewById(R.id.updateStatus);
        View tabGeneral = panel.findViewById(R.id.tabGeneral), tabKeys = panel.findViewById(R.id.tabKeys);
        View pageGeneral = panel.findViewById(R.id.pageGeneral), pageKeys = panel.findViewById(R.id.pageKeys);
        View phone = panel.findViewById(R.id.phone), projectLink = panel.findViewById(R.id.projectLink);
        boolean[] phoneReady = {false};
        // A tab shows its page as soon as it has focus, like the tabs in the TV's own apps.
        Runnable showGeneral = () -> {
            // Each tab is as tall as it needs: the dialog is pinned to the top of the screen, so the title,
            // tabs, Save and Cancel stay put and only the bottom edge moves.
            pageGeneral.setVisibility(View.VISIBLE);
            pageKeys.setVisibility(View.GONE);
            phone.setVisibility(View.GONE);
            projectLink.setVisibility(View.VISIBLE);
            tabGeneral.setSelected(true);
            tabKeys.setSelected(false);
        };
        Runnable showKeys = () -> {
            pageGeneral.setVisibility(View.GONE);
            pageKeys.setVisibility(View.VISIBLE);
            phone.setVisibility(phoneReady[0] ? View.VISIBLE : View.GONE);   // the QR code is for the keys
            projectLink.setVisibility(View.GONE);
            tabGeneral.setSelected(false);
            tabKeys.setSelected(true);
        };
        tabGeneral.setOnFocusChangeListener((v, focused) -> { if (focused) showGeneral.run(); });
        tabKeys.setOnFocusChangeListener((v, focused) -> { if (focused) showKeys.run(); });
        tabGeneral.setOnClickListener(v -> columnsEntry.requestFocus());
        tabKeys.setOnClickListener(v -> tmdbInfo.requestFocus());

        // Text fields keep Up/Down to themselves on this TV, so steer the remote by hand. Up from the top
        // of a page reaches its tab; Right from anything jumps to Save (and Cancel under it), and Left
        // comes back to where it left.
        View[] cameFrom = {columnsEntry};
        TextView smartTube = panel.findViewById(R.id.smartTubeButton);
        View[] order = {tmdbInfo, tmdb, youtubeInfo, smartTube, youtube, plexInfo, plex};
        for (int i = 0; i < order.length; i++) {
            steer(order[i], i > 0 ? order[i - 1] : tabKeys, i < order.length - 1 ? order[i + 1] : null, save, cameFrom);
        }
        View.OnKeyListener tabKeysNav = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;   // nothing above
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return v == tabGeneral ? columnsEntry.requestFocus() : tmdbInfo.requestFocus();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v == tabGeneral || tabGeneral.requestFocus();
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (v == tabGeneral) return tabKeys.requestFocus();
                cameFrom[0] = v;
                return save.requestFocus();
            }
            return false;
        };
        tabGeneral.setOnKeyListener(tabKeysNav);
        tabKeys.setOnKeyListener(tabKeysNav);
        for (View pill : columnPills) {
            pill.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return tabGeneral.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return hideEntry.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == columnPills[2]) {
                    cameFrom[0] = v;
                    return save.requestFocus();
                }
                return false;   // Left/Right move between the pills
            });
        }
        steer(languageButton, hideEntry, pinButton, save, cameFrom);
        steer(pinButton, languageButton, updateButton.getVisibility() == View.VISIBLE ? updateButton : null, save, cameFrom);
        for (View pill : hidePills) {
            pill.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return columnsEntry.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return languageButton.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == hidePills[1]) {
                    cameFrom[0] = v;
                    return save.requestFocus();
                }
                return false;   // Left/Right move between the pills
            });
        }
        steer(updateButton, pinButton, null, save, cameFrom);
        showUpdates(activity, updateStatus, updateButton);
        View.OnKeyListener back = (v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cameFrom[0].requestFocus();
        save.setOnKeyListener(back);
        cancel.setOnKeyListener(back);
        tmdb.setOnEditorActionListener((v, actionId, event) -> youtube.requestFocus());
        youtube.setOnEditorActionListener((v, actionId, event) -> plex.requestFocus());
        plex.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(plex.getWindowToken(), 0);
            return save.requestFocus();
        });
        // Typing a 32-character key with a remote is painful: offer a page to paste it from a phone.
        PhoneLink link = new PhoneLink(received -> {
            if (!dialog.isShowing()) return;
            String[] names = {"tmdbKey", "youtubeKey", "plexToken"};
            for (int i = 0; i < names.length; i++) {
                if (received.containsKey(names[i])) fields[i].setText(received.get(names[i]));
            }
            saveKeys.run();
            for (int i = 0; i < fields.length; i++) fields[i].setText(mask(saved[i]));
            Toast.makeText(activity, R.string.saved_from_phone, Toast.LENGTH_SHORT).show();
        }, settings);
        String url = link.start();
        if (url != null) {
            ((ImageView) panel.findViewById(R.id.qr)).setImageBitmap(PhoneLink.qr(url, (int) (114 * density)));
            ((TextView) panel.findViewById(R.id.phoneUrl)).setText(url.replace("http://", ""));
            phoneReady[0] = true;
        }
        // Open SmartTube to sign in, or install it first. The label follows along: checked again when an app
        // is installed or removed (the installer's screen closes before the install is done), and whenever
        // the dialog gets the screen back.
        Runnable showSmartTube = () -> smartTube.setText(SmartTube.launchIntent(activity) != null
                ? R.string.smarttube_open : R.string.smarttube_install);
        showSmartTube.run();
        panel.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> { if (hasFocus) showSmartTube.run(); });
        BroadcastReceiver packages = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                showSmartTube.run();
            }
        };
        IntentFilter packageFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(packages, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(packages, packageFilter);
        smartTube.setOnClickListener(v -> {
            Intent open = SmartTube.launchIntent(activity);
            if (open != null) {
                activity.startActivity(open);
                return;
            }
            Toast.makeText(activity, R.string.smarttube_downloading, Toast.LENGTH_LONG).show();
            SmartTube.install(activity, res -> Toast.makeText(activity,
                    res == R.string.update_failed ? R.string.smarttube_failed : res, Toast.LENGTH_LONG).show());
        });
        showGeneral.run();
        dialog.setOnDismissListener(d -> {
            link.stop();
            activity.unregisterReceiver(packages);
            // An update found here shows on the Settings pill straight away, not at the next return to Home.
            if (activity instanceof MainActivity) ((MainActivity) activity).showUpdateReady();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            saveKeys.run();
            dialog.dismiss();
            Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show();
            boolean rebuild = chosen[0] != current || !chosenLanguage[0].equals(savedLanguage);
            if (chosen[0] != current) settings.setColumns(chosen[0]);
            if (!chosenLanguage[0].equals(savedLanguage)) settings.setLanguage(chosenLanguage[0]);
            if (hideInputs[0] != savedHideInputs) settings.setHideInactiveInputs(hideInputs[0]);   // read when Inputs opens
            if (rebuild) activity.recreate();   // rebuild Home with the new grid or language
        });
        dialog.show();
        columnsEntry.requestFocus();
    }

    /**
     * The languages in a scrolling list over the dialog: Match system first, then each in its own name.
     * Opens on the one in use.
     */
    private static void pickLanguage(Activity activity, View anchor, String current, java.util.function.Consumer<String> picked) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (6 * density);
        scroll.setPadding(pad, pad, pad, pad);
        scroll.addView(rows);
        int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.7f);
        PopupWindow popup = new PopupWindow(scroll, (int) (340 * density), height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        View selected = null;
        for (int i = -1; i < Lang.TAGS.length; i++) {
            String tag = i < 0 ? "" : Lang.TAGS[i];
            View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((TextView) row.findViewById(R.id.title)).setText(i < 0 ? activity.getString(R.string.language_system) : Lang.NAMES[i]);
            row.findViewById(R.id.icon).setVisibility(View.GONE);
            row.findViewById(R.id.status).setVisibility(View.GONE);
            row.findViewById(R.id.dot).setVisibility(tag.equals(current) ? View.VISIBLE : View.GONE);   // the one in use
            row.setOnClickListener(v -> {
                popup.dismiss();
                picked.accept(tag);
            });
            rows.addView(row);
            if (tag.equals(current)) selected = row;
        }
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);   // on the dialog's window, so above it
        View start = selected != null ? selected : rows.getChildAt(0);
        start.requestFocus();
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, start.getTop() - height / 3)));
    }

    /** A saved key as shown in its field: bullets and the last four characters; "" when not set. */
    private static String mask(String key) {
        return key.isEmpty() ? "" : "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" + key.substring(Math.max(0, key.length() - 4));
    }

    /** What a field holds: the saved key while it still shows the mask, else what was typed. */
    private static String value(EditText field, String saved) {
        String text = field.getText().toString().trim();
        return !saved.isEmpty() && text.equals(mask(saved)) ? saved : text;
    }

    /** Up and Down move to these views; Right goes to Save, remembering where it came from. */
    private static void steer(View view, View up, View down, View save, View[] cameFrom) {
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cameFrom[0] = v;
                return save.requestFocus();
            }
            View to = keyCode == KeyEvent.KEYCODE_DPAD_UP ? up : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? down : null;
            return to != null && to.requestFocus();
        });
    }

    /** A glass bubble by the (i) explaining a key; Back or OK closes it. */
    private static void showInfo(Activity activity, View anchor, int textRes) {
        float density = activity.getResources().getDisplayMetrics().density;
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        TextView text = new TextView(activity);
        text.setText(textRes);
        text.setTextColor(0xE6FFFFFF);
        text.setTextSize(15);
        text.setLineSpacing(0, 1.15f);
        text.setTypeface(activity.getResources().getFont(R.font.outfit));
        int pad = (int) (18 * density);
        text.setPadding(pad, pad, pad, pad);
        text.setFocusable(true);
        // In a ScrollView in case even the widest bubble is taller than the screen allows; Down scrolls it.
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundResource(R.drawable.glass_panel);
        scroll.addView(text);
        // Below the (i), or above it when there's more room there (the Plex one sits low on screen). Longer
        // translations get a wider bubble before they get a scrolling one.
        int gap = (int) (6 * density), margin = (int) (24 * density);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int below = screenH - at[1] - anchor.getHeight() - gap - margin, above = at[1] - gap - margin;
        int room = Math.max(below, above);
        int width = 0, height = 0;
        for (int dp : new int[]{440, 560, 680}) {
            width = Math.min((int) (dp * density), screenW - 2 * margin);
            text.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            height = text.getMeasuredHeight();
            if (height <= room) break;
        }
        boolean fitsBelow = height <= below;
        height = Math.min(height, room);
        PopupWindow bubble = new PopupWindow(scroll, width, height, true);
        bubble.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        bubble.setOutsideTouchable(true);
        text.setOnClickListener(v -> bubble.dismiss());
        int dx = Math.max(margin, Math.min(at[0], screenW - margin - width)) - at[0];
        boolean under = fitsBelow || below >= above;
        bubble.showAsDropDown(anchor, dx, under ? gap : -(anchor.getHeight() + gap + height));
        text.requestFocus();
    }

    /**
     * The Updates part of General: the installed version and a button that checks for a newer one, then
     * offers to install it. A development build only says how it updates.
     */
    private static void showUpdates(Activity activity, TextView status, TextView button) {
        String version = Updater.installedVersion(activity);
        if (Updater.isDevelopmentBuild(activity)) {
            status.setText(activity.getString(R.string.update_development_status, version));
            button.setVisibility(View.GONE);
            return;
        }
        // What the last check found: the gear menu, which opens this dialog, has just asked GitHub again.
        Updater.Release found = Updater.available(activity);
        if (found != null) {
            offerUpdate(activity, status, button, found);
            return;
        }
        status.setText(activity.getString(R.string.version_current, version));
        button.setText(R.string.check_updates);
        button.setOnClickListener(v -> {
            button.setText(R.string.checking_updates);
            button.setEnabled(false);
            Updater.check(activity, (newer, failed) -> {
                button.setEnabled(true);
                if (newer != null) {
                    offerUpdate(activity, status, button, newer);
                } else {
                    status.setText(activity.getString(failed ? R.string.update_check_failed : R.string.version_up_to_date, version));
                    button.setText(R.string.check_updates);
                }
                button.requestFocus();
            });
        });
    }

    private static void offerUpdate(Activity activity, TextView status, TextView button, Updater.Release release) {
        status.setText(activity.getString(R.string.update_available, release.version));
        button.setText(activity.getString(R.string.update_to, release.version));
        button.setOnClickListener(v -> startUpdate(activity, release));
    }

    /**
     * What's new in the release, with Install and Cancel; Install downloads it and Android's own screen asks
     * to confirm. Shared with the gear menu.
     */
    static void startUpdate(Activity activity, Updater.Release release) {
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_update, null);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(activity.getString(R.string.update_to, release.version));
        TextView notes = panel.findViewById(R.id.notes);
        CharSequence text = changes(activity, release.notes);
        notes.setText(text);
        ScrollView scroll = panel.findViewById(R.id.notesScroll);
        scroll.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        float density = activity.getResources().getDisplayMetrics().density;
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (560 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        // Long notes scroll in at most half the screen; then Up from the buttons reaches them, and Up and
        // Down scroll.
        int cap = activity.getResources().getDisplayMetrics().heightPixels / 2;
        scroll.post(() -> {
            if (scroll.getHeight() <= cap) return;
            scroll.getLayoutParams().height = cap;
            scroll.setFocusable(true);
            scroll.requestLayout();
        });
        View install = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        cancel.setOnClickListener(v -> dialog.dismiss());
        install.setOnClickListener(v -> {
            dialog.dismiss();
            Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show();
            Updater.install(activity, release, problem -> Toast.makeText(activity, problem, Toast.LENGTH_LONG).show());
        });
        dialog.show();
        install.requestFocus();
    }

    /**
     * The release notes as shown before installing: "## 1.4.3" lines as version headings, **bold** in white
     * Outfit Medium (Outfit has no bold), "- " items as bullets, links as their text, and the download link
     * and checksum (for people downloading by hand) left out.
     */
    private static CharSequence changes(Context context, String markdown) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        android.graphics.Typeface medium = context.getResources().getFont(R.font.outfit_medium);
        for (String line : markdown.replace("\r", "").split("\n")) {
            String l = line.trim();
            if (l.startsWith("Latest APK") || l.startsWith("SHA-256")) continue;
            // A link shows as its text (a TV can't follow it), inline code without its backticks.
            l = l.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1").replaceAll("`([^`]*)`", "$1");
            if (l.startsWith("- ") || l.startsWith("* ")) l = "• " + l.substring(2);
            if (l.isEmpty() && (out.length() == 0 || endsWithBlankLine(out))) continue;
            if (l.startsWith("#")) {
                // A version's notes when several are shown (Updater.notesSince): its number, larger, after a
                // blank line.
                if (out.length() > 0 && !endsWithBlankLine(out)) out.append('\n');
                int start = out.length();
                out.append(l.replaceFirst("^#+\\s*", ""));
                Object weight = android.os.Build.VERSION.SDK_INT >= 28 ? new android.text.style.TypefaceSpan(medium)
                        : new android.text.style.StyleSpan(android.graphics.Typeface.BOLD);
                out.setSpan(weight, start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.ForegroundColorSpan(Color.WHITE), start, out.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.RelativeSizeSpan(1.25f), start, out.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            int at = 0;
            while (true) {
                int open = l.indexOf("**", at);
                int close = open < 0 ? -1 : l.indexOf("**", open + 2);
                if (close < 0) break;
                out.append(l, at, open);
                int start = out.length();
                out.append(l, open + 2, close);
                Object weight = android.os.Build.VERSION.SDK_INT >= 28 ? new android.text.style.TypefaceSpan(medium)
                        : new android.text.style.StyleSpan(android.graphics.Typeface.BOLD);
                out.setSpan(weight, start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.ForegroundColorSpan(Color.WHITE), start, out.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                at = close + 2;
            }
            out.append(l.substring(at)).append('\n');
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    private static boolean endsWithBlankLine(CharSequence s) {
        return s.length() >= 2 && s.charAt(s.length() - 1) == '\n' && s.charAt(s.length() - 2) == '\n';
    }
}
