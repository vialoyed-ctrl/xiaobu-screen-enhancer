package dev.codex.xiaobu;

import android.content.Intent;
import android.content.ComponentName;
import android.os.Parcelable;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Short-lived root entry point. No settings, permissions, or app databases are modified. */
public final class DouyinRootBridge {
    public static void main(String[] args) throws Exception {
        try {
            if (android.os.Process.myUid() != 0 || args.length != 2) throw new SecurityException("Root required");
            ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
            Class<?> model = loader.loadClass("X.0jkf"), picture = loader.loadClass("X.0jzS");
            Object params = model.getConstructor().newInstance();
            Object scan = picture.getConstructor(String.class, long.class, String.class,
                    java.util.List.class, Integer.class).newInstance(null, System.currentTimeMillis(), args[1], null, null);
            model.getMethod("LLFFF", picture).invoke(params, scan);
            for (String[] value : new String[][]{{"m", "result"}, {"a", "scan"}, {"d", "scan"},
                    {"f", "choose_album"}, {"g", "scan_album"}, {"F", "question"}, {"I", "question"}}) {
                Field field = model.getDeclaredField(value[0]); field.setAccessible(true); field.set(params, value[1]);
            }
            // Match the native photo-question album route: retain the image backdrop,
            // crop selector and its normal result-sheet height for subsequent searches.
            for (String fieldName : new String[]{"r", "s"}) {
                Field field = model.getDeclaredField(fieldName); field.setAccessible(true); field.set(params, 1);
            }
            Field ratio = model.getDeclaredField("o"); ratio.setAccessible(true); ratio.set(params, 0.6f);
            Intent intent = new Intent().setClassName("com.ss.android.ugc.aweme",
                    "com.ss.android.ugc.aweme.search.visualsearch.VisualSearchActivity");
            intent.putExtra("visual_search_params", (Parcelable) params);
            // Start the normal launcher and the result as one activity chain. The
            // result then finishes back into Douyin instead of becoming a task root.
            Intent launcher = Intent.makeMainActivity(new ComponentName("com.ss.android.ugc.aweme",
                    "com.ss.android.ugc.aweme.splash.SplashActivity"));
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            for (Method method : Class.forName("android.app.IActivityTaskManager").getMethods()) {
                if (!method.getName().equals("startActivities")) continue;
                Class<?>[] types = method.getParameterTypes(); Object[] values = new Object[types.length];
                int strings = 0;
                for (int n = 0; n < types.length; n++) {
                    if (types[n] == String.class) values[n] = strings++ == 0 ? "com.android.shell" : null;
                    else if (types[n] == Intent[].class) values[n] = new Intent[]{launcher, intent};
                    else if (types[n] == String[].class) values[n] = new String[]{null, null};
                    else if (types[n] == int.class) values[n] = 0;
                    else if (types[n] == boolean.class) values[n] = false;
                }
                int result = (Integer) method.invoke(service, values);
                if (result < 0) throw new IllegalStateException("Activity launch rejected: " + result);
                System.out.println("XIAOBU_DOUYIN_STARTED"); System.exit(0);
            }
            throw new NoSuchMethodException("startActivities");
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
