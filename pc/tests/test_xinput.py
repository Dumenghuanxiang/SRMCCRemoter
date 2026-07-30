import unittest

from srm_xbox.xinput import (
    A,
    B,
    BACK,
    DPAD_LEFT,
    DPAD_UP,
    LEFT_SHOULDER,
    LEFT_THUMB,
    RIGHT_SHOULDER,
    RIGHT_THUMB,
    START,
    X,
    Y,
    RawGamepad,
    apply_deadzone,
    map_to_srm,
    quantize_axis,
)
from srm_xbox.protocol import (
    PRO_BUTTON_A,
    PRO_BUTTON_B,
    PRO_BUTTON_DPAD_LEFT,
    PRO_BUTTON_DPAD_UP,
    PRO_BUTTON_L1,
    PRO_BUTTON_L2,
    PRO_BUTTON_R1,
    PRO_BUTTON_R2,
    PRO_BUTTON_SELECT,
    PRO_BUTTON_START,
    PRO_BUTTON_THUMB_L,
    PRO_BUTTON_THUMB_R,
    PRO_BUTTON_X,
    PRO_BUTTON_Y,
)


class XInputMappingTests(unittest.TestCase):
    def raw(self, **changes):
        values = dict(
            buttons=0,
            left_trigger=0,
            right_trigger=0,
            left_x=0,
            left_y=0,
            right_x=0,
            right_y=0,
        )
        values.update(changes)
        return RawGamepad(**values)

    def test_deadzone_is_zero_and_full_range_is_preserved(self):
        self.assertEqual(apply_deadzone(4096, 4096), 0)
        self.assertEqual(apply_deadzone(-4096, 4096), 0)
        self.assertEqual(apply_deadzone(32767, 4096), 32767)
        self.assertEqual(apply_deadzone(-32768, 4096), -32767)
        self.assertEqual(quantize_axis(-32767), -512)
        self.assertEqual(quantize_axis(32767), 511)

    def test_abxy_bits_match_protocol(self):
        state = map_to_srm(
            self.raw(buttons=A | B | X | Y), deadzone=0, trigger_threshold=30
        )
        self.assertEqual(state.buttons, PRO_BUTTON_A | PRO_BUTTON_B | PRO_BUTTON_X | PRO_BUTTON_Y)

    def test_six_switch_mapping(self):
        raw = self.raw(buttons=LEFT_SHOULDER | RIGHT_SHOULDER | BACK | START | LEFT_THUMB | RIGHT_THUMB,
                       left_trigger=30, right_trigger=255)
        state = map_to_srm(raw, deadzone=0, trigger_threshold=30)
        self.assertEqual(
            state.buttons,
            PRO_BUTTON_L1 | PRO_BUTTON_R1 | PRO_BUTTON_L2 | PRO_BUTTON_R2
            | PRO_BUTTON_THUMB_L | PRO_BUTTON_THUMB_R | PRO_BUTTON_SELECT | PRO_BUTTON_START,
        )
        self.assertEqual((state.left_trigger, state.right_trigger), (30, 255))

    def test_vertical_direction_wins_for_diagonal_dpad(self):
        state = map_to_srm(
            self.raw(buttons=DPAD_UP | DPAD_LEFT), deadzone=0, trigger_threshold=30
        )
        self.assertEqual(state.buttons, PRO_BUTTON_DPAD_UP | PRO_BUTTON_DPAD_LEFT)

    def test_xinput_y_direction_matches_protocol(self):
        state = map_to_srm(
            self.raw(left_y=20000, right_y=-20000), deadzone=0, trigger_threshold=30
        )
        self.assertGreater(state.left_y, 0)
        self.assertLess(state.right_y, 0)


if __name__ == "__main__":
    unittest.main()
