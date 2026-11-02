package dev.codex.xiaobu;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** A consistent set of rounded tiles, independent of installed provider apps. */
final class SearchEngineIcon extends Drawable {
    private static final int[] COLORS = {0xff4285f4, 0xffe53935, 0xff20212b,
            0xff315efb, 0xffef6c22, 0xff008b85, 0xff7356b8};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int engine, size;
    private int alpha = 255;

    SearchEngineIcon(Context context, int engine) {
        Resources resources = context.getResources();
        this.engine = engine;
        size = Math.round(24 * resources.getDisplayMetrics().density);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        canvas.scale(b.width() / 24f, b.height() / 24f);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(alpha);
        paint.setColor(COLORS[engine]);
        canvas.drawRoundRect(1, 1, 23, 23, 6, 6, paint);
        paint.setColor(0xffffffff);
        if (engine == 3) {
            canvas.drawOval(7, 12, 17, 19, paint);
            canvas.drawOval(5, 7, 8.5f, 12, paint);
            canvas.drawOval(9, 4.5f, 12, 10, paint);
            canvas.drawOval(13, 4.5f, 16, 10, paint);
            canvas.drawOval(17, 7, 20, 12, paint);
        } else if (engine == 2) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            Path note = new Path();
            note.moveTo(13, 6); note.lineTo(13, 15);
            note.cubicTo(13, 20, 6, 20, 6, 16);
            note.cubicTo(6, 13, 9, 13, 10, 14);
            canvas.drawPath(note, paint);
            canvas.drawArc(13, 3, 23, 12, 90, 85, false, paint);
        } else if (engine == 6) {
            Path play = new Path();
            play.moveTo(8.5f, 6); play.lineTo(18.5f, 12);
            play.lineTo(8.5f, 18); play.close(); canvas.drawPath(play, paint);
            canvas.drawCircle(6, 6, 1, paint);
            canvas.drawCircle(6, 18, 1, paint);
        } else {
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(18);
            canvas.drawText(engine == 0 ? "G" : engine == 1 ? "Y" : "S", 12, 18.3f, paint);
        }
        canvas.restoreToCount(save);
    }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
