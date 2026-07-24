package io.github.hankaviator.dualpathvpn;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks Android's standard VpnService socket-protection path inside VPN apps
 * selected by the user in LSPosed.
 */
public final class DualPathHook implements IXposedHookLoadPackage {
    private static final String TAG = "[DualPathVPN] ";
    private static final long NETWORK_CACHE_MS = 1_000L;
    private static final long CONNECTION_BURST_WINDOW_MS = 1_000L;
    private static final int CONNECTION_BURST_THRESHOLD = 4;
    private static final long CELLULAR_BOOST_MS = 30_000L;

    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean();
    private static final AtomicInteger NEXT_BOOST_NETWORK = new AtomicInteger();
    private static final AtomicInteger PROTECTED_SOCKET_COUNT = new AtomicInteger();
    private static final ArrayDeque<Long> RECENT_PROTECTS = new ArrayDeque<>();

    private static volatile PhysicalNetworks cachedNetworks = PhysicalNetworks.EMPTY;
    private static volatile long lastNetworkScan;
    private static volatile String processName = "unknown";
    private static long cellularBoostUntil;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // LSPosed controls which packages load this module. Installing the hooks
        // once per process makes this implementation work with any scoped VPN app.
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        processName = loadPackageParam.processName;
        hookSocketProtection();
        hookVpnUnderlays();
        log("loaded in " + processName);
    }

    private static void hookSocketProtection() {
        XposedHelpers.findAndHookMethod(
                VpnService.class,
                "protect",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!Boolean.TRUE.equals(param.getResult())) {
                            return;
                        }

                        VpnService service = (VpnService) param.thisObject;
                        PhysicalNetworks networks = getPhysicalNetworks(service, false);

                        // Do not interfere with the VPN's normal routing unless two
                        // usable physical links are available.
                        if (!networks.hasBoth()) {
                            return;
                        }

                        NetworkSelection selection = selectNetwork(networks);
                        Network target = selection.network;
                        int socketFd = (Integer) param.args[0];
                        if (bindSocket(target, socketFd)) {
                            int socketCount = PROTECTED_SOCKET_COUNT.getAndIncrement();
                            if (socketCount < 8 || socketCount % 50 == 49) {
                                log("bound fd " + socketFd + " to "
                                        + networks.nameOf(target) + " (" + target + ")");
                            }
                            return;
                        }

                        // A network can disappear during the one-second cache window.
                        // Refresh once and try the other live path before falling back
                        // to the VPN app's original routing behavior.
                        invalidateNetworkCache();
                        PhysicalNetworks refreshed = getPhysicalNetworks(service, true);
                        if (refreshed.hasBoth()) {
                            Network fallback = selection.boosted
                                    ? refreshed.otherThan(target)
                                    : refreshed.wifi;
                            if (bindSocket(fallback, socketFd)) {
                                log("recovered fd " + socketFd + " on "
                                        + refreshed.nameOf(fallback) + " (" + fallback + ")");
                            }
                        }
                    }
                });
    }

    /**
     * Keeps low-rate connections on Wi-Fi, where VPN control and keepalive
     * sockets are most likely to be created. A burst of protected sockets
     * indicates a parallel workload that can benefit from both paths.
     *
     * <p>No timer or wake lock is needed: boost expiry is evaluated only when
     * the VPN protects another socket.
     */
    private static NetworkSelection selectNetwork(PhysicalNetworks networks) {
        long now = SystemClock.elapsedRealtime();
        boolean boostStarted = false;
        boolean boosted;

        synchronized (RECENT_PROTECTS) {
            RECENT_PROTECTS.addLast(now);
            long oldestAllowed = now - CONNECTION_BURST_WINDOW_MS;
            while (!RECENT_PROTECTS.isEmpty()
                    && RECENT_PROTECTS.peekFirst() < oldestAllowed) {
                RECENT_PROTECTS.removeFirst();
            }
            while (RECENT_PROTECTS.size() > CONNECTION_BURST_THRESHOLD) {
                RECENT_PROTECTS.removeFirst();
            }

            if (RECENT_PROTECTS.size() >= CONNECTION_BURST_THRESHOLD) {
                boostStarted = now >= cellularBoostUntil;
                cellularBoostUntil = now + CELLULAR_BOOST_MS;
            }
            boosted = now < cellularBoostUntil;
        }

        if (boostStarted) {
            log("cellular boost enabled for " + CELLULAR_BOOST_MS / 1_000L
                    + "s after a connection burst");
        }
        if (!boosted) {
            return new NetworkSelection(networks.wifi, false);
        }

        int sequence = NEXT_BOOST_NETWORK.getAndIncrement();
        return new NetworkSelection(networks.chooseForBoost(sequence), true);
    }

    private static boolean bindSocket(Network network, int socketFd) {
        if (network == null) {
            return false;
        }

        // fromFd duplicates the descriptor. Binding the duplicate changes the
        // same underlying socket, while closing it cannot close the VPN's fd.
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.fromFd(socketFd)) {
            network.bindSocket(duplicate.getFileDescriptor());
            return true;
        } catch (IOException | RuntimeException error) {
            log("socket bind failed on " + network + ": " + error);
            return false;
        }
    }

    private static void hookVpnUnderlays() {
        XC_MethodHook replaceNetworks = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context context = contextFrom(param.thisObject);
                if (context == null) {
                    return;
                }

                PhysicalNetworks networks = getPhysicalNetworks(context, false);
                if (networks.hasBoth()) {
                    param.args[0] = networks.asArray();
                }
            }
        };

        XposedHelpers.findAndHookMethod(
                VpnService.class,
                "setUnderlyingNetworks",
                Network[].class,
                replaceNetworks);

        XposedHelpers.findAndHookMethod(
                VpnService.Builder.class,
                "setUnderlyingNetworks",
                Network[].class,
                replaceNetworks);
    }

    private static Context contextFrom(Object object) {
        if (object instanceof Context) {
            return (Context) object;
        }
        try {
            Object outer = XposedHelpers.getSurroundingThis(object);
            return outer instanceof Context ? (Context) outer : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static PhysicalNetworks getPhysicalNetworks(Context context, boolean forceRefresh) {
        long now = SystemClock.elapsedRealtime();
        PhysicalNetworks current = cachedNetworks;
        if (!forceRefresh && now - lastNetworkScan < NETWORK_CACHE_MS) {
            return current;
        }

        synchronized (DualPathHook.class) {
            now = SystemClock.elapsedRealtime();
            current = cachedNetworks;
            if (!forceRefresh && now - lastNetworkScan < NETWORK_CACHE_MS) {
                return current;
            }

            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network wifi = null;
            Network cellular = null;
            if (manager != null) {
                for (Network network : manager.getAllNetworks()) {
                    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                    if (!isUsablePhysicalNetwork(capabilities)
                            || !hasDefaultRoute(manager.getLinkProperties(network))) {
                        continue;
                    }

                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifi = network;
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        cellular = network;
                    }
                }
            }

            current = new PhysicalNetworks(wifi, cellular);
            cachedNetworks = current;
            lastNetworkScan = now;
            return current;
        }
    }

    private static boolean isUsablePhysicalNetwork(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static boolean hasDefaultRoute(LinkProperties properties) {
        if (properties == null) {
            return false;
        }
        for (RouteInfo route : properties.getRoutes()) {
            if (route.isDefaultRoute()) {
                return true;
            }
        }
        return false;
    }

    private static void invalidateNetworkCache() {
        lastNetworkScan = 0L;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + processName + ": " + message);
    }

    private static final class PhysicalNetworks {
        static final PhysicalNetworks EMPTY = new PhysicalNetworks(null, null);

        final Network wifi;
        final Network cellular;

        PhysicalNetworks(Network wifi, Network cellular) {
            this.wifi = wifi;
            this.cellular = cellular;
        }

        boolean hasBoth() {
            return wifi != null && cellular != null;
        }

        Network chooseForBoost(int sequence) {
            // Start each process with cellular so the burst that activates boost
            // immediately uses the second path.
            return (sequence & 1) == 0 ? cellular : wifi;
        }

        Network otherThan(Network failed) {
            if (failed != null && failed.equals(wifi)) {
                return cellular;
            }
            if (failed != null && failed.equals(cellular)) {
                return wifi;
            }
            return wifi;
        }

        Network[] asArray() {
            return new Network[]{wifi, cellular};
        }

        String nameOf(Network network) {
            return network != null && network.equals(wifi) ? "Wi-Fi" : "cellular";
        }
    }

    private static final class NetworkSelection {
        final Network network;
        final boolean boosted;

        NetworkSelection(Network network, boolean boosted) {
            this.network = network;
            this.boosted = boosted;
        }
    }
}
