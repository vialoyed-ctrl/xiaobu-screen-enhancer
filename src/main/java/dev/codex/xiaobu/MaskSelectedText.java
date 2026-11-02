package dev.codex.xiaobu;

/** Reconstructs owned disjoint selection in native OCR index order. */
final class MaskSelectedText {
    static String collect(boolean[] mask, String[] points) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < mask.length && i < points.length; i++) {
            String value = points[i];
            if (mask[i] && value != null && !value.contains("==START==") && !value.contains("==END=="))
                result.append(value);
        }
        return SelectedText.singleLine(result.toString());
    }
}
