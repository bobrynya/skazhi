package ru.home.skazhi;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

// Hold the remote voice button -> record the remote mic (VOICE_RECOGNITION source),
// on release recognize on the pi server and run the matched command.
// We do NOT consume the key (return false) so the firmware keeps streaming the
// remote audio into the VOICE_RECOGNITION source; the (disabled) XiaoAI start fails harmlessly.
public class KeyService extends AccessibilityService {
    static final String TAG = "skazhi";
    private Commands cfg;
    private final Recorder recorder = new Recorder();
    private volatile boolean recording;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        try { cfg = new Commands(this); Log.i(TAG, "config ok, server=" + cfg.server); }
        catch (Exception e) { Log.e(TAG, "config load failed", e); }
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (cfg == null || e.getScanCode() != cfg.triggerScan) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (!recording) { recording = true; recorder.start(); Log.i(TAG, "listening..."); }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (recording) { recording = false; new Thread(this::finish, "skazhi-finish").start(); }
        }
        return false; // let the firmware keep streaming the mic
    }

    private void finish() {
        byte[] pcm = recorder.stop();
        Log.i(TAG, "pcm=" + pcm.length + " bytes (" + (pcm.length/32000.0) + "s)");
        String[] r = Asr.recognize(cfg.server, pcm, cfg.grammar());
        if (r == null) { toast("Сервер распознавания недоступен"); return; }
        Commands.Hit hit = cfg.match(r[0], r[1]);
        Log.i(TAG, "heard='" + r[0] + "' free='" + r[1] + "' -> " + hit);
        if (hit == null) { toast(r[1].isEmpty() ? "Не расслышал" : "Не понял: " + r[1]); return; }
        try { run(hit); } catch (Exception e) { Log.e(TAG, "run " + hit, e); toast("Ошибка: " + hit); }
    }

    private void run(Commands.Hit h) {
        AudioManager am = getSystemService(AudioManager.class);
        switch (h.action) {
            case "launch": launch(h.arg); break;
            case "search": {
                Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(h.arg).putExtra("query", h.query);
                // Lampa's SEARCH filter lacks CATEGORY_DEFAULT, so an implicit start fails: resolve explicitly.
                java.util.List<android.content.pm.ResolveInfo> ri = getPackageManager().queryIntentActivities(i, 0);
                if (ri.isEmpty()) { toast("Поиск не поддерживается: " + h.arg); break; }
                i.setClassName(h.arg, ri.get(0).activityInfo.name);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                toast("Ищу: " + h.query);
                break;
            }
            case "volume": {
                int dir = h.arg.equals("up") ? AudioManager.ADJUST_RAISE
                        : h.arg.equals("down") ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
                // One voice command = several remote clicks, otherwise the change is barely audible.
                int steps = dir == AudioManager.ADJUST_TOGGLE_MUTE ? 1 : 3;
                for (int k = 0; k < steps; k++)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                break;
            }
            case "key": {
                int code = KeyEvent.keyCodeFromString(h.arg);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                break;
            }
            case "global":
                performGlobalAction(h.arg.equals("home") ? GLOBAL_ACTION_HOME
                        : h.arg.equals("back") ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_LOCK_SCREEN);
                break;
        }
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg); // e.g. TV settings
        if (i == null) { toast("Нет приложения: " + pkg); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    private void toast(String msg) {
        new android.os.Handler(getMainLooper()).post(() -> Toast.makeText(this, "skazhi: " + msg, Toast.LENGTH_SHORT).show());
    }
}
