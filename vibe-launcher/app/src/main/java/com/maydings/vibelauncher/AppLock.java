package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which apps are locked, and the PIN that opens them.
 *
 * The PIN is stored as a SHA-256 of the digits: this is a lock against a child with a remote, not
 * against an attacker with the device's flash, so a hash is enough and a full KDF would only add
 * startup cost. Forgetting it means clearing the app's data (adb shell pm clear), which is why
 * there is no recovery code to get wrong.
 */
final class AppLock {

    private static final String PREFS = "applock";
    private static final String KEY_PIN = "pin";
    private static final String KEY_LOCKED = "locked";
    /** Four digits: short enough to enter with a D-pad, long enough that guessing is hopeless. */
    static final int LENGTH = 4;

    private final SharedPreferences prefs;

    AppLock(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean hasPin() {
        String h = prefs.getString(KEY_PIN, null);
        return h != null && !h.isEmpty();
    }

    /** Stores the PIN; the first one set is the one that unlocks everything. */
    void setPin(String pin) {
        prefs.edit().putString(KEY_PIN, hash(pin)).apply();
    }

    void clearPin() {
        prefs.edit().remove(KEY_PIN).remove(KEY_LOCKED).apply();
    }

    boolean matches(String pin) {
        String h = prefs.getString(KEY_PIN, null);
        return h != null && h.equals(hash(pin));
    }

    Set<String> locked() {
        return new HashSet<>(prefs.getStringSet(KEY_LOCKED, Collections.emptySet()));
    }

    boolean isLocked(String pkg) {
        return pkg != null && locked().contains(pkg);
    }

    void lock(String pkg) {
        if (pkg == null) return;
        Set<String> s = locked();
        s.add(pkg);
        prefs.edit().putStringSet(KEY_LOCKED, s).apply();
    }

    void unlock(String pkg) {
        Set<String> s = locked();
        s.remove(pkg);
        prefs.edit().putStringSet(KEY_LOCKED, s).apply();
    }

    /**
     * An app is openable when it isn't locked, or when no PIN exists yet (locking without a PIN would
     * brick the tile: nothing could ever open it, and clearing data is the only way back).
     */
    boolean needsPin(String pkg) {
        return hasPin() && isLocked(pkg);
    }

    private static String hash(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("vibe-applock:" + pin).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is always present on Android; fall back to the digits themselves rather than
            // failing closed and locking the user out of their own apps.
            return "plain:" + pin;
        }
    }
}
