package dev.codex.xiaobu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Uploads through Sogou's website and returns the validated HTTPS image URL. */
final class SogouImageSearch {
    private static final String HOME = "https://pic.sogou.com/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36";
    interface Transport { String request(String url, byte[] body, String contentType) throws Exception; }
    static String upload(byte[] image) throws Exception { return upload(image, new Session()); }
    static String upload(byte[] image, Transport transport) throws Exception {
        if (image == null || image.length == 0 || image.length > 4 * 1024 * 1024)
            throw new IOException("Invalid screenshot size");
        String[] config = WebSearchRoutes.sogouConfig(transport.request(HOME, null, null));
        String boundary = "XiaobuSogou" + Long.toHexString(System.nanoTime());
        String endpoint = HOME + "pic/upload_pic.jsp?uuid=" + URLEncoder.encode(config[0], "UTF-8");
        String reply = transport.request(endpoint, multipart(image, config[1], boundary),
                "multipart/form-data; boundary=" + boundary);
        return WebSearchRoutes.sogouImageUrl(reply);
    }
    static byte[] multipart(byte[] image, String token, String boundary) throws IOException {
        boolean jpeg = image.length > 2 && image[0] == (byte) 0xff && image[1] == (byte) 0xd8;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"token\"\r\n\r\n"
                + token + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"pic_path\"; filename=\"screenshot."
                + (jpeg ? "jpg" : "png") + "\"\r\nContent-Type: image/" + (jpeg ? "jpeg" : "png") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(image);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
    private static final class Session implements Transport {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        @Override public String request(String address, byte[] body, String contentType) throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Upload cancelled");
            URI uri = new URI(address);
            HttpURLConnection connection = DirectNetwork.open(new URL(address));
            try {
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Referer", HOME);
                connection.setRequestProperty("Origin", "https://pic.sogou.com");
                for (Map.Entry<String, List<String>> header : cookies.get(uri, java.util.Collections.emptyMap()).entrySet())
                    connection.setRequestProperty(header.getKey(), String.join("; ", header.getValue()));
                if (body != null) {
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", contentType);
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(body); }
                }
                int status = connection.getResponseCode();
                cookies.put(uri, connection.getHeaderFields());
                if (status < 200 || status >= 300) throw new IOException("Sogou HTTP " + status);
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Upload cancelled");
                        if (out.size() + count > 1024 * 1024) throw new IOException("Sogou response exceeds limit");
                        out.write(buffer, 0, count);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        }
    }
}
