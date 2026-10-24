package dev.codex.xiaobu.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Only this app and system_server may read configuration. No exported writes are supported. */
public final class GestureSettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private static void enforceReader() {
        int caller = Binder.getCallingUid();
        if (caller != 1000 && caller != Process.myUid()) {
            throw new SecurityException("Gesture settings are available only to system_server and this app");
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceReader();
        if (!"get".equals(method)) throw new IllegalArgumentException("Only the get operation is supported");
        Bundle result = new Bundle();
        result.putBoolean("enabled", GestureConfig.enabled(getContext()));
        result.putBoolean("navigationTap", GestureConfig.navigationTap(getContext()));
        result.putBoolean("observerConnected", GestureObserverService.isConnected());
        result.putString("excluded", GestureConfig.excludedString(getContext()));
        result.putInt("timeoutMs", GestureConfig.timeoutMs(getContext()));
        result.putLong("lastActionPopupTime", GestureObserverService.lastActionPopupTime());
        result.putString("lastActionPopupPackage", GestureObserverService.lastActionPopupPackage());
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        enforceReader();
        throw new UnsupportedOperationException("Use call(get)");
    }
    @Override public String getType(Uri uri) {
        enforceReader();
        return null;
    }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new SecurityException("Configuration is read-only outside the settings screen");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new SecurityException("Configuration is read-only outside the settings screen");
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new SecurityException("Configuration is read-only outside the settings screen");
    }
}
