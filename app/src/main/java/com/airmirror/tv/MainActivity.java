package com.airmirror.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
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
import android.widget.ScrollView;
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
    private String focusKey; // which Settings row to re-focus after the panel is rebuilt
    private TextView updateButton;
    private UpdateChecker.Release availableUpdate;
    private boolean updateBusy;

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
                service.setSurface(pendingSurface);
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
        showUpdateAvailable(UpdateChecker.knownRelease(this)); // instant, from the last check
        if (UpdateChecker.autoCheckDue(this)) {
            UpdateChecker.check(this, (release, error) -> showUpdateAvailable(release));
        }
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

        LinearLayout box = glassDialogBox(R.string.approval_title,
                getString(R.string.approval_message, deviceName));
        box.addView(space(4));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView deny = dialogButton(R.string.approval_deny, false);
        TextView allow = dialogButton(R.string.approval_allow, true);
        buttons.addView(deny);
        buttons.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1));
        buttons.addView(allow);
        box.addView(buttons);

        approvalDialog = glassDialog(box, false);
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
            service.setSurface(surface);
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
            service.setSurface(null);
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

    @Override
    public void onBackPressed() {
        // With Settings open, Back returns to the home panel instead of leaving the app.
        if (showingSettings && overlay.getVisibility() == View.VISIBLE) {
            showingSettings = false;
            rebuildRightPanel();
            settingsButton.requestFocus();
            return;
        }
        super.onBackPressed();
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

        // Shown only when GitHub has a newer release than the installed app.
        updateButton = text(16, Color.WHITE, true);
        updateButton.setFocusable(true);
        updateButton.setClickable(true);
        updateButton.setPadding(dp(22), dp(10), dp(22), dp(10));
        updateButton.setBackground(GlassTheme.pillButton(this, 24));
        updateButton.setVisibility(View.GONE);
        updateButton.setOnClickListener(v -> {
            if (availableUpdate != null) {
                showUpdateDialog(availableUpdate);
            }
        });
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        updateLp.topMargin = dp(14);
        left.addView(updateButton, updateLp);

        panel.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(new View(this), new LinearLayout.LayoutParams(dp(64), 1));

        // Right column: either "how to connect" or Settings, toggled by the button above -
        // one screen instead of a separate Settings Activity to navigate to and back from.
        rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        rightPanel.setBackground(GlassTheme.panel(this, 32));
        rightPanel.setPadding(dp(36), dp(32), dp(36), dp(32));
        // Settings is taller than the screen on 720p boxes; the remote scrolls it with focus.
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setVerticalScrollBarEnabled(false);
        rightScroll.setClipToPadding(false);
        rightScroll.addView(rightPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(rightScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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
        if (settingsButton instanceof TextView) {
            ((TextView) settingsButton).setText(showingSettings ? R.string.settings_done : R.string.settings);
        }
        if (showingSettings) {
            buildSettingsPanel(rightPanel);
        } else {
            buildHowToConnectPanel(rightPanel);
        }
        if (focusKey != null) {
            View again = rightPanel.findViewWithTag(focusKey);
            if (again != null) {
                again.requestFocus();
            }
            focusKey = null;
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
        if (MirrorService.androidShareEnabled(this)) {
            into.addView(space(16));
            TextView android = text(16, GlassTheme.TEXT_TERTIARY, false);
            android.setGravity(Gravity.START);
            android.setText(R.string.how_to_android);
            into.addView(android);
        }
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
        into.addView(space(24));

        // --- Background ---
        into.addView(sectionLabel(R.string.settings_background_section));
        into.addView(space(10));
        boolean bootOn = MirrorService.startOnBoot(this);
        into.addView(settingsToggle("boot", R.string.settings_boot_title,
                getString(R.string.settings_boot_subtitle), bootOn, v -> {
                    MirrorService.setStartOnBoot(this, !bootOn);
                    focusKey = "boot";
                    rebuildRightPanel();
                }));
        into.addView(space(12));
        boolean autoOn = MirrorService.openAutomatically(this);
        boolean allowed = MirrorService.canOpenFromBackground(this);
        String autoSubtitle = !autoOn
                ? getString(R.string.settings_auto_open_off)
                : allowed ? getString(R.string.settings_auto_open_subtitle)
                : getString(R.string.settings_auto_open_needs_permission);
        into.addView(settingsToggle("auto_open", R.string.settings_auto_open_title,
                autoSubtitle, autoOn && allowed, v -> {
                    focusKey = "auto_open";
                    if (autoOn && !allowed) {
                        requestOverlayPermission();
                        return;
                    }
                    MirrorService.setOpenAutomatically(this, !autoOn);
                    if (!autoOn && !MirrorService.canOpenFromBackground(this)) {
                        requestOverlayPermission();
                    }
                    rebuildRightPanel();
                }));
        into.addView(space(24));

        // --- Android phones ---
        into.addView(sectionLabel(R.string.settings_android_section));
        into.addView(space(10));
        boolean androidOn = MirrorService.androidShareEnabled(this);
        into.addView(settingsToggle("android_share", R.string.settings_android_title,
                getString(R.string.settings_android_subtitle), androidOn, v -> {
                    if (service != null) {
                        service.setAndroidShareEnabled(!androidOn);
                    }
                    focusKey = "android_share";
                    rebuildRightPanel();
                }));
        into.addView(space(24));

        // --- About & updates ---
        into.addView(sectionLabel(R.string.settings_about_section));
        into.addView(space(10));
        String version = UpdateChecker.installedVersion(this);
        String updateSubtitle = availableUpdate != null
                ? getString(R.string.update_available_subtitle, availableUpdate.version)
                : getString(R.string.settings_update_subtitle);
        View updateRow = settingsRow(R.string.settings_update_title, updateSubtitle, v -> {
            if (availableUpdate != null) {
                showUpdateDialog(availableUpdate);
            } else {
                checkForUpdatesNow();
            }
        });
        updateRow.setTag("update");
        into.addView(updateRow);
        into.addView(space(10));
        TextView versionLabel = text(13, GlassTheme.TEXT_TERTIARY, false);
        versionLabel.setGravity(Gravity.START);
        versionLabel.setText(getString(R.string.settings_version, version));
        into.addView(versionLabel);
    }

    private TextView sectionLabel(int textRes) {
        TextView label = text(13, GlassTheme.TEXT_TERTIARY, false);
        label.setGravity(Gravity.START);
        label.setLetterSpacing(0.1f);
        label.setText(textRes);
        return label;
    }

    /** A settings row with an On/Off pill on the right. */
    private View settingsToggle(String key, int titleRes, String subtitle, boolean on, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(key);
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

        TextView pill = text(14, Color.WHITE, true);
        pill.setText(on ? R.string.on : R.string.off);
        pill.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(14));
        pillBg.setColor(on ? GREEN : Color.argb(90, 255, 255, 255));
        pill.setBackground(pillBg);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.leftMargin = dp(16);
        row.addView(pill, pillLp);

        row.setOnClickListener(onClick);
        return row;
    }

    // --- Auto-open permission (Android 10+) ---

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, R.string.overlay_toast, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            // Many Android TV builds hide this screen; it can still be granted over ADB.
            LinearLayout box = glassDialogBox(R.string.overlay_missing_title,
                    getString(R.string.overlay_missing_message, getPackageName()));
            TextView ok = dialogButton(android.R.string.ok, true);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setGravity(Gravity.END);
            buttons.addView(ok);
            box.addView(buttons, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            AlertDialog dialog = glassDialog(box, true);
            ok.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
            ok.requestFocus();
        }
    }

    // --- Updates ---

    private void showUpdateAvailable(UpdateChecker.Release release) {
        if (isFinishing()) {
            return;
        }
        availableUpdate = release;
        if (release != null) {
            boolean ready = UpdateChecker.readyApk(this, release) != null;
            updateButton.setText(getString(ready ? R.string.update_button_ready : R.string.update_button,
                    release.version));
            updateButton.setVisibility(View.VISIBLE);
        } else {
            updateButton.setVisibility(View.GONE);
        }
        if (showingSettings) {
            rebuildRightPanel();
        }
    }

    private void checkForUpdatesNow() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        UpdateChecker.check(this, (release, error) -> {
            if (error != null) {
                Toast.makeText(this, getString(R.string.update_failed, error), Toast.LENGTH_LONG).show();
                return;
            }
            showUpdateAvailable(release);
            if (release == null) {
                Toast.makeText(this, R.string.update_none, Toast.LENGTH_SHORT).show();
            } else {
                showUpdateDialog(release);
            }
        });
    }

    private void showUpdateDialog(UpdateChecker.Release release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > 600) {
            notes = notes.substring(0, 600) + "…";
        }
        boolean ready = UpdateChecker.readyApk(this, release) != null;
        String message = getString(ready ? R.string.update_ready_message : R.string.update_message,
                release.version, UpdateChecker.installedVersion(this))
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        LinearLayout box = glassDialogBox(R.string.update_title, message);

        TextView progress = text(16, GlassTheme.TEXT_SECONDARY, false);
        progress.setGravity(Gravity.START);
        progress.setVisibility(View.GONE);
        box.addView(progress);
        box.addView(space(8));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView later = dialogButton(R.string.update_later, false);
        TextView now = dialogButton(R.string.update_now, true);
        buttons.addView(later);
        buttons.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1));
        buttons.addView(now);
        box.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = glassDialog(box, true);
        later.setOnClickListener(v -> dialog.dismiss());
        now.setOnClickListener(v -> {
            if (updateBusy) {
                return;
            }
            if (!UpdateChecker.ensureInstallAllowed(this)) {
                Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show();
                return;
            }
            updateBusy = true;
            now.setEnabled(false);
            now.setAlpha(0.5f);
            progress.setVisibility(View.VISIBLE);
            progress.setText(getString(R.string.update_downloading, 0));
            UpdateChecker.download(this, release, new UpdateChecker.DownloadCallback() {
                @Override
                public void onProgress(int percent) {
                    progress.setText(getString(R.string.update_downloading, percent));
                }

                @Override
                public void onDone(java.io.File apk, String error) {
                    updateBusy = false;
                    now.setEnabled(true);
                    now.setAlpha(1f);
                    if (apk == null) {
                        progress.setText(getString(R.string.update_failed, error));
                        return;
                    }
                    dialog.dismiss();
                    try {
                        UpdateChecker.install(MainActivity.this, apk);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(MainActivity.this, R.string.update_no_installer, Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
        dialog.show();
        now.requestFocus();
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

        row.setTag("mode_" + mode);
        row.setOnClickListener(v -> {
            focusKey = "mode_" + mode;
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
        LinearLayout box = glassDialogBox(R.string.rename_title, getString(R.string.rename_hint));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(service != null ? service.name() : "");
        input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setTextSize(22);
        input.setTextColor(GlassTheme.TEXT_PRIMARY);
        input.setHintTextColor(GlassTheme.TEXT_TERTIARY);
        input.setHint(R.string.rename_input_hint);
        input.setBackground(GlassTheme.focusableRow(this, 16));
        input.setPadding(dp(20), dp(14), dp(20), dp(14));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(space(28));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView reset = dialogButton(R.string.reset, false);
        TextView cancel = dialogButton(android.R.string.cancel, false);
        TextView save = dialogButton(R.string.save, true);
        buttons.addView(reset);
        buttons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        buttons.addView(cancel);
        buttons.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1));
        buttons.addView(save);
        box.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = glassDialog(box, true);
        reset.setOnClickListener(v -> {
            dialog.dismiss();
            applyRename(null);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            dialog.dismiss();
            applyRename(input.getText().toString());
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            save.performClick();
            return true;
        });
        dialog.show();
        input.requestFocus();
    }

    /** The shared glass card used by every popup: a title and a short message. */
    private LinearLayout glassDialogBox(int titleRes, String messageText) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(GlassTheme.panel(this, 32));
        box.setPadding(dp(40), dp(36), dp(40), dp(32));
        box.setMinimumWidth(dp(520));

        TextView title = text(24, GlassTheme.TEXT_PRIMARY, true);
        title.setGravity(Gravity.START);
        title.setText(titleRes);
        box.addView(title);
        box.addView(space(12));

        TextView message = text(17, GlassTheme.TEXT_SECONDARY, false);
        message.setGravity(Gravity.START);
        message.setText(messageText);
        box.addView(message);
        box.addView(space(24));
        return box;
    }

    /** Wraps a glass card in a dialog window with no stock grey frame behind it. */
    private AlertDialog glassDialog(View content, boolean cancelable) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setCancelable(cancelable)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        return dialog;
    }

    private TextView dialogButton(int textRes, boolean primary) {
        TextView button = text(17, Color.WHITE, true);
        button.setText(textRes);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(28), dp(12), dp(28), dp(12));
        button.setBackground(primary
                ? GlassTheme.panelAccent(this, 24, ACCENT)
                : GlassTheme.pillButton(this, 24));
        return button;
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
