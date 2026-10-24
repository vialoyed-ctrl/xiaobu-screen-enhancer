package dev.codex.xiaobu;

import android.content.ClipData;
import android.content.ClipboardManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Fallback for native extraction, which has a separate text selection model. */
final class SelectedTextHook {
    static void install() {
        try {
            XposedHelpers.findAndHookMethod(ClipboardManager.class, "setPrimaryClip", ClipData.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    ClipData clip = (ClipData) hook.args[0];
                    if (clip == null || clip.getItemCount() != 1) return;
                    ClipData.Item item = clip.getItemAt(0);
                    if (item.getText() == null || item.getHtmlText() != null || item.getUri() != null || item.getIntent() != null) return;
                    String original = item.getText().toString();
                    String text = SelectedText.singleLine(original);
                    if (original.equals(text) || !recognitionCopy()) return;
                    hook.args[0] = ClipData.newPlainText(clip.getDescription().getLabel(), text);
                }
            });
        } catch (Throwable error) { ScreenshotButtons.log("single-line copy unavailable", error); }
    }

    private static boolean recognitionCopy() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("com.oplus.supertext.") || name.startsWith("com.coloros.participle.")
                    || name.startsWith("com.newui.tool.")) return true;
        }
        return false;
    }
}
