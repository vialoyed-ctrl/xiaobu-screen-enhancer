package dev.codex.xiaobu;

import java.util.List;

/** Native extraction's FirstItemDependentToggleAndUndo interaction, on word indices. */
final class WordSelectionDrag {
    final boolean[] baseline;
    private final List<int[]> words;
    private final int anchor;
    private final boolean select;

    WordSelectionDrag(boolean[] baseline, List<int[]> words, int anchor) {
        this.baseline = baseline.clone();
        this.words = words;
        this.anchor = anchor;
        int[] first = words.get(anchor);
        boolean selected = true;
        for (int i = first[0]; i <= first[1]; i++) selected &= baseline[i];
        select = !selected;
    }

    boolean[] update(int end) {
        boolean[] result = baseline.clone();
        for (int word = Math.min(anchor, end); word <= Math.max(anchor, end); word++) {
            int[] span = words.get(word);
            for (int i = span[0]; i <= span[1]; i++) result[i] = select;
        }
        return result;
    }
}
