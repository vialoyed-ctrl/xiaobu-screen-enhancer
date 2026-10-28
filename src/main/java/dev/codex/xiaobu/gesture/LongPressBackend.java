package dev.codex.xiaobu.gesture;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The receiver looper never performs provider, package-manager or disk I/O. */
final class LongPressBackend {
    private static final String MODULE = "dev.codex.xiaobu.launcherhook";
    private static final String XIAOBU = "com.coloros.colordirectservice";
    private static final String CONFIG_CHANGED = "dev.codex.xiaobu.CONFIG_CHANGED";
    private static final Uri SETTINGS = Uri.parse("content://dev.codex.xiaobu.launcherhook.settings");
    private static final long NATIVE_OPERATION_GRACE_MS = 100L;
    private static final long CONFIG_CHECK_BUDGET_MS = 250L;
    private static final int DISPLAY = Display.DEFAULT_DISPLAY;

    private final Context systemContext;
    private final ClassLoader loader;
    private final FocusReader focusReader;
    private final Method eventDisplayId;
    private final HandlerThread inputThread = new HandlerThread("XiaobuGesture");
    private final ThreadPoolExecutor queries = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(1), runnable -> {
                Thread thread = new Thread(runnable, "XiaobuGestureSettings");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicLong configurationGeneration = new AtomicLong();
    private Handler handler;
    private final PressGate<Candidate> presses = new PressGate<Candidate>();
    // All fields below are owned by the receiver looper.
    private Snapshot snapshot;
    private GestureInputReceiver receiver;
    private Object monitor;
    private Method pilfer;
    private Method disposeMonitor;
    private boolean monitorFailed;
    private boolean reloadPosted;
    private long lastReportedTimeout = -1;
    private String lastConfigError;
    private NavigationTap navigation;
    private long lastNavigationLaunch;
    private NavigationTapShield navigationShield;
    private boolean shieldFailed;
    private final Runnable refreshShield = new Runnable() {
        @Override public void run() {
            updateNavigationShield();
            if (receiver != null && snapshot != null && snapshot.navigationTap && !shieldFailed)
                handler.postDelayed(this, 500L);
        }
    };

    private void scheduleShieldRefresh() {
        Handler target = handler;
        if (target == null) return;
        target.removeCallbacks(refreshShield);
        target.post(refreshShield);
    }

    private void updateNavigationShield() {
        try {
            Rect region = null;
            Snapshot settings = snapshot;
            if (!shieldFailed && receiver != null && settings != null && settings.navigationTap
                    && deviceAvailable(settings.context)) {
                Focus focus = focusReader.read();
                if (focus != null && !excluded(settings, focus.packageName)) {
                    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                    WindowManager windows = (WindowManager) settings.context.getSystemService(Context.WINDOW_SERVICE);
                    windows.getDefaultDisplay().getRealMetrics(metrics);
                    int half = Math.min(metrics.widthPixels / 2, Math.round(72 * metrics.density));
                    int height = Math.round(18 * metrics.density);
                    int center = metrics.widthPixels / 2;
                    // Keep the small bottom-center target available in immersive video too.
                    region = new Rect(center - half, Math.max(0, metrics.heightPixels - height),
                            center + half, metrics.heightPixels);
                }
            }
            if (navigationShield == null && region != null) navigationShield = new NavigationTapShield(systemContext);
            if (navigationShield != null) navigationShield.update(region);
        } catch (Throwable error) {
            shieldFailed = true;
            if (navigationShield != null) navigationShield.remove();
            GestureSystemHook.log("navigation tap disabled to prevent app click-through: " + error);
        }
    }

    LongPressBackend(Context context, Object wm, ClassLoader loader) throws Exception {
        this.systemContext = context;
        this.loader = loader;
        this.focusReader = new FocusReader(wm);
        Method displayId = null;
        try { displayId = GestureSystemHook.method(MotionEvent.class, "getDisplayId"); }
        catch (NoSuchMethodException ignored) {
            // Older InputEvent versions lack this accessor; the channel itself is
            // already registered for the default display only.
        }
        this.eventDisplayId = displayId;
    }

    void start() {
        inputThread.start();
        handler = new Handler(inputThread.getLooper());
        handler.post(() -> {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(CONFIG_CHANGED);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                filter.addAction(Intent.ACTION_USER_UNLOCKED);
                filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
                filter.addAction("android.intent.action.USER_SWITCHED");
                systemContext.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        try {
                            String action = intent.getAction();
                            cancelPending("configuration or device state changed");
                            if (Intent.ACTION_SCREEN_OFF.equals(action)
                                    || "android.intent.action.USER_SWITCHED".equals(action)) {
                                snapshot = null;
                                configurationGeneration.incrementAndGet();
                                stopMonitor();
                            }
                            if (!Intent.ACTION_SCREEN_OFF.equals(action)) scheduleReload();
                        } catch (Throwable error) { GestureSystemHook.callbackFailure(error); }
                    }
                }, filter, null, handler, 2 /* Context.RECEIVER_EXPORTED */);
                requestReload();
            } catch (Throwable error) {
                stopMonitor();
                GestureSystemHook.log("initialization failed; native input retained: " + error);
            }
        });
    }

    private void scheduleReload() {
        // Any app can send the notification; only the protected provider supplies data.
        // Coalesce notifications and never inspect their extras.
        if (reloadPosted) return;
        reloadPosted = true;
        handler.postDelayed(() -> {
            reloadPosted = false;
            requestReload();
        }, 150L);
    }

    private void requestReload() {
        final long generation = configurationGeneration.incrementAndGet();
        cancelPending("settings reloaded");
        queries.execute(() -> {
            markBackendLoaded();
            Snapshot result = readSnapshot();
            handler.post(() -> {
                try {
                    if (configurationGeneration.get() != generation) return;
                    snapshot = result;
                    if (result != null) GestureSystemHook.log("configuration longPress=" + result.longPress
                            + " navigationTap=" + result.navigationTap);
                    if (result == null || !result.allowed || !deviceAvailable(result.context)) {
                        monitorFailed = false;
                        stopMonitor();
                        return;
                    }
                    if (!monitorFailed && receiver == null) startMonitor();
                    scheduleShieldRefresh();
                } catch (Throwable error) {
                    stopMonitor();
                    GestureSystemHook.log("reload isolated: " + error);
                }
            });
        });
    }

    private Snapshot readSnapshot() {
        long identity = Binder.clearCallingIdentity();
        try {
            int user = currentUserId();
            Context app = contextForUser(user);
            Bundle data = app.getContentResolver().call(SETTINGS, "get", null, null);
            if (data == null) throw new IllegalStateException("Settings provider returned no configuration");
            boolean longPress = data.getBoolean("enabled", false)
                    && data.getBoolean("observerConnected", false);
            boolean navTap = data.getBoolean("navigationTap", false);
            boolean allowed = longPress || navTap;
            int configuredTimeout = data.getInt("timeoutMs", 0);
            Set<String> excluded = new HashSet<String>();
            String text = data.getString("excluded", "");
            if (text == null) text = "";
            for (String name : text.split("[,\\s]+")) {
                if (!name.isEmpty()) excluded.add(name);
            }
            Intent entrance = entranceIntent();
            if (allowed && app.getPackageManager().resolveService(entrance, 0) == null) allowed = false;
            long popupTime = data.getLong("lastActionPopupTime", 0L);
            String popupPackage = data.getString("lastActionPopupPackage", "");
            if (popupPackage == null) popupPackage = "";
            lastConfigError = null;
            return new Snapshot(user, app, allowed, longPress, navTap, configuredTimeout,
                    Collections.unmodifiableSet(excluded), popupTime, popupPackage);
        } catch (Throwable error) {
            String message = error.getClass().getSimpleName() + ": " + error.getMessage();
            if (!message.equals(lastConfigError)) {
                lastConfigError = message;
                GestureSystemHook.log("settings unavailable; gesture disabled: " + message);
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void startMonitor() {
        Object created = null;
        long identity = Binder.clearCallingIdentity();
        try {
            Object inputManager = systemContext.getSystemService(Context.INPUT_SERVICE);
            if (inputManager == null) throw new IllegalStateException("Missing input manager");
            Method create = GestureSystemHook.method(inputManager.getClass(), "monitorGestureInput",
                    String.class, int.class);
            created = create.invoke(inputManager, "XiaobuScreenEnhancer", DISPLAY);
            if (created == null) throw new IllegalStateException("Input monitor returned null");
            Method nextDispose = GestureSystemHook.method(created.getClass(), "dispose");
            Method nextPilfer = GestureSystemHook.method(created.getClass(), "pilferPointers");
            Object channel = GestureSystemHook.method(created.getClass(), "getInputChannel").invoke(created);
            if (!(channel instanceof InputChannel)) throw new IllegalStateException("Invalid monitor channel");
            GestureInputReceiver nextReceiver = new GestureInputReceiver(
                    (InputChannel) channel, handler.getLooper(), this);
            monitor = created;
            pilfer = nextPilfer;
            disposeMonitor = nextDispose;
            receiver = nextReceiver;
            presses.release();
            GestureSystemHook.log("single-finger monitor enabled");
        } catch (Throwable error) {
            if (created != null) dispose(created);
            monitorFailed = true;
            GestureSystemHook.log("input monitor unsupported; native input retained: " + error);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void stopMonitor() {
        handler.removeCallbacks(refreshShield);
        if (navigationShield != null) navigationShield.remove();
        navigation = null;
        Candidate current = presses.current();
        if (current != null) discard(current);
        GestureInputReceiver oldReceiver = receiver;
        Object oldMonitor = monitor;
        Method oldDispose = disposeMonitor;
        receiver = null;
        monitor = null;
        pilfer = null;
        disposeMonitor = null;
        presses.release();
        try { if (oldReceiver != null) oldReceiver.dispose(); }
        catch (Throwable error) { GestureSystemHook.log("receiver disposal isolated: " + error); }
        try { if (oldMonitor != null && oldDispose != null) oldDispose.invoke(oldMonitor); }
        catch (Throwable error) { GestureSystemHook.log("monitor disposal isolated: " + error); }
        if (oldMonitor != null) GestureSystemHook.log("single-finger monitor released");
    }

    private static void dispose(Object value) {
        try { GestureSystemHook.method(value.getClass(), "dispose").invoke(value); }
        catch (Throwable ignored) { }
    }

    void onMotionEvent(MotionEvent event) {
        if (receiver == null || !fromMonitoredDisplay(event)) return;
        navigationEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            Candidate current = presses.current();
            if (current != null) discard(current);
            presses.release();
            return;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() != 1) {
            cancelPending("multiple fingers");
            presses.block();
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            Candidate previous = presses.current();
            if (previous != null) discard(previous);
            presses.release();
            Snapshot settings = snapshot;
            if (settings == null || !settings.longPress || !deviceAvailable(settings.context)
                    || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return;
            Focus focus = focusReader.read();
            if (focus == null || excluded(settings, focus.packageName)) return;
            if (navigationShield != null && navigationShield.contains(event.getX(), event.getY())) return;
            long timeout = timeout(settings);
            int slop = ViewConfiguration.get(settings.context).getScaledTouchSlop();
            Candidate next = new Candidate(event.getEventTime(), event.getPointerId(0),
                    event.getX(0), event.getY(0), slop, settings.userId, focus,
                    configurationGeneration.get(), timeout);
            next.trigger = () -> beginFinalCheck(next);
            presses.begin(next);
            handler.postAtTime(next.trigger, next.deadline);
            return;
        }
        Candidate current = presses.current();
        if (action != MotionEvent.ACTION_MOVE || current == null || presses.blocked()) return;
        int index = event.findPointerIndex(current.pointerId);
        if (index < 0 || moved(current, event.getX(index), event.getY(index))) {
            cancelPending("movement");
            presses.block();
            return;
        }
        // Coalesced moves must not hide a scroll that moved out and returned.
        for (int i = 0; i < event.getHistorySize(); i++) {
            if (moved(current, event.getHistoricalX(index, i), event.getHistoricalY(index, i))) {
                cancelPending("historical movement");
                presses.block();
                return;
            }
        }
    }

    private void navigationEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                || event.getPointerCount() != 1) { navigation = null; return; }
        if (action == MotionEvent.ACTION_DOWN) {
            navigation = null;
            Snapshot settings = snapshot;
            if (settings == null || !settings.navigationTap || !deviceAvailable(settings.context)
                    || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                    || event.getEventTime() - lastNavigationLaunch < 1200) return;
            Focus focus = focusReader.read();
            if (focus == null || excluded(settings, focus.packageName)) return;
            // A monitor cannot consume a tap after UP. Only recognize sequences
            // whose DOWN was protected by the real input surface.
            if (navigationShield == null || !navigationShield.contains(event.getX(), event.getY())) return;
            navigation = new NavigationTap(event.getX(), event.getY(), event.getEventTime(), focus,
                    settings.userId, configurationGeneration.get(), ViewConfiguration.get(settings.context).getScaledTouchSlop());
            return;
        }
        NavigationTap value = navigation;
        if (value == null) return;
        float dx = event.getX() - value.x, dy = event.getY() - value.y;
        if (dx * dx + dy * dy > value.slop * value.slop || event.getEventTime() - value.time > 250) {
            navigation = null; return;
        }
        for (int i = 0; i < event.getHistorySize(); i++) {
            dx = event.getHistoricalX(0, i) - value.x; dy = event.getHistoricalY(0, i) - value.y;
            if (dx * dx + dy * dy > value.slop * value.slop) { navigation = null; return; }
        }
        if (action != MotionEvent.ACTION_UP) return;
        navigation = null;
        // Configuration is kept current by the authenticated provider + change broadcasts.
        // Do not enqueue a provider/package-manager round trip on every tap: a busy
        // settings queue used to silently discard otherwise valid navigation taps.
        handler.post(() -> {
            Snapshot fresh = snapshot;
                try {
                    if (fresh == null || !fresh.navigationTap || fresh.userId != value.userId
                            || value.configuration != configurationGeneration.get()
                            || SystemClock.uptimeMillis() - value.time > 750
                            || SystemClock.uptimeMillis() - lastNavigationLaunch < 1200
                            || !deviceAvailable(fresh.context)) return;
                    Focus focus = focusReader.read();
                    if (focus == null || focus.window != value.focus.window
                            || excluded(fresh, focus.packageName)) return;
                    long identity = Binder.clearCallingIdentity();
                    try {
                        if (RecognitionStarter.start(fresh.context, entranceIntent()) != null) {
                            lastNavigationLaunch = SystemClock.uptimeMillis();
                            vibrate(40L);
                            GestureSystemHook.log("navigation tap recognition requested");
                        }
                    } finally { Binder.restoreCallingIdentity(identity); }
                } catch (Throwable error) { GestureSystemHook.log("navigation tap isolated: " + error); }
        });
    }

    private static boolean moved(Candidate value, float x, float y) {
        float dx = x - value.x;
        float dy = y - value.y;
        return dx * dx + dy * dy > value.slop * value.slop;
    }

    private boolean fromMonitoredDisplay(MotionEvent event) {
        try { return eventDisplayId == null || ((Integer) eventDisplayId.invoke(event)) == DISPLAY; }
        catch (Throwable ignored) { return false; }
    }

    private void beginFinalCheck(final Candidate value) {
        try {
            if (!valid(value)) { discard(value); return; }
            // A slow or dead provider must not stall the input receiver, nor trigger a
            // long-delayed recognition after an unrelated later gesture.
            value.expiry = () -> { if (presses.current() == value) discard(value); };
            handler.postAtTime(value.expiry, value.deadline + CONFIG_CHECK_BUDGET_MS);
            queries.execute(() -> {
                Snapshot fresh = readSnapshot();
                handler.post(() -> finishFinalCheck(value, fresh));
            });
        } catch (Throwable error) {
            discard(value);
            GestureSystemHook.callbackFailure(error);
        }
    }

    private void finishFinalCheck(Candidate value, Snapshot fresh) {
        try {
            if (!valid(value)) { discard(value); return; }
            if (fresh == null || !fresh.longPress || fresh.userId != value.userId) {
                snapshot = fresh;
                discard(value);
                if (fresh == null || !fresh.allowed) stopMonitor();
                return;
            }
            if (excluded(fresh, value.focus.packageName) || !deviceAvailable(fresh.context)
                    || currentUserId() != value.userId
                    || (fresh.popupTime >= value.downTime
                        && (fresh.popupPackage.isEmpty()
                            || fresh.popupPackage.equals(value.focus.packageName)))) {
                discard(value);
                return;
            }
            Focus now = focusReader.read();
            if (now == null || now.window != value.focus.window || monitor == null || pilfer == null) {
                discard(value);
                return;
            }
            if (!presses.commit(value)) { discard(value); return; }
            // The state gate commits only once. Input is cancelled only after the
            // native entrance accepts the request; failed launches retain app input.
            long identity = Binder.clearCallingIdentity();
            try {
                discard(value);
                presses.block();
                ComponentName started = RecognitionStarter.start(fresh.context, entranceIntent());
                if (started != null) {
                    try { pilfer.invoke(monitor); }
                    catch (Throwable unavailable) {
                        GestureSystemHook.log("recognition started; input pilfer unavailable: " + unavailable);
                    }
                    vibrate();
                    GestureSystemHook.log("single-finger recognition requested");
                } else {
                    GestureSystemHook.log("native recognition service did not start");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (Throwable error) {
            discard(value);
            GestureSystemHook.log("trigger isolated; native input resumes on release: " + error);
        }
    }

    private boolean valid(Candidate value) {
        return presses.isCurrent(value) && receiver != null
                && configurationGeneration.get() == value.configuration
                && SystemClock.uptimeMillis() <= value.deadline + CONFIG_CHECK_BUDGET_MS;
    }

    void cancelPending(String reason) {
        final Candidate current = presses.current();
        if (!presses.veto(current)) return;
        Handler target = handler;
        if (target != null) target.post(() -> {
            if (presses.current() == current) {
                discard(current);
                presses.block();
            }
        });
    }

    void onInputFailure(Throwable error) {
        cancelPending("input callback failure");
        // Disposal is posted so the current event is finished before its receiver
        // releases the native channel.
        handler.post(() -> {
            if (!monitorFailed) {
                monitorFailed = true;
                GestureSystemHook.log("input callback failed; gesture disabled: " + error);
            }
            stopMonitor();
        });
    }

    private void discard(Candidate value) {
        value.cancelled = true;
        if (value.trigger != null) handler.removeCallbacks(value.trigger);
        if (value.expiry != null) handler.removeCallbacks(value.expiry);
        presses.clear(value);
    }

    void onAccessibilityEvent(AccessibilityEvent event, int user, int callingUid) {
        Candidate current = presses.current();
        if (current == null || current.cancelled || event.getEventTime() < current.downTime) return;
        // USER_CURRENT (-2) and profile events are resolved by the native service.
        // Never treat an explicitly different non-profile user as this gesture.
        if (user >= 0 && user != current.userId && callingUid / 100000 != current.userId) return;
        int type = event.getEventType();
        // Native text views often emit selection/long-click events without opening
        // an action. Actual focus changes and touch movement are checked separately.
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || !NativeActionPolicy.isActionPopup(event.getClassName())) return;
        CharSequence name = event.getPackageName();
        if (name == null || current.focus.packageName.contentEquals(name)) {
            cancelPending("native action popup");
        }
    }

    void onFocusMayHaveChanged() {
        scheduleShieldRefresh();
        Candidate current = presses.current();
        if (current == null || current.cancelled) return;
        Focus focus = focusReader.read();
        if (focus == null || focus.window != current.focus.window) cancelPending("focus changed");
    }

    void onWindowAdded(WindowManager.LayoutParams params) {
        Candidate current = presses.current();
        boolean popup = (params.type >= 1000 && params.type <= 1999) || params.type == 2038;
        boolean touchable = (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        if (popup && touchable && current != null && !current.cancelled && params.packageName != null
                && params.packageName.equals(current.focus.packageName)) {
            cancelPending("native popup");
        }
    }

    private boolean deviceAvailable(Context context) {
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return power != null && power.isInteractive() && keyguard != null
                    && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()
                    && users != null && users.isUserUnlocked();
        } catch (Throwable ignored) { return false; }
    }

    private long timeout(Snapshot settings) {
        long value;
        if (settings.timeoutMs > 0) value = Math.max(200L, Math.min(5000L, settings.timeoutMs));
        else {
            value = nativeTimeout();
            if (value < 100L || value > 10000L) value = ViewConfiguration.getLongPressTimeout();
        }
        if (lastReportedTimeout != value) {
            lastReportedTimeout = value;
            GestureSystemHook.log("long press timeout=" + value + "ms plus native-operation grace=100ms");
        }
        return value + NATIVE_OPERATION_GRACE_MS;
    }

    private long nativeTimeout() {
        try {
            Class<?> nativeGesture = Class.forName(
                    "com.android.server.oplus.input.exinputservice.globalgesture.OplusScreenDirectManager", false, loader);
            return GestureSystemHook.field(nativeGesture, "LONG_PRESS_TIME").getLong(null);
        } catch (Throwable ignored) {
            return ViewConfiguration.getLongPressTimeout();
        }
    }

    private static boolean excluded(Snapshot settings, String name) {
        return name == null || name.isEmpty() || MODULE.equals(name) || XIAOBU.equals(name)
                || settings.excluded.contains(name);
    }

    private static Intent entranceIntent() {
        return new Intent("oplus.intent.action.DIRECT_SIDEBAR_SERVICE")
                .setClassName(XIAOBU, XIAOBU + ".InvokeEntranceService")
                .putExtra("extra_entrance_function", "full_screen_ocr")
                .putExtra("triggered_app", "com.coloros.smartsidebar");
    }

    private void markBackendLoaded() {
        try {
            int boot = Settings.Global.getInt(systemContext.getContentResolver(), "boot_count", -1);
            if (boot >= 0) Settings.Global.putInt(systemContext.getContentResolver(),
                    "xiaobu_single_finger_backend_boot", boot);
        } catch (Throwable error) {
            // This status marker is optional and never controls input behavior.
            GestureSystemHook.log("backend status marker unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static int currentUserId() throws Exception {
        Class<?> manager = Class.forName("android.app.ActivityManager");
        return (Integer) GestureSystemHook.method(manager, "getCurrentUser").invoke(null);
    }

    private Context contextForUser(int user) throws Exception {
        Class<?> handle = Class.forName("android.os.UserHandle");
        Constructor<?> constructor = handle.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object value = constructor.newInstance(user);
        return (Context) GestureSystemHook.method(Context.class, "createContextAsUser", handle, int.class)
                .invoke(systemContext, value, 0);
    }

    private void vibrate() {
        vibrate(12L);
    }

    private void vibrate(long durationMs) {
        try {
            Vibrator vibrator = (Vibrator) systemContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) { }
    }

    private static final class Candidate extends PressGate.Attempt {
        final long downTime;
        final int pointerId;
        final float x;
        final float y;
        final int slop;
        final int userId;
        final Focus focus;
        final long configuration;
        final long deadline;
        Runnable trigger;
        Runnable expiry;

        Candidate(long time, int pointerId, float x, float y, int slop, int userId,
                Focus focus, long configuration, long timeout) {
            this.downTime = time;
            this.pointerId = pointerId;
            this.x = x;
            this.y = y;
            this.slop = slop;
            this.userId = userId;
            this.focus = focus;
            this.configuration = configuration;
            this.deadline = time + timeout;
        }
    }

    private static final class Snapshot {
        final int userId;
        final Context context;
        final boolean allowed;
        final boolean longPress;
        final boolean navigationTap;
        final int timeoutMs;
        final Set<String> excluded;
        final long popupTime;
        final String popupPackage;

        Snapshot(int user, Context context, boolean allowed, boolean longPress, boolean navigationTap, int timeout, Set<String> excluded,
                long popup, String popupPackage) {
            this.userId = user;
            this.context = context;
            this.allowed = allowed;
            this.longPress = longPress;
            this.navigationTap = navigationTap;
            this.timeoutMs = timeout;
            this.excluded = excluded;
            this.popupTime = popup;
            this.popupPackage = popupPackage;
        }
    }

    private static final class NavigationTap {
        final float x, y;
        final long time, configuration;
        final Focus focus;
        final int userId, slop;
        NavigationTap(float x, float y, long time, Focus focus, int userId, long configuration, int slop) {
            this.x = x; this.y = y; this.time = time; this.focus = focus;
            this.userId = userId; this.configuration = configuration; this.slop = slop;
        }
    }

    private static final class Focus {
        final Object window;
        final String packageName;
        Focus(Object window, String packageName) { this.window = window; this.packageName = packageName; }
    }

    private static final class FocusReader {
        final Object wm;
        final Method display;
        java.lang.reflect.Field currentFocus;
        java.lang.reflect.Field attrs;

        FocusReader(Object wm) throws Exception {
            this.wm = wm;
            this.display = GestureSystemHook.method(wm.getClass(), "getDefaultDisplayContentLocked");
        }

        synchronized Focus read() {
            try {
                Object content = display.invoke(wm);
                if (content == null) return null;
                if (currentFocus == null) currentFocus = GestureSystemHook.field(content.getClass(), "mCurrentFocus");
                Object window = currentFocus.get(content);
                if (window == null) return null;
                if (attrs == null) attrs = GestureSystemHook.field(window.getClass(), "mAttrs");
                WindowManager.LayoutParams layout = (WindowManager.LayoutParams) attrs.get(window);
                if (layout == null || layout.packageName == null) return null;
                return new Focus(window, layout.packageName);
            } catch (Throwable ignored) { return null; }
        }


    }
}
