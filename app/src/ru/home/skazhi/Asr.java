package ru.home.skazhi;

import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** HTTP client for the skazhi server ({@code server/asr_server.py}). */
public final class Asr {
    static final String TAG = "skazhi";

    private Asr() {}

    /** The server's answer to one utterance. */
    public static final class Result {
        /** Transcript restricted to the command phrases. */
        public final String text;
        /** Unrestricted transcript. */
        public final String free;
        /** {@code launch}, {@code volume}, {@code key}, {@code global}, {@code search}, or {@code null} if nothing matched. */
        public final String action;
        /** Action argument: package name, volume direction, key name or global action. */
        public final String arg;
        /** Search query for {@code search}, otherwise {@code null}. */
        public final String query;

        Result(JSONObject o) {
            text = o.optString("text", "");
            free = o.optString("free", "");
            action = o.isNull("action") ? null : o.optString("action");
            arg = o.isNull("arg") ? null : o.optString("arg");
            query = o.isNull("query") ? null : o.optString("query");
        }

        @Override public String toString() { return action + ":" + arg + (query != null ? " q=" + query : ""); }
    }

    /**
     * Sends one utterance to the server; it transcribes and matches it against its commands.json.
     *
     * <p>Blocking network call: never run it on the main thread.
     *
     * @param server base URL, e.g. {@code http://192.168.88.241:2700}
     * @param pcm    raw 16 kHz mono PCM16 audio
     * @return the server's answer, or {@code null} if the server is unreachable or failed
     */
    public static Result recognize(String server, byte[] pcm) {
        // two Vosk passes over up to 10 s of audio on a Pi
        JSONObject o = call(server + "/recognize", pcm, 15000);
        return o == null ? null : new Result(o);
    }

    /**
     * Fetches the voice-button scancode configured on the server.
     *
     * @param server base URL
     * @return the scancode, or {@code -1} if the server is unreachable
     */
    public static int triggerScanCode(String server) {
        JSONObject o = call(server + "/config", null, 3000);
        return o == null ? -1 : o.optInt("trigger_scan_code", -1);
    }

    /** POSTs {@code body} (or GETs if {@code null}) and parses the JSON reply; {@code null} on any failure. */
    private static JSONObject call(String url, byte[] body, int readTimeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(readTimeoutMs);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
            String reply = bo.toString("UTF-8");
            if (code >= 400) { Log.e(TAG, url + " -> " + code + ": " + reply); return null; }
            return new JSONObject(reply);
        } catch (Throwable t) {
            Log.e(TAG, url, t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
