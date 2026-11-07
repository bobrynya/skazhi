package ru.home.skazhi;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

// Hold the remote voice button -> record the remote mic (VOICE_RECOGNITION source),
// on release recognize on the pi server and launch the matched app.
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
        String text = Asr.recognize(cfg.server, pcm, cfg.grammar());
        String pkg = cfg.match(text);
        Log.i(TAG, "heard='" + text + "' -> " + pkg);
        if (pkg != null) launch(pkg);
        else toast(text.isEmpty() ? "Не расслышал" : "Не понял: " + text);
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { toast("Нет приложения: " + pkg); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    private void toast(String msg) {
        new android.os.Handler(getMainLooper()).post(() -> Toast.makeText(this, "skazhi: " + msg, Toast.LENGTH_SHORT).show());
    }
}
