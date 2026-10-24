package dev.codex.xiaobu;

import java.io.IOException;
import java.util.List;

/** Ordered fallback; the first successful upload ends the chain. */
final class HostUploadPipeline {
    interface Upload { String send(byte[] image) throws Exception; }
    interface Progress { void trying(String host); }
    static final class Host {
        final String name;
        final Upload upload;
        Host(String name, Upload upload) { this.name = name; this.upload = upload; }
    }
    static String upload(byte[] image, List<Host> hosts, Progress progress) throws IOException {
        IOException failure = new IOException("All configured image hosts failed");
        for (Host host : hosts) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Upload cancelled");
            if (progress != null) progress.trying(host.name);
            try {
                String url = host.upload.send(image);
                if (url == null || url.isEmpty()) throw new IOException("Missing image URL");
                return url;
            } catch (Exception error) {
                failure.addSuppressed(new IOException(host.name + ": " + error.getClass().getSimpleName()));
            }
        }
        throw failure;
    }
}
