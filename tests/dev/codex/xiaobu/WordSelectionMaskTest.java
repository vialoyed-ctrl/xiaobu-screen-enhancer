package dev.codex.xiaobu;

public final class WordSelectionMaskTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        boolean[] original = {true, false, false, false, false, true};
        boolean[] first = WordSelectionMask.toggle(original, 1, 2);
        check(first[0] && first[1] && first[2] && !first[3] && !first[4] && first[5]);
        check(!original[1] && !original[2]);
        boolean[] second = WordSelectionMask.toggle(first, 3, 4);
        for (boolean bit : second) check(bit);
        boolean[] removed = WordSelectionMask.toggle(second, 1, 2);
        check(removed[0] && !removed[1] && !removed[2] && removed[3] && removed[4] && removed[5]);
        boolean[] partial = WordSelectionMask.toggle(new boolean[]{true, false, true}, 0, 1);
        check(partial[0] && partial[1] && partial[2]);
        try { WordSelectionMask.toggle(original, 0, original.length); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { check(original[0] && original[5] && !original[1]); }
        System.out.println("WordSelectionMaskTest: PASS (whole words, disjoint selections, toggle, partial selection, invalid bounds)");
    }
}
