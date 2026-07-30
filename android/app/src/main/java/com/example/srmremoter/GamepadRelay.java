package com.example.srmremoter;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

final class GamepadRelay implements InputManager.InputDeviceListener {
    interface Listener {
        void onActiveControllerDisconnected(String deviceName);
    }

    private static final int NO_DEVICE = -1;
    private static final float HAT_THRESHOLD = 0.5f;

    private final InputManager inputManager;
    private final Listener listener;
    private int activeDeviceId = NO_DEVICE;
    private String activeDeviceName = "";
    private int leftX;
    private int leftY;
    private int rightX;
    private int rightY;
    private int leftTrigger;
    private int rightTrigger;
    private int keyButtons;
    private int hatButtons;
    private boolean listenerRegistered;

    GamepadRelay(Context context, Listener listener) {
        inputManager = context.getSystemService(InputManager.class);
        this.listener = listener;
    }

    void register() {
        if (!listenerRegistered && inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
            listenerRegistered = true;
        }
    }

    void unregister() {
        if (listenerRegistered && inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
            listenerRegistered = false;
        }
    }

    synchronized InputDevice startFirstConnectedController() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (isController(device)) {
                activeDeviceId = deviceId;
                activeDeviceName = device.getName();
                clearState();
                return device;
            }
        }
        return null;
    }

    synchronized void stop() {
        activeDeviceId = NO_DEVICE;
        activeDeviceName = "";
        clearState();
    }

    synchronized boolean isActive() {
        return activeDeviceId != NO_DEVICE;
    }

    synchronized String getActiveDeviceName() {
        return activeDeviceName;
    }

    synchronized boolean validateActiveDevice() {
        if (activeDeviceId == NO_DEVICE) return true;
        InputDevice device = InputDevice.getDevice(activeDeviceId);
        return isController(device);
    }

    synchronized ProControlState snapshot() {
        return new ProControlState(leftX, leftY, rightX, rightY,
                leftTrigger, rightTrigger, keyButtons | hatButtons);
    }

    synchronized boolean handleMotionEvent(MotionEvent event) {
        if (event.getDeviceId() != activeDeviceId || event.getAction() != MotionEvent.ACTION_MOVE
                || !(event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || event.isFromSource(InputDevice.SOURCE_GAMEPAD))) return false;
        InputDevice device = event.getDevice();
        if (device == null) return false;

        leftX = readSignedAxis(event, device, MotionEvent.AXIS_X, -1, false);
        leftY = readSignedAxis(event, device, MotionEvent.AXIS_Y, -1, true);
        rightX = readSignedAxis(event, device, MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RX, false);
        rightY = readSignedAxis(event, device, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_RY, true);
        leftTrigger = readTrigger(event, device, MotionEvent.AXIS_LTRIGGER,
                MotionEvent.AXIS_BRAKE);
        rightTrigger = readTrigger(event, device, MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_GAS);
        updateHat(event, device);
        return true;
    }

    synchronized boolean handleKeyEvent(KeyEvent event) {
        if (event.getDeviceId() != activeDeviceId
                || !(event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                || event.isFromSource(InputDevice.SOURCE_DPAD))) return false;
        int button = buttonForKeyCode(event.getKeyCode());
        if (button == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) keyButtons |= button;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            keyButtons &= ~button;
        } else {
            return false;
        }
        return true;
    }

    private static boolean isController(InputDevice device) {
        return device != null && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }

    private static int readSignedAxis(MotionEvent event, InputDevice device,
                                      int primaryAxis, int fallbackAxis, boolean invert) {
        InputDevice.MotionRange range = device.getMotionRange(primaryAxis, event.getSource());
        int axis = primaryAxis;
        if (range == null && fallbackAxis >= 0) {
            range = device.getMotionRange(fallbackAxis, event.getSource());
            axis = fallbackAxis;
        }
        if (range == null) return 0;
        return ProControlState.quantizeSigned(event.getAxisValue(axis),
                range.getMin(), range.getMax(), range.getFlat(), invert);
    }

    private static int readTrigger(MotionEvent event, InputDevice device,
                                   int primaryAxis, int fallbackAxis) {
        InputDevice.MotionRange range = device.getMotionRange(primaryAxis, event.getSource());
        int axis = primaryAxis;
        if (range == null) {
            range = device.getMotionRange(fallbackAxis, event.getSource());
            axis = fallbackAxis;
        }
        if (range == null) return 0;
        return ProControlState.quantizeTrigger(event.getAxisValue(axis),
                range.getMin(), range.getMax(), range.getFlat());
    }

    private void updateHat(MotionEvent event, InputDevice device) {
        int next = 0;
        InputDevice.MotionRange xRange = device.getMotionRange(
                MotionEvent.AXIS_HAT_X, event.getSource());
        InputDevice.MotionRange yRange = device.getMotionRange(
                MotionEvent.AXIS_HAT_Y, event.getSource());
        float x = xRange == null ? 0f : event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = yRange == null ? 0f : event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (x <= -HAT_THRESHOLD) next |= ProControlState.BUTTON_DPAD_LEFT;
        if (x >= HAT_THRESHOLD) next |= ProControlState.BUTTON_DPAD_RIGHT;
        if (y <= -HAT_THRESHOLD) next |= ProControlState.BUTTON_DPAD_UP;
        if (y >= HAT_THRESHOLD) next |= ProControlState.BUTTON_DPAD_DOWN;
        hatButtons = next;
    }

    private static int buttonForKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return ProControlState.BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B: return ProControlState.BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X: return ProControlState.BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return ProControlState.BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return ProControlState.BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return ProControlState.BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_L2: return ProControlState.BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return ProControlState.BUTTON_R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return ProControlState.BUTTON_THUMB_L;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return ProControlState.BUTTON_THUMB_R;
            case KeyEvent.KEYCODE_BUTTON_START: return ProControlState.BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return ProControlState.BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_MODE: return ProControlState.BUTTON_MODE;
            case KeyEvent.KEYCODE_DPAD_UP: return ProControlState.BUTTON_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return ProControlState.BUTTON_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return ProControlState.BUTTON_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ProControlState.BUTTON_DPAD_RIGHT;
            default: return 0;
        }
    }

    private void clearState() {
        leftX = 0;
        leftY = 0;
        rightX = 0;
        rightY = 0;
        leftTrigger = 0;
        rightTrigger = 0;
        keyButtons = 0;
        hatButtons = 0;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        // Inactive relays discover devices on the next button press.
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        String disconnectedName;
        synchronized (this) {
            if (deviceId != activeDeviceId) return;
            disconnectedName = activeDeviceName;
            stop();
        }
        listener.onActiveControllerDisconnected(disconnectedName);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        if (deviceId == activeDeviceId && !validateActiveDevice()) {
            onInputDeviceRemoved(deviceId);
        }
    }
}
