package dev.codex.xiaobu;
import java.util.ArrayList;
import java.util.Collection;
import de.robv.android.xposed.XC_MethodHook;

/** Installs a capability completely, or removes its partially installed hooks. */
final class HookTransaction implements AutoCloseable {
    private final ArrayList<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private boolean committed;
    void add(XC_MethodHook.Unhook hook) {
        if (hook == null) throw new IllegalStateException("No hook installed");
        hooks.add(hook);
    }
    void add(Collection<XC_MethodHook.Unhook> group) {
        if (group == null || group.isEmpty()) throw new IllegalStateException("No matching framework member");
        hooks.addAll(group);
    }
    void commit() { committed = true; }
    @Override public void close() {
        if (committed) return;
        for (int i = hooks.size() - 1; i >= 0; i--) {
            try { hooks.get(i).unhook(); } catch (Throwable ignored) { }
        }
    }
}
