package com.example.srmremoter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SrmProtocolTest {
    @Test
    public void crcMatchesCrc8AtmCheckValue() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xF4, SrmProtocol.crc8(check, 0, check.length));
    }

    @Test
    public void version4ControlMatchesPackedTenBitGoldenVector() {
        SrmProtocol protocol = new SrmProtocol();
        protocol.applyControl("JL,-32767,32767\n");
        protocol.applyControl("JR,-12345,23400\n");
        protocol.applyControl("B,1\n");
        protocol.applyControl("Y,1\n");
        protocol.applyControl("SW1,1\n");
        protocol.applyControl("SW6,1\n");
        protocol.applyControl("L\n");
        for (int sequence = 0; sequence < 42; sequence++) protocol.encodeControlState();

        byte[] wire = protocol.encodeControlState();
        byte[] expected = {
                (byte) 0xA5, 0x5A, 0x40, 0x2A, 0x07,
                0x00, (byte) 0xFE, (byte) 0xF7, 0x73, 0x5B,
                0x1A, 0x0E, (byte) 0x98
        };
        assertArrayEquals(expected, wire);
    }

    @Test(expected = IllegalArgumentException.class)
    public void screenControlRejectsOutOfRangeAxis() {
        new SrmProtocol().applyControl("JL,-32768,0\n");
    }

    @Test
    public void helloIsOptionalAndAdvertisesFfe1AndProControl() {
        SrmProtocol protocol = new SrmProtocol();
        assertEquals(0x40, protocol.encodeControlState()[2] & 0xFF);
        List<SrmProtocol.Frame> frames = new SrmProtocol.StreamDecoder()
                .feed(protocol.encodeHello(false));
        assertEquals(1, frames.size());
        assertEquals(SrmProtocol.VERSION, frames.get(0).version);
        assertEquals(1, frames.get(0).payload[0]);
        assertEquals(0x06, frames.get(0).payload[1]);
    }

    @Test
    public void helloAdvertisesSppOnlyInExperimentalMode() {
        List<SrmProtocol.Frame> frames = new SrmProtocol.StreamDecoder()
                .feed(new SrmProtocol().encodeHello(true));
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0).payload[0]);
        assertEquals(0x05, frames.get(0).payload[1]);
    }

    @Test
    public void version3FramesAreRejected() {
        byte[] wire = new SrmProtocol().encodeControlState();
        wire[2] = 0x30;
        wire[wire.length - 1] = (byte) SrmProtocol.crc8(wire, 2, wire.length - 3);
        assertTrue(new SrmProtocol.StreamDecoder().feed(wire).isEmpty());
    }

    @Test
    public void proControlUsesCompactVersion4Payload() {
        SrmProtocol protocol = new SrmProtocol();
        int buttons = ProControlState.BUTTON_A | ProControlState.BUTTON_Y
                | ProControlState.BUTTON_R1 | ProControlState.BUTTON_L2
                | ProControlState.BUTTON_THUMB_L | ProControlState.BUTTON_START
                | ProControlState.BUTTON_MODE | ProControlState.BUTTON_DPAD_UP
                | ProControlState.BUTTON_DPAD_RIGHT;
        for (int sequence = 0; sequence < 44; sequence++) protocol.encodeControlState();
        byte[] wire = protocol.encodeProControl(new ProControlState(
                -512, 511, -257, 256, 31, 255, buttons));
        byte[] expected = {
                (byte) 0xA5, 0x5A, 0x47, 0x2C, 0x0A,
                0x00, (byte) 0xFE, (byte) 0xF7, 0x2F, 0x40,
                0x1F, (byte) 0xFF, 0x69, 0x35, 0x01, 0x54
        };
        assertArrayEquals(expected, wire);
        SrmProtocol.Frame frame = new SrmProtocol.StreamDecoder().feed(wire).get(0);
        assertEquals(SrmProtocol.VERSION, frame.version);
        assertEquals(10, frame.payload.length);
        assertEquals(255, frame.payload[6] & 0xFF);
        assertEquals(0x69, frame.payload[7] & 0xFF);
    }

    @Test(expected = IllegalArgumentException.class)
    public void proControlRejectsAxisBelowTenBitRange() {
        new SrmProtocol().encodeProControl(new ProControlState(-513, 0, 0, 0, 0, 0, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void proControlRejectsReservedButtonBits() {
        new SrmProtocol().encodeProControl(new ProControlState(
                0, 0, 0, 0, 0, 0, 1 << 17));
    }

    @Test
    public void proQuantizationUsesSignedSticksAndUnsignedTriggers() {
        assertEquals(-512, ProControlState.quantizeSigned(-1f, -1f, 1f, 0.05f, false));
        assertEquals(511, ProControlState.quantizeSigned(-1f, -1f, 1f, 0.05f, true));
        assertEquals(0, ProControlState.quantizeSigned(0.04f, -1f, 1f, 0.05f, false));
        assertEquals(255, ProControlState.quantizeTrigger(1f, 0f, 1f, 0.02f));
    }

    @Test
    public void streamDecoderHandlesNoiseAndFragments() {
        SrmProtocol protocol = new SrmProtocol();
        byte[] frame = protocol.encodeDebug("电机 OK");
        SrmProtocol.StreamDecoder decoder = new SrmProtocol.StreamDecoder();
        assertTrue(decoder.feed(new byte[] {1, 2, (byte) 0xA5}).isEmpty());
        assertTrue(decoder.feed(new byte[] {(byte) 0x5A, frame[2], frame[3]}).isEmpty());
        byte[] rest = java.util.Arrays.copyOfRange(frame, 4, frame.length);
        List<SrmProtocol.Frame> decoded = decoder.feed(rest);
        assertEquals(1, decoded.size());
        assertEquals("电机 OK", SrmProtocol.payloadText(decoded.get(0)));
    }

    @Test
    public void corruptedFrameIsRejected() {
        byte[] frame = new SrmProtocol().encodeControlState();
        frame[6] ^= 1;
        assertTrue(new SrmProtocol.StreamDecoder().feed(frame).isEmpty());
    }

}
