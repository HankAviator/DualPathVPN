# DualPathVPN

DualPathVPN is an LSPosed module that distributes an Android VPN app's
outbound connections across validated Wi-Fi and cellular networks.

It was created to keep Xiaomi/HyperOS "Use mobile data to boost speed" useful while an
Android `VpnService` is active, but the hook targets standard Android APIs and
is not tied to Xiaomi or to a particular VPN app.

## What it does

For VPN apps selected in the module's LSPosed scope, DualPathVPN:

1. watches for simultaneously validated Wi-Fi and cellular networks;
2. advertises both networks as underlays of the VPN;
3. keeps low-rate connections on Wi-Fi, then distributes new sockets across
   Wi-Fi and cellular during connection bursts; and
4. leaves the VPN app's normal routing untouched whenever fewer than two
   usable links are available.

In HyperOS Settings, the module replaces the **Use mobile data to boost speed**
app list with a concise note about boost priority. It sorts all installed apps with internet
permission by their last recorded use, newest first, then fills the boost
whitelist with as many app UIDs as the phone's SLA daemon accepts. Apps with no
recorded use come last. The ranking refreshes when the screen is next opened
after seven days; no background polling is used. This works with VPN on or off
and does not enable the speed-boost switch. Some apps may not fit simultaneously.

With **System Framework** in its LSPosed scope, the module also bypasses
HyperOS's weak-cellular-signal stop condition while Mobile Speed Boost is on.
Thermal limits and network availability checks still apply. Using a weak
cellular link can increase battery use or reduce throughput; the override
does not strengthen the signal.

Without a VPN, HyperOS controls routing. The module only expands its app
whitelist; it does not bind ordinary app sockets to Wi-Fi or cellular. Xiaomi
describes mobile data boost as using both links when Wi-Fi is poor, so a
Speedtest on a good Wi-Fi connection may not use the cellular link at all.
See [Xiaomi's network acceleration guide](https://www.mi.com/sa-en/support/faq/details/KA-503463/).

VPN routing behavior is gated by Xiaomi/HyperOS's **Use mobile data to boost
speed** switch (`Settings.System.linkturbo_is_enable`). When the switch is off
or unavailable, the module removes its VPN socket and underlay hooks. One
settings observer remains to detect when the switch is turned on again. The
Settings whitelist sync does not depend on VPN state and runs only while the
speed-boost switch is on.

The module requests no Android permissions, contains no analytics, and has no
network client of its own.

## Requirements

- Android 6.0 or newer
- root with LSPosed (API 93 or newer)
- Wi-Fi and mobile data connected at the same time
- a VPN app that sends outbound sockets through Android's standard
  `VpnService.protect(int)` path

Some Android builds turn off cellular whenever Wi-Fi is validated. If needed,
keep mobile data alive with:

```shell
adb shell settings put global mobile_data_always_on 1
```

On Xiaomi/HyperOS, enable **Use mobile data to boost speed** in system settings.
VPN routing remains inactive while this switch is off. The Settings screen can
still populate the app whitelist while the switch is off.

## Installation

1. Build or download the APK and install it.
2. Open LSPosed and enable **DualPathVPN**.
3. In the module scope, select **Settings** (`com.android.settings`),
   **System Framework** (`android`), and the VPN app(s) you want to modify.
4. Reboot to load the framework signal override. For later changes that affect
   only Settings or VPN hooks, force-stop and restart those apps.

System Framework, Settings, and Exclave (`com.github.dyhkwong.sagernet`) are in
the recommended scope.
Exclave is the first verified VPN implementation. Other VPN apps must be
selected manually.

## Verification

With the VPN connected, Android should report both physical networks:

```shell
adb shell dumpsys connectivity | grep -A2 "VPN CONNECTED"
```

LSPosed logs use the prefix `[DualPathVPN]` and show whether protected sockets
were assigned to Wi-Fi or cellular.

For definitive testing on a rooted device, capture the VPN server traffic on
both physical interfaces with `tcpdump`.

## Compatibility and limitations

- This is per-connection load balancing, not true packet bonding. Multiple
  connections can use both links concurrently, but one TCP connection is not
  split across Wi-Fi and cellular. A single Speedtest result is not expected
  to equal the sum of standalone Wi-Fi and cellular results.
- Cellular boost starts when at least four sockets are protected within one
  second. It remains available for thirty seconds after the most recent
  connection burst. Boost expiry requires no timer or wake lock.
- While enabled, physical networks are scanned at most once per second under
  normal operation. Each successfully protected VPN socket may require one
  extra socket bind. While disabled, there is no per-socket module hook work.
- Existing cellular-bound sockets remain on cellular until the VPN app closes
  them. The module cannot safely migrate or tear down sockets owned by the VPN
  app, so turn the switch off before connecting the VPN, or reconnect the VPN
  after turning it off, to remove all prior module effects.
- A VPN core that bypasses `VpnService.protect(int)`, or binds its socket to a
  network after calling `protect`, may not be compatible.
- Both physical networks must have Android's `VALIDATED` capability and a
  default route.
- HyperOS disables its native boost service while a VPN is connected. With a
  VPN, the module can use both links only through compatible VPN apps' protected
  sockets. The note describes the selected policy, not a guarantee that every
  app or connection receives a speed increase.
- Scope Settings for the HyperOS app-list override and System Framework for
  the weak-signal override; scope the VPN apps whose routing you want to modify.
- Carrier data usage and battery consumption can increase.

### Verified setup

- Xiaomi 14 (`houji`)
- Android 15 / HyperOS
- Exclave 0.17.46
- Wi-Fi and cellular traffic observed concurrently at the VPN server endpoint

Compatibility reports for other VPN apps and ROMs are welcome.

## Building

Install JDK 17 and Android SDK Platform 35, then run:

```shell
./gradlew :app:assembleDebug
```

The installable APK is written under `app/build/outputs/apk/debug/`.

GitHub Actions runs the same build plus Android lint on every push and pull
request, and uploads the APK as a workflow artifact.

## License

Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
