package ru.home.skazhi;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import java.util.List;

/**
 * The whole app: an accessibility service that turns the remote's voice button into
 * voice commands.
 *
 * <p>Hold the button: the remote mic is recorded. Release: the audio goes to the
 * skazhi server, which transcribes it, matches it against its commands.json and
 * answers with an action that runs here. The server is found on the LAN by
 * {@link Discovery}; its address and the button's scancode are remembered, so voice
 * control works right after a reboot.
 *
 * <p>The key is NOT consumed ({@link #onKeyEvent} returns {@code false}): the firmware
 * streams the remote audio only while it thinks its own assistant is listening; that
 * (disabled) XiaoAI assistant then fails harmlessly.
 */
public class KeyService extends AccessibilityService {
    static final String TAG = "skazhi";
    /** Scancode of the voice button on the Xiaomi remote, used until the server says otherwise. */
    static final int DEFAULT_SCAN_CODE = 63;

    private final Recorder recorder = new Recorder();
    private volatile boolean recording;
    private volatile String server;
    private volatile int triggerScan = DEFAULT_SCAN_CODE;
    private SharedPreferences prefs;
    private Discovery discovery;
    private Handler main;

    /** Instantiated by the system when the service is enabled in accessibility settings. */
    public KeyService() {}

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    /** Restores the remembered server and starts looking for it on the LAN. */
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        main = new Handler(getMainLooper());
        prefs = getSharedPreferences("skazhi", MODE_PRIVATE);
        server = prefs.getString("server", null);
        triggerScan = prefs.getInt("trigger_scan_code", DEFAULT_SCAN_CODE);
        Log.i(TAG, "remembered server=" + server + " scan=" + triggerScan);
        // off the NSD thread: onServerFound makes a blocking HTTP call
        discovery = new Discovery(this, url -> new Thread(() -> onServerFound(url), "skazhi-config").start());
        discovery.start();
    }

    /** Called once {@link Discovery} finds the server: remember it and fetch its config. */
    private void onServerFound(String url) {
        server = url;
        int scan = Asr.triggerScanCode(url);
        if (scan > 0) triggerScan = scan;
        prefs.edit().putString("server", url).putInt("trigger_scan_code", triggerScan).apply();
    }

    /**
     * Starts recording on voice-button down and hands the result to a worker thread on
     * button up. The firmware repeats key-down while the button is held; only the first
     * one counts.
     *
     * @return always {@code false}, so the firmware keeps streaming the remote mic
     */
    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getScanCode() != triggerScan) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (!recording) { recording = true; recorder.start(); Log.i(TAG, "listening..."); }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (recording) { recording = false; new Thread(this::finish, "skazhi-finish").start(); }
        }
        return false;
    }

    /** Worker thread: stop recording, send to the server, run its answer. Reports failures as toasts. */
    private void finish() {
        byte[] pcm = recorder.stop();
        Log.i(TAG, "pcm=" + pcm.length + " bytes (" + (pcm.length / (double) Recorder.BYTES_PER_SEC) + "s)");
        String s = server;
        if (s == null) { toast("Сервер skazhi не найден в сети"); discovery.start(); return; }
        Asr.Result r = Asr.recognize(s, pcm);
        if (r == null) {
            toast("Сервер распознавания недоступен");
            discovery.start();  // it may have moved to another address
            return;
        }
        Log.i(TAG, "heard='" + r.text + "' free='" + r.free + "' -> " + r);
        if (r.action == null) { toast(r.free.isEmpty() ? "Не расслышал" : "Не понял: " + r.free); return; }
        try { run(r); } catch (Exception e) { Log.e(TAG, "run " + r, e); toast("Ошибка: " + r); }
    }

    /** Executes the server's answer. */
    private void run(Asr.Result r) {
        AudioManager am = getSystemService(AudioManager.class);
        switch (r.action) {
            case "launch": launch(r.arg); break;
            case "search": search(r.arg, r.query); break;
            case "volume": {
                int dir = r.arg.equals("up") ? AudioManager.ADJUST_RAISE
                        : r.arg.equals("down") ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
                // One voice command = several remote clicks, otherwise the change is barely audible.
                int steps = dir == AudioManager.ADJUST_TOGGLE_MUTE ? 1 : 3;
                for (int k = 0; k < steps; k++)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                break;
            }
            case "key": {
                int code = KeyEvent.keyCodeFromString(r.arg);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                break;
            }
            case "global":
                performGlobalAction(r.arg.equals("home") ? GLOBAL_ACTION_HOME
                        : r.arg.equals("back") ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_LOCK_SCREEN);
                break;
            default:
                toast("Неизвестное действие: " + r.action);  // server newer than the app
        }
    }

    /** Opens an app, falling back to its Android TV (leanback) entry, e.g. for TV settings. */
    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) { toast("Нет приложения: " + pkg); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    /**
     * Sends {@code ACTION_SEARCH} with a {@code query} extra to {@code pkg}. The target
     * activity is resolved explicitly: Lampa's SEARCH filter lacks
     * {@code CATEGORY_DEFAULT}, so an implicit start finds nothing.
     */
    private void search(String pkg, String query) {
        Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra("query", query);
        List<ResolveInfo> ri = getPackageManager().queryIntentActivities(i, 0);
        if (ri.isEmpty()) { toast("Поиск не поддерживается: " + pkg); return; }
        i.setClassName(pkg, ri.get(0).activityInfo.name);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        toast("Ищу: " + query);
    }

    /** Shows a short toast from any thread. */
    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, "skazhi: " + msg, Toast.LENGTH_SHORT).show());
    }
}
