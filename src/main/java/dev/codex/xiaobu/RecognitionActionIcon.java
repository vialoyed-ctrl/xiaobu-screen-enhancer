package dev.codex.xiaobu;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Shared 24dp stroke icons; colors follow the host toolbar's current light/dark mode. */
class RecognitionActionIcon extends Drawable {
    static final int SAVE = 0, SEARCH = 1;
    private final Resources resources;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size, kind;
    private int alpha = 255;

    RecognitionActionIcon(Context context, int kind) {
        resources = context.getResources();
        size = Math.round(24 * resources.getDisplayMetrics().density);
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        boolean dark = (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        paint.setColor(dark ? 0xfff2f2f2 : 0xff202124);
        paint.setAlpha(alpha);
        Rect bounds = getBounds();
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
        if (kind == SEARCH) {
            canvas.drawCircle(10, 10, 6.5f, paint);
            canvas.drawLine(15, 15, 21, 21, paint);
        } else {
            canvas.drawRoundRect(3, 3, 21, 21, 2, 2, paint);
            canvas.drawLine(8, 3, 8, 10, paint);
            canvas.drawLine(8, 10, 16, 10, paint);
            canvas.drawLine(16, 10, 16, 3, paint);
            canvas.drawLine(7, 20, 7, 15, paint);
            canvas.drawLine(7, 15, 17, 15, paint);
            canvas.drawLine(17, 15, 17, 20, paint);
        }
        canvas.restoreToCount(saved);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
