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
 * type an IP address with the remote. Listeners are top-level classes: d8 from
 * build-tools 34 crashes on anonymous and nested classes compiled by recent JDKs.
 * Each NSD call gets its own listener object: NsdManager keeps discovery and resolve
 * listeners in one map and throws "listener already in use" on reuse.
 */
final class Discovery implements NsdManager.DiscoveryListener {
    static final String TAG = "skazhi";
    static final String SERVICE_TYPE = "_skazhi._tcp";

    private final NsdManager nsd;
    private final Consumer<String> onFound;
    /** True from discoverServices() until NSD confirms the discovery is over. */
    private boolean active;
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

    /** Starts looking for the server; no-op while a discovery is running. Stops by itself once found. */
    synchronized void start() {
        if (active) return;
        active = true;
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);
        } catch (IllegalArgumentException e) {  // previous discovery not fully stopped yet: next start() retries
            active = false;
            Log.w(TAG, "discovery start: " + e.getMessage());
        }
    }

    /** Asks NSD to stop; {@code active} clears only when it confirms, so start() can't race it. */
    private void stop() {
        try { nsd.stopServiceDiscovery(this); } catch (IllegalArgumentException ignored) {}  // already stopped
    }

    @Override public void onDiscoveryStarted(String type) { Log.i(TAG, "looking for the server..."); }
    @Override public synchronized void onDiscoveryStopped(String type) { active = false; }
    @Override public void onServiceLost(NsdServiceInfo s) {}

    @Override public synchronized void onStartDiscoveryFailed(String type, int err) {
        Log.e(TAG, "discovery failed: " + err);
        active = false;
    }

    @Override public synchronized void onStopDiscoveryFailed(String type, int err) { active = false; }

    // resolveService/getHost are deprecated in API 34, but their replacement
    // (registerServiceInfoCallback) does not exist below it, and we support API 28+.
    @SuppressWarnings("deprecation")
    @Override public synchronized void onServiceFound(NsdServiceInfo s) {
        if (resolving) return;  // NsdManager resolves one service at a time
        resolving = true;
        nsd.resolveService(s, new DiscoveryResolver(this));
    }

    /** Resolve finished; {@code url} is {@code null} if it failed. */
    void resolved(String url) {
        synchronized (this) { resolving = false; }
        // On failure NSD won't report the same service again in this session; the next start() will.
        stop();
        if (url != null) onFound.accept(url);
    }
}

/** Resolves a found service to host:port for {@link Discovery}. One instance per resolveService() call. */
final class DiscoveryResolver implements NsdManager.ResolveListener {
    private final Discovery discovery;

    DiscoveryResolver(Discovery discovery) { this.discovery = discovery; }

    @Override public void onResolveFailed(NsdServiceInfo s, int err) {
        Log.e(Discovery.TAG, "resolve failed: " + err);
        discovery.resolved(null);
    }

    @SuppressWarnings("deprecation")  // getHost: see Discovery.onServiceFound
    @Override public void onServiceResolved(NsdServiceInfo s) {
        String url = "http://" + s.getHost().getHostAddress() + ":" + s.getPort();
        Log.i(Discovery.TAG, "server found: " + url);
        discovery.resolved(url);
    }
}
