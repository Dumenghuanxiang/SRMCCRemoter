import unittest

from srm_xbox.protocol import (
    ControlState,
    ProControlState,
    StreamDecoder,
    crc8_atm,
    encode_control,
    encode_hello,
    encode_pro_control,
)


class ProtocolTests(unittest.TestCase):
    def test_crc_standard_vector(self):
        self.assertEqual(crc8_atm(b"123456789"), 0xF4)

    def test_neutral_control_matches_android_documentation(self):
        wire = encode_control(ControlState(), 0)
        self.assertEqual(
            wire,
            bytes.fromhex("A5 5A 40 00 07 00 00 00 00 00 00 00 3F"),
        )

    def test_full_control_matches_firmware_vector(self):
        state = ControlState(-512, 511, -193, 365, 0x0A, 0x21, 3)
        wire = encode_control(state, 0x2A)
        self.assertEqual(
            wire,
            bytes.fromhex("A5 5A 40 2A 07 00 FE F7 73 5B 1A 0E 98"),
        )

    def test_pro_control_matches_android_and_firmware_vector(self):
        state = ProControlState(-512, 511, -257, 256, 31, 255, 0x013569)
        self.assertEqual(
            encode_pro_control(state, 0x2C),
            bytes.fromhex("A5 5A 47 2C 0A 00 FE F7 2F 40 1F FF 69 35 01 54"),
        )

    def test_ble_hello_matches_android_documentation(self):
        self.assertEqual(
            encode_hello(0, ble=True),
            bytes.fromhex("A5 5A 41 00 04 01 06 02 00 54"),
        )

    def test_stream_decoder_accepts_noise_and_fragments(self):
        frame = encode_control(ControlState(left_x=123), 7)
        decoder = StreamDecoder()
        self.assertEqual(decoder.feed(b"noise\xA5"), [])
        self.assertEqual(decoder.feed(frame[1:8]), [])
        decoded = decoder.feed(frame[8:])
        self.assertEqual(len(decoded), 1)
        self.assertEqual(decoded[0].sequence, 7)
        self.assertEqual(decoded[0].version, 4)
        self.assertEqual(decoded[0].payload[:2], b"{\x00")

    def test_stream_decoder_rejects_bad_crc(self):
        frame = bytearray(encode_control(ControlState(), 0))
        frame[5] ^= 1
        self.assertEqual(StreamDecoder().feed(frame), [])

    def test_reserved_axis_value_is_rejected(self):
        with self.assertRaises(ValueError):
            ControlState(left_x=-32768)

    def test_pro_control_rejects_reserved_buttons(self):
        with self.assertRaises(ValueError):
            ProControlState(buttons=1 << 17)


if __name__ == "__main__":
    unittest.main()
