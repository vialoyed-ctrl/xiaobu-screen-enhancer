package dev.codex.xiaobu;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.json.JSONArray;

/** Avoids repeating a network preflight that recently returned no live-image rule. */
final class RecognitionRuleCacheHook {
    private static final long TTL_MS = 60000L;
    private static final int CAPACITY = 64;

    static void install(ClassLoader loader) {
        try (HookTransaction hooks = new HookTransaction()) {
            Class<?> source = Class.forName(
                    "com.coloros.colordirectservice.repository.scenerule.QueryRuleExtDataSource", false, loader);
            Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", false, loader);
            Class<?> response = Class.forName("com.coloros.commons.repository.net.ResponseBean", false, loader);
            Class<?> unit = Class.forName("kotlin.Unit", false, loader);
            final Method ensure = source.getDeclaredMethod("d", String.class, String.class, continuation);
            final Method local = source.getDeclaredMethod("e", String.class, String.class);
            final Method onResponse = source.getDeclaredMethod("g", String.class, String.class, response);
            final Method getCode = response.getDeclaredMethod("getCode");
            Field semaphore = source.getDeclaredField("b");
            Field latch = source.getDeclaredField("c");
            Field singleton = unit.getDeclaredField("INSTANCE");
            if (ensure.getReturnType() != Object.class || Modifier.isStatic(ensure.getModifiers())
                    || !List.class.isAssignableFrom(local.getReturnType())
                    || onResponse.getReturnType() != void.class || Modifier.isStatic(onResponse.getModifiers())
                    || getCode.getReturnType() != int.class
                    || semaphore.getType() != Semaphore.class || !Modifier.isStatic(semaphore.getModifiers())
                    || latch.getType() != CountDownLatch.class || !Modifier.isStatic(latch.getModifiers())
                    || singleton.getType() != unit || !Modifier.isStatic(singleton.getModifiers())
                    || !continuation.isInterface()) {
                throw new NoSuchMethodException("Unknown live-image rule preflight implementation");
            }
            getCode.setAccessible(true);
            singleton.setAccessible(true);
            final Object completed = singleton.get(null);
            if (completed == null) throw new IllegalStateException("Missing Kotlin Unit singleton");
            final RuleMissCache misses = new RuleMissCache(TTL_MS, CAPACITY);
            final Persistence persistence = new Persistence(misses);
            final boolean diagnostic = diagnosticEnabled();
            hooks.add(XposedBridge.hookMethod(onResponse, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.hasThrowable() || hook.args[2] == null) return;
                    try {
                        int code = (Integer) getCode.invoke(hook.args[2]);
                        if (code != 200 && code != RuleMissCache.NO_MATCH) return;
                        long nowMs = System.currentTimeMillis();
                        String packageName = (String) hook.args[0];
                        String activityName = (String) hook.args[1];
                        boolean knownMiss = misses.contains(packageName, activityName, nowMs);
                        misses.response(packageName, activityName, code, nowMs);
                        persistence.changed();
                        if (diagnostic && code == RuleMissCache.NO_MATCH && !knownMiss) {
                            XposedBridge.log("XiaobuPerf: live-image rule server confirmed no match; cached 60s");
                        }
                    } catch (Throwable ignored) {
                        // Unreadable responses must keep the native network path.
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(ensure, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    // Resumed suspend calls carry null arguments and must finish their
                    // native latch/semaphore cleanup. Only initial calls can hit this cache.
                    if (!(hook.args[0] instanceof String) || !(hook.args[1] instanceof String)) return;
                    if (misses.contains((String) hook.args[0], (String) hook.args[1],
                            System.currentTimeMillis())) {
                        // d() only ensures remote rules. Its caller still reads all local
                        // rules and performs native live-photo/animation URL extraction.
                        hook.setResult(completed);
                        if (diagnostic) XposedBridge.log("XiaobuPerf: recent missing live-image rule cache hit");
                    }
                }
            }));
            hooks.add(XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            if (!hook.hasThrowable()) persistence.initialize((Context) hook.args[0]);
                        }
                    }));
            hooks.commit();
            persistence.initialize(AndroidAppHelper.currentApplication());
            XposedBridge.log("XiaobuLauncherHook: recent missing live-image rules cached for 60s (max 64)");
        } catch (Throwable unavailable) {
            XposedBridge.log("XiaobuLauncherHook: native live-image rule preflight retained: " + unavailable);
        }
    }

    private static boolean diagnosticEnabled() {
        try {
            return Boolean.TRUE.equals(Class.forName("android.os.SystemProperties")
                    .getDeclaredMethod("getBoolean", String.class, boolean.class)
                    .invoke(null, "debug.xiaobu.perf", false));
        } catch (Throwable ignored) { return false; }
    }

    /** All preference reads, serialization and package-manager calls run off the UI thread. */
    private static final class Persistence {
        private final RuleMissCache misses;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "XiaobuRuleCache");
            thread.setDaemon(true);
            return thread;
        });
        private boolean started;
        private SharedPreferences preferences;
        private long hostVersion;

        Persistence(RuleMissCache misses) { this.misses = misses; }

        synchronized void initialize(final Context context) {
            if (started || context == null) return;
            started = true;
            final long initialRevision = misses.revision();
            worker.execute(() -> {
                try {
                    if (!"com.coloros.colordirectservice".equals(context.getPackageName())) return;
                    hostVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                            .getLongVersionCode();
                    SharedPreferences saved = context.getSharedPreferences("xiaobu_rule_misses_v1", Context.MODE_PRIVATE);
                    if (saved.getLong("host_version", -1L) == hostVersion && saved.getInt("schema", 0) == 1) {
                        String encoded = saved.getString("entries", "[]");
                        if (encoded != null && encoded.length() <= 32768) {
                            JSONArray rows = new JSONArray(encoded);
                            if (rows.length() <= CAPACITY) {
                                ArrayList<RuleMissCache.Entry> entries = new ArrayList<RuleMissCache.Entry>();
                                for (int i = 0; i < rows.length(); i++) {
                                    JSONArray row = rows.getJSONArray(i);
                                    if (row.length() != 3) throw new IllegalArgumentException("Unknown cache record");
                                    entries.add(new RuleMissCache.Entry(row.getString(0), row.getString(1), row.getLong(2)));
                                }
                                misses.restore(entries, initialRevision, System.currentTimeMillis());
                            }
                        }
                    }
                    preferences = saved;
                    save();
                } catch (Throwable ignored) {
                    // Locked storage, corrupt data or an unavailable Context retain memory-only behavior.
                }
            });
        }

        void changed() { worker.execute(this::save); }

        private void save() {
            if (preferences == null) return;
            try {
                JSONArray rows = new JSONArray();
                for (RuleMissCache.Entry entry : misses.snapshot(System.currentTimeMillis())) {
                    rows.put(new JSONArray().put(entry.packageName).put(entry.activityName).put(entry.recordedMs));
                }
                preferences.edit().putLong("host_version", hostVersion).putInt("schema", 1)
                        .putString("entries", rows.toString()).apply();
            } catch (Throwable ignored) { }
        }
    }
}
