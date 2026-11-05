package com.airmirror.tv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app updates from this project's GitHub Releases. No Google Play needed.
 * <p>
 * Checks {@code releases/latest} only when the app's screen opens (at most every few hours,
 * or on demand from Settings), so it costs nothing in the background. The APK is downloaded
 * into the app's own cache and handed to the system installer, which asks the user to confirm.
 * Everything here works from Android 5.0: on 7.0+ the file is shared through {@link ApkProvider},
 * on 8.0+ the user may first have to allow this app to install apps.
 */
final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    static final String REPO = "mbnetworku/AirPlay-Android-TV-";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000; // 6 hours
    private static final String PREFS = "updates";

    /** A newer release than the installed app. */
    static final class Release {
        final String version;   // e.g. "1.18"
        final String apkUrl;
        final String notes;

        Release(String version, String apkUrl, String notes) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    interface CheckCallback {
        /** {@code release} is null when the app is up to date; {@code error} is set when the check failed. */
        void onResult(Release release, String error);
    }

    interface DownloadCallback {
        void onProgress(int percent);

        void onDone(File apk, String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    static String installedVersion(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** The newer release found by the last check (by the app or in the background), or null. */
    static Release knownRelease(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String version = prefs.getString("latest_version", null);
        String url = prefs.getString("latest_url", null);
        if (version == null || url == null || compareVersions(version, installedVersion(ctx)) <= 0) {
            return null;
        }
        return new Release(version, url, prefs.getString("latest_notes", ""));
    }

    /** True when an automatic check is due (the screen opened and the last check is old enough). */
    static boolean autoCheckDue(Context ctx) {
        long last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_check", 0);
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS;
    }

    static void check(Context ctx, CheckCallback callback) {
        Context app = ctx.getApplicationContext();
        String installed = installedVersion(app);
        EXECUTOR.execute(() -> {
            Release result = null;
            String error = null;
            try {
                JSONObject json = new JSONObject(httpGetString(LATEST_URL));
                String tag = json.optString("tag_name", "");
                String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                String apkUrl = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.optString("name", "").toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                SharedPreferences.Editor prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                prefs.putLong("last_check", System.currentTimeMillis());
                if (apkUrl != null && compareVersions(version, installed) > 0) {
                    result = new Release(version, apkUrl, json.optString("body", ""));
                    // Remembered so the screen shows the Update button at once, even when the
                    // background service was the one that found it.
                    prefs.putString("latest_version", version)
                            .putString("latest_url", apkUrl)
                            .putString("latest_notes", result.notes);
                } else {
                    prefs.remove("latest_version").remove("latest_url").remove("latest_notes");
                }
                prefs.apply();
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            Release r = result;
            String err = error;
            MAIN.post(() -> callback.onResult(r, err));
        });
    }

    private static File apkFile(Context app) {
        return new File(new File(app.getCacheDir(), "update"), "AirMirrorTV.apk");
    }

    /**
     * The APK for {@code release} if it was already downloaded (e.g. in the background by
     * {@link MirrorService}), so pressing Update installs it at once. Null otherwise.
     */
    static File readyApk(Context ctx, Release release) {
        Context app = ctx.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File apk = apkFile(app);
        if (release != null && release.version.equals(prefs.getString("ready_version", null))
                && apk.length() > 0 && apk.length() == prefs.getLong("ready_size", -1)) {
            return apk;
        }
        return null;
    }

    /** Deletes a downloaded APK once that version (or newer) is installed. */
    static void cleanUp(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ready = prefs.getString("ready_version", null);
        if (ready != null && compareVersions(ready, installedVersion(app)) <= 0) {
            //noinspection ResultOfMethodCallIgnored
            apkFile(app).delete();
            prefs.edit().remove("ready_version").remove("ready_size").apply();
        }
    }

    static void download(Context ctx, Release release, DownloadCallback callback) {
        Context app = ctx.getApplicationContext();
        File ready = readyApk(app, release);
        if (ready != null) {
            if (callback != null) {
                MAIN.post(() -> callback.onDone(ready, null));
            }
            return;
        }
        EXECUTOR.execute(() -> {
            File apk = apkFile(app);
            File dir = apk.getParentFile();
            String error = null;
            // Not "ready" until this download completes.
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove("ready_version").remove("ready_size").apply();
            HttpURLConnection conn = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                conn = open(release.apkUrl);
                long total = conn.getContentLength();
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[32 * 1024];
                    long done = 0;
                    int lastPercent = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (callback == null) {
                            continue; // background download: no progress to show
                        }
                        if (total > 0) {
                            int percent = (int) (done * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                MAIN.post(() -> callback.onProgress(percent));
                            }
                        }
                    }
                }
                if (total > 0 && apk.length() != total) {
                    throw new java.io.IOException("Download incomplete");
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("ready_version", release.version)
                        .putLong("ready_size", apk.length())
                        .apply();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    // Before 7.0 the installer reads the file directly, so it must be readable
                    // by other apps (there is no content:// sharing for installs yet).
                    //noinspection ResultOfMethodCallIgnored
                    dir.setExecutable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    app.getCacheDir().setExecutable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    apk.setReadable(true, false);
                }
            } catch (Exception e) {
                Log.w(TAG, "Update download failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            String err = error;
            if (callback != null) {
                MAIN.post(() -> callback.onDone(err == null ? apk : null, err));
            }
        });
    }

    /**
     * On 8.0+ the user has to allow this app to install apps once. Returns false and opens that
     * settings screen when it is still needed.
     */
    static boolean ensureInstallAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ctx.getPackageManager().canRequestPackageInstalls()) {
            return true;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No 'install unknown apps' screen on this device", e);
            return true; // let the installer itself ask, as older TV builds do
        }
        return false;
    }

    /** Hands the downloaded APK to the system installer, which asks the user to confirm. */
    static void install(Context ctx, File apk) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(ApkProvider.uriFor(ctx));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    /** Compares dotted versions numerically: "1.18" > "1.9". */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) {
                break;
            }
            digits.append(c);
        }
        try {
            return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String httpGetString(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /** GET with redirects followed by hand (GitHub sends release downloads to another host). */
    private static HttpURLConnection open(String url) throws Exception {
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "AirMirrorTV-updater");
            conn.setRequestProperty("Accept", url.contains("api.github.com")
                    ? "application/vnd.github+json" : "application/octet-stream");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = conn.getHeaderField("Location");
                conn.disconnect();
                if (next == null) {
                    throw new java.io.IOException("Redirect without a location");
                }
                url = new URL(new URL(url), next).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new java.io.IOException(code == 404 ? "No release published yet" : "Server replied " + code);
            }
            return conn;
        }
        throw new java.io.IOException("Too many redirects");
    }
}
