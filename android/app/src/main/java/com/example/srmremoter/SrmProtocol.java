package com.example.srmremoter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class SrmProtocol {
    static final int VERSION = 4;
    static final int MAX_PAYLOAD = 64;
    static final int TYPE_CONTROL = 0;
    static final int TYPE_HELLO = 1;
    static final int TYPE_DEBUG = 2;
    static final int TYPE_ACK = 3;
    static final int TYPE_ERROR = 4;
    static final int TYPE_LOG = 5;
    static final int TYPE_STATUS = 6;
    static final int TYPE_PRO_CONTROL = 7;
    private static final int SYNC_1 = 0xA5;
    private static final int SYNC_2 = 0x5A;

    static final class Frame {
        final int version;
        final int sequence;
        final int type;
        final byte[] payload;

        Frame(int version, int sequence, int type, byte[] payload) {
            this.version = version;
            this.sequence = sequence;
            this.type = type;
            this.payload = payload;
        }
    }

    static final class StreamDecoder {
        private final byte[] body = new byte[MAX_PAYLOAD + 4];
        private int state;
        private int position;
        private int expectedBodyLength;

        List<Frame> feed(byte[] bytes) {
            List<Frame> frames = new ArrayList<>();
            for (byte value : bytes) {
                int unsigned = value & 0xFF;
                if (state == 0) {
                    if (unsigned == SYNC_1) state = 1;
                    continue;
                }
                if (state == 1) {
                    if (unsigned == SYNC_2) {
                        state = 2;
                        position = 0;
                        expectedBodyLength = 0;
                    } else if (unsigned != SYNC_1) {
                        state = 0;
                    }
                    continue;
                }
                body[position++] = value;
                if (position == 1 && (unsigned >>> 4) != VERSION) {
                    resetWithPotentialSync(unsigned);
                    continue;
                }
                if (position == 3) {
                    int length = body[2] & 0xFF;
                    if (length > MAX_PAYLOAD) {
                        resetWithPotentialSync(unsigned);
                        continue;
                    }
                    expectedBodyLength = 3 + length + 1;
                }
                if (expectedBodyLength > 0 && position == expectedBodyLength) {
                    int receivedCrc = body[position - 1] & 0xFF;
                    if (crc8(body, 0, position - 1) == receivedCrc) {
                        frames.add(new Frame(body[0] >>> 4, body[1] & 0xFF,
                                body[0] & 0x0F,
                                Arrays.copyOfRange(body, 3, position - 1)));
                    }
                    state = 0;
                    position = 0;
                }
            }
            return frames;
        }

        private void resetWithPotentialSync(int value) {
            state = value == SYNC_1 ? 1 : 0;
            position = 0;
            expectedBodyLength = 0;
        }
    }

    private final AtomicInteger nextSequence = new AtomicInteger();
    private int leftX;
    private int leftY;
    private int rightX;
    private int rightY;
    private int buttons;
    private int switches;
    private int dpad;

    synchronized void applyControl(String rawControl) {
        String[] parts = rawControl.trim().split(",", -1);
        if (parts.length == 1) {
            switch (parts[0]) {
                case "F": dpad = 1; return;
                case "B": dpad = 2; return;
                case "L": dpad = 3; return;
                case "R": dpad = 4; return;
                case "S": dpad = 0; return;
                default: throw new IllegalArgumentException("Unknown control input");
            }
        }
        if (("JL".equals(parts[0]) || "JR".equals(parts[0])) && parts.length == 3) {
            int x = boundedAxis(parts[1]);
            int y = boundedAxis(parts[2]);
            if ("JL".equals(parts[0])) {
                leftX = x;
                leftY = y;
            } else {
                rightX = x;
                rightY = y;
            }
            return;
        }
        if (parts.length == 2 && "ABXY".contains(parts[0]) && parts[0].length() == 1) {
            int bit = "ABXY".indexOf(parts[0]);
            buttons = setBit(buttons, bit, binary(parts[1]));
            return;
        }
        if (parts.length == 2 && parts[0].startsWith("SW")) {
            int number = Integer.parseInt(parts[0].substring(2));
            if (number < 1 || number > 6) throw new IllegalArgumentException("Switch out of range");
            switches = setBit(switches, number - 1, binary(parts[1]));
            return;
        }
        throw new IllegalArgumentException("Unknown control input");
    }

    synchronized byte[] encodeControlState() {
        byte[] frame = new byte[13];
        frame[0] = (byte) SYNC_1;
        frame[1] = (byte) SYNC_2;
        frame[2] = (byte) ((VERSION << 4) | TYPE_CONTROL);
        frame[3] = (byte) nextSequence.getAndUpdate(value -> (value + 1) & 0xFF);
        frame[4] = 7;
        packAxes(frame, 5,
                quantizeControlAxis(leftX), quantizeControlAxis(leftY),
                quantizeControlAxis(rightX), quantizeControlAxis(rightY));
        int controls = buttons | (switches << 4) | (dpad << 10);
        putInt16LittleEndian(frame, 10, controls);
        frame[12] = (byte) crc8(frame, 2, 10);
        return frame;
    }

    byte[] encodeProControl(ProControlState state) {
        if (state == null || !isSignedAxis(state.leftX) || !isSignedAxis(state.leftY)
                || !isSignedAxis(state.rightX) || !isSignedAxis(state.rightY)
                || state.leftTrigger < 0 || state.leftTrigger > 255
                || state.rightTrigger < 0 || state.rightTrigger > 255
                || (state.buttons & ~ProControlState.VALID_BUTTON_MASK) != 0) {
            throw new IllegalArgumentException("Invalid PRO_CONTROL state");
        }
        byte[] payload = new byte[10];
        packAxes(payload, 0, state.leftX, state.leftY, state.rightX, state.rightY);
        payload[5] = (byte) state.leftTrigger;
        payload[6] = (byte) state.rightTrigger;
        payload[7] = (byte) state.buttons;
        payload[8] = (byte) (state.buttons >>> 8);
        payload[9] = (byte) (state.buttons >>> 16);
        return encode(TYPE_PRO_CONTROL, payload);
    }

    byte[] encodeDebug(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Debug payload exceeds 64 bytes");
        return encode(TYPE_DEBUG, payload);
    }

    byte[] encodeHello(boolean spp) {
        return encode(TYPE_HELLO, new byte[] {1, (byte) (spp ? 0x05 : 0x06), 2, 0});
    }

    byte[] encode(int type, byte[] payload) {
        if (type < 0 || type > 15 || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid frame type or payload length");
        }
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) SYNC_1;
        frame[1] = (byte) SYNC_2;
        frame[2] = (byte) ((VERSION << 4) | type);
        frame[3] = (byte) nextSequence.getAndUpdate(value -> (value + 1) & 0xFF);
        frame[4] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 5, payload.length);
        frame[frame.length - 1] = (byte) crc8(frame, 2, frame.length - 3);
        return frame;
    }

    static int crc8(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int index = offset; index < offset + length; index++) {
            crc ^= bytes[index] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    static String typeName(int type) {
        switch (type) {
            case TYPE_CONTROL: return "CONTROL";
            case TYPE_HELLO: return "HELLO";
            case TYPE_DEBUG: return "DEBUG";
            case TYPE_ACK: return "ACK";
            case TYPE_ERROR: return "ERROR";
            case TYPE_LOG: return "LOG";
            case TYPE_STATUS: return "STATUS";
            case TYPE_PRO_CONTROL: return "PRO_CONTROL";
            default: return "TYPE_" + type;
        }
    }

    static String payloadText(Frame frame) {
        if ((frame.type == TYPE_LOG || frame.type == TYPE_DEBUG) && frame.payload.length > 1) {
            int offset = frame.type == TYPE_LOG ? 1 : 0;
            return new String(frame.payload, offset, frame.payload.length - offset, StandardCharsets.UTF_8);
        }
        return toHex(frame.payload);
    }

    static String toHex(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0F];
        }
        return new String(output);
    }

    private static int boundedAxis(String value) {
        int axis = Integer.parseInt(value);
        if (axis < -32767 || axis > 32767) {
            throw new IllegalArgumentException("Axis out of range");
        }
        return axis;
    }

    private static boolean isSignedAxis(int value) {
        return value >= -512 && value <= 511;
    }

    private static int quantizeControlAxis(int value) {
        if (value >= 0) return (value * 511 + 16383) / 32767;
        return (value * 512 - 16383) / 32767;
    }

    private static void packAxes(byte[] output, int offset,
                                 int leftX, int leftY, int rightX, int rightY) {
        long packed = (leftX & 0x3FFL)
                | ((leftY & 0x3FFL) << 10)
                | ((rightX & 0x3FFL) << 20)
                | ((rightY & 0x3FFL) << 30);
        for (int index = 0; index < 5; index++) {
            output[offset + index] = (byte) (packed >>> (index * 8));
        }
    }

    private static void putInt16LittleEndian(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
    }

    private static boolean binary(String value) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw new IllegalArgumentException("Expected 0 or 1");
    }

    private static int setBit(int value, int bit, boolean set) {
        return set ? value | (1 << bit) : value & ~(1 << bit);
    }
}
