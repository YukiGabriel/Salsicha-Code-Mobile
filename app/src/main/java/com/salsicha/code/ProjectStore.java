package com.salsicha.code;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Guarda projeto atual (treeUri) + recentes, estilo IntelliJ. */
public final class ProjectStore {
    private ProjectStore() {}

    private static final String PREFS = "salsicha_projects";
    public static final String EXTRA_PROJECT_URI = "project_tree_uri";
    public static final String EXTRA_PROJECT_NAME = "project_name";

    public static void setCurrent(Context ctx, Uri treeUri, String name) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("current_uri", treeUri != null ? treeUri.toString() : null)
                    .putString("current_name", name != null ? name : "")
                    .apply();
        } catch (Exception ignored) {}
        if (treeUri != null) pushRecent(ctx, name, treeUri);
    }

    public static Uri currentUri(Context ctx) {
        try {
            String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("current_uri", null);
            return s != null ? Uri.parse(s) : null;
        } catch (Exception e) { return null; }
    }

    public static String currentName(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("current_name", "");
        } catch (Exception e) { return ""; }
    }

    public static void clearCurrent(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove("current_uri").remove("current_name").apply();
        } catch (Exception ignored) {}
    }

    public static void pushRecent(Context ctx, String name, Uri uri) {
        if (uri == null) return;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> set = new LinkedHashSet<>(p.getStringSet("recents", new LinkedHashSet<>()));
            String n = (name == null || name.isEmpty()) ? displayName(ctx, uri) : name;
            String entry = n + "|" + uri.toString();
            set.removeIf(s -> s.endsWith("|" + uri.toString()));
            set.add(entry);
            while (set.size() > 12) set.remove(set.iterator().next());
            p.edit().putStringSet("recents", set).apply();
        } catch (Exception ignored) {}
    }

    public static List<String[]> recents(Context ctx) {
        List<String[]> out = new ArrayList<>();
        try {
            Set<String> set = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet("recents", new LinkedHashSet<>());
            List<String> rev = new ArrayList<>(set);
            for (int i = rev.size() - 1; i >= 0; i--) {
                String[] parts = rev.get(i).split("\\|", 2);
                if (parts.length == 2) out.add(parts);
                else if (parts.length == 1) out.add(new String[]{parts[0], parts[0]});
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void removeRecent(Context ctx, String uri) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> set = new LinkedHashSet<>(p.getStringSet("recents", new LinkedHashSet<>()));
            set.removeIf(s -> s.endsWith("|" + uri));
            p.edit().putStringSet("recents", set).apply();
        } catch (Exception ignored) {}
    }

    public static String displayName(Context ctx, Uri uri) {
        if (uri == null) return "Projeto";
        try {
            androidx.documentfile.provider.DocumentFile d =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri);
            if (d != null && d.getName() != null) return d.getName();
        } catch (Exception ignored) {}
        try {
            String p = uri.getLastPathSegment();
            if (p != null) {
                int k = p.lastIndexOf(':');
                if (k >= 0 && k + 1 < p.length()) p = p.substring(k + 1);
                int s = p.lastIndexOf('/');
                if (s >= 0) p = p.substring(s + 1);
                if (!p.isEmpty()) return p;
            }
        } catch (Exception ignored) {}
        return "Projeto";
    }
}
