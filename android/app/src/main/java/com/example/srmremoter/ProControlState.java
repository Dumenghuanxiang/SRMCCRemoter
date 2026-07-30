package com.example.srmremoter;

final class ProControlState {
    static final int BUTTON_A = 1 << 0;
    static final int BUTTON_B = 1 << 1;
    static final int BUTTON_X = 1 << 2;
    static final int BUTTON_Y = 1 << 3;
    static final int BUTTON_L1 = 1 << 4;
    static final int BUTTON_R1 = 1 << 5;
    static final int BUTTON_L2 = 1 << 6;
    static final int BUTTON_R2 = 1 << 7;
    static final int BUTTON_THUMB_L = 1 << 8;
    static final int BUTTON_THUMB_R = 1 << 9;
    static final int BUTTON_START = 1 << 10;
    static final int BUTTON_SELECT = 1 << 11;
    static final int BUTTON_MODE = 1 << 12;
    static final int BUTTON_DPAD_UP = 1 << 13;
    static final int BUTTON_DPAD_DOWN = 1 << 14;
    static final int BUTTON_DPAD_LEFT = 1 << 15;
    static final int BUTTON_DPAD_RIGHT = 1 << 16;
    static final int VALID_BUTTON_MASK = (1 << 17) - 1;

    final int leftX;
    final int leftY;
    final int rightX;
    final int rightY;
    final int leftTrigger;
    final int rightTrigger;
    final int buttons;

    ProControlState(int leftX, int leftY, int rightX, int rightY,
                    int leftTrigger, int rightTrigger, int buttons) {
        this.leftX = leftX;
        this.leftY = leftY;
        this.rightX = rightX;
        this.rightY = rightY;
        this.leftTrigger = leftTrigger;
        this.rightTrigger = rightTrigger;
        this.buttons = buttons;
    }

    static ProControlState neutral() {
        return new ProControlState(0, 0, 0, 0, 0, 0, 0);
    }

    static int quantizeSigned(float value, float minimum, float maximum,
                              float flat, boolean invert) {
        float center = (minimum + maximum) * 0.5f;
        float offset = value - center;
        if (Math.abs(offset) <= Math.max(0f, flat)) return 0;
        float extent = offset < 0f ? center - minimum : maximum - center;
        if (extent <= 0f) return 0;
        float normalized = Math.max(-1f, Math.min(1f, offset / extent));
        if (invert) normalized = -normalized;
        return Math.round(normalized * (normalized < 0f ? 512f : 511f));
    }

    static int quantizeTrigger(float value, float minimum, float maximum, float flat) {
        float extent = maximum - minimum;
        if (extent <= 0f || value <= minimum + Math.max(0f, flat)) return 0;
        float normalized = Math.max(0f, Math.min(1f, (value - minimum) / extent));
        return Math.round(normalized * 255f);
    }
}
