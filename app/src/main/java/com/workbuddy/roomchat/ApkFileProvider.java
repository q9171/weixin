package com.workbuddy.roomchat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

// 最小实现：仅用于把下载好的 APK 通过 content:// 暴露给系统安装器，
// 避免引入 support/AndroidX 库的 FileProvider 依赖。
public class ApkFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.workbuddy.roomchat.apkprovider";
    private File baseDir;

    @Override
    public boolean onCreate() {
        baseDir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (baseDir == null) baseDir = getContext().getFilesDir();
        if (!baseDir.exists()) baseDir.mkdirs();
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(baseDir, "update.apk");
        if (!f.exists()) throw new FileNotFoundException("update.apk not found");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
}
