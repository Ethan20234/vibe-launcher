package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Popular films and shows for the tiles of apps that publish no TV provider cards — Emby, Jellyfin,
 * Kodi and the like on most TVs. Those apps leave the provider empty, so the launcher would otherwise
 * fall back to the colour glow and the owner would never see any artwork at all.
 *
 * The source is the same free Stremio metadata {@link Stremio} uses, but by list rather than by title:
 * Cinemeta's top catalogs, which already carry each entry's textless backdrop and its logo, so one
 * request fills a whole pool. No key, no server of the owner's own, and the list turns over daily.
 *
 * Two details the catalogue gets wrong for a plain Android HTTP client:
 * <ul>
 *   <li>{@code images.metahub.space} answers 307 to {@code live.metahub.space}, and
 *       {@link Http} does not follow redirects, so the host is rewritten here.</li>
 *   <li>A bare {@code Java/1.x} user agent gets a 403 from the catalogue; an ordinary one gets 200.</li>
 * </ul>
 *
 * Not an official public API: any failure simply means the caller keeps its own art.
 */
final class Popular {

    private static final String CATALOG = "https://cinemeta-catalogs.strem.io/top/catalog/%s/top.json";
    private static final String REDIRECTING_HOST = "images.metahub.space";
    private static final String IMAGE_HOST = "live.metahub.space";
    private static final long CACHE_MS = 60L * 60 * 1000;   // an hour: pools are rebuilt every six
    private static final Map<String, String> HEADERS =
            Collections.singletonMap("User-Agent", "VibeLauncher/1.5.1 (Android TV)");

    private final SharedPreferences cache;

    Popular(Context context) {
        cache = context.getSharedPreferences("popular_cache", Context.MODE_PRIVATE);
    }

    /** One title from the list: its textless backdrop and its logo, both URLs. */
    static final class Entry {
        final String title, backdrop, logo;

        Entry(String title, String backdrop, String logo) {
            this.title = title;
            this.backdrop = backdrop;
            this.logo = logo;
        }
    }

    /**
     * The top films and shows, most popular first, cached for six hours. Network: worker thread only.
     * Returns an empty list when nothing could be fetched, so the caller falls back as before.
     */
    List<Entry> entries() {
        long now = System.currentTimeMillis();
        String cached = cache.getString("list", "");
        if (!cached.isEmpty()) {
            int bar = cached.indexOf('|');
            if (bar > 0 && now - parse(cached.substring(0, bar)) < CACHE_MS) {
                List<Entry> entries = decode(cached.substring(bar + 1));
                if (!entries.isEmpty()) return entries;
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (String type : new String[]{"movie", "series"}) {
            try {
                String body = Http.getText(String.format(CATALOG, type), HEADERS);
                entries.addAll(decode(new JSONObject(body).optJSONArray("metas")));
            } catch (IOException | JSONException | RuntimeException e) {
                // Offline, or the service changed: whatever came back from the other list is used.
            }
        }
        if (!entries.isEmpty()) cache.edit().putString("list", now + "|" + encode(entries)).apply();
        return entries;
    }

    /**
     * The usable entries of a Cinemeta list. The backdrop is kept at its 1280x720 "medium" size — the
     * 4K "large" one is 1.4MB and buys nothing once it is cut to the screen — while the logo is taken
     * at "large", since the medium size is sometimes the portrait poster instead of the title.
     */
    private static List<Entry> decode(JSONArray metas) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; metas != null && i < metas.length(); i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null) continue;
            String title = m.optString("name");
            String backdrop = live(m.optString("background"));
            String logo = live(m.optString("logo")).replace("/medium/", "/large/");
            if (title.isEmpty() || backdrop.isEmpty() || logo.isEmpty()) continue;
            out.add(new Entry(title, backdrop, logo));
        }
        return out;
    }

    /** The same artwork on the host that serves it directly, without the 307 the client won't follow. */
    private static String live(String url) {
        return url.replace(REDIRECTING_HOST, IMAGE_HOST);
    }

    /** The cache holds the list as one line of tab-separated fields, a row per entry. */
    private static String encode(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.title.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(e.backdrop).append('\t').append(e.logo);
        }
        return sb.toString();
    }

    private static List<Entry> decode(String text) {
        List<Entry> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length == 3 && !f[0].isEmpty() && !f[1].isEmpty()) out.add(new Entry(f[0], f[1], f[2]));
        }
        return out;
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
