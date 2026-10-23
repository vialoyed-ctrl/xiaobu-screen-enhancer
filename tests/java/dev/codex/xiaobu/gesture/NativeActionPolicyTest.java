package dev.codex.xiaobu.gesture;

public final class NativeActionPolicyTest {
    public static void main(String[] args) {
        String[] ordinary = {"android.widget.TextView", "android.widget.EditText",
                "android.webkit.WebView", "android.widget.ScrollView",
                "com.android.settings.Settings$DisplaySettingsActivity"};
        for (String name : ordinary) {
            if (NativeActionPolicy.isActionPopup(name)) throw new AssertionError("Ordinary text blocked: " + name);
        }
        String[] actions = {"android.widget.PopupWindow$PopupDecorView", "android.app.AlertDialog",
                "com.android.internal.widget.FloatingToolbar", "android.view.ActionMode",
                "com.example.SelectionToolbar", "com.example.ContextMenu"};
        for (String name : actions) {
            if (!NativeActionPolicy.isActionPopup(name)) throw new AssertionError("Native action not recognized: " + name);
        }
        if (NativeActionPolicy.isActionPopup(null)) throw new AssertionError("Missing metadata blocked text");
        System.out.println("NativeActionPolicyTest: PASS (ordinary text allowed, actual popup classes vetoed)");
    }
}
