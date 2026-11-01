package com.airmirror.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen AirPlay receiver. While this screen is open the TV box shows up under
 * Screen Mirroring on iPhone / iPad / Mac, and the mirrored picture fills the TV.
 */
public class MainActivity extends Activity implements MirrorPlayback.Listener, SurfaceHolder.Callback {

    private static final String TAG = "AirMirrorTV";

    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private SurfaceView surfaceView;
    private View overlay;
    private TextView statusView;
    private TextView nameView;
    private TextView addressView;

    private MirrorPlayback playback;
    private AirPlayServer server;
    private WifiManager.MulticastLock multicastLock;
    private int videoWidth;
    private int videoHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        playback = new MirrorPlayback(this);
        MdnsAdvertiser.deviceIdOverride = deviceId();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startReceiver();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        serverExecutor.shutdown();
    }

    private void startReceiver() {
        showOverlay(true);
        statusView.setText(R.string.status_starting);
        String name = receiverName();
        nameView.setText(name);
        List<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            statusView.setText(R.string.status_no_network);
            nameView.setText("");
            addressView.setText("");
            return;
        }
        addressView.setText(getString(R.string.status_address, TextUtils.join(", ", addresses)));

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("airplay-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        AirPlayConfig config = new AirPlayConfig();
        config.setServerName(name);
        config.setWidth(1920);
        config.setHeight(1080);
        config.setFps(60);
        config.setHlsEnabled(false);
        config.setMdnsLoopback(false);
        config.setTrayEnabled(false);

        serverExecutor.execute(() -> {
            try {
                AirPlayServer s = new AirPlayServer(config, playback);
                s.start();
                server = s;
                Log.i(TAG, "AirPlay receiver started as " + name);
                runOnUiThread(() -> statusView.setText(R.string.status_ready));
            } catch (Throwable e) {
                Log.e(TAG, "Cannot start AirPlay receiver", e);
                runOnUiThread(() -> statusView.setText(getString(R.string.status_error, String.valueOf(e.getMessage()))));
            }
        });
    }

    private void stopReceiver() {
        serverExecutor.execute(() -> {
            AirPlayServer s = server;
            server = null;
            if (s != null) {
                try {
                    s.stop();
                } catch (Throwable e) {
                    Log.w(TAG, "Error stopping receiver", e);
                }
            }
            playback.release();
        });
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    // --- MirrorPlayback.Listener (called from network threads) ---

    @Override
    public void onMirroringStarted() {
        runOnUiThread(() -> showOverlay(false));
    }

    @Override
    public void onMirroringStopped() {
        runOnUiThread(() -> showOverlay(true));
    }

    @Override
    public void onVideoSize(int width, int height) {
        runOnUiThread(() -> {
            videoWidth = width;
            videoHeight = height;
            layoutVideo();
            showOverlay(false);
        });
    }

    // --- Surface ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        playback.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        playback.setSurface(null);
    }

    // --- UI ---

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutVideo());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(Color.rgb(12, 18, 32));
        int pad = dp(48);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = text(32, Color.WHITE, true);
        title.setText(R.string.app_name);
        statusView = text(20, Color.rgb(200, 210, 230), false);
        nameView = text(40, Color.rgb(120, 170, 255), true);
        addressView = text(16, Color.rgb(140, 150, 170), false);

        panel.addView(title);
        panel.addView(space());
        panel.addView(statusView);
        panel.addView(space());
        panel.addView(nameView);
        panel.addView(space());
        panel.addView(addressView);

        overlay = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /** Letterbox the picture: portrait iPhone screens get bars left and right. */
    private void layoutVideo() {
        int pw = root.getWidth();
        int ph = root.getHeight();
        if (pw == 0 || ph == 0) {
            return;
        }
        int w = pw;
        int h = ph;
        if (videoWidth > 0 && videoHeight > 0) {
            if ((long) videoWidth * ph > (long) videoHeight * pw) {
                h = (int) ((long) pw * videoHeight / videoWidth);
            } else {
                w = (int) ((long) ph * videoWidth / videoHeight);
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            surfaceView.setLayoutParams(lp);
        }
    }

    private void showOverlay(boolean show) {
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            videoWidth = 0;
            videoHeight = 0;
            layoutVideo();
        }
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(16)));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // --- Identity / network ---

    private String receiverName() {
        String name = Settings.Global.getString(getContentResolver(), "device_name");
        if (name == null || name.trim().isEmpty()) {
            name = Build.MODEL;
        }
        // mDNS instance names cannot contain dots.
        return name.replace('.', ' ').trim();
    }

    /** A random, locally administered MAC-style id, kept so the iPhone recognises this TV next time. */
    private String deviceId() {
        SharedPreferences prefs = getSharedPreferences("receiver", MODE_PRIVATE);
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
