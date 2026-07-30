package com.example.srmremoter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

public final class StatusLightView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int indicatorColor = 0xFFFF3B30;

    public StatusLightView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setIndicatorColor(@ColorInt int color) {
        indicatorColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * .5f;
        float cy = getHeight() * .5f;
        float size = Math.min(getWidth(), getHeight());
        float coreRadius = size * .24f;
        float glowRadius = size * .48f;

        paint.setShader(new RadialGradient(cx, cy, glowRadius,
                new int[] {
                        withAlpha(indicatorColor, 150),
                        withAlpha(indicatorColor, 70),
                        withAlpha(indicatorColor, 0)
                },
                new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, glowRadius, paint);
        paint.setShader(null);
        paint.setColor(indicatorColor);
        canvas.drawCircle(cx, cy, coreRadius, paint);

        paint.setColor(0x66FFFFFF);
        canvas.drawCircle(cx - coreRadius * .28f, cy - coreRadius * .30f,
                coreRadius * .28f, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
