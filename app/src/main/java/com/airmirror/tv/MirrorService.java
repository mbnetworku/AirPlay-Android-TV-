package com.airmirror.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Hosts the AirPlay receiver independent of {@link MainActivity}, so it keeps listening for a
 * connection after the app is closed. While idle it only runs a couple of listening sockets and
 * the mDNS responder — no decoders, no surface, no real CPU use. Decoding only starts once a
 * phone actually begins mirroring, same as before.
 * <p>
 * When mirroring starts (or a never-before-seen device asks to connect), this brings
 * {@link MainActivity} to the front so there is a screen to show the picture on, and so an
 * unapproved device's prompt is visible.
 */
public class MirrorService extends Service implements MirrorPlayback.Listener, MirrorPlayback.ApprovalGate {

    private static final String TAG = "MirrorService";
    private static final String CHANNEL_ID = "airplay_receiver";
    private static final String PREFS = "receiver";

    public interface UiCallback {
        void onStatusChanged(); // ask the Activity to re-read name()/addresses()/isReady() etc.

        void onMirroringStarted();

        void onMirroringStopped();

        void onVideoSize(int width, int height);

        /** Show an Allow/Deny prompt for a never-before-approved device. */
        void onApprovalNeeded(String deviceName);
    }

    public class LocalBinder extends Binder {
        MirrorService service() {
            return MirrorService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MirrorPlayback playback;
    private AirPlayServer server;
    private WifiManager.MulticastLock multicastLock;
    private volatile UiCallback ui;

    // One pending approval at a time; AirPlay only has one active sender anyway.
    private String pendingApprovalName;
    private Consumer<Boolean> pendingApprovalDecision;

    /** How a new connection is let through. Kept as plain strings so they're simple prefs values. */
    static final String MODE_APPROVAL = "approval"; // ask Allow/Deny on the TV (default)
    static final String MODE_PIN = "pin"; // enter a code on the iPhone — not implemented yet
    static final String MODE_NONE = "none"; // anyone can connect automatically

    @Override
    public void onCreate() {
        super.onCreate();
        playback = new MirrorPlayback(this);
        applyConnectMode();
        MdnsAdvertiser.deviceIdOverride = deviceId();
        startForeground(1, buildNotification(getString(R.string.status_starting)));
        startReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopReceiver();
        playback.release();
        serverExecutor.shutdown();
    }

    // --- UI binding: only while MainActivity is actually resumed ---

    void registerUi(UiCallback callback) {
        ui = callback;
    }

    void unregisterUi(UiCallback callback) {
        if (ui == callback) {
            ui = null;
        }
    }

    /** The Activity calls this right after registering, in case a prompt was waiting on it. */
    void checkPendingApproval() {
        String name;
        synchronized (this) {
            name = pendingApprovalName;
        }
        if (name != null && ui != null) {
            ui.onApprovalNeeded(name);
        }
    }

    void resolveApproval(boolean allow) {
        Consumer<Boolean> decision;
        String name;
        synchronized (this) {
            decision = pendingApprovalDecision;
            name = pendingApprovalName;
            pendingApprovalDecision = null;
            pendingApprovalName = null;
        }
        if (decision == null) {
            return;
        }
        if (allow && name != null) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> approved = new HashSet<>(prefs.getStringSet("approved_devices", Collections.emptySet()));
            approved.add(name);
            prefs.edit().putStringSet("approved_devices", approved).apply();
        }
        decision.accept(allow);
        updateNotification();
    }

    MirrorPlayback playback() {
        return playback;
    }

    // --- Connect mode (Settings) ---

    String connectMode() {
        String mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("connect_mode", MODE_APPROVAL);
        // PIN mode isn't built yet; never leave the receiver running unguarded because of it.
        return MODE_PIN.equals(mode) ? MODE_APPROVAL : mode;
    }

    void setConnectMode(String mode) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("connect_mode", mode).apply();
        applyConnectMode();
    }

    private void applyConnectMode() {
        boolean gated = !MODE_NONE.equals(connectMode());
        playback.setApprovalGate(gated ? this : null);
    }

    // --- MirrorPlayback.ApprovalGate (called from Netty threads) ---

