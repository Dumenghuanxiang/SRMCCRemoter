package com.example.srmremoter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class BluetoothSppClient {
    static final UUID SERIAL_PORT_PROFILE =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int WRITE_QUEUE_CAPACITY = 64;
    private static final byte[] CONTROL_SIGNAL = new byte[0];

    interface Listener {
        void onConnecting(BluetoothDevice device);
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onError(String message);
        void onReceived(byte[] bytes);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedBlockingDeque<byte[]> writeQueue =
            new LinkedBlockingDeque<>(WRITE_QUEUE_CAPACITY);
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<byte[]> latestControlFrame = new AtomicReference<>();

    private volatile BluetoothSocket socket;
    private volatile boolean connected;
    private volatile boolean userDisconnect;
    private BluetoothDevice device;

    BluetoothSppClient(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    synchronized void connect(BluetoothDevice target) {
        closeSocket();
        int currentGeneration = generation.incrementAndGet();
        device = target;
        userDisconnect = false;
        listener.onConnecting(target);
        Thread connectionThread = new Thread(
                () -> connectInBackground(target, currentGeneration), "spp-connect");
        connectionThread.start();
    }

    void send(byte[] bytes) {
        if (!connected || bytes == null || bytes.length == 0) return;
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        if (!writeQueue.offerLast(copy)) {
            writeQueue.pollFirst();
            writeQueue.offerLast(copy);
        }
    }

    void sendControl(byte[] bytes) {
        if (!connected || bytes == null || bytes.length == 0) return;
        byte[] previous = latestControlFrame.getAndSet(bytes);
        if (previous == null) writeQueue.offerLast(CONTROL_SIGNAL);
    }

    void clearPendingControl() {
        latestControlFrame.set(null);
        writeQueue.remove(CONTROL_SIGNAL);
    }

    synchronized void disconnect() {
        userDisconnect = true;
        generation.incrementAndGet();
        closeSocket();
        mainHandler.post(listener::onDisconnected);
    }

    synchronized void close() {
        userDisconnect = true;
        generation.incrementAndGet();
        closeSocket();
    }

    @SuppressLint("MissingPermission")
    private void connectInBackground(BluetoothDevice target, int expectedGeneration) {
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // The caller already checked runtime permissions.
        }

        List<String> errors = new ArrayList<>();
        BluetoothSocket connectedSocket = tryConnect(target, true, errors, expectedGeneration);
        if (connectedSocket == null && isCurrent(expectedGeneration)) {
            connectedSocket = tryConnect(target, false, errors, expectedGeneration);
        }
        if (connectedSocket == null) {
            if (isCurrent(expectedGeneration)) {
                notifyError(String.join(" | ", errors), expectedGeneration);
            }
            return;
        }

        synchronized (this) {
            if (!isCurrent(expectedGeneration)) {
                closeQuietly(connectedSocket);
                return;
            }
            socket = connectedSocket;
            connected = true;
            writeQueue.clear();
        }
        mainHandler.post(() -> {
            if (isCurrent(expectedGeneration)) listener.onConnected(target);
        });

        BluetoothSocket activeSocket = connectedSocket;
        new Thread(() -> readLoop(activeSocket, expectedGeneration), "spp-reader").start();
        new Thread(() -> writeLoop(activeSocket, expectedGeneration), "spp-writer").start();
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket tryConnect(BluetoothDevice target, boolean secure,
                                       List<String> errors, int expectedGeneration) {
        BluetoothSocket candidate = null;
        String label = secure ? "secure SPP" : "insecure SPP";
        try {
            candidate = secure
                    ? target.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE)
                    : target.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE);
            socket = candidate;
            candidate.connect();
            if (!isCurrent(expectedGeneration)) {
                closeQuietly(candidate);
                return null;
            }
            return candidate;
        } catch (IOException | SecurityException error) {
            errors.add(label + ": " + conciseMessage(error));
            closeQuietly(candidate);
            if (socket == candidate) socket = null;
            return null;
        }
    }

    private void readLoop(BluetoothSocket activeSocket, int expectedGeneration) {
        byte[] buffer = new byte[256];
        try {
            InputStream input = activeSocket.getInputStream();
            while (isCurrent(expectedGeneration) && connected) {
                int count = input.read(buffer);
                if (count < 0) throw new IOException("remote stream closed");
                if (count == 0) continue;
                byte[] copy = Arrays.copyOf(buffer, count);
                mainHandler.post(() -> {
                    if (isCurrent(expectedGeneration)) listener.onReceived(copy);
                });
            }
        } catch (IOException error) {
            if (isCurrent(expectedGeneration) && !userDisconnect) {
                notifyError("SPP read: " + conciseMessage(error), expectedGeneration);
            }
        }
    }

    private void writeLoop(BluetoothSocket activeSocket, int expectedGeneration) {
        try {
            OutputStream output = activeSocket.getOutputStream();
            while (isCurrent(expectedGeneration) && connected) {
                byte[] bytes = writeQueue.pollFirst(500L, TimeUnit.MILLISECONDS);
                if (bytes == null) continue;
                boolean controlSignal = bytes == CONTROL_SIGNAL;
                if (controlSignal) {
                    bytes = latestControlFrame.getAndSet(null);
                    if (bytes == null) continue;
                }
                output.write(bytes);
                if (!controlSignal) {
                    byte[] currentControl = latestControlFrame.getAndSet(null);
                    if (currentControl != null) output.write(currentControl);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            if (isCurrent(expectedGeneration) && !userDisconnect) {
                notifyError("SPP write: " + conciseMessage(error), expectedGeneration);
            }
        }
    }

    private boolean isCurrent(int expectedGeneration) {
        return generation.get() == expectedGeneration;
    }

    private void notifyError(String message, int expectedGeneration) {
        int errorGeneration;
        synchronized (this) {
            if (!isCurrent(expectedGeneration)) return;
            errorGeneration = generation.incrementAndGet();
            closeSocket();
        }
        mainHandler.post(() -> {
            if (generation.get() == errorGeneration) listener.onError(message);
        });
    }

    private synchronized void closeSocket() {
        connected = false;
        writeQueue.clear();
        latestControlFrame.set(null);
        BluetoothSocket oldSocket = socket;
        socket = null;
        closeQuietly(oldSocket);
    }

    private static void closeQuietly(BluetoothSocket candidate) {
        if (candidate == null) return;
        try {
            candidate.close();
        } catch (IOException ignored) {
            // Closing is best effort and is also used to unblock connect/read.
        }
    }

    private static String conciseMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
