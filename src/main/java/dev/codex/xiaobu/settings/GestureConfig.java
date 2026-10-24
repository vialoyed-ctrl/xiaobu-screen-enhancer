package dev.codex.xiaobu.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Private preferences; the exported provider exposes an authenticated read-only snapshot. */
public final class GestureConfig {
    public static final String AUTHORITY = "dev.codex.xiaobu.launcherhook.settings";
    public static final String ACTION_CHANGED = "dev.codex.xiaobu.CONFIG_CHANGED";
    private static final String PREFS = "single_finger_gesture";

    private GestureConfig() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        return preferences(context).getBoolean("enabled", false);
    }

    public static boolean navigationTap(Context context) {
        return preferences(context).getBoolean("navigationTap", true);
    }

    static void setNavigationTap(Context context, boolean value) {
        preferences(context).edit().putBoolean("navigationTap", value).apply();
        notifyChanged(context);
    }

    public static int timeoutMs(Context context) {
        int value = preferences(context).getInt("timeoutMs", 0);
        return value == 0 ? 0 : Math.max(200, Math.min(2000, value));
    }

    public static Set<String> excluded(Context context) {
        Set<String> value = preferences(context).getStringSet("excluded", Collections.<String>emptySet());
        return new HashSet<String>(value);
    }

    static String excludedString(Context context) {
        StringBuilder result = new StringBuilder();
        for (String name : new TreeSet<String>(excluded(context))) {
            if (result.length() > 0) result.append('\n');
            result.append(name);
        }
        return result.toString();
    }

    static void setEnabled(Context context, boolean value) {
        preferences(context).edit().putBoolean("enabled", value).apply();
        notifyChanged(context);
    }

    static void setTimeoutMs(Context context, int value) {
        preferences(context).edit().putInt("timeoutMs", value == 0 ? 0 : Math.max(200, Math.min(2000, value))).apply();
        notifyChanged(context);
    }

    static void setExcluded(Context context, Set<String> values) {
        preferences(context).edit().putStringSet("excluded", new HashSet<String>(values)).apply();
        notifyChanged(context);
    }

    static void notifyChanged(Context context) {
        // Contains no settings or page data. Receivers must reload the provider, never trust extras.
        try {
            context.sendBroadcast(new Intent(ACTION_CHANGED));
        } catch (RuntimeException ignored) {
            // A temporarily unavailable system broadcast service must not stop the observer.
        }
    }
}
