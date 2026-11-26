package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Lightweight sparkline chart for market index cards (IHSG / S&P 500). */
public final class SparklineView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final List<Float> points = new ArrayList<>();
    private int lineColor = 0xFFEF6C9A;

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f * context.getResources().getDisplayMetrics().density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setSeries(List<Float> values, int color) {
        points.clear();
        if (values != null) points.addAll(values);
        lineColor = color;
        linePaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.size() < 2) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : points) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float range = Math.max(1e-4f, max - min);
        float padY = h * 0.14f;
        float usableH = Math.max(1f, h - 2f * padY);

        linePath.reset();
        fillPath.reset();
        int n = points.size();
        for (int i = 0; i < n; i++) {
            float x = (n == 1) ? 0f : (i * (float) w) / (n - 1);
            float y = padY + usableH * (1f - (points.get(i) - min) / range);
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, h);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(w, h);
        fillPath.close();

        int topAlpha = (lineColor & 0x00FFFFFF) | 0x44000000;
        int botAlpha = (lineColor & 0x00FFFFFF);
        fillPaint.setShader(new LinearGradient(0, 0, 0, h, topAlpha, botAlpha, Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}
