package dev.codex.xiaobu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public image hosting, followed by URL-based Google/Yandex search. */
final class WebImageSearch {
    private static final int MAX_IMAGE = 4 * 1024 * 1024;
    private static final int MAX_RESPONSE = 1024 * 1024;

    static String upload(InputStream picture, int engine) throws Exception { return upload(picture, engine, null); }

    static String upload(InputStream picture, int engine, HostUploadPipeline.Progress progress) throws Exception {
        if (engine != 0 && engine != 1) throw new IllegalArgumentException("Unknown engine");
        byte[] image = read(picture, MAX_IMAGE);
        if (image.length == 0) throw new IOException("Empty screenshot");
        String direct = HostUploadPipeline.upload(image, Arrays.asList(
                new HostUploadPipeline.Host("postimg.cc", bytes -> {
                    // Its website explicitly rejects automated uploads. Without official API
                    // authorization, skip it rather than bypassing its upload restriction.
                    throw new IOException("Postimages official API authorization required");
                }),
                new HostUploadPipeline.Host("IM.GE", WebImageSearch::imge),
                new HostUploadPipeline.Host("pixhost.top", WebImageSearch::pixhost)), progress);
        return (engine == 0 ? "https://www.google.com/searchbyimage?image_url="
                : "https://yandex.com/images/search?rpt=imageview&url=") + URLEncoder.encode(direct, "UTF-8");
    }

    private static String imge(byte[] image) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("nsfw", "true");
        fields.put("visibility", "unlisted");
        fields.put("exif_strip", "true");
        // Its native Never option omits expires entirely.
        JSONObject response = post("https://im.ge/api/upload", "file", fields, image);
        JSONArray success = response.optJSONArray("success");
        if (success == null || success.length() == 0) throw new IOException("IM.GE upload rejected");
        return imageUrl(success.getJSONObject(0).getString("cdnUrl"), "im.ge");
    }

    private static String pixhost(byte[] image) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("expires_in", "0");
        // This site has no NSFW field. Respect its supported form and guest quota.
        JSONObject response = post("https://pixhost.top/upload.php", "image", fields, image);
        if (!response.optBoolean("ok")) throw new IOException("pixhost.top upload rejected");
        return imageUrl(response.getString("url"), "pixhost.top");
    }

    private static JSONObject post(String endpoint, String fileField, Map<String, String> fields, byte[] image) throws Exception {
        String boundary = "XiaobuImage" + Long.toHexString(System.nanoTime());
        boolean jpeg = image.length > 2 && image[0] == (byte) 0xff && image[1] == (byte) 0xd8;
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            header.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        header.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                + "\"; filename=\"screenshot." + (jpeg ? "jpg" : "png") + "\"\r\nContent-Type: image/"
                + (jpeg ? "jpeg" : "png") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] prefix = header.toByteArray(), suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", "XiaobuScreenEnhancer/2.1.0");
            connection.setRequestProperty("Accept", "application/json");
            String origin = "https://" + new URL(endpoint).getHost();
            connection.setRequestProperty("Origin", origin);
            connection.setRequestProperty("Referer", origin + "/");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(prefix.length + image.length + suffix.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(prefix); out.write(image); out.write(suffix); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Image host HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(read(input, MAX_RESPONSE), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private static String imageUrl(String link, String domain) throws IOException {
        URL url = new URL(link);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(url.getProtocol()) || !(host.equals(domain) || host.endsWith("." + domain))
                || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443))
            throw new IOException("Unexpected image host");
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
