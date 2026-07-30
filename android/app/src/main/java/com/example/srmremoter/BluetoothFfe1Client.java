package com.example.srmremoter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

final class BluetoothFfe1Client {
    static final UUID SERIAL_CHARACTERISTIC =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int ATT_PAYLOAD = 20;

    interface Listener {
        void onConnecting(BluetoothDevice device);
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onError(String message);
        void onReceived(byte[] bytes);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread writeThread = new HandlerThread("gatt-writer");
    private final Handler writeHandler;
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private byte[] latestControlFrame;

    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic serialCharacteristic;
    private boolean writePending;
    private int pendingWriteType;
    private int writeGeneration;
    private int writeRetryCount;
    private int connectionGeneration;
    private boolean highPriorityRequested;

    BluetoothFfe1Client(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        writeThread.start();
        writeHandler = new Handler(writeThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    synchronized void connect(BluetoothDevice target) {
        int expectedGeneration = ++connectionGeneration;
        closeGattLocked();
        device = target;
        listener.onConnecting(target);
        try {
            gatt = target.connectGatt(context, false,
                    createCallback(expectedGeneration), BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                notifyError("connectGatt returned null", expectedGeneration);
            }
        } catch (RuntimeException error) {
            notifyError("connectGatt: " + conciseMessage(error), expectedGeneration);
        }
    }

    synchronized void send(byte[] bytes) {
        if (serialCharacteristic == null || gatt == null
                || bytes == null || bytes.length == 0) return;
        for (int offset = 0; offset < bytes.length; offset += ATT_PAYLOAD) {
            int end = Math.min(bytes.length, offset + ATT_PAYLOAD);
            writeQueue.add(Arrays.copyOfRange(bytes, offset, end));
        }
        pumpWrites(connectionGeneration);
    }

    synchronized void sendControl(byte[] bytes) {
        if (serialCharacteristic == null || gatt == null
                || bytes == null || bytes.length == 0) return;
        if (bytes.length > ATT_PAYLOAD) {
            send(bytes);
            return;
        }
        latestControlFrame = bytes;
        pumpWrites(connectionGeneration);
    }

    @SuppressLint("MissingPermission")
    synchronized void setControlStreaming(boolean streaming) {
        if (!streaming) latestControlFrame = null;
        if (gatt == null || highPriorityRequested == streaming) return;
        int expectedGeneration = connectionGeneration;
        try {
            if (gatt.requestConnectionPriority(streaming
                    ? BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    : BluetoothGatt.CONNECTION_PRIORITY_BALANCED)) {
                highPriorityRequested = streaming;
            }
        } catch (RuntimeException error) {
            notifyError("GATT connection priority: " + conciseMessage(error),
                    expectedGeneration);
        }
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        int disconnectedGeneration;
        synchronized (this) {
            disconnectedGeneration = ++connectionGeneration;
            BluetoothGatt activeGatt = gatt;
            if (activeGatt != null) {
                try {
                    activeGatt.disconnect();
                } catch (RuntimeException ignored) {
                    // Closing below is sufficient to invalidate a broken platform connection.
                }
            }
            closeGattLocked();
        }
        mainHandler.post(() -> {
            if (isCurrent(disconnectedGeneration)) listener.onDisconnected();
        });
    }

    synchronized void close() {
        connectionGeneration++;
        closeGattLocked();
        writeThread.quitSafely();
    }

    private BluetoothGattCallback createCallback(int expectedGeneration) {
        return new BluetoothGattCallback() {
            @Override
            @SuppressLint("MissingPermission")
            public void onConnectionStateChange(BluetoothGatt callbackGatt, int status,
                                                int newState) {
                if (!isCurrent(callbackGatt, expectedGeneration)) return;
                try {
                    if (status == BluetoothGatt.GATT_SUCCESS
                            && newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!callbackGatt.discoverServices()) {
                            notifyError("GATT service discovery could not start",
                                    expectedGeneration);
                        }
                    } else if (status != BluetoothGatt.GATT_SUCCESS
                            || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        notifyError("GATT disconnected, status=" + status,
                                expectedGeneration);
                    }
                } catch (RuntimeException error) {
                    notifyError("GATT connection state: " + conciseMessage(error),
                            expectedGeneration);
                }
            }

            @Override
            @SuppressLint("MissingPermission")
            public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
                if (!isCurrent(callbackGatt, expectedGeneration)) return;
                try {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        notifyError("GATT service discovery failed, status=" + status,
                                expectedGeneration);
                        return;
                    }
                    BluetoothGattCharacteristic characteristic =
                            findSerialCharacteristic(callbackGatt);
                    if (characteristic == null) {
                        notifyError("FFE1 characteristic not found", expectedGeneration);
                        return;
                    }
                    int properties = characteristic.getProperties();
                    if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                        notifyError("FFE1 characteristic is not writable", expectedGeneration);
                        return;
                    }
                    synchronized (BluetoothFfe1Client.this) {
                        if (!isCurrentLocked(callbackGatt, expectedGeneration)) return;
                        serialCharacteristic = characteristic;
                    }
                    boolean canNotify = (properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                    BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD);
                    if (canNotify && cccd != null) {
                        if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
                            notifyError("FFE1 notification enable failed", expectedGeneration);
                            return;
                        }
                        byte[] value = (properties
                                & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                        if (!writeDescriptor(callbackGatt, cccd, value)) {
                            notifyError("FFE1 notification setup failed", expectedGeneration);
                        }
                    } else {
                        notifyConnected(expectedGeneration);
                    }
                } catch (RuntimeException error) {
                    notifyError("GATT service setup: " + conciseMessage(error),
                            expectedGeneration);
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt callbackGatt,
                                          BluetoothGattDescriptor descriptor, int status) {
                if (!isCurrent(callbackGatt, expectedGeneration)
                        || !CCCD.equals(descriptor.getUuid())) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notifyConnected(expectedGeneration);
                } else {
                    notifyError("FFE1 notification status=" + status, expectedGeneration);
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {
                synchronized (BluetoothFfe1Client.this) {
                    if (!isCurrentLocked(callbackGatt, expectedGeneration)
                            || !SERIAL_CHARACTERISTIC.equals(characteristic.getUuid())) return;
                    if (pendingWriteType
                            == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) return;
                    writePending = false;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        notifyError("FFE1 write status=" + status, expectedGeneration);
                        return;
                    }
                    pumpWrites(expectedGeneration);
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                                BluetoothGattCharacteristic characteristic,
                                                byte[] value) {
                deliverChanged(callbackGatt, characteristic, value, expectedGeneration);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                                BluetoothGattCharacteristic characteristic) {
                try {
                    deliverChanged(callbackGatt, characteristic, characteristic.getValue(),
                            expectedGeneration);
                } catch (RuntimeException error) {
                    notifyError("FFE1 notification: " + conciseMessage(error),
                            expectedGeneration);
                }
            }
        };
    }