    @Override
    public void requestApproval(String deviceName, Consumer<Boolean> decision) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> approved = prefs.getStringSet("approved_devices", Collections.emptySet());
        if (approved.contains(deviceName)) {
            decision.accept(true);
            return;
        }
        synchronized (this) {
            pendingApprovalName = deviceName;
            pendingApprovalDecision = decision;
        }
        bringAppToFront();
        mainHandler.post(() -> {
            updateNotification();
            if (ui != null) {
                ui.onApprovalNeeded(deviceName);
            }
        });
    }

    // --- MirrorPlayback.Listener (called from Netty threads) ---

    @Override
    public void onMirroringStarted() {
        bringAppToFront();
        mainHandler.post(() -> {
            updateNotification();
            if (ui != null) {
                ui.onMirroringStarted();
            }
        });
    }

    @Override
    public void onMirroringStopped() {
        mainHandler.post(() -> {
            updateNotification();
            if (ui != null) {
                ui.onMirroringStopped();
            }
        });
    }

    @Override
    public void onVideoSize(int width, int height) {
        mainHandler.post(() -> {
            if (ui != null) {
                ui.onVideoSize(width, height);
            }
        });
    }

    private void bringAppToFront() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    // --- Receiver lifecycle (moved out of the Activity so it survives the app closing) ---

    private void startReceiver() {
        String name = receiverName();
        List<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            updateNotification();
            mainHandler.postDelayed(this::startReceiver, 5000); // Wi-Fi may still be coming up
            return;
        }

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && multicastLock == null) {
            multicastLock = wifi.createMulticastLock("airplay-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        AirPlayConfig config = new AirPlayConfig();
        config.setServerName(name);
        config.setWidth(1920);
        config.setHeight(1080);
        config.setFps(60);
        config.setHlsEnabled(true); // also offer the AirPlay button inside Safari/YouTube/etc, not just mirroring
        config.setMdnsLoopback(false);
        config.setTrayEnabled(false);

        serverExecutor.execute(() -> {
            try {
                AirPlayServer s = new AirPlayServer(config, playback);
                s.start();
                server = s;
                Log.i(TAG, "AirPlay receiver started as " + name);
                mainHandler.post(() -> {
                    updateNotification();
                    if (ui != null) {
                        ui.onStatusChanged();
                    }
                });
            } catch (Throwable e) {
                Log.e(TAG, "Cannot start AirPlay receiver", e);
            }
        });
    }

    private void stopReceiver() {
        AirPlayServer s = server;
        server = null;
        if (s != null) {
            try {
                s.stop();
            } catch (Throwable e) {
                Log.w(TAG, "Error stopping receiver", e);
            }
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    /** Restart under a new name without a full service restart, e.g. after Rename. */
    void restart() {
        stopReceiver();
        startReceiver();
    }

    boolean isReady() {
        return server != null;
    }

    String name() {
        return receiverName();
    }

    List<String> addresses() {
        return localAddresses();
    }

    // --- Notification ---

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1, buildNotification(notificationText()));
        }
    }

    private String notificationText() {
        if (pendingApprovalName != null) {
            return getString(R.string.notif_approval, pendingApprovalName);
        }
        if (server == null) {
            return getString(R.string.status_starting);
        }
        return getString(R.string.notif_waiting, receiverName());
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    // --- Identity / network (same as before, now owned by the service) ---

    private String receiverName() {
        String custom = getSharedPreferences(PREFS, MODE_PRIVATE).getString("name", null);
        if (custom != null && !custom.trim().isEmpty()) {
            return cleanName(custom);
        }
        String name = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        if (name == null || name.trim().isEmpty()) {
            name = Build.MODEL;
        }
        return cleanName(name);
    }

    private static String cleanName(String raw) {
        return raw.replace('.', ' ').trim();
    }

    private String deviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            byte[] mac = new byte[6];
            new SecureRandom().nextBytes(mac);
            mac[0] = (byte) ((mac[0] & 0xFC) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mac.length; i++) {
                sb.append(String.format("%02X", mac[i]));
                if (i < mac.length - 1) {
                    sb.append(':');
                }
            }
            id = sb.toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) {
                    continue;
                }
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot list network interfaces", e);
        }
        return result;
    }
}
