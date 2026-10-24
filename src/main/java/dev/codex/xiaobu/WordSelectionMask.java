package dev.codex.xiaobu;

/** Inclusive native character indices; copies preserve every unrelated selection. */
final class WordSelectionMask {
    static boolean[] toggle(boolean[] source, int start, int end) {
        if (source == null || start < 0 || end < start || end >= source.length)
            throw new IllegalArgumentException("Invalid native word range");
        boolean selected = true;
        for (int i = start; i <= end; i++) selected &= source[i];
        boolean[] result = source.clone();
        for (int i = start; i <= end; i++) result[i] = !selected;
        return result;
    }
}
