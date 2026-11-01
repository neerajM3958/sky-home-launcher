package com.skyhome.launcher;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * What the TV is connected to: the Wi-Fi network (name and signal) or the cable, and the Bluetooth devices in use (remote,
 * headphones...). One per process, kept up to date by the system's callbacks (never polled); `draw` paints it as one line,
 * so every screen that shows it (the home screen's weather block, the Quick Settings card) shares the reading and the look.
 * Needs no Sky Home Launcher classes beyond `Ui.tr`, so the `:keys` process can use it. Without the location permission the
 * network's name is hidden by Android (the signal still shows); without Bluetooth permission there are no device names.
 * The home screen asks for both once, with the TV's own dialogs (`missing`, `HomeActivity`); no ADB involved.
 */
final class ConnectionStatus {
    static final int NONE = 0, WIFI = 1, CABLE = 2, ASK = 41;
    /** `State.radio`: the Bluetooth adapter's state when no device shows. */
    static final int RADIO_UNKNOWN = 0, RADIO_OFF = 1, RADIO_ON = 2;
    private static final int MAX_NAME = 24, BT_DELAY_MS = 1500;
    /**
     * Only audio devices count (headset and A2DP profiles: headphones, speakers, soundbars). Remotes, keyboards, mice and
     * game pads connect through the input profiles (HID host 4, GATT 7), left out so the line isn't always the TV's remote.
     */
    private static final int[] PROFILES = {1, 2};

    /** A reading; never changed after it is made, so a drawing thread can hold one. */
    static final class State {
        final int link;
        /** The Wi-Fi network's name, "" when Android hides it. */
        final String network;
        /** Signal, 0 to 4 (Wi-Fi only). */
        final int level;
        /** The connected Bluetooth audio devices: the first one's name, and "+N" for the others; "" for none. */
        final String bluetooth;
        /** Wi-Fi is switched on (shown as "Not connected" or "Off" when there's no link). */
        final boolean wifiOn;
        /** Bluetooth's switch (RADIO_*), for when no device is connected. */
        final int radio;

        State(int link, String network, int level, String bluetooth, boolean wifiOn, int radio) {
            this.wifiOn = wifiOn;
            this.radio = radio;
            this.link = link;
            this.network = network;
            this.level = level;
            this.bluetooth = bluetooth;
        }

        /** True when nothing at all is worth a line (no Wi-Fi hardware answered and no Bluetooth). */
        boolean isEmpty() {
            return false;
        }

        boolean same(State o) {
            return link == o.link && wifiOn == o.wifiOn && radio == o.radio && level == o.level && network.equals(o.network) && bluetooth.equals(o.bluetooth);
        }
    }

    private static ConnectionStatus instance;

    static synchronized ConnectionStatus get(Context c) {
        if (instance == null) instance = new ConnectionStatus(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile State state = new State(NONE, "", 0, "", true, RADIO_UNKNOWN);
    private String bluetooth = "";
    private int radio = RADIO_UNKNOWN;
    private int btRound;
    /** Names of the devices that connected, the latest last (from the system's connect and disconnect broadcasts). */
    private final List<String> recent = new ArrayList<>();
    private final Runnable refresh = this::read;
    private final Runnable refreshBluetooth = this::readBluetooth;

    private ConnectionStatus(Context c) {
        app = c;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { later(); }
                @Override public void onLost(Network n) { later(); }
                // The signal level moves with this one (Wi-Fi); `read` only tells when the bars changed.
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) { later(); }
            });
        } catch (RuntimeException e) {
            AppLog.w("status network callback: " + e.getMessage());
        }
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { later(); }
            }, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        } catch (RuntimeException e) {
            AppLog.w("status wifi receiver: " + e.getMessage());
        }
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    track(intent);
                    // The profiles connect a moment after the link: ask then, once for a burst of them.
                    main.removeCallbacks(refreshBluetooth);
                    main.postDelayed(refreshBluetooth, BT_DELAY_MS);
                }
            }, f);
        } catch (RuntimeException e) {
            AppLog.w("status bluetooth receiver: " + e.getMessage());
        }
        main.post(refresh);
        main.post(refreshBluetooth);
    }

    /** The permissions the line still lacks: location for the Wi-Fi name, Bluetooth (Android 12+) for the devices' names. */
    static String[] missing(Context c) {
        List<String> m = new ArrayList<>();
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            m.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            m.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return m.toArray(new String[0]);
    }

    /** A permission was just answered: read everything again. */
    void reload() {
        main.post(refresh);
        main.post(refreshBluetooth);
    }

    /** Keeps the order devices connected in, so the line can name the latest. */
    private void track(Intent intent) {
        String action = intent.getAction();
        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String name = null;
        try {
            name = d == null ? null : d.getName();
        } catch (RuntimeException ignored) {}
        if (name == null || name.trim().isEmpty()) return;
        name = shorten(name.trim());
        recent.remove(name);
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) recent.add(name);
    }

    State state() {
        return state;
    }

    /** `l` runs on the main thread whenever the reading changes. */
    void addListener(Runnable l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Runnable l) {
        listeners.remove(l);
    }

    /** Coalesces the system's bursts of callbacks into one reading. */
    private void later() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 300);
    }

    private void read() {
        int link = NONE, level = 0;
        String name = "";
        boolean wifiOn = true;
        try {
            WifiManager enabled = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            wifiOn = enabled == null || enabled.isWifiEnabled();
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                link = CABLE;
            } else if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                link = WIFI;
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    level = level(info.getRssi());
                    name = clean(info.getSSID());
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("status network: " + e.getMessage());
        }
        publish(new State(link, name, level, bluetooth, wifiOn, radio));
    }

    /** Android's five bars from the signal in dBm. */
    static int level(int rssi) {
        return rssi >= -55 ? 4 : rssi >= -65 ? 3 : rssi >= -75 ? 2 : rssi >= -85 ? 1 : 0;
    }

    /** The name without its quotes; "" for what Android shows when it hides the name. */
    private static String clean(String ssid) {
        if (ssid == null) return "";
        String s = ssid.trim();
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.equals("<unknown ssid>") || s.equals("0x") ? "" : shorten(s);
    }

    private static String shorten(String s) {
        return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME - 1) + "…";
    }

    private void publish(State next) {
        if (next.same(state)) return;
        state = next;
        for (Runnable l : new ArrayList<>(listeners)) l.run();
    }

    // MARK: - Bluetooth

    private boolean canBluetooth() {
        String permission = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
        return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Asks each profile who is connected; the answers come on the main thread, and the last one makes the reading. */
    private void readBluetooth() {
        final BluetoothAdapter adapter;
        try {
            BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null) {
                setBluetooth(new TreeSet<String>(), RADIO_UNKNOWN);
                return;
            }
            if (!adapter.isEnabled()) {
                setBluetooth(new TreeSet<String>(), RADIO_OFF);
                return;
            }
            if (!canBluetooth()) {      // on, but who is connected stays hidden without the permission
                setBluetooth(new TreeSet<String>(), RADIO_UNKNOWN);
                return;
            }
        } catch (RuntimeException e) {
            AppLog.w("status bluetooth: " + e.getMessage());
            return;
        }
        final int round = ++btRound;
        final TreeSet<String> found = new TreeSet<>();
        final int[] waiting = {PROFILES.length};
        for (int profile : PROFILES) {
            boolean asked = false;
            try {
                asked = adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int p, BluetoothProfile proxy) {
                        try {
                            for (BluetoothDevice d : proxy.getConnectedDevices()) {
                                String n = d.getName();
                                if (n != null && !n.trim().isEmpty()) found.add(shorten(n.trim()));
                            }
                        } catch (RuntimeException ignored) {
                        } finally {
                            adapter.closeProfileProxy(p, proxy);
                        }
                        done(round, waiting, found);
                    }

                    @Override public void onServiceDisconnected(int p) {}
                }, profile);
            } catch (RuntimeException ignored) {}
            if (!asked) done(round, waiting, found);
        }
    }

    private void done(int round, int[] waiting, TreeSet<String> found) {
        if (--waiting[0] == 0 && round == btRound) setBluetooth(found, RADIO_ON);
    }

    private void setBluetooth(TreeSet<String> names, int radioNow) {
        // The one that connected last (devices already connected when the app started rank below those seen since).
        String latest = names.isEmpty() ? "" : names.first();
        for (String n : recent) if (names.contains(n)) latest = n;
        String text = latest.isEmpty() ? "" : latest + (names.size() > 1 ? " +" + (names.size() - 1) : "");
        if (text.equals(bluetooth) && radioNow == radio) return;
        bluetooth = text;
        radio = radioNow;
        State s = state;
        publish(new State(s.link, s.network, s.level, text, s.wifiOn, radioNow));
    }

    // MARK: - Drawing

    private static final Paint ICON = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path RUNE = new Path();

    static {
        // The Bluetooth rune in a box from -1 to 1 (same shape as the Quick Settings tile's).
        RUNE.moveTo(-0.55f, -0.5f);
        RUNE.lineTo(0.55f, 0.5f);
        RUNE.lineTo(0, 1);
        RUNE.lineTo(0, -1);
        RUNE.lineTo(0.55f, -0.5f);
        RUNE.lineTo(-0.55f, 0.5f);
        ICON.setStrokeCap(Paint.Cap.ROUND);
        ICON.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * The state as one line from `x` on the baseline `base`: the signal bars and the network's name, then the Bluetooth
     * rune and the device's name, in `text`'s font and colour. Returns its width (0: nothing to show). No allocation.
     */
    static float draw(Canvas c, State s, float x, float base, Paint text) {
        float w = wifi(c, s, x, base, text, true);
        if (w > 0 && bluetoothText(s) != null) w += text.getTextSize() * 0.8f;
        return w + bluetooth(c, s, x + w, base, text, true);
    }

    /** Only the network (bars and name), its right end at `right`: a row that shows it as its value. No bars without a link. Returns its width. */
    static float drawWifi(Canvas c, State s, float right, float base, Paint text) {
        return wifi(c, s, right - wifi(null, s, 0, base, text, false), base, text, false);
    }

    /** Only the Bluetooth device or its switch, its right end at `right`, with the rune before it if `icon`. Returns its width. */
    static float drawBluetooth(Canvas c, State s, float right, float base, Paint text, boolean icon) {
        return bluetooth(c, s, right - bluetooth(null, s, 0, base, text, icon), base, text, icon);
    }

    /** The network part from `x`; with no canvas it only measures. Without a link the bars (all faint) show only if `idleBars`. */
    private static float wifi(Canvas c, State s, float x, float base, Paint text, boolean idleBars) {
        float size = text.getTextSize(), h = size * 0.95f, start = x;
        if (s.link == WIFI || (s.link == NONE && idleBars)) {
            if (c != null) bars(c, x, base, h, s.link == WIFI ? s.level : -1, text.getColor());
            x += h * 1.05f + size * 0.24f;
        }
        String name = s.link == NONE ? (s.wifiOn ? Ui.tr("Not connected") : Ui.tr("Off"))
                : s.network.isEmpty() ? (s.link == WIFI ? Ui.tr("Wi-Fi") : Ui.tr("Ethernet")) : s.network;
        if (c != null) c.drawText(name, x, base, text);
        return x + text.measureText(name) - start;
    }

    /** What the Bluetooth part says: the device, else whether the radio is on or off; null when it can't tell. */
    private static String bluetoothText(State s) {
        if (!s.bluetooth.isEmpty()) return s.bluetooth;
        return s.radio == RADIO_ON ? Ui.tr("On") : s.radio == RADIO_OFF ? Ui.tr("Off") : null;
    }

    /** The Bluetooth part from `x`; with no canvas it only measures. */
    private static float bluetooth(Canvas c, State s, float x, float base, Paint text, boolean icon) {
        String label = bluetoothText(s);
        if (label == null) return 0;
        float size = text.getTextSize(), h = size * 0.95f, r = h * 0.5f, start = x;
        if (icon && c != null) {
            ICON.setStyle(Paint.Style.STROKE);
            ICON.setColor(text.getColor());
            c.save();
            c.translate(x + r * 0.55f, base - h * 0.5f);
            c.scale(r * 0.9f, r * 0.9f);
            ICON.setStrokeWidth(0.2f);
            c.drawPath(RUNE, ICON);
            c.restore();
        }
        if (icon) x += r * 1.1f + size * 0.24f;
        if (c != null) c.drawText(label, x, base, text);
        return x + text.measureText(label) - start;
    }

    /** Four rising bars of height `h` standing on `base`; the first `level` (at least one; none for -1) full, the rest faint. */
    private static void bars(Canvas c, float x, float base, float h, int level, int ink) {
        float w = h * 0.2f, gap = h * 0.08f;
        int filled = level < 0 ? 0 : Math.max(level, 1);
        ICON.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float top = base - h * (0.35f + 0.65f * i / 3);
            ICON.setColor(i < filled ? ink : (ink & 0x00FFFFFF) | ((ink >>> 24) * 30 / 100) << 24);
            c.drawRoundRect(x + i * (w + gap), top, x + i * (w + gap) + w, base, w / 3, w / 3, ICON);
        }
    }
}
