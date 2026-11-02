package dev.codex.xiaobu;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verified provider routes; IDs are shared with the search menu. */
final class WebSearchRoutes {
    static boolean isWeb(int engine) { return engine == 0 || engine == 1 || engine == 3 || engine == 4 || engine == 6; }
    static String name(int engine) {
        switch (engine) {
            case 0: return "Google";
            case 1: return "Yandex";
            case 3: return "百度";
            case 4: return "搜狗";
            case 6: return "WhatAnime";
            default: throw new IllegalArgumentException("Unknown web engine");
        }
    }
    static String publicImageResult(int engine, String direct) throws Exception {
        String encoded = URLEncoder.encode(direct, "UTF-8");
        switch (engine) {
            case 0: return "https://www.google.com/searchbyimage?image_url=" + encoded;
            case 1: return "https://yandex.com/images/touch/search?rpt=imageview&url=" + encoded;
            case 3: return "https://graph.baidu.com/details?isfromtusoupc=1&tn=pc&carousel=0&promotion_name=pc_image_shituindex&extUiData%5BisLogoShow%5D=1&image=" + encoded;
            case 4: return "https://ris.sogou.com/ris?flag=1&from=pic_result_list&query=" + encoded;
            case 6: return "https://trace.moe/?url=" + encoded;
            default: throw new IllegalArgumentException("Engine does not use a public host");
        }
    }

    static String[] sogouConfig(String html) throws IOException {
        return new String[]{configValue(html, "uuid", "[0-9a-fA-F-]{36}"),
                configValue(html, "uploadToken", "[0-9a-fA-F]{32}")};
    }
    private static String configValue(String html, String key, String format) throws IOException {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"(" + format + ")\"").matcher(html);
        if (!matcher.find()) throw new IOException("Sogou upload session unavailable");
        return matcher.group(1);
    }
    static String sogouResult(String response) throws IOException {
        try { return publicImageResult(4, sogouImageUrl(response)); }
        catch (IOException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IOException("Invalid Sogou result", invalid); }
    }
    static String sogouImageUrl(String response) throws IOException {
        try {
            URI uri = new URI(response.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !(host.equals("sogoucdn.com") || host.endsWith(".sogoucdn.com"))
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawPath() == null || uri.getRawPath().isEmpty())
                throw new IOException("Unexpected Sogou image response");
            // The official uploader returns HTTP; this exact CDN supports HTTPS.
            String secure = new URI("https", null, host, -1, uri.getPath(), uri.getQuery(), null).toASCIIString();
            return secure;
        } catch (java.net.URISyntaxException invalid) { throw new IOException("Invalid Sogou image URL", invalid); }
    }
}
