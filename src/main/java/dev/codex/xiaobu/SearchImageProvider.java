package dev.codex.xiaobu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Accepts screenshots only from Xiaobu; engines read only individually granted URIs. */
public final class SearchImageProvider extends ContentProvider {
    static final Uri CONTENT = Uri.parse("content://dev.codex.xiaobu.launcherhook.search/images");
    private SearchImageCache cache;

    @Override public boolean onCreate() {
        cache = new SearchImageCache(new File(getContext().getCacheDir(), "image-search"));
        return true;
    }
    private boolean writer() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid() || uid == 0) return true;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String name : packages)
            if ("com.coloros.colordirectservice".equals(name)) return true;
        return false;
    }
    private void requireWriter() { if (!writer()) throw new SecurityException("Only Xiaobu may write search images"); }
    private void requireReader(Uri uri) {
        if (!writer() && getContext().checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Search image requires a URI grant");
    }
    private File file(Uri uri) throws FileNotFoundException {
        if (!CONTENT.getAuthority().equals(uri.getAuthority()) || uri.getPathSegments().size() != 2
                || !"images".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("Invalid search URI");
        try { return cache.resolve(uri.getLastPathSegment()); }
        catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) {
        requireWriter();
        if (!CONTENT.equals(uri)) throw new IllegalArgumentException("Invalid search collection");
        try {
            File file = cache.create();
            Uri result = CONTENT.buildUpon().appendPath(file.getName()).build();
            // Xiaobu forwards this read grant to the chosen app via ClipData.
            long identity = Binder.clearCallingIdentity();
            try { getContext().grantUriPermission("com.coloros.colordirectservice", result, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (RuntimeException error) { file.delete(); throw error; }
            finally { Binder.restoreCallingIdentity(identity); }
            return result;
        } catch (IOException error) { throw new IllegalStateException("Cannot prepare search image", error); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("r".equals(mode)) {
            requireReader(uri);
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        requireWriter();
        if (!"w".equals(mode) && !"wt".equals(mode)) throw new FileNotFoundException("Unsupported image mode");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
    }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        requireReader(uri);
        try {
            File file = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        requireWriter();
        try {
            boolean removed = file(uri).delete();
            long identity = Binder.clearCallingIdentity();
            try { getContext().revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            finally { Binder.restoreCallingIdentity(identity); }
            return removed ? 1 : 0;
        } catch (FileNotFoundException error) { return 0; }
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Search images do not support metadata updates");
    }
}
