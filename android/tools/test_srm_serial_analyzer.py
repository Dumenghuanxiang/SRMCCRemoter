import struct
import unittest

from srm_serial_analyzer import (
    Analyzer,
    ControlState,
    ProControlState,
    StreamDecoder,
    crc8_atm,
    decode_control,
    decode_pro_control,
    format_debug_frame,
    format_realtime_state,
    format_realtime_pro_state,
)


def pack_axes(left_x, left_y, right_x, right_y):
    packed = sum((axis & 0x03FF) << shift for axis, shift in zip(
        (left_x, left_y, right_x, right_y), (0, 10, 20, 30)
    ))
    return packed.to_bytes(5, "little")


def frame(sequence, payload=None, frame_type=0, version=4):
    if payload is None:
        payload = pack_axes(0, 0, 0, 0) + struct.pack("<H", 0)
    body = bytes([(version << 4) | frame_type, sequence, len(payload)]) + payload
    return b"\xA5\x5A" + body + bytes([crc8_atm(body)])


class DecoderTest(unittest.TestCase):
    def test_protocol_check_vector(self):
        self.assertEqual(0xF4, crc8_atm(b"123456789"))

    def test_decodes_fragmented_control_after_noise(self):
        decoder = StreamDecoder()
        wire = frame(42, pack_axes(-512, 511, -193, 365) + struct.pack("<H", 0x0E1A))
        self.assertEqual([], decoder.feed(b"noise\xA5"))
        frames = decoder.feed(wire[1:7]) + decoder.feed(wire[7:])
        self.assertEqual(1, len(frames))
        self.assertEqual(
            ControlState(-512, 511, -193, 365, 10, 33, 3),
            decode_control(frames[0]),
        )
        self.assertEqual(4, frames[0].version)
        self.assertEqual(5, decoder.noise_bytes)

    def test_rejects_bad_crc_and_resynchronizes(self):
        decoder = StreamDecoder()
        damaged = bytearray(frame(1))
        damaged[8] ^= 1
        frames = decoder.feed(bytes(damaged) + frame(2))
        self.assertEqual([2], [item.sequence for item in frames])
        self.assertEqual(1, decoder.bad_crc)

    def test_rejects_semantically_invalid_control(self):
        decoder = StreamDecoder()
        invalid = frame(1, pack_axes(0, 0, 0, 0) + struct.pack("<H", 0xE000))
        decoded = decoder.feed(invalid)[0]
        self.assertIsNone(decode_control(decoded))

    def test_rejects_version3_frames(self):
        decoder = StreamDecoder()
        self.assertEqual([], decoder.feed(frame(7, version=3)))
        self.assertEqual(1, decoder.bad_version)

    def test_decodes_compact_v4_control(self):
        wire = frame(0x2A, pack_axes(-512, 511, -193, 365)
                     + struct.pack("<H", 0x0E1A))
        self.assertEqual(bytes.fromhex(
            "A5 5A 40 2A 07 00 FE F7 73 5B 1A 0E 98"
        ), wire)
        decoded = StreamDecoder().feed(wire)[0]
        self.assertEqual(ControlState(-512, 511, -193, 365, 10, 33, 3),
                         decode_control(decoded))

    def test_decodes_v4_pro_control(self):
        payload = (pack_axes(-512, 511, -257, 256)
                   + bytes((31, 255, 0x69, 0x35, 0x01)))
        decoded = StreamDecoder().feed(frame(8, payload, frame_type=7))[0]
        state = decode_pro_control(decoded)
        self.assertEqual(ProControlState(-512, 511, -257, 256, 31, 255, 0x013569), state)
        text = format_realtime_pro_state(state, 8, 50, 1.0, StreamDecoder(), 0)
        self.assertIn("LT= 31 RT=255", text)
        self.assertIn("A+Y+R1+L2", text)

    def test_rejects_pro_control_in_v3_frame(self):
        payload = pack_axes(0, 0, 0, 0) + bytes(5)
        self.assertEqual([], StreamDecoder().feed(
            frame(8, payload, frame_type=7, version=3)
        ))

    def test_reports_sequence_loss_and_reference_accuracy(self):
        decoder = StreamDecoder()
        expected = ControlState(0, 0, 0, 0, 0, 0, 0)
        analyzer = Analyzer(decoder, expected, 0)
        for index, sequence in enumerate((10, 11, 13)):
            for item in decoder.feed(frame(sequence), timestamp=1.0 + index * 0.02):
                analyzer.accept(item)
        report = analyzer.report(0.06)
        self.assertEqual(1, report["protocol_accuracy"]["sequence_inferred_lost"])
        self.assertEqual(75.0, report["protocol_accuracy"]["sequence_delivery_percent"])
        self.assertEqual(100.0, report["reference_accuracy"]["matching_percent"])
        self.assertEqual(20.0, report["control_stability"]["period_mean_ms"])

    def test_formats_realtime_control_state(self):
        decoder = StreamDecoder()
        state = ControlState(-12, 34, 56, -78, 0b1010, 0b100001, 3)
        text = format_realtime_state(state, 9, 33, 1.0, decoder, 2)
        self.assertIn("SEQ=009", text)
        self.assertIn("ABXY=-B-Y", text)
        self.assertIn("SW=1----6", text)
        self.assertIn("D=左", text)
        self.assertIn("RATE= 33pkt/1s= 33.0Hz", text)
        self.assertIn("LOST=   2", text)

    def test_formats_debug_frame_as_separate_safe_line(self):
        decoder = StreamDecoder()
        decoded = decoder.feed(frame(42, "电机 OK\n\x1b".encode("utf-8"), frame_type=2))[0]
        text = format_debug_frame(decoded)
        self.assertEqual("[DEBUG #042 len=11] 电机 OK\\n\\x1B", text)


if __name__ == "__main__":
    unittest.main()
