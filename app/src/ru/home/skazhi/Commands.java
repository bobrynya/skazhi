package ru.home.skazhi;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// Loads commands.json (server URL, trigger key, phrase->package map) and matches text.
public class Commands {
    public final String server;
    public final int triggerScan;
    private final List<String[]> phrases = new ArrayList<>(); // {phrase, package}
    private final String grammarJson;

    public Commands(Context ctx) throws Exception {
        JSONObject root = new JSONObject(readAsset(ctx, "commands.json"));
        server = root.getString("server");
        triggerScan = root.optInt("trigger_scan_code", 63);
        JSONArray cmds = root.getJSONArray("commands");
        JSONArray gram = new JSONArray();
        for (int i = 0; i < cmds.length(); i++) {
            JSONObject c = cmds.getJSONObject(i);
            String pkg = c.getString("package");
            JSONArray ps = c.getJSONArray("phrases");
            for (int j = 0; j < ps.length(); j++) {
                String p = norm(ps.getString(j));
                phrases.add(new String[]{p, pkg});
                gram.put(p);
            }
        }
        gram.put("[unk]");
        grammarJson = gram.toString();
    }

    public String grammar() { return grammarJson; }

    // Returns the package for recognized text, or null. Exact phrase, else phrase-contained.
    public String match(String text) {
        String t = norm(text);
        if (t.isEmpty()) return null;
        for (String[] pp : phrases) if (t.equals(pp[0])) return pp[1];
        for (String[] pp : phrases) if (t.contains(pp[0])) return pp[1];
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
