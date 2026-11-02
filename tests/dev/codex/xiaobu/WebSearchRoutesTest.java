package dev.codex.xiaobu;

public final class WebSearchRoutesTest {
    public static void main(String[] args) throws Exception {
        check(WebSearchRoutes.isWeb(0) && WebSearchRoutes.isWeb(1) && WebSearchRoutes.isWeb(3) && WebSearchRoutes.isWeb(4) && WebSearchRoutes.isWeb(6), "supported web engines");
        check(!WebSearchRoutes.isWeb(2) && !WebSearchRoutes.isWeb(5) && !WebSearchRoutes.isWeb(-1) && !WebSearchRoutes.isWeb(7), "app/removed/invalid engines rejected");
        String source = "{\"CONFIG\":{\"uuid\":\"12345678-abcd-abcd-abcd-123456789012\",\"uploadToken\":\"1234567890abcdef1234567890abcdef\"}}";
        String[] config = WebSearchRoutes.sogouConfig(source);
        check(config[0].equals("12345678-abcd-abcd-abcd-123456789012") && config[1].length() == 32, "fresh Sogou config");
        reject(() -> WebSearchRoutes.sogouConfig("<html>captcha</html>"));
        check(WebSearchRoutes.sogouImageUrl("http://img04.sogoucdn.com/app/a/fixture").equals("https://img04.sogoucdn.com/app/a/fixture"), "Sogou upload URL reusable across engines");
        String result = WebSearchRoutes.sogouResult("http://img04.sogoucdn.com/app/a/fixture");
        check(result.equals("https://ris.sogou.com/ris?flag=1&from=pic_result_list&query=https%3A%2F%2Fimg04.sogoucdn.com%2Fapp%2Fa%2Ffixture"), "verified Sogou HTTPS route");
        reject(() -> WebSearchRoutes.sogouResult("https://sogoucdn.com.evil.test/img.png"));
        reject(() -> WebSearchRoutes.sogouResult("https://user@img04.sogoucdn.com/img.png"));
        reject(() -> WebSearchRoutes.sogouResult("https://img04.sogoucdn.com:444/img.png"));
        reject(() -> WebSearchRoutes.sogouResult("<html>upload failed</html>"));
        check(WebSearchRoutes.publicImageResult(0, "https://test.example/image?a=1&b=2").contains("image_url=https%3A%2F%2Ftest.example%2Fimage%3Fa%3D1%26b%3D2"), "Google URL encoded");
        check(WebSearchRoutes.publicImageResult(1, "https://test.example/image").startsWith("https://yandex.com/images/touch/search?rpt=imageview&url="), "Yandex route retained");
        reject(() -> WebSearchRoutes.publicImageResult(2, "https://test.example/image"));
        check(WebSearchRoutes.publicImageResult(3, "https://test.example/image?a=1&b=2").contains("image=https%3A%2F%2Ftest.example%2Fimage%3Fa%3D1%26b%3D2"), "Baidu encoded image URL");
        reject(() -> WebSearchRoutes.publicImageResult(5, "https://test.example/image"));
        reject(() -> WebSearchRoutes.name(5));
        check(WebSearchRoutes.publicImageResult(6, "https://test.example/image").equals("https://trace.moe/?url=https%3A%2F%2Ftest.example%2Fimage"), "WhatAnime route");
        System.out.println("WebSearchRoutesTest: provider routes, config, HTTPS validation, and app-only rejection passed");
    }
    interface Attempt { void run() throws Exception; }
    static void reject(Attempt attempt) throws Exception { try { attempt.run(); } catch (java.io.IOException | IllegalArgumentException expected) { return; } throw new AssertionError("Invalid provider input accepted"); }
    static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
}
