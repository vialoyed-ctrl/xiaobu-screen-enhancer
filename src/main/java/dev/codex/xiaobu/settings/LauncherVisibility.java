package dev.codex.xiaobu.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Controls only this module's launcher alias; settings and module entry stay enabled. */
final class LauncherVisibility {
    static final String ENTRY = "dev.codex.xiaobu.launcherhook.LauncherEntry";

    private static ComponentName entry(Context context) {
        return new ComponentName(context.getPackageName(), ENTRY);
    }

    static boolean hidden(Context context) {
        int state = context.getPackageManager().getComponentEnabledSetting(entry(context));
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            try { return !context.getPackageManager().getActivityInfo(entry(context), PackageManager.MATCH_DISABLED_COMPONENTS).enabled; }
            catch (PackageManager.NameNotFoundException missing) { return true; }
        }
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    static void setHidden(Context context, boolean hidden) {
        context.getPackageManager().setComponentEnabledSetting(entry(context),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }
}
