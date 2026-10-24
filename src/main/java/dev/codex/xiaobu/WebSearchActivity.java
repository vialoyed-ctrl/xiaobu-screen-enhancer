package dev.codex.xiaobu;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground user initiated upload; uses the module's network identity and browser defaults. */
public final class WebSearchActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "XiaobuWebUpload"));
    private boolean destroyed;
    private int engine;
    private Uri image;
    private TextView message;
    private ProgressBar progress;
    private Button retry;
    private String result;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = getIntent().getIntExtra("engine", -1);
        image = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if ((engine != 0 && engine != 1) || image == null || !"content".equals(image.getScheme())) { finish(); return; }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        message = new TextView(this);
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        panel.addView(message);
        progress = new ProgressBar(this);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(pad * 2, pad * 2);
        spacing.topMargin = pad;
        panel.addView(progress, spacing);
        retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> { if (result == null) upload(); else openResult(); });
        panel.addView(retry);
        setContentView(panel);
        getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - pad * 2, pad * 13), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        setFinishOnTouchOutside(false);
        upload();
    }

    private void upload() {
        android.util.Log.i("XiaobuWebSearch", "upload.begin engine=" + engine);
        message.setText("正在使用 " + (engine == 0 ? "Google" : "Yandex") + " 识图…");
        progress.setVisibility(android.view.View.VISIBLE);
        retry.setVisibility(android.view.View.GONE);
        worker.execute(() -> {
            try {
                byte[] compressed = searchImage();
                android.util.Log.i("XiaobuWebSearch", "image.ready engine=" + engine + " bytes=" + compressed.length);
                String link = WebImageSearch.upload(new ByteArrayInputStream(compressed), engine, host ->
                        runOnUiThread(() -> { if (!destroyed) message.setText("正在上传到 " + host + "…"); }));
                android.util.Log.i("XiaobuWebSearch", "result.ready engine=" + engine + " active=" + !destroyed);
                runOnUiThread(() -> { if (!destroyed) { result = link; openResult(); } });
            } catch (Exception error) {
                android.util.Log.i("XiaobuWebSearch", "upload.error engine=" + engine + " type=" + error.getClass().getSimpleName());
                String detail = error instanceof java.net.SocketTimeoutException ? "连接超时，请检查网络后重试"
                        : error instanceof javax.net.ssl.SSLException ? "安全连接失败，请检查网络"
                        : error instanceof java.net.UnknownHostException ? "无法连接识图服务，请检查网络"
                        : "上传失败，请重试（" + error.getClass().getSimpleName() + "）";
                runOnUiThread(() -> { if (!destroyed) { message.setText(detail); progress.setVisibility(android.view.View.GONE); retry.setVisibility(android.view.View.VISIBLE); } });
            }
        });
    }

    private byte[] searchImage() throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(image)) { BitmapFactory.decodeStream(input, null, bounds); }
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 20000 || bounds.outHeight > 20000) throw new java.io.IOException("Invalid image dimensions");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(image)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
        if (bitmap == null) throw new java.io.IOException("Screenshot unavailable");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new java.io.IOException("Image encoding failed");
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private void openResult() {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(result));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            android.content.pm.ResolveInfo chosen = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")).addCategory(Intent.CATEGORY_BROWSABLE),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (chosen != null && chosen.activityInfo != null && !"android".equals(chosen.activityInfo.packageName)) browser.setPackage(chosen.activityInfo.packageName);
            android.util.Log.i("XiaobuWebSearch", "browser.request engine=" + engine + " package=" + browser.getPackage());
            startActivity(browser);
            android.util.Log.i("XiaobuWebSearch", "browser.started engine=" + engine);
            finish();
        } catch (Exception error) {
            message.setText("无法打开默认浏览器");
            progress.setVisibility(android.view.View.GONE);
            retry.setVisibility(android.view.View.VISIBLE);
        }
    }

    @Override protected void onDestroy() {
        android.util.Log.i("XiaobuWebSearch", "activity.destroy engine=" + engine);
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