    private BluetoothGattCharacteristic findSerialCharacteristic(BluetoothGatt callbackGatt) {
        for (BluetoothGattService service : callbackGatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (SERIAL_CHARACTERISTIC.equals(characteristic.getUuid())) return characteristic;
            }
        }
        return null;
    }

    private void deliverChanged(BluetoothGatt callbackGatt,
                                BluetoothGattCharacteristic characteristic, byte[] value,
                                int expectedGeneration) {
        if (!isCurrent(callbackGatt, expectedGeneration)
                || !SERIAL_CHARACTERISTIC.equals(characteristic.getUuid())
                || value == null || value.length == 0) return;
        byte[] copy = Arrays.copyOf(value, value.length);
        mainHandler.post(() -> {
            if (isCurrent(expectedGeneration)) listener.onReceived(copy);
        });
    }

    @SuppressLint("MissingPermission")
    private synchronized void pumpWrites(int expectedGeneration) {
        if (!isCurrentLocked(expectedGeneration) || writePending
                || gatt == null || serialCharacteristic == null) return;
        byte[] value = writeQueue.pollFirst();
        boolean controlFrame = false;
        if (value == null && latestControlFrame != null) {
            value = latestControlFrame;
            latestControlFrame = null;
            controlFrame = true;
        }
        if (value == null) return;
        int properties = serialCharacteristic.getProperties();
        boolean supportsNoResponse = (properties
                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int writeType = controlFrame && supportsNoResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        int result;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                result = gatt.writeCharacteristic(serialCharacteristic, value, writeType);
            } else {
                result = writeLegacy(gatt, serialCharacteristic, value, writeType)
                        ? BluetoothStatusCodes.SUCCESS : BluetoothStatusCodes.ERROR_UNKNOWN;
            }
        } catch (RuntimeException error) {
            notifyError("FFE1 write: " + conciseMessage(error), expectedGeneration);
            return;
        }
        if (result != BluetoothStatusCodes.SUCCESS) {
            boolean retryable = result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                    || (Build.VERSION.SDK_INT < 33
                    && result == BluetoothStatusCodes.ERROR_UNKNOWN);
            if (retryable && writeRetryCount < 5) {
                writeRetryCount++;
                if (controlFrame) {
                    if (latestControlFrame == null) latestControlFrame = value;
                } else {
                    writeQueue.addFirst(value);
                }
                writeHandler.postDelayed(() -> pumpWrites(expectedGeneration), 30L);
                return;
            }
            notifyError("FFE1 write rejected, status=" + result, expectedGeneration);
            return;
        }
        writeRetryCount = 0;
        writePending = true;
        pendingWriteType = writeType;
        int expectedWriteGeneration = ++writeGeneration;
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writeHandler.postDelayed(() -> {
                synchronized (BluetoothFfe1Client.this) {
                    if (!isCurrentLocked(expectedGeneration) || !writePending
                            || writeGeneration != expectedWriteGeneration) return;
                    writePending = false;
                    pumpWrites(expectedGeneration);
                }
            }, 12L);
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private boolean writeLegacy(BluetoothGatt activeGatt,
                                BluetoothGattCharacteristic characteristic,
                                byte[] value, int writeType) {
        characteristic.setWriteType(writeType);
        characteristic.setValue(value);
        return activeGatt.writeCharacteristic(characteristic);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private boolean writeDescriptor(BluetoothGatt callbackGatt,
                                    BluetoothGattDescriptor descriptor, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            return callbackGatt.writeDescriptor(descriptor, value)
                    == BluetoothStatusCodes.SUCCESS;
        }
        descriptor.setValue(value);
        return callbackGatt.writeDescriptor(descriptor);
    }

