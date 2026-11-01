package com.airmirror.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A full settings screen of its own (not a dialog over the home screen): renaming the receiver,
 * and who can connect to it.
 */
public class SettingsActivity extends Activity {

    private static final int ACCENT = Color.rgb(76, 141, 255);

    private MirrorService service;
    private LinearLayout optionsBox;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MirrorService.LocalBinder) binder).service();
            rebuildOptions();
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
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MirrorService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService(connection);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(8, 12, 28), Color.rgb(24, 40, 92)});
        root.setBackground(bg);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(72), dp(48), dp(72), dp(48));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(22, Color.rgb(170, 185, 220), true);
        back.setText(R.string.settings_back);
        back.setFocusable(true);
        back.setClickable(true);
        back.setPadding(0, dp(8), dp(24), dp(8));
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = text(32, Color.WHITE, true);
        title.setGravity(Gravity.START);
        title.setText(R.string.settings_title);
        header.addView(title);
        page.addView(header);
        page.addView(space(36));

        optionsBox = new LinearLayout(this);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(dp(800), FrameLayout.LayoutParams.WRAP_CONTENT);
        page.addView(optionsBox, new LinearLayout.LayoutParams(dp(800), LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(page);
        setContentView(root);
        back.requestFocus();
    }

    private void rebuildOptions() {
        if (service == null) {
            return;
        }
        optionsBox.removeAllViews();
        String current = service.connectMode();

        optionsBox.addView(settingsRow(R.string.rename_title,
                getString(R.string.settings_rename_subtitle, service.name()), v -> showRenameDialog()));
        optionsBox.addView(space(28));

        TextView sectionLabel = text(13, Color.rgb(150, 165, 200), false);
        sectionLabel.setGravity(Gravity.START);
        sectionLabel.setLetterSpacing(0.1f);
        sectionLabel.setText(R.string.settings_connect_section);
        optionsBox.addView(sectionLabel);
        optionsBox.addView(space(10));

        optionsBox.addView(settingsOption(MirrorService.MODE_APPROVAL,
                R.string.settings_approval_title, R.string.settings_approval_subtitle, current));
        optionsBox.addView(space(14));
        optionsBox.addView(settingsOption(MirrorService.MODE_PIN,
                R.string.settings_pin_title, R.string.settings_pin_subtitle, current));
        optionsBox.addView(space(14));
        optionsBox.addView(settingsOption(MirrorService.MODE_NONE,
                R.string.settings_none_title, R.string.settings_none_subtitle, current));
    }

    private View settingsRow(int titleRes, String subtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(22), dp(18), dp(22), dp(18));
        row.setBackground(rounded(Color.argb(22, 255, 255, 255), dp(18), 0, 0));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(19, Color.WHITE, true);
        title.setGravity(Gravity.START);
        title.setText(titleRes);
        TextView subtitleView = text(14, Color.rgb(170, 180, 205), false);
        subtitleView.setGravity(Gravity.START);
        subtitleView.setText(subtitle);
        texts.addView(title);
        texts.addView(subtitleView);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = text(22, Color.rgb(150, 165, 200), false);
        chevron.setText("›");
        row.addView(chevron, new LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT));

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
        row.setPadding(dp(22), dp(18), dp(22), dp(18));
        row.setBackground(selected
                ? rounded(Color.argb(45, 76, 141, 255), dp(18), dp(2), ACCENT)
                : rounded(Color.argb(18, 255, 255, 255), dp(18), 0, 0));
        row.setAlpha(comingSoon ? 0.6f : 1f);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(19, Color.WHITE, true);
        title.setGravity(Gravity.START);
        title.setText(titleRes);
        TextView subtitle = text(14, Color.rgb(170, 180, 205), false);
        subtitle.setGravity(Gravity.START);
        subtitle.setText(comingSoon
                ? getString(subtitleRes) + getString(R.string.settings_coming_soon_suffix)
                : getString(subtitleRes));
        texts.addView(title);
        texts.addView(subtitle);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) {
            TextView mark = text(22, ACCENT, true);
            mark.setText("✓");
            row.addView(mark, new LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        row.setOnClickListener(v -> {
            if (comingSoon) {
                Toast.makeText(this, R.string.settings_pin_toast, Toast.LENGTH_SHORT).show();
                return;
            }
            if (service != null) {
                service.setConnectMode(mode);
                rebuildOptions();
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
        rebuildOptions();
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        if (bold) {
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private View space(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
