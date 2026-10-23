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

/** Google uses a temporary public image URL; Yandex receives the image directly. */
final class WebImageSearch {
    private static final int MAX_IMAGE = 20 * 1024 * 1024;
    private static final int MAX_RESPONSE = 1024 * 1024;

    static String upload(InputStream picture, int engine) throws Exception {
        if (engine != 0 && engine != 1) throw new IllegalArgumentException("Unknown engine");
        byte[] image = read(picture, MAX_IMAGE);
        if (image.length == 0) throw new IOException("Empty screenshot");
        if (engine == 0) return googleByImageUrl(image);
        String endpoint = "https://yandex.com/images-apphost/image-download?cbir=1";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        String mime = image.length > 2 && image[0] == (byte) 0xff && image[1] == (byte) 0xd8 ? "image/jpeg" : "image/png";
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Content-Type", mime);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(image.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(image);
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
                JSONObject uploaded = new JSONObject(body);
                String imageUrl = uploaded.optString("url", "");
                if (!imageUrl.isEmpty()) {
                    URL asset = new URL(imageUrl);
                    if (!"https".equals(asset.getProtocol()) || !asset.getHost().endsWith(".yandex.net")) {
                        throw new IOException("Unexpected Yandex image host");
                    }
                    return "https://yandex.com/images/search?rpt=imageview&url=" + URLEncoder.encode(imageUrl, "UTF-8");
                }
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

    private static String googleByImageUrl(byte[] image) throws Exception {
        String boundary = "XiaobuTemporary" + Long.toHexString(System.nanoTime());
        boolean jpeg = image.length > 2 && image[0] == (byte) 0xff && image[1] == (byte) 0xd8;
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"expire\"\r\n\r\n3600\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"screenshot."
                + (jpeg ? "jpg" : "png") + "\"\r\nContent-Type: image/" + (jpeg ? "jpeg" : "png") + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection upload = temporaryConnection("https://tmpfiles.org/api/v1/upload");
        String page;
        try {
            upload.setRequestMethod("POST");
            upload.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            upload.setDoOutput(true);
            upload.setFixedLengthStreamingMode(prefix.length + image.length + suffix.length);
            try (OutputStream out = upload.getOutputStream()) { out.write(prefix); out.write(image); out.write(suffix); }
            JSONObject response = new JSONObject(responseText(upload));
            if (!"success".equals(response.optString("status"))) throw new IOException("Temporary image upload failed");
            page = checkedTemporary(response.getJSONObject("data").getString("url"));
        } finally { upload.disconnect(); }
        // The API returns a preview page. Its download URL includes a signed expiry token;
        // inserting /dl/ into the page URL no longer produces a direct image.
        HttpURLConnection preview = temporaryConnection(page);
        String html;
        try { html = responseText(preview); } finally { preview.disconnect(); }
        java.util.regex.Matcher imageLink = java.util.regex.Pattern.compile(
                "id=[\"']img_preview[\"'][^>]*src=[\"']([^\"']+)[\"']").matcher(html);
        if (!imageLink.find()) throw new IOException("Temporary image link unavailable");
        String direct = checkedTemporary(imageLink.group(1).replace("&amp;", "&"));
        if (!new URL(direct).getPath().startsWith("/dl/")) throw new IOException("Invalid temporary image link");
        return "https://www.google.com/searchbyimage?image_url=" + URLEncoder.encode(direct, "UTF-8");
    }

    private static HttpURLConnection temporaryConnection(String link) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(checkedTemporary(link)).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "XiaobuScreenEnhancer/2.0");
        return connection;
    }

    private static String checkedTemporary(String link) throws Exception {
        URL url = new URL(link);
        if (!"https".equals(url.getProtocol()) || !"tmpfiles.org".equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null) {
            throw new IOException("Unexpected temporary image host");
        }
        return url.toString();
    }

    private static String responseText(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        if (status != 200) throw new IOException("Temporary image service HTTP " + status);
        try (InputStream input = connection.getInputStream()) {
            return new String(read(input, MAX_RESPONSE), StandardCharsets.UTF_8);
        }
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
