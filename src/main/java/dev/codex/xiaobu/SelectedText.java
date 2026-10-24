package dev.codex.xiaobu;

/** Preserve word order and spaces, omitting only paragraph/line separators. */
final class SelectedText {
    static String singleLine(String text) {
        if (text == null) return null;
        StringBuilder result = null;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '\r' || value == '\n' || value == '\u2028' || value == '\u2029') {
                if (result == null) result = new StringBuilder(text.length()).append(text, 0, i);
            } else if (result != null) result.append(value);
        }
        return result == null ? text : result.toString();
    }
}
