package com.example.srmremoter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

import java.util.EnumSet;

public final class GamepadView extends View {
    private static final float STICK_VISUAL_RADIUS_RATIO = .135f;
    private static final float STICK_HIT_RADIUS_RATIO = .24f;
    private static final float STICK_MAPPING_RADIUS_RATIO = .235f;
    private static final float STICK_DEAD_ZONE = .08f;
    private static final int STICK_OUTPUT_STEP = 32;

    public interface CommandListener {
        void onCommand(String frame, String label);
    }

    private enum Control { DPAD, LEFT_STICK, RIGHT_STICK, A, B, X, Y, NONE }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stickTextureClip = new Path();
    private final SparseArray<Control> activePointers = new SparseArray<>();
    private final int backgroundColor;
    private final int shellColor;
    private final int controlColor;
    private final int controlInnerColor;
    private final int outlineColor;
    private final int primaryColor;
    private final int actionAColor;
    private final int actionBColor;
    private final int actionXColor;
    private final int actionYColor;

    private CommandListener commandListener;
    private boolean controlsEnabled;
    private float leftX;
    private float leftY;
    private float rightX;
    private float rightY;
    private int lastLeftX;
    private int lastLeftY;
    private int lastRightX;
    private int lastRightY;
    private String dpadFrame = "";
    private final EnumSet<Control> pressedActions = EnumSet.noneOf(Control.class);
    private ValueAnimator leftReturnAnimator;
    private ValueAnimator rightReturnAnimator;
    private RenderNode staticLayer;
    private Bitmap leftStickIdle;
    private Bitmap leftStickActive;
    private Bitmap rightStickIdle;
    private Bitmap rightStickActive;

