package ru.home.skazhi;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import java.util.function.Consumer;

/**
 * Finds the skazhi server on the LAN over mDNS ({@code _skazhi._tcp}).
 *
 * <p>The server announces itself (see {@code server/asr_server.py}), so nobody has to
 * type an IP address with the remote. Implements both NSD listeners itself instead of
 * using anonymous classes: d8 from build-tools 34 crashes on those with recent JDKs.
 */
final class Discovery implements NsdManager.DiscoveryListener, NsdManager.ResolveListener {
    static final String TAG = "skazhi";
    static final String SERVICE_TYPE = "_skazhi._tcp";

    private final NsdManager nsd;
    private final Consumer<String> onFound;
    private boolean discovering;
    private boolean resolving;

    /**
     * @param ctx     any context
     * @param onFound receives the server's base URL, e.g. {@code http://192.168.88.241:2700};
     *                called on an NSD thread
     */
    Discovery(Context ctx, Consumer<String> onFound) {
        this.nsd = ctx.getSystemService(NsdManager.class);
        this.onFound = onFound;
    }

    /** Starts looking for the server; no-op if already looking. Stops by itself once found. */
    synchronized void start() {
        if (discovering) return;
        discovering = true;
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);
    }

    private synchronized void stop() {
        if (!discovering) return;
        discovering = false;
        try { nsd.stopServiceDiscovery(this); } catch (IllegalArgumentException ignored) {}  // already stopped
    }

    @Override public void onDiscoveryStarted(String type) { Log.i(TAG, "looking for the server..."); }
    @Override public void onDiscoveryStopped(String type) {}
    @Override public void onServiceLost(NsdServiceInfo s) {}

    @Override public synchronized void onStartDiscoveryFailed(String type, int err) {
        Log.e(TAG, "discovery failed: " + err);
        discovering = false;
    }

    @Override public void onStopDiscoveryFailed(String type, int err) {}

    // resolveService/getHost are deprecated in API 34, but their replacement
    // (registerServiceInfoCallback) does not exist below it, and we support API 28+.
    @SuppressWarnings("deprecation")
    @Override public synchronized void onServiceFound(NsdServiceInfo s) {
        if (resolving) return;  // NsdManager resolves one service at a time
        resolving = true;
        nsd.resolveService(s, this);
    }

    @Override public synchronized void onResolveFailed(NsdServiceInfo s, int err) {
        Log.e(TAG, "resolve failed: " + err);
        resolving = false;  // the next onServiceFound retries
    }

    @SuppressWarnings("deprecation")
    @Override public void onServiceResolved(NsdServiceInfo s) {
        String url = "http://" + s.getHost().getHostAddress() + ":" + s.getPort();
        Log.i(TAG, "server found: " + url);
        synchronized (this) { resolving = false; }
        stop();
        onFound.accept(url);
    }
}
