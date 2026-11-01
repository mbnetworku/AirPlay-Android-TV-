package com.airmirror.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
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
    private View statusDot;

    private static final int AMBER = Color.rgb(255, 193, 7);
    private static final int GREEN = Color.rgb(61, 220, 132);
    private static final int RED = Color.rgb(255, 82, 82);
    private static final int ACCENT = Color.rgb(76, 141, 255);

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
        setStatus(getString(R.string.status_starting), AMBER);
        String name = receiverName();
        nameView.setText(name);
        List<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            setStatus(getString(R.string.status_no_network), RED);
            nameView.setText("");
            addressView.setText("");
            return;
        }
        addressView.setText(getString(R.string.status_address, TextUtils.join("  ·  ", addresses)));

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
                runOnUiThread(() -> setStatus(getString(R.string.status_ready), GREEN));
            } catch (Throwable e) {
                Log.e(TAG, "Cannot start AirPlay receiver", e);
                runOnUiThread(() -> setStatus(getString(R.string.status_error, String.valueOf(e.getMessage())), RED));
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

        // Idle screen: brand + receiver name on the left, how-to steps on the right.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(8, 12, 28), Color.rgb(24, 40, 92)});
        panel.setBackground(bg);
        panel.setPadding(dp(72), dp(40), dp(72), dp(40));

        // Left column
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setClipToOutline(true);
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        brand.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(16), 0, 0, 0);
        TextView title = text(34, Color.WHITE, true);
        title.setGravity(Gravity.START);
        title.setText(R.string.app_name);
        TextView tagline = text(16, Color.rgb(150, 165, 200), false);
        tagline.setGravity(Gravity.START);
        tagline.setText(R.string.tagline);
        titles.addView(title);
        titles.addView(tagline);
        brand.addView(titles);
        left.addView(brand);
        left.addView(space(32));

        // Status chip
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(rounded(Color.argb(30, 255, 255, 255), dp(24), 0, 0));
        chip.setPadding(dp(16), dp(8), dp(20), dp(8));
        statusDot = new View(this);
        statusDot.setBackground(oval(AMBER));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotLp.rightMargin = dp(10);
        chip.addView(statusDot, dotLp);
        statusView = text(18, Color.rgb(225, 232, 250), false);
        statusView.setGravity(Gravity.START);
        chip.addView(statusView);
        left.addView(chip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        left.addView(space(28));

        // Receiver name card
        TextView nameLabel = text(14, Color.rgb(150, 165, 200), false);
        nameLabel.setGravity(Gravity.START);
        nameLabel.setLetterSpacing(0.15f);
        nameLabel.setText(R.string.name_label);
        left.addView(nameLabel);
        left.addView(space(8));
        nameView = text(48, Color.WHITE, true);
        nameView.setGravity(Gravity.CENTER_VERTICAL);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setBackground(rounded(Color.argb(40, 76, 141, 255), dp(20), dp(2), ACCENT));
        nameView.setPadding(dp(28), dp(16), dp(28), dp(16));
        left.addView(nameView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        left.addView(space(16));
        addressView = text(14, Color.rgb(120, 135, 170), false);
        addressView.setGravity(Gravity.START);
        left.addView(addressView);

        panel.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(new View(this), new LinearLayout.LayoutParams(dp(64), 1));

        // Right column: how to connect
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setBackground(rounded(Color.argb(22, 255, 255, 255), dp(24), dp(1), Color.argb(40, 255, 255, 255)));
        steps.setPadding(dp(36), dp(32), dp(36), dp(32));
        TextView howTo = text(22, Color.WHITE, true);
        howTo.setGravity(Gravity.START);
        howTo.setText(R.string.how_to);
        steps.addView(howTo);
        steps.addView(space(20));
        steps.addView(step(1, getString(R.string.step_1)));
        steps.addView(step(2, getString(R.string.step_2)));
        steps.addView(step(3, getString(R.string.step_3)));
        panel.addView(steps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

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

    private View space(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private void setStatus(String message, int color) {
        statusView.setText(message);
        statusDot.setBackground(oval(color));
    }

    private GradientDrawable rounded(int fill, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) {
            d.setStroke(strokeWidth, strokeColor);
        }
        return d;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** One numbered row of the how-to list. */
    private View step(int number, String message) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView badge = text(18, Color.WHITE, true);
        badge.setText(String.valueOf(number));
        badge.setBackground(oval(ACCENT));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        badgeLp.rightMargin = dp(18);
        row.addView(badge, badgeLp);
        TextView label = text(19, Color.rgb(215, 225, 245), false);
        label.setGravity(Gravity.START);
        label.setText(message);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
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
