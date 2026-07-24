# DualPathVPN

DualPathVPN is an LSPosed module that distributes an Android VPN app's
outbound connections across validated Wi-Fi and cellular networks.

It was created to keep Xiaomi/HyperOS "Data acceleration" useful while an
Android `VpnService` is active, but the hook targets standard Android APIs and
is not tied to Xiaomi or to a particular VPN app.

## What it does

For VPN apps selected in the module's LSPosed scope, DualPathVPN:

1. watches for simultaneously validated Wi-Fi and cellular networks;
2. advertises both networks as underlays of the VPN;
3. binds each newly protected VPN socket to Wi-Fi or cellular in round-robin
   order; and
4. leaves the VPN app's normal routing untouched whenever fewer than two
   usable links are available.

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

On Xiaomi/HyperOS, also enable **Data acceleration** in system settings.

## Installation

1. Build or download the APK and install it.
2. Open LSPosed and enable **DualPathVPN**.
3. In the module scope, select only the VPN app(s) you want to modify.
4. Force-stop and restart each selected VPN app, then reconnect its VPN.

Exclave (`com.github.dyhkwong.sagernet`) is the default scope because it is the
first verified implementation. Other VPN apps must be selected manually.

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
  split across Wi-Fi and cellular.
- A VPN core that bypasses `VpnService.protect(int)`, or binds its socket to a
  network after calling `protect`, may not be compatible.
- Both physical networks must have Android's `VALIDATED` capability and a
  default route.
- Selecting non-VPN apps in LSPosed provides no benefit. Keep the scope narrow.
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
