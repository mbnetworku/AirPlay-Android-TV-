package com.airmirror.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Phone side of Android screen sharing ("Share to TV" icon). Finds TVs running AirMirror TV on
 * the same Wi-Fi, asks Android for screen-capture permission, then hands over to
 * {@link ShareService}. Never starts the TV receiver, so a phone pays nothing in the background.
 */
public class ShareActivity extends Activity implements ShareService.StatusListener {

    private static final int REQUEST_CAPTURE = 1;
    private static final String PREFS = "share";

    private LinearLayout tvList;
    private TextView emptyHint;
    private TextView statusView;
    private TextView stopButton;
    private EditText addressInput;
    private final Set<String> shown = new HashSet<>();

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;

    private String pendingHost;
    private int pendingPort;
    private String pendingName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShareService.statusListener = this;
        onShareStatus(ShareService.state, ShareService.stateMessage);
        startDiscovery();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (ShareService.statusListener == this) {
            ShareService.statusListener = null;
        }
        stopDiscovery();
    }

    // --- UI ---

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.addView(GlassTheme.buildBackground(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(36), pad, dp(36));
        scroll.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView title = GlassTheme.text(this, 30, GlassTheme.TEXT_PRIMARY, true);
        title.setGravity(Gravity.START);
        title.setText(R.string.share_title);
        col.addView(title);
        TextView sub = GlassTheme.text(this, 15, GlassTheme.TEXT_SECONDARY, false);
        sub.setGravity(Gravity.START);
        sub.setText(R.string.share_subtitle);
        col.addView(sub);
        col.addView(GlassTheme.space(this, 20));

        statusView = GlassTheme.text(this, 16, Color.WHITE, false);
        statusView.setGravity(Gravity.START);
        statusView.setBackground(GlassTheme.panelAccent(this, 18, GlassTheme.ACCENT));
        statusView.setPadding(dp(18), dp(14), dp(18), dp(14));
        statusView.setVisibility(View.GONE);
        col.addView(statusView, matchWidth());

        stopButton = button(R.string.share_stop, true);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> startService(
                new Intent(this, ShareService.class).setAction(ShareService.ACTION_STOP)));
        LinearLayout.LayoutParams stopLp = matchWidth();
        stopLp.topMargin = dp(12);
        col.addView(stopButton, stopLp);
        col.addView(GlassTheme.space(this, 24));

        TextView listLabel = GlassTheme.text(this, 13, GlassTheme.TEXT_TERTIARY, false);
        listLabel.setGravity(Gravity.START);
        listLabel.setLetterSpacing(0.1f);
        listLabel.setText(R.string.share_tvs_label);
        col.addView(listLabel);
        col.addView(GlassTheme.space(this, 10));

        tvList = new LinearLayout(this);
        tvList.setOrientation(LinearLayout.VERTICAL);
        col.addView(tvList, matchWidth());
        emptyHint = GlassTheme.text(this, 15, GlassTheme.TEXT_SECONDARY, false);
        emptyHint.setGravity(Gravity.START);
        emptyHint.setText(R.string.share_searching);
        emptyHint.setBackground(GlassTheme.panelDim(this, 18));
        emptyHint.setPadding(dp(18), dp(16), dp(18), dp(16));
        tvList.addView(emptyHint, matchWidth());
        col.addView(GlassTheme.space(this, 24));

        TextView manualLabel = GlassTheme.text(this, 13, GlassTheme.TEXT_TERTIARY, false);
        manualLabel.setGravity(Gravity.START);
        manualLabel.setLetterSpacing(0.1f);
        manualLabel.setText(R.string.share_manual_label);
        col.addView(manualLabel);
        col.addView(GlassTheme.space(this, 10));

        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setHint(R.string.share_manual_hint);
        addressInput.setTextColor(GlassTheme.TEXT_PRIMARY);
        addressInput.setHintTextColor(GlassTheme.TEXT_TERTIARY);
        addressInput.setBackground(GlassTheme.panelDim(this, 16));
        addressInput.setPadding(dp(18), dp(14), dp(18), dp(14));
        addressInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_address", ""));
        addressInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressInput.setOnEditorActionListener((v, a, e) -> {
            connectManual();
            return true;
        });
        col.addView(addressInput, matchWidth());
        TextView connect = button(R.string.share_connect, false);
        connect.setOnClickListener(v -> connectManual());
        LinearLayout.LayoutParams cLp = matchWidth();
        cLp.topMargin = dp(12);
        col.addView(connect, cLp);
        col.addView(GlassTheme.space(this, 20));

        TextView note = GlassTheme.text(this, 13, GlassTheme.TEXT_TERTIARY, false);
        note.setGravity(Gravity.START);
        note.setText(R.string.share_note);
        col.addView(note);

        setContentView(root);
    }

    private void addTv(String name, String host, int port) {
        String key = host + ":" + port;
        if (!shown.add(key)) {
            return;
        }
        if (emptyHint.getParent() != null) {
            tvList.removeView(emptyHint);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(GlassTheme.focusableRow(this, 18));
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView n = GlassTheme.text(this, 18, GlassTheme.TEXT_PRIMARY, true);
        n.setGravity(Gravity.START);
        n.setText("📺  " + name);
        TextView a = GlassTheme.text(this, 13, GlassTheme.TEXT_SECONDARY, false);
        a.setGravity(Gravity.START);
        a.setText(host);
        row.addView(n);
        row.addView(a);
        row.setOnClickListener(v -> requestCapture(host, port, name));
        LinearLayout.LayoutParams lp = matchWidth();
        lp.bottomMargin = dp(10);
        tvList.addView(row, lp);
    }

    private TextView button(int textRes, boolean primary) {
        TextView b = GlassTheme.text(this, 17, Color.WHITE, true);
        b.setText(textRes);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        b.setBackground(primary ? GlassTheme.panelAccent(this, 24, GlassTheme.RED)
                : GlassTheme.pillButton(this, 24));
        return b;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return GlassTheme.dp(this, v);
    }

    @Override
    public void onShareStatus(ShareService.State s, String message) {
        boolean visible = s != ShareService.State.IDLE && !TextUtils.isEmpty(message);
        statusView.setVisibility(visible ? View.VISIBLE : View.GONE);
        statusView.setText(message);
        boolean live = s == ShareService.State.CONNECTING || s == ShareService.State.WAITING_FOR_TV
                || s == ShareService.State.SHARING;
        stopButton.setVisibility(live ? View.VISIBLE : View.GONE);
    }

    // --- Starting a share ---

    private void connectManual() {
        String raw = addressInput.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.share_manual_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        String host = raw;
        int port = AndroidShareReceiver.PORT;
        int colon = raw.lastIndexOf(':');
        if (colon > 0 && raw.indexOf(':') == colon) { // "ip:port", not IPv6
            host = raw.substring(0, colon);
            try {
                port = Integer.parseInt(raw.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // keep the default port
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_address", raw).apply();
        requestCapture(host, port, host);
    }

    private void requestCapture(String host, int port, String name) {
        ShareService.State s = ShareService.state;
        if (s == ShareService.State.SHARING || s == ShareService.State.CONNECTING
                || s == ShareService.State.WAITING_FOR_TV) {
            Toast.makeText(this, R.string.share_already, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingHost = host;
        pendingPort = port;
        pendingName = name;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || pendingHost == null) {
            Toast.makeText(this, R.string.share_capture_declined, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, ShareService.class)
                .putExtra(ShareService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ShareService.EXTRA_RESULT_DATA, data)
                .putExtra(ShareService.EXTRA_HOST, pendingHost)
                .putExtra(ShareService.EXTRA_PORT, pendingPort)
                .putExtra(ShareService.EXTRA_TV_NAME, pendingName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    // --- Finding TVs (one resolve at a time: older Android allows no more) ---

    private void startDiscovery() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsd == null) {
                return;
            }
            discovery = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String t) {
                }

                @Override
                public void onDiscoveryStopped(String t) {
                }

                @Override
                public void onStartDiscoveryFailed(String t, int e) {
                    runOnUiThread(() -> emptyHint.setText(R.string.share_search_failed));
                }

                @Override
                public void onStopDiscoveryFailed(String t, int e) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    runOnUiThread(() -> {
                        toResolve.add(info);
                        resolveNext();
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            nsd.discoverServices(AndroidShareReceiver.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
            emptyHint.setText(R.string.share_search_failed);
        }
    }

    private void resolveNext() {
        if (resolving || toResolve.isEmpty() || nsd == null) {
            return;
        }
        resolving = true;
        NsdServiceInfo next = toResolve.poll();
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int e) {
                    runOnUiThread(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    runOnUiThread(() -> {
                        resolving = false;
                        if (i.getHost() != null) {
                            addTv(i.getServiceName(), i.getHost().getHostAddress(), i.getPort());
                        }
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        }
        discovery = null;
        toResolve.clear();
        resolving = false;
    }
}
