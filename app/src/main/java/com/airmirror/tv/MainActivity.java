package com.airmirror.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Full-screen AirPlay receiver UI. The actual receiver runs in {@link MirrorService}, so it
 * keeps listening after this screen is closed; this Activity binds to it for the picture, the
 * status display and the approval prompt, and the service brings it back to the front itself
 * whenever a phone actually starts mirroring.
 */
public class MainActivity extends Activity implements MirrorService.UiCallback, TextureView.SurfaceTextureListener {

    private static final String TAG = "AirMirrorTV";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextureView surfaceView;
    private View overlay;
    private TextView statusView;
    private TextView nameView;
    private TextView addressView;
    private View statusDot;
    private View settingsButton;
    private TextView rotationHint;
    private int rotationDegrees;
    private LinearLayout rightPanel;
    private boolean showingSettings;

    private static final int AMBER = GlassTheme.AMBER;
    private static final int GREEN = GlassTheme.GREEN;
    private static final int RED = GlassTheme.RED;
    private static final int ACCENT = GlassTheme.ACCENT;

    private MirrorService service;
    private Surface pendingSurface;
    private AlertDialog approvalDialog;
    private int videoWidth;
    private int videoHeight;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MirrorService.LocalBinder) binder).service();
            service.registerUi(MainActivity.this);
            if (pendingSurface != null) {
                service.playback().setSurface(pendingSurface);
            }
            refreshStatus();
            service.checkPendingApproval();
            rebuildRightPanel();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        Intent intent = new Intent(this, MirrorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MirrorService.class), connection, Context.BIND_AUTO_CREATE);
        settingsButton.requestFocus(); // so OK on the remote opens Settings right away
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (service != null) {
            service.unregisterUi(this);
        }
        unbindService(connection);
        // The service itself (and the AirPlay receiver in it) keeps running in the background.
    }

    private void refreshStatus() {
        if (service == null) {
            return;
        }
        List<String> addresses = service.addresses();
        if (addresses.isEmpty()) {
            setStatus(getString(R.string.status_no_network), RED);
            nameView.setText("");
            addressView.setText("");
            return;
        }
        nameView.setText(service.name());
        addressView.setText(getString(R.string.status_address, TextUtils.join("  ·  ", addresses)));
        setStatus(getString(service.isReady() ? R.string.status_ready : R.string.status_starting),
                service.isReady() ? GREEN : AMBER);
    }

    // --- MirrorService.UiCallback (always delivered on the main thread) ---

    @Override
    public void onStatusChanged() {
        refreshStatus();
    }

    @Override
    public void onMirroringStarted() {
        showOverlay(false);
    }

    @Override
    public void onMirroringStopped() {
        showOverlay(true);
    }

    @Override
    public void onVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        layoutVideo();
        showOverlay(false);
    }

    @Override
    public void onApprovalNeeded(String deviceName) {
        if (approvalDialog != null) {
            approvalDialog.dismiss();
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(GlassTheme.panel(this, 32));
        box.setPadding(dp(40), dp(36), dp(40), dp(32));

        TextView title = text(24, GlassTheme.TEXT_PRIMARY, true);
        title.setGravity(Gravity.START);
        title.setText(R.string.approval_title);
        box.addView(title);
        box.addView(space(12));

        TextView message = text(17, GlassTheme.TEXT_SECONDARY, false);
        message.setGravity(Gravity.START);
        message.setText(getString(R.string.approval_message, deviceName));
        box.addView(message);
        box.addView(space(28));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        TextView deny = text(17, Color.WHITE, true);
        deny.setText(R.string.approval_deny);
        deny.setFocusable(true);
        deny.setClickable(true);
        deny.setPadding(dp(28), dp(12), dp(28), dp(12));
        deny.setBackground(GlassTheme.pillButton(this, 24));
        buttons.addView(deny);

        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(16), 1));

        TextView allow = text(17, Color.WHITE, true);
        allow.setText(R.string.approval_allow);
        allow.setFocusable(true);
        allow.setClickable(true);
        allow.setPadding(dp(28), dp(12), dp(28), dp(12));
        allow.setBackground(GlassTheme.panelAccent(this, 24, ACCENT));
        buttons.addView(allow);

        box.addView(buttons);

        approvalDialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .create();
        approvalDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        deny.setOnClickListener(v -> {
            approvalDialog.dismiss();
            if (service != null) {
                service.resolveApproval(false);
            }
        });
        allow.setOnClickListener(v -> {
            approvalDialog.dismiss();
            if (service != null) {
                service.resolveApproval(true);
            }
        });
        approvalDialog.show();
        allow.requestFocus();
    }

    // --- Surface ---

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        Surface surface = new Surface(texture);
        if (service != null) {
            service.playback().setSurface(surface);
        } else {
            pendingSurface = surface;
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        pendingSurface = null;
        if (service != null) {
            service.playback().setSurface(null);
        }
        return true; // we're done with it; let the system release it
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    // --- Rotate (remote d-pad left/right while mirroring) ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (overlay.getVisibility() != View.VISIBLE) { // only while a picture is actually showing
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                rotate(90);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                rotate(-90);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void rotate(int deltaDegrees) {
        rotationDegrees = ((rotationDegrees + deltaDegrees) % 360 + 360) % 360;
        layoutVideo();
        rotationHint.setText(getString(R.string.rotated, rotationDegrees));
        rotationHint.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> rotationHint.setVisibility(View.GONE), 1500);
    }

    // --- UI ---

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new TextureView(this);
        surfaceView.setSurfaceTextureListener(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutVideo());

        rotationHint = text(18, Color.WHITE, true);
        rotationHint.setBackground(GlassTheme.panel(this, 24));
        rotationHint.setPadding(dp(24), dp(12), dp(24), dp(12));
        rotationHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = dp(48);
        root.addView(rotationHint, hintLp);

        // Idle screen: a colourful glass backdrop with brand + receiver name on the left and
        // how-to steps on the right, each floating as its own translucent glass panel.
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.addView(GlassTheme.buildBackground(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(72), dp(40), dp(72), dp(40));

        // Left column
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setBackground(GlassTheme.panel(this, 32));
        left.setPadding(dp(40), dp(36), dp(40), dp(36));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setClipToOutline(true);
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        brand.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(16), 0, 0, 0);
        TextView title = text(34, GlassTheme.TEXT_PRIMARY, true);
        title.setGravity(Gravity.START);
        title.setText(R.string.app_name);
        TextView tagline = text(16, GlassTheme.TEXT_TERTIARY, false);
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
        chip.setBackground(GlassTheme.panelDim(this, 24));
        chip.setPadding(dp(16), dp(8), dp(20), dp(8));
        statusDot = new View(this);
        statusDot.setBackground(oval(AMBER));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotLp.rightMargin = dp(10);
        chip.addView(statusDot, dotLp);
        statusView = text(18, GlassTheme.TEXT_SECONDARY, false);
        statusView.setGravity(Gravity.START);
        chip.addView(statusView);
        left.addView(chip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        left.addView(space(28));

        // Receiver name card
        TextView nameLabel = text(14, GlassTheme.TEXT_TERTIARY, false);
        nameLabel.setGravity(Gravity.START);
        nameLabel.setLetterSpacing(0.15f);
        nameLabel.setText(R.string.name_label);
        left.addView(nameLabel);
        left.addView(space(8));
        nameView = text(48, GlassTheme.TEXT_PRIMARY, true);
        nameView.setGravity(Gravity.CENTER_VERTICAL);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setBackground(GlassTheme.panelAccent(this, 22, ACCENT));
        nameView.setPadding(dp(28), dp(16), dp(28), dp(16));
        left.addView(nameView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        left.addView(space(16));

        TextView settings = pillButton(R.string.settings);
        settings.setOnClickListener(v -> {
            showingSettings = !showingSettings;
            rebuildRightPanel();
        });
        left.addView(settings, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        settingsButton = settings; // first thing OK on the remote should reach
        left.addView(space(16));

        addressView = text(14, GlassTheme.TEXT_TERTIARY, false);
        addressView.setGravity(Gravity.START);
        left.addView(addressView);

        panel.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(new View(this), new LinearLayout.LayoutParams(dp(64), 1));

        // Right column: either "how to connect" or Settings, toggled by the button above -
        // one screen instead of a separate Settings Activity to navigate to and back from.
        rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        rightPanel.setBackground(GlassTheme.panel(this, 32));
        rightPanel.setPadding(dp(36), dp(32), dp(36), dp(32));
        panel.addView(rightPanel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rebuildRightPanel();

        backdrop.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = backdrop;
        root.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /** Swaps the right-hand panel between "how to connect" and Settings, in place. */
    private void rebuildRightPanel() {
        if (rightPanel == null) {
            return;
        }
        rightPanel.removeAllViews();
        if (showingSettings) {
            buildSettingsPanel(rightPanel);
        } else {
            buildHowToConnectPanel(rightPanel);
        }
    }

    private void buildHowToConnectPanel(LinearLayout into) {
        TextView howTo = text(22, GlassTheme.TEXT_PRIMARY, true);
        howTo.setGravity(Gravity.START);
        howTo.setText(R.string.how_to);
        into.addView(howTo);
        into.addView(space(20));
        into.addView(step(1, getString(R.string.step_1)));
        into.addView(step(2, getString(R.string.step_2)));
        into.addView(step(3, getString(R.string.step_3)));
    }

    private void buildSettingsPanel(LinearLayout into) {
        TextView title = text(22, GlassTheme.TEXT_PRIMARY, true);
        title.setGravity(Gravity.START);
        title.setText(R.string.settings_title);
        into.addView(title);
        into.addView(space(20));

        if (service == null) {
            return;
        }
        String current = service.connectMode();

        into.addView(settingsRow(R.string.rename_title,
                getString(R.string.settings_rename_subtitle, service.name()), v -> showRenameDialog()));
        into.addView(space(24));

        TextView sectionLabel = text(13, GlassTheme.TEXT_TERTIARY, false);
        sectionLabel.setGravity(Gravity.START);
        sectionLabel.setLetterSpacing(0.1f);
        sectionLabel.setText(R.string.settings_connect_section);
        into.addView(sectionLabel);
        into.addView(space(10));

        into.addView(settingsOption(MirrorService.MODE_APPROVAL,
                R.string.settings_approval_title, R.string.settings_approval_subtitle, current));
        into.addView(space(12));
        into.addView(settingsOption(MirrorService.MODE_PIN,
                R.string.settings_pin_title, R.string.settings_pin_subtitle, current));
        into.addView(space(12));
        into.addView(settingsOption(MirrorService.MODE_NONE,
                R.string.settings_none_title, R.string.settings_none_subtitle, current));
    }

    private View settingsRow(int titleRes, String subtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setBackground(GlassTheme.focusableRow(this, 18));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(18, GlassTheme.TEXT_PRIMARY, true);
        title.setGravity(Gravity.START);
        title.setText(titleRes);
        TextView subtitleView = text(13, GlassTheme.TEXT_SECONDARY, false);
        subtitleView.setGravity(Gravity.START);
        subtitleView.setText(subtitle);
        texts.addView(title);
        texts.addView(subtitleView);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = text(20, GlassTheme.TEXT_TERTIARY, false);
        chevron.setText("›");
        row.addView(chevron, new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(onClick);
        return row;
    }

    /** One selectable row for a connect mode; the PIN row is shown but not yet selectable. */
    private View settingsOption(String mode, int titleRes, int subtitleRes, String current) {
        boolean selected = mode.equals(current);
        boolean comingSoon = MirrorService.MODE_PIN.equals(mode);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setBackground(selected
                ? GlassTheme.panelAccent(this, 18, ACCENT)
                : GlassTheme.panelDim(this, 18));
        row.setAlpha(comingSoon ? 0.6f : 1f);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(18, GlassTheme.TEXT_PRIMARY, true);
        title.setGravity(Gravity.START);
        title.setText(titleRes);
        TextView subtitle = text(13, GlassTheme.TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.START);
        subtitle.setText(comingSoon
                ? getString(subtitleRes) + getString(R.string.settings_coming_soon_suffix)
                : getString(subtitleRes));
        texts.addView(title);
        texts.addView(subtitle);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) {
            TextView mark = text(20, Color.WHITE, true);
            mark.setText("✓");
            row.addView(mark, new LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        row.setOnClickListener(v -> {
            if (comingSoon) {
                Toast.makeText(this, R.string.settings_pin_toast, Toast.LENGTH_SHORT).show();
                return;
            }
            if (service != null) {
                service.setConnectMode(mode);
                rebuildRightPanel();
            }
        });
        return row;
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(service != null ? service.name() : "");
        input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setTextColor(GlassTheme.TEXT_PRIMARY);
        input.setHintTextColor(GlassTheme.TEXT_TERTIARY);
        input.setBackground(GlassTheme.panelDim(this, 16));
        input.setPadding(dp(18), dp(12), dp(18), dp(12));
        int pad = dp(24);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setMessage(R.string.rename_hint)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> applyRename(input.getText().toString()))
                .setNeutralButton(R.string.reset, (d, w) -> applyRename(null))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            dialog.dismiss();
            return true;
        });
        dialog.show();
    }

    private void applyRename(String rawName) {
        SharedPreferences.Editor editor = getSharedPreferences("receiver", MODE_PRIVATE).edit();
        String name = rawName == null ? "" : rawName.replace('.', ' ').trim();
        if (name.isEmpty()) {
            editor.remove("name");
        } else {
            editor.putString("name", name);
        }
        editor.apply();
        if (service != null) {
            service.restart();
        }
        rebuildRightPanel();
    }

    /**
     * Letterbox the picture to fit the TV, then apply the manual rotation on top. A 90°/270°
     * rotation swaps which dimension is "wide", so we fit using the swapped video aspect and
     * give the (unrotated) view the swapped size back, so that after rotating it lands at the
     * fitted size.
     */
    private void layoutVideo() {
        int pw = root.getWidth();
        int ph = root.getHeight();
        if (pw == 0 || ph == 0) {
            return;
        }
        boolean swapped = rotationDegrees == 90 || rotationDegrees == 270;
        int effVideoW = swapped ? videoHeight : videoWidth;
        int effVideoH = swapped ? videoWidth : videoHeight;

        int fitW = pw;
        int fitH = ph;
        if (effVideoW > 0 && effVideoH > 0) {
            if ((long) effVideoW * ph > (long) effVideoH * pw) {
                fitH = (int) ((long) pw * effVideoH / effVideoW);
            } else {
                fitW = (int) ((long) ph * effVideoW / effVideoH);
            }
        }

        int w = swapped ? fitH : fitW;
        int h = swapped ? fitW : fitH;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            surfaceView.setLayoutParams(lp);
        }
        surfaceView.setRotation(rotationDegrees);
    }

    private void showOverlay(boolean show) {
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            videoWidth = 0;
            videoHeight = 0;
            rotationDegrees = 0; // start each mirroring session unrotated
            layoutVideo();
        }
    }

    /** A small remote-focusable glass pill button, styled like Rename/Settings. */
    private TextView pillButton(int textRes) {
        TextView button = text(18, Color.WHITE, true);
        button.setText(textRes);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(28), dp(12), dp(28), dp(12));
        button.setBackground(GlassTheme.pillButton(this, 28));
        return button;
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
        TextView label = text(19, GlassTheme.TEXT_SECONDARY, false);
        label.setGravity(Gravity.START);
        label.setText(message);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
