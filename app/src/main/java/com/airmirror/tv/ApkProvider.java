package com.airmirror.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Shares exactly one file, the downloaded update APK, with the system installer on Android 7.0+
 * (which no longer accepts file:// links). A tiny stand-in for AndroidX's FileProvider so the
 * app keeps no extra dependencies. Not exported; the installer only gets temporary read access
 * through the intent's grant flag.
 */
public class ApkProvider extends ContentProvider {

    private static final String AUTHORITY_SUFFIX = ".apk";
    private static final String FILE_NAME = "AirMirrorTV.apk";

    static Uri uriFor(Context ctx) {
        return Uri.parse("content://" + ctx.getPackageName() + AUTHORITY_SUFFIX + "/" + FILE_NAME);
    }

    private File apkFile() {
        return new File(new File(getContext().getCacheDir(), "update"), FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment()) || !"r".equals(mode)) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(apkFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{FILE_NAME, apkFile().length()});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
