package dev.codex.xiaobu.gesture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Method;

/** User-initiated recognition gets a short-lived allowance, never a permanent whitelist. */
final class RecognitionStarter {
    private static final String PACKAGE = "com.coloros.colordirectservice";
    private static final long ALLOWANCE_MS = 3000L;
    private static volatile boolean unavailableLogged;

    static ComponentName start(Context context, Intent entrance) {
        try {
            Class<?> type = Class.forName("android.os.PowerExemptionManager");
            Object manager = context.getSystemService(type);
            if (manager == null) manager = type.getConstructor(Context.class).newInstance(context);
            Method allow = type.getMethod("addToTemporaryAllowList", String.class, int.class, String.class, long.class);
            int reason = type.getField("REASON_OTHER").getInt(null);
            allow.invoke(manager, PACKAGE, reason, "Xiaobu user gesture", ALLOWANCE_MS);
        } catch (ClassNotFoundException olderSystem) {
            try {
                Class<?> type = Class.forName("android.os.PowerWhitelistManager");
                Object manager = context.getSystemService(type);
                if (manager == null) manager = type.getConstructor(Context.class).newInstance(context);
                type.getMethod("whitelistAppTemporarily", String.class, long.class)
                        .invoke(manager, PACKAGE, ALLOWANCE_MS);
            } catch (Throwable error) { report(error); }
        } catch (Throwable error) { report(error); }
        return context.startService(entrance);
    }

    private static void report(Throwable error) {
        if (!unavailableLogged) {
            unavailableLogged = true;
            GestureSystemHook.log("temporary recognition allowance unavailable: " + error);
        }
    }
}
