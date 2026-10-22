package com.maydings.vibelauncher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A four-digit PIN entered with the D-pad: a 3x4 keypad where Left/Right/Up/Down move and OK presses
 * the digit under the cursor. No text field, because a TV remote has no number keys on most models and
 * the system IME on a 10-foot screen is worse than a grid.
 *
 * The digit is committed on OK-down and the dialog closes on OK-up: opening an activity while the OK
 * key is still down leaves the new activity receiving the key-up, which some apps read as a second
 * press.
 */
final class PinDialog {

    /** Called with the entered digits; return true to close the dialog (right PIN), false to stay. */
    interface Check {
        boolean onPin(String pin);
    }

    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕"};

    private PinDialog() {
    }

    static void show(Context ctx, String title, Check check) {
        View panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_pin, null);
        TextView titleView = panel.findViewById(R.id.pinTitle);
        TextView dots = panel.findViewById(R.id.pinDots);
        LinearLayout grid = panel.findViewById(R.id.pinGrid);
        titleView.setText(title);

        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCancelable(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.75f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        StringBuilder entered = new StringBuilder();
        TextView[] cells = new TextView[KEYS.length];
        int gap = Math.round(8 * ctx.getResources().getDisplayMetrics().density);
        for (int i = 0; i < KEYS.length; i++) {
            if (i % 3 == 0) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            LinearLayout row = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
            TextView cell = new TextView(ctx);
            cell.setText(KEYS[i]);
            cell.setTextColor(0xFFFFFFFF);
            cell.setTextSize(30);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.pin_key);
            cell.setFocusable(true);
            cell.setFocusableInTouchMode(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(gap, gap, gap, gap);
            row.addView(cell, lp);
            cells[i] = cell;

            final int index = i;
            cell.setOnClickListener(v -> press(index, entered, dots, dialog, check));
        }

        // Land on the first digit so the D-pad has somewhere to start.
        cells[0].requestFocus();
        dialog.show();
        cells[0].requestFocus();
    }

    private static void press(int index, StringBuilder entered, TextView dots, Dialog dialog, Check check) {
        String key = KEYS[index];
        if ("✕".equals(key)) {
            dialog.dismiss();
            return;
        }
        if ("⌫".equals(key)) {
            if (entered.length() > 0) entered.setLength(entered.length() - 1);
            renderDots(dots, entered.length());
            return;
        }
        if (entered.length() >= AppLock.LENGTH) return;
        entered.append(key);
        renderDots(dots, entered.length());
        if (entered.length() == AppLock.LENGTH) {
            if (check.onPin(entered.toString())) {
                dialog.dismiss();
            } else {
                // Wrong PIN: start over, so the next attempt doesn't build on the wrong digits.
                entered.setLength(0);
                renderDots(dots, 0);
                dots.postDelayed(() -> dots.setText(R.string.pin_wrong), 900);
            }
        }
    }

    private static void renderDots(TextView dots, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AppLock.LENGTH; i++) {
            if (i > 0) sb.append(' ');
            sb.append(i < count ? '●' : '○');
        }
        dots.setText(sb);
    }
}
