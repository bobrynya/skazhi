package ru.home.skazhi;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// Loads commands.json and matches recognized text to an action.
// A command is {"phrases":[...], <action>:<arg>} with action one of
// launch (package), volume (up|down|mute), key (KEYCODE_* media key), global (home|back|lock).
// Launch phrases are app names; every launch verb is prepended automatically.
public class Commands {
    public static class Hit {
        public final String action, arg, query;
        Hit(String action, String arg, String query) { this.action = action; this.arg = arg; this.query = query; }
        @Override public String toString() { return action + ":" + arg + (query != null ? " q=" + query : ""); }
    }

    static final String[] ACTIONS = {"launch", "volume", "key", "global"};

    public final String server;
    public final int triggerScan;
    private final List<String[]> phrases = new ArrayList<>(); // {normalized phrase, action, arg}, longest first
    private final List<String> searchPrefixes = new ArrayList<>();
    private String searchPackage;
    private final String grammarJson;

    public Commands(Context ctx) throws Exception {
        this(readAsset(ctx, "commands.json"));
    }

    Commands(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        server = root.getString("server");
        triggerScan = root.optInt("trigger_scan_code", 63);
        JSONArray verbs = root.optJSONArray("launch_verbs");
        JSONObject search = root.optJSONObject("search");
        if (search != null) {
            searchPackage = search.getString("package");
            JSONArray ps = search.getJSONArray("prefixes");
            for (int j = 0; j < ps.length(); j++) searchPrefixes.add(norm(ps.getString(j)));
        }
        JSONArray cmds = root.getJSONArray("commands");
        JSONArray gram = new JSONArray();
        for (int i = 0; i < cmds.length(); i++) {
            JSONObject c = cmds.getJSONObject(i);
            String action = null;
            for (String a : ACTIONS) if (c.has(a)) action = a;
            if (action == null) throw new IllegalArgumentException("command without action: " + c);
            String arg = c.getString(action);
            JSONArray ps = c.getJSONArray("phrases");
            for (int j = 0; j < ps.length(); j++) {
                // Grammar keeps the raw spelling: the model vocabulary has "вперёд", not "вперед".
                String raw = ps.getString(j).toLowerCase();
                phrases.add(new String[]{norm(raw), action, arg});
                gram.put(raw);
                if (action.equals("launch") && verbs != null)
                    for (int k = 0; k < verbs.length(); k++) gram.put(verbs.getString(k) + " " + raw);
            }
        }
        // Longest first so "включи звук" wins over a shorter phrase it contains.
        phrases.sort((a, b) -> b[0].length() - a[0].length());
        gram.put("[unk]");
        grammarJson = gram.toString();
    }

    public String grammar() { return grammarJson; }

    // text = grammar-restricted transcript, free = unrestricted one (needed for search queries).
    public Hit match(String text, String free) {
        String f = norm(free);
        if (searchPackage != null)
            for (String p : searchPrefixes)
                if (f.startsWith(p + " ")) return new Hit("search", searchPackage, f.substring(p.length() + 1));
        Hit h = matchPhrase(norm(text));
        return h != null ? h : matchPhrase(f);
    }

    private Hit matchPhrase(String t) {
        if (t.isEmpty()) return null;
        for (String[] p : phrases) if (t.equals(p[0])) return new Hit(p[1], p[2], null);
        for (String[] p : phrases) if ((" " + t + " ").contains(" " + p[0] + " ")) return new Hit(p[1], p[2], null);
        return null;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replace('ё', 'е').replaceAll("[^а-я0-9a-z ]", " ").replaceAll("\\s+", " ").trim();
    }

    static String readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
