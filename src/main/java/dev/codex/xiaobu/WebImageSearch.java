package dev.codex.xiaobu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uploads only to the engine selected by the user, without an intermediary host. */
final class WebImageSearch {
    private static final int MAX_IMAGE = 20 * 1024 * 1024;
    private static final int MAX_RESPONSE = 1024 * 1024;

    static String upload(InputStream picture, int engine) throws Exception {
        if (engine != 0 && engine != 1) throw new IllegalArgumentException("Unknown engine");
        byte[] image = read(picture, MAX_IMAGE);
        if (image.length == 0) throw new IOException("Empty screenshot");
        String endpoint = engine == 0
                ? "https://lens.google.com/v3/upload?ep=cntpubb&hl=zh-CN"
                : "https://yandex.com/images/search?rpt=imageview&format=json&request="
                    + URLEncoder.encode("{\"blocks\":[{\"block\":\"b-page_type_search-by-image__link\"}]}", "UTF-8");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        String boundary = "XiaobuImage" + Long.toHexString(System.nanoTime());
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + (engine == 0 ? "encoded_image" : "upfile")
                + "\"; filename=\"screenshot.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(prefix.length + image.length + suffix.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(prefix);
                out.write(image);
                out.write(suffix);
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location != null) return checked(new URL(new URL(endpoint), location).toString(), engine);
            }
            if (status != 200) throw new IOException("Image upload HTTP " + status);
            String body;
            try (InputStream response = connection.getInputStream()) {
                body = new String(read(response, MAX_RESPONSE), StandardCharsets.UTF_8);
            }
            if (engine == 1) {
                JSONArray blocks = new JSONObject(body).optJSONArray("blocks");
                if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
                    JSONObject params = blocks.getJSONObject(i).optJSONObject("params");
                    if (params == null) continue;
                    String id = params.optString("cbirId", "");
                    if (!id.isEmpty()) return "https://yandex.com/images/search?rpt=imageview&cbir_id="
                            + URLEncoder.encode(id, "UTF-8");
                    String query = params.optString("url", "");
                    if (!query.isEmpty()) return checked("https://yandex.com/images/search?" + query, engine);
                }
            }
            throw new IOException("Engine did not return an image search result URL");
        } finally { connection.disconnect(); }
    }

    private static String checked(String link, int engine) throws Exception {
        URL url = new URL(link);
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        String domain = engine == 0 ? "google.com" : "yandex.com";
        if (!"https".equals(url.getProtocol()) || !(host.equals(domain) || host.endsWith("." + domain))) {
            throw new IOException("Unexpected upload destination");
        }
        return url.toString();
    }

    private static byte[] read(InputStream stream, int limit) throws IOException {
        if (stream == null) throw new IOException("Missing screenshot stream");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException("Image response exceeds limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