    private void notifyConnected(int expectedGeneration) {
        BluetoothDevice connectedDevice;
        synchronized (this) {
            if (!isCurrentLocked(expectedGeneration)) return;
            connectedDevice = device;
        }
        if (connectedDevice == null) return;
        mainHandler.post(() -> {
            if (isCurrent(expectedGeneration)) listener.onConnected(connectedDevice);
        });
    }

    private void notifyError(String message, int expectedGeneration) {
        int errorGeneration;
        synchronized (this) {
            if (!isCurrentLocked(expectedGeneration)) return;
            errorGeneration = ++connectionGeneration;
            closeGattLocked();
        }
        mainHandler.post(() -> {
            if (isCurrent(errorGeneration)) listener.onError(message);
        });
    }

    private synchronized boolean isCurrent(BluetoothGatt callbackGatt,
                                           int expectedGeneration) {
        return isCurrentLocked(callbackGatt, expectedGeneration);
    }

    private synchronized boolean isCurrent(int expectedGeneration) {
        return isCurrentLocked(expectedGeneration);
    }

    private boolean isCurrentLocked(BluetoothGatt callbackGatt, int expectedGeneration) {
        return isCurrentLocked(expectedGeneration) && callbackGatt == gatt;
    }

    private boolean isCurrentLocked(int expectedGeneration) {
        return connectionGeneration == expectedGeneration;
    }

    @SuppressLint("MissingPermission")
    private void closeGattLocked() {
        BluetoothGatt oldGatt = gatt;
        writeHandler.removeCallbacksAndMessages(null);
        gatt = null;
        serialCharacteristic = null;
        writeQueue.clear();
        latestControlFrame = null;
        writePending = false;
        pendingWriteType = 0;
        writeGeneration++;
        writeRetryCount = 0;
        highPriorityRequested = false;
        if (oldGatt != null) {
            try {
                oldGatt.close();
            } catch (RuntimeException ignored) {
                // Some vendor Bluetooth stacks throw while tearing down a failed GATT.
            }
        }
    }

    private static String conciseMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
