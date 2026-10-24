package dev.codex.xiaobu;

public final class SelectedTextTest {
    public static void main(String[] args) {
        check("环境色自适应", "环境\n色\r\n自适应");
        check("Hello world测试", "Hello world\r测试");
        check("甲乙丙", "甲\u2028乙\u2029丙");
        check("", "\n\r\n");
        String unchanged = "空格 保留 123 😀";
        if (SelectedText.singleLine(unchanged) != unchanged) throw new AssertionError("unnecessary allocation");
        if (SelectedText.singleLine(null) != null) throw new AssertionError("null");
        System.out.println("SelectedTextTest: PASS (no inserted line breaks; spaces, emoji and order preserved)");
    }
    private static void check(String expected, String input) {
        if (!expected.equals(SelectedText.singleLine(input))) throw new AssertionError(input);
    }
}
