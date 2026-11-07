package ru.home.skazhi;

import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Sends PCM to the skazhi server; returns {grammar text, free text}.
public class Asr {
    static final String TAG = "skazhi";

    public static String[] recognize(String url, byte[] pcm, String grammar) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/octet-stream");
            if (grammar != null) c.setRequestProperty("X-Grammar-B64", Base64.encodeToString(grammar.getBytes("UTF-8"), Base64.NO_WRAP));
            c.setFixedLengthStreamingMode(pcm.length);
            try (OutputStream os = c.getOutputStream()) { os.write(pcm); }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
            String body = bo.toString("UTF-8");
            if (code >= 400) { Log.e(TAG, "server " + code + ": " + body); return new String[]{"", ""}; }
            JSONObject o = new JSONObject(body);
            return new String[]{o.optString("text", ""), o.optString("free", "")};
        } catch (Throwable t) {
            Log.e(TAG, "recognize", t);
            return new String[]{"", ""};
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
