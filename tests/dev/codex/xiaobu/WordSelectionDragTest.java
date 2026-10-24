package dev.codex.xiaobu;

import java.util.Arrays;
import java.util.List;

public final class WordSelectionDragTest {
    public static void main(String[] args) {
        List<int[]> words = Arrays.asList(new int[]{0,1}, new int[]{2,3}, new int[]{4,5}, new int[]{6,7});
        boolean[] base = {false,false,false,false,false,false,true,true};
        WordSelectionDrag add = new WordSelectionDrag(base, words, 0);
        check(add.update(2), new boolean[]{true,true,true,true,true,true,true,true}, "grow across words");
        check(add.update(0), new boolean[]{true,true,false,false,false,false,true,true}, "reverse restores baseline");
        check(base, new boolean[]{false,false,false,false,false,false,true,true}, "source immutable");
        WordSelectionDrag remove = new WordSelectionDrag(add.update(2), words, 2);
        check(remove.update(0), new boolean[]{false,false,false,false,false,false,true,true}, "selected start removes");
        check(remove.update(2), new boolean[]{true,true,true,true,false,false,true,true}, "reverse restores earlier words");
        System.out.println("WordSelectionDragTest: PASS (native first-item toggle and undo, disjoint preservation, grow, reverse)");
    }
    private static void check(boolean[] actual, boolean[] expected, String name) {
        if (!Arrays.equals(actual, expected)) throw new AssertionError(name);
    }
}
