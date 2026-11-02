package dev.codex.xiaobu;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Copies only the selected screenshot, then starts the native photo-question result. */
final class DouyinImageSearch {
    static void open(Context context, Uri image) throws Exception {
        ApplicationInfo target = context.getPackageManager().getApplicationInfo("com.ss.android.ugc.aweme", 0);
        // The internal parcel model is versioned. Fail explicitly after an app upgrade.
        String version = context.getPackageManager().getPackageInfo(target.packageName, 0).versionName;
        if (!"40.8.0".equals(version)) throw new java.io.IOException("Unsupported Douyin version");
        File stage = File.createTempFile("douyin-search-", ".png", context.getCacheDir());
        try {
            try (InputStream in = context.getContentResolver().openInputStream(image);
                    FileOutputStream out = new FileOutputStream(stage)) {
                if (in == null) throw new java.io.IOException("Screenshot unavailable");
                byte[] buffer = new byte[16384]; int n, total = 0;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > 32 * 1024 * 1024) throw new java.io.IOException("Screenshot too large");
                    out.write(buffer, 0, n);
                }
                if (total == 0) throw new java.io.IOException("Empty screenshot");
            }
            // Each result page retains its original while the user crops or revisits it.
            String destination = target.dataDir + "/cache/xiaobu-photo-question-" + stage.getName();
            String script = "set -e\ncp " + quote(stage.getAbsolutePath()) + " " + quote(destination)
                    + "\nchown " + target.uid + ":" + target.uid + " " + quote(destination)
                    + "\nchmod 600 " + quote(destination) + "\nrestorecon " + quote(destination)
                    + "\nCLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
                    + " app_process /system/bin dev.codex.xiaobu.DouyinRootBridge "
                    + quote(target.sourceDir) + " " + quote(destination) + "\n";
            Process process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) throw new java.io.IOException("Root handoff timed out");
                java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[1024]; int n;
                try (InputStream in = process.getInputStream()) {
                    while ((n = in.read(b)) != -1) { if (log.size() + n > 32768) break; log.write(b, 0, n); }
                }
                String result = new String(log.toByteArray(), StandardCharsets.UTF_8);
                if (process.exitValue() != 0 || !result.contains("XIAOBU_DOUYIN_STARTED")) {
                    android.util.Log.w("XiaobuDouyin", "root handoff failed: " + result);
                    throw new java.io.IOException("Root handoff rejected");
                }
                android.util.Log.i("XiaobuDouyin", "photo question screenshot delivered");
            } finally { process.destroy(); }
        } finally { stage.delete(); }
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
