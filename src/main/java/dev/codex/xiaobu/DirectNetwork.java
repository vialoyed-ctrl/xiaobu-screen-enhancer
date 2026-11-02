package dev.codex.xiaobu;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/** Process-local direct routing. Never changes the device VPN or proxy settings. */
final class DirectNetwork {
    private static volatile ConnectivityManager manager;
    private static boolean installed;
    private static Network bound;
    static synchronized void install(Context context) {
        if (installed) return;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        manager = cm;
        try {
            ProxySelector.setDefault(new ProxySelector() {
                public List<Proxy> select(URI uri) { return Collections.singletonList(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            });
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(Network n) { bind(); }
                        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { bind(); }
                        @Override public void onLost(Network n) { bind(); }
                    });
            installed = true;
            bind();
        } catch (Exception error) { android.util.Log.w("XiaobuDirectNetwork", "direct routing unavailable: " + error.getClass().getSimpleName()); }
    }
    private static Network physical() throws IOException {
        ConnectivityManager cm = manager;
        if (cm == null) throw new IOException("Direct network not initialized");
        Network fallback = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || !(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n;
            fallback = n;
        }
        if (fallback != null) return fallback;
        throw new IOException("No direct Wi-Fi or mobile network");
    }
    private static synchronized void bind() {
        try {
            Network n = physical();
            if (n.equals(bound)) return;
            if (!manager.bindProcessToNetwork(n)) throw new IOException("Direct binding denied");
            bound = n;
            android.util.Log.i("XiaobuDirectNetwork", "process bound to physical network=" + n);
        } catch (Exception error) { android.util.Log.w("XiaobuDirectNetwork", "physical network binding failed: " + error.getClass().getSimpleName()); }
    }
    static HttpURLConnection open(URL url) throws IOException {
        Network n = physical();
        android.util.Log.i("XiaobuDirectNetwork", "direct request network=" + n + " host=" + url.getHost());
        return (HttpURLConnection) n.openConnection(url, Proxy.NO_PROXY);
    }
}
