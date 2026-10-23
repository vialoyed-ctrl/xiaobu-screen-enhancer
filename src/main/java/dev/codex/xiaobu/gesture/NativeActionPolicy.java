package dev.codex.xiaobu.gesture;

/** A text/selection event alone does not prove that a usable native menu opened. */
public final class NativeActionPolicy {
    private NativeActionPolicy() { }

    public static boolean isActionPopup(CharSequence className) {
        if (className == null) return false;
        String name = className.toString();
        return name.contains("Popup") || name.contains("Dialog")
                || name.contains("FloatingToolbar") || name.contains("SelectionToolbar")
                || name.contains("ActionMode") || name.contains("ActionMenu")
                || name.contains("ContextMenu");
    }
}
