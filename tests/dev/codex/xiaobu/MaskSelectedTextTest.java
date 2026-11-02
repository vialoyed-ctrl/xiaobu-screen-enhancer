package dev.codex.xiaobu;
public final class MaskSelectedTextTest {
    public static void main(String[] args) {
        String[] points = {"==START==", "城", "市", "无", "关", "天", "气", "==END=="};
        check("城市天气", MaskSelectedText.collect(new boolean[]{false,true,true,false,false,true,true,false}, points));
        check("天气", MaskSelectedText.collect(new boolean[]{false,false,false,false,false,true,true,false}, points));
        check("", MaskSelectedText.collect(new boolean[8], points));
        check("A 😀B", MaskSelectedText.collect(new boolean[]{true,true,true,true,true}, new String[]{"A", " ", "😀", "\n", "B"}));
        check("城市天气", MaskSelectedText.collect(new boolean[]{true,true,true,false,false,true,true,true}, points));
        System.out.println("MaskSelectedTextTest: PASS (disjoint completeness, toggle, cleared mask, spaces, emoji, markers)");
    }
    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
}
