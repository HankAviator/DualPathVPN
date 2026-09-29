package io.github.hankaviator.dualpathvpn;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dalvik.system.BaseDexClassLoader;

/**
 * Hooks Android's standard VpnService socket-protection path inside VPN apps
 * selected by the user in LSPosed.
 */
public final class DualPathHook implements IXposedHookLoadPackage {
    private static final String TAG = "[DualPathVPN] ";
    private static final String LINK_TURBO_SETTING = "linkturbo_is_enable";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String STATUS_MANAGER = "com.xiaomi.NetworkBoost.StatusManager";
    private static final String SLA_SERVICE = "com.xiaomi.NetworkBoost.slaservice.SLAService";
    private static final String LINK_TURBO_SCREEN =
            "com.android.settings.wifi.linkturbo.WifiLinkTurboSettings";
    private static final String BOOST_NOTE_TAG = "dualpathvpn.mobile_boost_note";
    private static final String BOOST_NOTE =
            "Recently used apps get boost priority, VPN on or off.";
    private static final long NETWORK_CACHE_MS = 1_000L;
    private static final long CONNECTION_BURST_WINDOW_MS = 1_000L;
    private static final int CONNECTION_BURST_THRESHOLD = 4;
    private static final long CELLULAR_BOOST_MS = 30_000L;
    // The phone's SLA HAL rejects a roughly 1 KiB UID message. Leave room for its framing.
    private static final int SLA_UID_LIST_MAX_BYTES = 960;
    private static final long APP_RANKING_PERIOD_MS = 7L * 24 * 60 * 60 * 1_000L;
    private static final String BOOST_PREFS = "dualpathvpn_mobile_boost";
    private static final String LAST_APP_RANKING = "last_recency_ranking";

    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean APP_SYNC_RUNNING = new AtomicBoolean();
    private static final AtomicBoolean SIGNAL_CALLBACK_HOOKED = new AtomicBoolean();
    private static final AtomicBoolean SIGNAL_OVERRIDE_LOGGED = new AtomicBoolean();
    private static final ArrayList<XC_MethodHook.Unhook> frameworkLoaderHooks = new ArrayList<>();
    private static final AtomicInteger NEXT_BOOST_NETWORK = new AtomicInteger();
    private static final AtomicInteger PROTECTED_SOCKET_COUNT = new AtomicInteger();
    private static final ArrayDeque<Long> RECENT_PROTECTS = new ArrayDeque<>();
    private static final Object BOOST_SETTING_LOCK = new Object();

    private static volatile PhysicalNetworks cachedNetworks = PhysicalNetworks.EMPTY;
    private static volatile long lastNetworkScan;
    private static volatile String processName = "unknown";
    private static volatile boolean frameworkProcess;
    private static volatile boolean boostSettingInitialized;
    private static volatile boolean boostSettingEnabled;
    private static ContentObserver boostSettingObserver;
    private static final ArrayList<XC_MethodHook.Unhook> vpnHooks = new ArrayList<>();
    private static long cellularBoostUntil;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if ("android".equals(loadPackageParam.packageName)) {
            if (frameworkProcess) {
                return;
            }
            frameworkProcess = true;
            processName = "system_server";
            hookFrameworkSignalGate(loadPackageParam.classLoader);
            return;
        }
        // System-server also reports later package/context loads, sometimes with null names.
        // They must never install the VPN app hooks in the framework process.
        if (frameworkProcess || loadPackageParam.packageName == null) {
            return;
        }
        if (SETTINGS_PACKAGE.equals(loadPackageParam.packageName)) {
            processName = loadPackageParam.processName;
            hookMobileBoostAppList(loadPackageParam.classLoader);
            return;
        }

