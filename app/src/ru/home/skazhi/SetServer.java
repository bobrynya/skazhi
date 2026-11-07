package ru.home.skazhi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Manual fallback for networks where mDNS discovery doesn't work (multicast filtered,
 * client isolation): sets the server address over adb.
 *
 * <pre>adb shell am broadcast -n ru.home.skazhi/.SetServer --es url http://192.168.1.10:2700</pre>
 *
 * <p>Protected by {@code android.permission.DUMP}, which the adb shell has and ordinary
 * apps can't get, so no app on the TV can redirect the microphone audio elsewhere.
 */
public class SetServer extends BroadcastReceiver {
    /** Instantiated by the system for each broadcast. */
    public SetServer() {}

    /** Stores the {@code url} extra; {@link KeyService} picks it up immediately. */
    @Override public void onReceive(Context ctx, Intent i) {
        String url = i.getStringExtra("url");
        if (url == null || !url.matches("https?://[^/\\s]+")) {
            Log.e(KeyService.TAG, "SetServer: want --es url http://host:port, got " + url);
            return;
        }
        ctx.getSharedPreferences(KeyService.PREFS, Context.MODE_PRIVATE).edit().putString("server", url).apply();
        Log.i(KeyService.TAG, "server set by hand: " + url);
    }
}