    public GamepadView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        backgroundColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant);
        shellColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurface);
        int onSurface = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface);
        controlColor = physicalControlColor(shellColor, onSurface);
        controlInnerColor = ColorUtils.blendARGB(controlColor,
                contrastColor(controlColor), .16f);
        outlineColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOutline);
        primaryColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary);
        actionAColor = harmonizedAccent(0xFF42A86B, primaryColor);
        actionBColor = harmonizedAccent(0xFFD84D57, primaryColor);
        actionXColor = harmonizedAccent(0xFF4787D7, primaryColor);
        actionYColor = harmonizedAccent(0xFFD3A62E, primaryColor);
        paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        setFocusable(true);
    }

    public void setCommandListener(CommandListener commandListener) {
        this.commandListener = commandListener;
    }

    public void setControlsEnabled(boolean enabled) {
        controlsEnabled = enabled;
        if (!enabled) {
            resetControls(false);
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        buildStaticLayer(width, height);
    }

    private void buildStaticLayer(int width, int height) {
        if (width <= 0 || height <= 0) return;
        releaseCachedResources();
        staticLayer = new RenderNode("gamepad-static");
        staticLayer.setPosition(0, 0, width, height);
        RecordingCanvas canvas = staticLayer.beginRecording(width, height);
        try {
            drawStaticLayer(canvas, width, height);
        } finally {
            staticLayer.endRecording();
        }

        float stickRadius = height * STICK_VISUAL_RADIUS_RATIO;
        leftStickIdle = buildStickKnob(stickRadius, "L", false);
        leftStickActive = buildStickKnob(stickRadius, "L", true);
        rightStickIdle = buildStickKnob(stickRadius, "R", false);
        rightStickActive = buildStickKnob(stickRadius, "R", true);
    }

    private void drawStaticLayer(Canvas canvas, float width, float height) {
        drawStaticBody(canvas, width, height);

        String activeDpad = dpadFrame;
        EnumSet<Control> activeActions = EnumSet.copyOf(pressedActions);
        dpadFrame = "";
        pressedActions.clear();
        drawDpad(canvas, width * .18f, height * .35f, height * .205f);
        drawActions(canvas, width * .82f, height * .35f, height * .125f);
        pressedActions.addAll(activeActions);
        dpadFrame = activeDpad;

        drawStickBase(canvas, width * .25f, height * .73f,
                height * STICK_VISUAL_RADIUS_RATIO);
        drawStickBase(canvas, width * .75f, height * .73f,
                height * STICK_VISUAL_RADIUS_RATIO);
    }

    private void releaseCachedResources() {
        if (staticLayer != null) staticLayer.discardDisplayList();
        recycleBitmap(leftStickIdle);
        recycleBitmap(leftStickActive);
        recycleBitmap(rightStickIdle);
        recycleBitmap(rightStickActive);
        staticLayer = null;
        leftStickIdle = null;
        leftStickActive = null;
        rightStickIdle = null;
        rightStickActive = null;
    }

    private static void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelStickReturn(true);
        cancelStickReturn(false);
        releaseCachedResources();
        super.onDetachedFromWindow();
    }

    private void drawStaticBody(Canvas canvas, float width, float height) {

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0f, 0f, 0f, height,
                highlight(backgroundColor, .05f), shade(backgroundColor, .045f),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        paint.setShadowLayer(height * .025f, 0, height * .015f, 0x33000000);
        paint.setShader(new LinearGradient(0f, height * .07f, 0f, height * .94f,
                new int[]{highlight(shellColor, .075f), shellColor, shade(shellColor, .055f)},
                null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(width * .025f, height * .07f, width * .975f, height * .94f,
                height * .22f, height * .22f, paint);
        paint.setShader(null);
        paint.clearShadowLayer();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, height * .003f));
        paint.setColor(ColorUtils.setAlphaComponent(highlight(shellColor, .18f), 145));
        canvas.drawRoundRect(width * .027f, height * .072f, width * .973f, height * .938f,
                height * .218f, height * .218f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (staticLayer == null || !staticLayer.hasDisplayList()
                || staticLayer.getWidth() != getWidth()
                || staticLayer.getHeight() != getHeight()) {
            buildStaticLayer(getWidth(), getHeight());
        }
        if (canvas.isHardwareAccelerated() && staticLayer != null
                && staticLayer.hasDisplayList()) {
            canvas.drawRenderNode(staticLayer);
        } else {
            drawStaticLayer(canvas, width, height);
        }

        if (!dpadFrame.isEmpty()) {
            drawDpad(canvas, width * .18f, height * .35f, height * .205f);
        }
        drawPressedActions(canvas, width * .82f, height * .35f, height * .125f);
        drawStick(canvas, width * .25f, height * .73f,
                height * STICK_VISUAL_RADIUS_RATIO,
                leftX, leftY, "L", Control.LEFT_STICK);
        drawStick(canvas, width * .75f, height * .73f,
                height * STICK_VISUAL_RADIUS_RATIO,
                rightX, rightY, "R", Control.RIGHT_STICK);

        if (!controlsEnabled) {
            paint.setColor(ColorUtils.setAlphaComponent(backgroundColor, 90));
            canvas.drawRoundRect(width * .025f, height * .07f, width * .975f, height * .94f,
                    height * .22f, height * .22f, paint);
        }
    }

    private void drawPressedActions(Canvas canvas, float cx, float cy, float spread) {
        if (pressedActions.contains(Control.A)) {
            drawAction(canvas, cx, cy + spread, "A", Control.A, actionAColor);
        }
        if (pressedActions.contains(Control.B)) {
            drawAction(canvas, cx + spread, cy, "B", Control.B, actionBColor);
        }
        if (pressedActions.contains(Control.X)) {
            drawAction(canvas, cx - spread, cy, "X", Control.X, actionXColor);
        }
        if (pressedActions.contains(Control.Y)) {
            drawAction(canvas, cx, cy - spread, "Y", Control.Y, actionYColor);
        }
    }

    private void drawDpad(Canvas canvas, float cx, float cy, float size) {
        float arm = size * .35f;
        paint.setShadowLayer(size * .12f, 0f, size * .055f, 0x55000000);
        paint.setShader(new LinearGradient(0f, cy - size, 0f, cy + size,
                new int[]{highlight(controlColor, .22f), controlColor,
                        shade(controlColor, .20f)}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(cx - arm, cy - size, cx + arm, cy + size,
                arm * .28f, arm * .28f, paint);
        canvas.drawRoundRect(cx - size, cy - arm, cx + size, cy + arm,
                arm * .28f, arm * .28f, paint);
        paint.setShader(null);
        paint.clearShadowLayer();

        paint.setColor(ColorUtils.setAlphaComponent(highlight(controlColor, .35f), 95));
        canvas.drawRoundRect(cx - arm * .72f, cy - size * .91f,
                cx + arm * .72f, cy - size * .76f, arm * .2f, arm * .2f, paint);

        if (!dpadFrame.isEmpty()) {
            paint.setShadowLayer(size * .13f, 0, 0,
                    ColorUtils.setAlphaComponent(primaryColor, 150));
            paint.setShader(new LinearGradient(0f, cy - size, 0f, cy + size,
                    new int[]{highlight(primaryColor, .20f), primaryColor,
                            shade(primaryColor, .16f)}, null,
                    Shader.TileMode.CLAMP));
            float pressedOffset = size * .025f;
            canvas.save();
            canvas.translate(0f, pressedOffset);
            if ("F\n".equals(dpadFrame)) {
                canvas.drawRoundRect(cx - arm, cy - size, cx + arm, cy + arm * .18f,
                        arm * .28f, arm * .28f, paint);
            } else if ("B\n".equals(dpadFrame)) {
                canvas.drawRoundRect(cx - arm, cy - arm * .18f, cx + arm, cy + size,
                        arm * .28f, arm * .28f, paint);
            } else if ("L\n".equals(dpadFrame)) {
                canvas.drawRoundRect(cx - size, cy - arm, cx + arm * .18f, cy + arm,
                        arm * .28f, arm * .28f, paint);
            } else if ("R\n".equals(dpadFrame)) {
                canvas.drawRoundRect(cx - arm * .18f, cy - arm, cx + size, cy + arm,
                        arm * .28f, arm * .28f, paint);
            }
            canvas.restore();
            paint.setShader(null);
            paint.clearShadowLayer();
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, size * .025f));
        paint.setColor(outlineColor);
        canvas.drawRoundRect(cx - arm, cy - size, cx + arm, cy + size,
                arm * .28f, arm * .28f, paint);
        canvas.drawRoundRect(cx - size, cy - arm, cx + size, cy + arm,
                arm * .28f, arm * .28f, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setTextAlign(Paint.Align.CENTER);
        drawDpadArrow(canvas, "▲", cx, cy - size * .53f,
                "F\n".equals(dpadFrame), size);
        drawDpadArrow(canvas, "▼", cx, cy + size * .72f,
                "B\n".equals(dpadFrame), size);
        drawDpadArrow(canvas, "◀", cx - size * .62f, cy + size * .12f,
                "L\n".equals(dpadFrame), size);
        drawDpadArrow(canvas, "▶", cx + size * .62f, cy + size * .12f,
                "R\n".equals(dpadFrame), size);
    }

    private void drawDpadArrow(Canvas canvas, String arrow, float x, float y,
                               boolean active, float size) {
        paint.setTextSize(size * (active ? .40f : .34f));
        paint.setColor(active ? contrastColor(primaryColor)
                : ColorUtils.setAlphaComponent(contrastColor(controlColor), 210));
        canvas.drawText(arrow, x, y, paint);
    }

    private void drawActions(Canvas canvas, float cx, float cy, float spread) {
        drawAction(canvas, cx, cy + spread, "A", Control.A, actionAColor);
        drawAction(canvas, cx + spread, cy, "B", Control.B, actionBColor);
        drawAction(canvas, cx - spread, cy, "X", Control.X, actionXColor);
        drawAction(canvas, cx, cy - spread, "Y", Control.Y, actionYColor);
    }

    private void drawAction(Canvas canvas, float cx, float cy, String label,
                            Control control, int accent) {
        float radius = getHeight() * .062f;
        boolean pressed = pressedActions.contains(control);
        float drawCy = cy + (pressed ? radius * .075f : 0f);
        if (pressed) {
            paint.setShadowLayer(radius * .42f, 0, 0,
                    ColorUtils.setAlphaComponent(accent, 160));
            paint.setColor(ColorUtils.setAlphaComponent(accent, 90));
            canvas.drawCircle(cx, cy, radius * 1.20f, paint);
            paint.clearShadowLayer();
        }
        float drawnRadius = radius * (pressed ? .94f : 1f);
        int buttonBase = ColorUtils.blendARGB(controlColor, accent, pressed ? .58f : .20f);
        paint.setShadowLayer(radius * .16f, 0f, radius * (pressed ? .035f : .10f), 0x66000000);
        paint.setShader(new RadialGradient(cx - radius * .28f, drawCy - radius * .35f,
                radius * 1.45f,
                new int[]{highlight(buttonBase, .30f), buttonBase, shade(buttonBase, .28f)},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, drawCy, drawnRadius, paint);
        paint.setShader(null);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, radius * (pressed ? .14f : .09f)));
        paint.setColor(ColorUtils.setAlphaComponent(accent, pressed ? 255 : 220));
        canvas.drawCircle(cx, drawCy, radius * .78f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * (pressed ? .84f : .76f));
        paint.setColor(contrastColor(buttonBase));
        canvas.drawText(label, cx, drawCy - (paint.ascent() + paint.descent()) / 2f, paint);
    }

    private void drawStick(Canvas canvas, float cx, float cy, float radius,
                           float x, float y, String label, Control control) {
        boolean active = isControlActive(control);
        if (active) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(radius * .12f);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, 150));
            canvas.drawCircle(cx, cy, radius * 1.04f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        float knobX = cx + x * radius * .58f;
        float knobY = cy + y * radius * .58f;
        if (active && (x != 0f || y != 0f)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(radius * .08f);
            paint.setColor(primaryColor);
            canvas.drawLine(cx, cy, knobX, knobY, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        Bitmap knob = "L".equals(label)
                ? (active ? leftStickActive : leftStickIdle)
                : (active ? rightStickActive : rightStickIdle);
        if (knob != null) {
            canvas.drawBitmap(knob, knobX - knob.getWidth() / 2f,
                    knobY - knob.getHeight() / 2f, null);
        }
    }

    private Bitmap buildStickKnob(float radius, String label, boolean active) {
        int size = Math.max(1, (int) Math.ceil(radius * 1.45f));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float center = size / 2f;
        float knobRadius = radius * .52f;
        int knobColor = active
                ? ColorUtils.blendARGB(controlInnerColor, primaryColor, .38f)
                : controlInnerColor;
        paint.setShadowLayer(radius * .12f, 0f, radius * .055f, 0x66000000);
        paint.setShader(new RadialGradient(center - knobRadius * .30f,
                center - knobRadius * .38f, knobRadius * 1.45f,
                new int[]{highlight(knobColor, .28f), knobColor, shade(knobColor, .30f)},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(center, center, knobRadius, paint);
        paint.setShader(null);
        paint.clearShadowLayer();

        drawStickTexture(canvas, center, center, knobRadius, active);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * .36f);
        paint.setColor(contrastColor(knobColor));
        canvas.drawText(label, center,
                center - (paint.ascent() + paint.descent()) / 2f, paint);
        return bitmap;
    }

    private void drawStickBase(Canvas canvas, float cx, float cy, float radius) {
        paint.setShadowLayer(radius * .17f, 0f, radius * .08f, 0x5C000000);
        paint.setShader(new RadialGradient(cx - radius * .25f, cy - radius * .30f,
                radius * 1.35f,
                new int[]{highlight(controlColor, .24f), controlColor, shade(controlColor, .25f)},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * .075f);
        paint.setColor(outlineColor);
        canvas.drawCircle(cx, cy, radius * .80f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawStickTexture(Canvas canvas, float cx, float cy, float radius,
                                  boolean active) {
        stickTextureClip.reset();
        stickTextureClip.addCircle(cx, cy, radius * .86f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(stickTextureClip);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.2f, radius * .026f));
        paint.setColor(ColorUtils.setAlphaComponent(
                active ? contrastColor(primaryColor) : contrastColor(controlInnerColor), 34));
        float spacing = radius * .22f;
        for (float offset = -radius * 1.5f; offset <= radius * 1.5f; offset += spacing) {
            canvas.drawLine(cx - radius, cy + offset - radius,
                    cx + radius, cy + offset + radius, paint);
            canvas.drawLine(cx - radius, cy + offset + radius,
                    cx + radius, cy + offset - radius, paint);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, radius * .045f));
        paint.setColor(ColorUtils.setAlphaComponent(highlight(controlInnerColor, .40f), 125));
        canvas.drawCircle(cx, cy, radius * .88f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!controlsEnabled) {
            return true;
        }
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                Control control = findControl(event.getX(actionIndex), event.getY(actionIndex));
                if (control != Control.NONE && !isControlActive(control)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    activePointers.put(pointerId, control);
                    updateControl(control, event.getX(actionIndex), event.getY(actionIndex));
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int index = 0; index < event.getPointerCount(); index++) {
                    Control active = activePointers.get(event.getPointerId(index), Control.NONE);
                    if (active != Control.NONE) {
                        updateControl(active, event.getX(index), event.getY(index));
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                releaseControl(activePointers.get(pointerId, Control.NONE));
                activePointers.remove(pointerId);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                resetControls(true);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private Control findControl(float x, float y) {
        float width = getWidth();
        float height = getHeight();
        Control nearest = Control.NONE;
        float nearestScore = 1f;

        float score = distance(x, y, width * .25f, height * .73f)
                / (height * STICK_HIT_RADIUS_RATIO);
        if (score < nearestScore) {
            nearest = Control.LEFT_STICK;
            nearestScore = score;
        }
        score = distance(x, y, width * .75f, height * .73f)
                / (height * STICK_HIT_RADIUS_RATIO);
        if (score < nearestScore) {
            nearest = Control.RIGHT_STICK;
            nearestScore = score;
        }
        score = distance(x, y, width * .18f, height * .35f) / (height * .27f);
        if (score < nearestScore) {
            nearest = Control.DPAD;
            nearestScore = score;
        }
        float cx = width * .82f;
        float cy = height * .35f;
        float spread = height * .125f;
        float hit = height * .09f;
        score = distance(x, y, cx, cy + spread) / hit;
        if (score < nearestScore) {
            nearest = Control.A;
            nearestScore = score;
        }
        score = distance(x, y, cx + spread, cy) / hit;
        if (score < nearestScore) {
            nearest = Control.B;
            nearestScore = score;
        }
        score = distance(x, y, cx - spread, cy) / hit;
        if (score < nearestScore) {
            nearest = Control.X;
            nearestScore = score;
        }
        score = distance(x, y, cx, cy - spread) / hit;
        if (score < nearestScore) nearest = Control.Y;
        return nearest;
    }

    private void updateControl(Control control, float x, float y) {
        if (control == Control.DPAD) {
            updateDpad(x, y);
        } else if (control == Control.LEFT_STICK) {
            updateStick(true, x, y);
        } else if (control == Control.RIGHT_STICK) {
            updateStick(false, x, y);
        } else {
            if (pressedActions.add(control)) {
                send(actionFrame(control, true), control.name());
            }
        }
        requestControlRedraw(control);
    }

    private void updateDpad(float x, float y) {
        float dx = x - getWidth() * .18f;
        float dy = y - getHeight() * .35f;
        String next;
        String label;
        if (Math.abs(dx) > Math.abs(dy)) {
            next = dx < 0 ? "L\n" : "R\n";
            label = dx < 0 ? "十字键 左" : "十字键 右";
        } else {
            next = dy < 0 ? "F\n" : "B\n";
            label = dy < 0 ? "十字键 上" : "十字键 下";
        }
        if (!next.equals(dpadFrame)) {
            if (!dpadFrame.isEmpty()) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            dpadFrame = next;
            send(next, label);
        }
    }

    private void updateStick(boolean left, float x, float y) {
        cancelStickReturn(left);
        float cx = getWidth() * (left ? .25f : .75f);
        float cy = getHeight() * .73f;
        float radius = getHeight() * STICK_MAPPING_RADIUS_RATIO;
        float nx = (x - cx) / radius;
        float ny = (cy - y) / radius;
        float magnitude = (float) Math.hypot(nx, ny);
        if (magnitude > 1f) {
            nx /= magnitude;
            ny /= magnitude;
        }
        if (magnitude < STICK_DEAD_ZONE) {
            nx = 0f;
            ny = 0f;
        }
        int valueX = Math.round(nx * 32767f);
        int valueY = Math.round(ny * 32767f);
        if (left) {
            leftX = nx;
            leftY = -ny;
            if (Math.abs(valueX - lastLeftX) >= STICK_OUTPUT_STEP
                    || Math.abs(valueY - lastLeftY) >= STICK_OUTPUT_STEP) {
                lastLeftX = valueX;
                lastLeftY = valueY;
                send("JL," + valueX + "," + valueY + "\n", "左摇杆 " + valueX + "," + valueY);
            }
        } else {
            rightX = nx;
            rightY = -ny;
            if (Math.abs(valueX - lastRightX) >= STICK_OUTPUT_STEP
                    || Math.abs(valueY - lastRightY) >= STICK_OUTPUT_STEP) {
                lastRightX = valueX;
                lastRightY = valueY;
                send("JR," + valueX + "," + valueY + "\n", "右摇杆 " + valueX + "," + valueY);
            }
        }
    }

    private void releaseControl(Control control) {
        if (control == Control.DPAD) {
            dpadFrame = "";
            send("S\n", "十字键 中立");
        } else if (control == Control.LEFT_STICK) {
            lastLeftX = lastLeftY = 0;
            send("JL,0,0\n", "左摇杆回中");
            animateStickReturn(true);
        } else if (control == Control.RIGHT_STICK) {
            lastRightX = lastRightY = 0;
            send("JR,0,0\n", "右摇杆回中");
            animateStickReturn(false);
        } else if (control != Control.NONE) {
            send(actionFrame(control, false), control.name() + " 松开");
            pressedActions.remove(control);
        }
        requestControlRedraw(control);
    }

    private String actionFrame(Control control, boolean pressed) {
        return control.name() + "," + (pressed ? "1" : "0") + "\n";
    }

    private void resetControls(boolean notifyDevice) {
        boolean hadDpad = !dpadFrame.isEmpty();
        boolean hadLeft = leftX != 0f || leftY != 0f;
        boolean hadRight = rightX != 0f || rightY != 0f;
        EnumSet<Control> actions = EnumSet.copyOf(pressedActions);
        activePointers.clear();
        cancelStickReturn(true);
        cancelStickReturn(false);
        dpadFrame = "";
        leftX = leftY = rightX = rightY = 0f;
        lastLeftX = lastLeftY = lastRightX = lastRightY = 0;
        pressedActions.clear();
        if (notifyDevice) {
            if (hadDpad) send("S\n", "十字键 中立");
            if (hadLeft) send("JL,0,0\n", "左摇杆回中");
            if (hadRight) send("JR,0,0\n", "右摇杆回中");
            for (Control action : actions) {
                send(actionFrame(action, false), action.name() + " 松开");
            }
        }
        postInvalidateOnAnimation();
    }

    private void animateStickReturn(boolean left) {
        cancelStickReturn(left);
        float startX = left ? leftX : rightX;
        float startY = left ? leftY : rightY;
        if (startX == 0f && startY == 0f) return;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(140L);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(animation -> {
            float remaining = 1f - (float) animation.getAnimatedValue();
            if (left) {
                leftX = startX * remaining;
                leftY = startY * remaining;
            } else {
                rightX = startX * remaining;
                rightY = startY * remaining;
            }
            requestControlRedraw(left ? Control.LEFT_STICK : Control.RIGHT_STICK);
        });
        if (left) leftReturnAnimator = animator;
        else rightReturnAnimator = animator;
        animator.start();
    }

    private void cancelStickReturn(boolean left) {
        ValueAnimator animator = left ? leftReturnAnimator : rightReturnAnimator;
        if (animator != null) animator.cancel();
        if (left) leftReturnAnimator = null;
        else rightReturnAnimator = null;
    }

    private boolean isControlActive(Control control) {
        for (int index = 0; index < activePointers.size(); index++) {
            if (activePointers.valueAt(index) == control) return true;
        }
        return false;
    }

    private void requestControlRedraw(Control control) {
        float width = getWidth();
        float height = getHeight();
        float cx;
        float cy;
        float radius;
        if (control == Control.LEFT_STICK || control == Control.RIGHT_STICK) {
            cx = width * (control == Control.LEFT_STICK ? .25f : .75f);
            cy = height * .73f;
            radius = height * .19f;
        } else if (control == Control.DPAD) {
            cx = width * .18f;
            cy = height * .35f;
            radius = height * .25f;
        } else if (control == Control.A || control == Control.B
                || control == Control.X || control == Control.Y) {
            cx = width * .82f;
            cy = height * .35f;
            radius = height * .24f;
        } else {
            postInvalidateOnAnimation();
            return;
        }
        postInvalidateOnAnimation(
                (int) Math.floor(cx - radius),
                (int) Math.floor(cy - radius),
                (int) Math.ceil(cx + radius),
                (int) Math.ceil(cy + radius));
    }

    private void send(String frame, String label) {
        if (commandListener != null) {
            commandListener.onCommand(frame, label);
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static int contrastColor(int background) {
        return ColorUtils.calculateLuminance(background) > .48 ? Color.BLACK : Color.WHITE;
    }

    private static int physicalControlColor(int surface, int onSurface) {
        float surfaceLuminance = (float) ColorUtils.calculateLuminance(surface);
        float amount = surfaceLuminance > .48f ? .80f : .23f;
        return ColorUtils.blendARGB(surface, onSurface, amount);
    }

    private static int harmonizedAccent(int accent, int primary) {
        return ColorUtils.blendARGB(accent, primary, .12f);
    }

    private static int highlight(int color, float amount) {
        return ColorUtils.blendARGB(color, Color.WHITE, amount);
    }

    private static int shade(int color, float amount) {
        return ColorUtils.blendARGB(color, Color.BLACK, amount);
    }
}