        // LSPosed controls which packages load this module. Application.attach
        // gives us a Context before the VPN starts protecting sockets.
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        processName = loadPackageParam.processName;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                initializeBoostSetting((Context) param.args[0]);
            }
        });
    }

    private static void hookFrameworkSignalGate(ClassLoader loader) {
        if (installSignalListenerHook(loader)) {
            return;
        }
        // HyperOS loads NetworkBoost.jar separately during system-server startup.
        // Watch loader creation, rather than intercepting every class load.
        synchronized (frameworkLoaderHooks) {
            frameworkLoaderHooks.addAll(XposedBridge.hookAllConstructors(
                    BaseDexClassLoader.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    for (Object argument : param.args) {
                        if (argument instanceof String
                                && ((String) argument).contains("NetworkBoost")
                                && installSignalListenerHook((ClassLoader) param.thisObject)) {
                            synchronized (frameworkLoaderHooks) {
                                for (XC_MethodHook.Unhook hook : frameworkLoaderHooks) {
                                    hook.unhook();
                                }
                                frameworkLoaderHooks.clear();
                            }
                            return;
                        }
                    }
                }
            }));
        }
    }

    private static boolean installSignalListenerHook(ClassLoader loader) {
        Class<?> manager = XposedHelpers.findClassIfExists(STATUS_MANAGER, loader);
        if (manager == null) {
            return false;
        }
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(manager,
                "registerModemSignalStrengthListener", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object listener = param.args[0];
                if (listener == null || !listener.getClass().getName().startsWith(SLA_SERVICE + "$")
                        || SIGNAL_CALLBACK_HOOKED.get()) {
                    return;
                }
                try {
                    Class<?> service = XposedHelpers.findClass(SLA_SERVICE,
                            listener.getClass().getClassLoader());
                    XposedHelpers.findAndHookMethod(listener.getClass(),
                            "isModemSingnalStrengthStatus", int.class, int.class,
                            new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam signal) {
                            if (!Integer.valueOf(1).equals(signal.args[0])) {
                                return;
                            }
                            Context context = (Context) XposedHelpers.getStaticObjectField(
                                    service, "mContext");
                            if (context != null && Settings.System.getInt(
                                    context.getContentResolver(), LINK_TURBO_SETTING, 0) == 1) {
                                signal.args[0] = 0;
                                if (SIGNAL_OVERRIDE_LOGGED.compareAndSet(false, true)) {
                                    log("bypassed HyperOS weak-signal stop at " + signal.args[1]
                                            + " dBm while Mobile Speed Boost is enabled");
                                }
                            }
                        }
                    });
                    SIGNAL_CALLBACK_HOOKED.set(true);
                    log("HyperOS SLA weak-signal callback override installed");
                } catch (Throwable error) {
                    log("HyperOS SLA signal override unavailable: " + error);
                }
            }
        });
        if (hooks.isEmpty()) {
            log("HyperOS modem signal listener registration unavailable");
            return false;
        }
        log("waiting for HyperOS SLA modem signal listener");
        return true;
    }

    private static void hookMobileBoostAppList(ClassLoader classLoader) {
        Class<?> screen = XposedHelpers.findClassIfExists(LINK_TURBO_SCREEN, classLoader);
        if (screen == null) {
            log("HyperOS Mobile Speed Boost screen unavailable; hook skipped");
            return;
        }

        XposedHelpers.findAndHookMethod(screen, "onCreate", android.os.Bundle.class,
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                replaceBoostList((Context) param.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(screen, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.thisObject;
                updateBoostNote(context);
                if ((Boolean) XposedHelpers.callMethod(context, "isWifiLinkTurboEnabled")) {
                    syncBoostApps(context);
                }
            }
        });
        XposedHelpers.findAndHookMethod(screen, "enableWifiLinkTurbo", boolean.class,
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.thisObject;
                updateBoostNote(context);
                if ((Boolean) param.args[0]) {
                    syncBoostApps(context);
                }
            }
        });
        XposedHelpers.findAndHookMethod(screen, "loadPackages", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
            }
        });
        log("HyperOS Mobile Speed Boost app-list override installed");
    }

    private static void replaceBoostList(Context context) {
        View list = (View) XposedHelpers.getObjectField(context, "mAppRecyclerView");
        if (!(list.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) list.getParent();
        int index = parent.indexOfChild(list);
        ViewGroup.LayoutParams params = list.getLayoutParams();
        parent.removeView(list);

        TextView note = new TextView(context);
        note.setTag(BOOST_NOTE_TAG);
        note.setText(BOOST_NOTE);
        note.setTextAppearance(android.R.style.TextAppearance_Medium);
        note.setGravity(Gravity.CENTER);
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                context.getResources().getDisplayMetrics());
        note.setPadding(padding, padding, padding, padding);
        parent.addView(note, index, params);
    }

    private static void updateBoostNote(Context context) {
        View note = ((android.app.Activity) context).getWindow().getDecorView()
                .findViewWithTag(BOOST_NOTE_TAG);
        if (note != null) {
            boolean enabled = (Boolean) XposedHelpers.callMethod(context,
                    "isWifiLinkTurboEnabled");
            note.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private static void syncBoostApps(Context context) {
        if (!APP_SYNC_RUNNING.compareAndSet(false, true)) {
            return;
        }
        Object client = XposedHelpers.getObjectField(context, "mLinkTurboClient");
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                PackageManager manager = appContext.getPackageManager();
                XposedHelpers.callMethod(client, "checkServiceIsConnected");
                Object slaManager = XposedHelpers.getObjectField(client, "mSlaManager");
                String current = (String) XposedHelpers.callMethod(client,
                        "getLinkTurboWhiteList");
                SharedPreferences prefs = appContext.getSharedPreferences(
                        BOOST_PREFS, Context.MODE_PRIVATE);
                long now = System.currentTimeMillis();
                long lastRanking = prefs.getLong(LAST_APP_RANKING, 0L);
                long age = now - lastRanking;
                if (current != null && current.length() <= SLA_UID_LIST_MAX_BYTES
                        && age >= 0 && age < APP_RANKING_PERIOD_MS) {
                    return;
                }
                Set<String> enabled = new HashSet<>();
                if (current != null) {
                    for (String uid : current.split(",")) {
                        if (!uid.isEmpty()) {
                            enabled.add(uid);
                        }
                    }
                }

                Map<String, Long> lastUsed = getLastUsedTimes(appContext, now);
                if (lastUsed.isEmpty()) {
                    log("Mobile Speed Boost app ranking skipped: no usage history available");
                    return;
                }
                Map<String, Long> lastUsedByUid = new HashMap<>();
                for (ApplicationInfo app : manager.getInstalledApplications(0)) {
                    if (app.uid < 10000 || manager.checkPermission(
                            android.Manifest.permission.INTERNET, app.packageName)
                            != PackageManager.PERMISSION_GRANTED) {
                        continue;
                    }
                    String uid = Integer.toString(app.uid);
                    Long packageUse = lastUsed.get(app.packageName);
                    long time = packageUse == null ? 0L : packageUse;
                    Long previous = lastUsedByUid.get(uid);
                    if (previous == null || time > previous) {
                        lastUsedByUid.put(uid, time);
                    }
                }
                List<String> candidates = new ArrayList<>(lastUsedByUid.keySet());
                Collections.sort(candidates, (left, right) -> {
                    int order = Long.compare(lastUsedByUid.get(right), lastUsedByUid.get(left));
                    if (order != 0) {
                        return order;
                    }
                    if (enabled.contains(left) != enabled.contains(right)) {
                        return enabled.contains(left) ? -1 : 1;
                    }
                    return left.compareTo(right);
                });
                int recordedApps = 0;
                for (long time : lastUsedByUid.values()) {
                    if (time > 0) {
                        recordedApps++;
                    }
                }

                Set<String> selected = new HashSet<>();
                int bytes = 0;
                for (String uid : candidates) {
                    int length = uid.length() + 1; // getLinkTurboWhiteList adds a comma.
                    if (bytes + length <= SLA_UID_LIST_MAX_BYTES) {
                        selected.add(uid);
                        bytes += length;
                    }
                }
                if (selected.isEmpty()) {
                    log("Mobile Speed Boost app sync skipped: no eligible apps");
                    return;
                }
                int removed = 0;
                for (String uid : new HashSet<>(enabled)) {
                    if (!selected.contains(uid) && Boolean.TRUE.equals(XposedHelpers.callMethod(
                            slaManager, "removeUidInLinkTurboWhiteList", uid))) {
                        enabled.remove(uid);
                        removed++;
                    }
                }
                String pruned = (String) XposedHelpers.callMethod(client,
                        "getLinkTurboWhiteList");
                if (pruned == null || pruned.length() > SLA_UID_LIST_MAX_BYTES) {
                    log("Mobile Speed Boost app sync could not shrink the UID list: "
                            + (pruned == null ? "unavailable" : pruned.length() + " bytes"));
                    return;
                }
                int added = 0;
                int remaining = SLA_UID_LIST_MAX_BYTES - pruned.length();
                for (String uid : candidates) {
                    if (!selected.contains(uid) || enabled.contains(uid)) {
                        continue;
                    }
                    if (uid.length() + 1 > remaining) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(slaManager,
                            "addUidToLinkTurboWhiteList", uid))) {
                        enabled.add(uid);
                        added++;
                        remaining -= uid.length() + 1;
                    }
                }
                String updated = (String) XposedHelpers.callMethod(client,
                        "getLinkTurboWhiteList");
                if (updated != null && updated.length() <= SLA_UID_LIST_MAX_BYTES) {
                    prefs.edit().putLong(LAST_APP_RANKING, now).apply();
                }
                log("Mobile Speed Boost whitelist: " + recordedApps
                        + " UIDs with use history, " + selected.size() + " selected, "
                        + added + " added, " + removed + " removed, "
                        + (updated == null ? -1 : updated.length()) + " bytes");
            } catch (Throwable error) {
                log("Mobile Speed Boost app sync failed: " + error);
            } finally {
                APP_SYNC_RUNNING.set(false);
            }
        }, "DualPathVPN-boost-app-sync").start();
    }

    private static Map<String, Long> getLastUsedTimes(Context context, long now) {
        Map<String, Long> lastUsed = new HashMap<>();
        UsageStatsManager usage = (UsageStatsManager) context.getSystemService(
                Context.USAGE_STATS_SERVICE);
        if (usage == null) {
            return lastUsed;
        }
        List<UsageStats> stats;
        try {
            // Yearly buckets cover all usage history retained by Android in one cheap query.
            stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, 0L, now);
        } catch (SecurityException error) {
            log("Usage history unavailable: " + error);
            return lastUsed;
        }
        if (stats == null) {
            return lastUsed;
        }
        for (UsageStats stat : stats) {
            long time = stat.getLastTimeUsed();
            if (time > 0) {
                String packageName = stat.getPackageName();
                Long previous = lastUsed.get(packageName);
                if (previous == null || time > previous) {
                    lastUsed.put(packageName, time);
                }
            }
        }
        return lastUsed;
    }

    private static XC_MethodHook.Unhook hookSocketProtection() {
        return XposedHelpers.findAndHookMethod(
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
                        if (!isSystemBoostEnabled()) {
                            return;
                        }

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

    private static void hookVpnUnderlays(ArrayList<XC_MethodHook.Unhook> hooks) {
        XC_MethodHook replaceNetworks = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context context = contextFrom(param.thisObject);
                if (context == null) {
                    return;
                }
                if (!isSystemBoostEnabled()) {
                    return;
                }

                PhysicalNetworks networks = getPhysicalNetworks(context, false);
                if (networks.hasBoth()) {
                    param.args[0] = networks.asArray();
                }
            }
        };

        hooks.add(XposedHelpers.findAndHookMethod(
                VpnService.class,
                "setUnderlyingNetworks",
                Network[].class,
                replaceNetworks));

        hooks.add(XposedHelpers.findAndHookMethod(
                VpnService.Builder.class,
                "setUnderlyingNetworks",
                Network[].class,
                replaceNetworks));
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

    private static boolean isSystemBoostEnabled() {
        return boostSettingEnabled;
    }

    private static void initializeBoostSetting(Context context) {
        synchronized (BOOST_SETTING_LOCK) {
            if (boostSettingInitialized) {
                return;
            }

            ContentResolver resolver = context.getContentResolver();
            try {
                boostSettingObserver = new ContentObserver(
                        new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateBoostSetting(resolver);
                    }
                };
                resolver.registerContentObserver(
                        Settings.System.getUriFor(LINK_TURBO_SETTING),
                        false,
                        boostSettingObserver);
                updateBoostSetting(resolver);
            } catch (RuntimeException error) {
                // Missing or inaccessible vendor settings must leave the module
                // inactive rather than silently enabling dual-path behavior.
                boostSettingEnabled = false;
                log("cannot observe system boost setting; module inactive: " + error);
            }
            boostSettingInitialized = true;
        }
    }

    private static void updateBoostSetting(ContentResolver resolver) {
        synchronized (BOOST_SETTING_LOCK) {
            boolean enabled;
            try {
                enabled = Settings.System.getInt(resolver, LINK_TURBO_SETTING, 0) == 1;
            } catch (RuntimeException error) {
                log("cannot read system boost setting: " + error);
                enabled = false;
            }
            if (enabled && vpnHooks.isEmpty()) {
                try {
                    vpnHooks.add(hookSocketProtection());
                    hookVpnUnderlays(vpnHooks);
                    boostSettingEnabled = true;
                    log("VPN hooks enabled");
                } catch (Throwable error) {
                    boostSettingEnabled = false;
                    removeVpnHooks();
                    log("cannot install VPN hooks: " + error);
                }
            } else if (!enabled && !vpnHooks.isEmpty()) {
                boostSettingEnabled = false;
                removeVpnHooks();
                resetBoostPolicy();
                log("VPN hooks disabled");
            }
        }
    }

    private static void removeVpnHooks() {
        for (XC_MethodHook.Unhook hook : vpnHooks) {
            hook.unhook();
        }
        vpnHooks.clear();
    }

    private static void resetBoostPolicy() {
        synchronized (RECENT_PROTECTS) {
            RECENT_PROTECTS.clear();
            cellularBoostUntil = 0L;
        }
        synchronized (DualPathHook.class) {
            cachedNetworks = PhysicalNetworks.EMPTY;
            lastNetworkScan = 0L;
        }
        NEXT_BOOST_NETWORK.set(0);
        PROTECTED_SOCKET_COUNT.set(0);
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
